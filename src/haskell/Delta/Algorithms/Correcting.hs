{-# LANGUAGE BangPatterns #-}

module Delta.Algorithms.Correcting (diffCorrecting) where

import Data.ByteString (ByteString)
import qualified Data.ByteString as BS
import qualified Data.IntMap.Strict as IM
import qualified Data.Map.Strict as M
import Data.Sequence (Seq(..), ViewL(..), ViewR(..), (|>))
import qualified Data.Sequence as Seq
import Data.Word (Word64)
import Delta.Hash
import Delta.Types
import Delta.Util

data BufEntry = BufEntry
  { beStart :: !Int
  , beEnd :: !Int
  , beCmd :: !Command
  }

type CheckpointHash = IM.IntMap (Word64, Int)
type CheckpointSplay = M.Map Word64 Int

diffCorrecting :: ByteString -> ByteString -> DiffOptions -> [Command]
diffCorrecting r v opts
  | BS.null v = []
  | otherwise =
      let (tblHash, tblSplay) = buildTables
          (outRev, buf, vSFinal) = scanV tblHash tblSplay
          baseOut = reverse outRev ++ fmap beCmd (toListSeq buf)
          trailing
            | vSFinal < vLen = [Add (sliceV vSFinal vLen)]
            | otherwise = []
       in baseOut ++ trailing
  where
    p = optSeedLen opts
    useSplay = optUseSplay opts
    qFloor = max 2 (optTableSize opts)
    bufCap = max 1 (optBufCap opts)
    maxTbl = max 2 (optMaxTable opts)
    rLen = BS.length r
    vLen = BS.length v
    numSeeds = max 0 (rLen - p + 1)

    cap
      | numSeeds > 0 = nextPrime (min maxTbl (max qFloor ((2 * numSeeds) `div` max 1 p)))
      | otherwise = nextPrime (min maxTbl qFloor)

    fSize
      | numSeeds > 0 = nextPrime (2 * numSeeds)
      | otherwise = 1

    m = max 1 ((fSize + cap - 1) `div` cap)

    k
      | vLen >= p =
          let mid = min (vLen `div` 2) (vLen - p)
              fpMid = fingerprint v mid p
              fMid = fromIntegral (fpMid `mod` fromIntegral fSize) :: Int
           in fMid `mod` m
      | otherwise = 0

    sliceV a b = BS.take (b - a) (BS.drop a v)

    buildTables :: (CheckpointHash, CheckpointSplay)
    buildTables
      | numSeeds <= 0 = (IM.empty, M.empty)
      | otherwise = go 0 (initRollingHash r 0 p) IM.empty M.empty
      where
        go !a !rh !tblHash !tblSplay
          | a >= numSeeds = (tblHash, tblSplay)
          | otherwise =
              let !fp = rhValue rh
                  !f = fromIntegral (fp `mod` fromIntegral fSize) :: Int
                  (!tblHash', !tblSplay') =
                    if f `mod` m /= k
                      then (tblHash, tblSplay)
                      else
                        if useSplay
                          then
                            let inserted = M.insertWith (\_ old -> old) fp a tblSplay
                             in (tblHash, inserted)
                          else (insertCheckpoint cap (f `div` m) fp a tblHash, tblSplay)
               in if a + 1 >= numSeeds
                    then go (a + 1) rh tblHash' tblSplay'
                    else
                      let !oldB = fromIntegral (byteAt r a) :: Word64
                          !newB = fromIntegral (byteAt r (a + p)) :: Word64
                          !rh' = rollHash oldB newB rh
                       in go (a + 1) rh' tblHash' tblSplay'

    scanV :: CheckpointHash -> CheckpointSplay -> ([Command], Seq BufEntry, Int)
    scanV tblHash tblSplay
      | vLen < p = ([], Seq.empty, 0)
      | otherwise = go 0 0 (initRollingHash v 0 p) 0 Seq.empty []
      where
        lookupSeed fp fV
          | useSplay = M.lookup fp tblSplay
          | otherwise = lookupCheckpoint cap (fV `div` m) fp tblHash

        go !vC !vS !rh !rhPos !buf !outRev
          | vC + p > vLen = (outRev, buf, vS)
          | otherwise =
              let (!fpV, !rh', !rhPos') =
                    if vC == rhPos
                      then (rhValue rh, rh, rhPos)
                      else
                        if vC == rhPos + 1
                          then
                            let !oldB = fromIntegral (byteAt v (vC - 1)) :: Word64
                                !newB = fromIntegral (byteAt v (vC + p - 1)) :: Word64
                                !rhNext = rollHash oldB newB rh
                             in (rhValue rhNext, rhNext, vC)
                          else
                            let !rhNext = initRollingHash v vC p
                             in (rhValue rhNext, rhNext, vC)
                  !fV = fromIntegral (fpV `mod` fromIntegral fSize) :: Int
               in if fV `mod` m /= k
                    then go (vC + 1) vS rh' rhPos' buf outRev
                    else
                      case lookupSeed fpV fV of
                        Nothing -> go (vC + 1) vS rh' rhPos' buf outRev
                        Just rOff
                          | not (regionEquals r rOff v vC p) ->
                              go (vC + 1) vS rh' rhPos' buf outRev
                          | otherwise ->
                              let !fwd = extendForward r v rOff vC p
                                  !bwd = extendBackward r v rOff vC
                                  !vM = vC - bwd
                                  !rM = rOff - bwd
                                  !ml = bwd + fwd
                                  !matchEnd = vM + ml
                               in if ml < p
                                    then go (vC + 1) vS rh' rhPos' buf outRev
                                    else
                                      if vS <= vM
                                        then
                                          let (!buf1, !out1) =
                                                if vS < vM
                                                  then emitBuf bufCap (BufEntry vS vM (Add (sliceV vS vM))) buf outRev
                                                  else (buf, outRev)
                                              (!buf2, !out2) = emitBuf bufCap (BufEntry vM matchEnd (Copy rM ml)) buf1 out1
                                           in go matchEnd matchEnd rh' rhPos' buf2 out2
                                        else
                                          let (!effectiveStart, !buf1) = tailCorrect vM matchEnd vS buf
                                              !adj = effectiveStart - vM
                                              !newLen = matchEnd - effectiveStart
                                              (!buf2, !out2) =
                                                if newLen > 0
                                                  then emitBuf bufCap (BufEntry effectiveStart matchEnd (Copy (rM + adj) newLen)) buf1 outRev
                                                  else (buf1, outRev)
                                           in go matchEnd matchEnd rh' rhPos' buf2 out2

    tailCorrect :: Int -> Int -> Int -> Seq BufEntry -> (Int, Seq BufEntry)
    tailCorrect vM matchEnd vS0 = go vS0
      where
        go !effectiveStart b =
          case Seq.viewr b of
            EmptyR -> (effectiveStart, b)
            rest :> tail
              | beStart tail >= vM && beEnd tail <= matchEnd ->
                  go (min effectiveStart (beStart tail)) rest
              | beEnd tail > vM && beStart tail < vM ->
                  case beCmd tail of
                    Add payload ->
                      let keep = vM - beStart tail
                          effective' = min effectiveStart vM
                       in if keep > 0
                            then
                              let trimmed = BufEntry (beStart tail) vM (Add (BS.take keep payload))
                               in (effective', rest |> trimmed)
                            else (effective', rest)
                    Copy _ _ -> (effectiveStart, b)
              | otherwise -> (effectiveStart, b)

emitBuf :: Int -> BufEntry -> Seq BufEntry -> [Command] -> (Seq BufEntry, [Command])
emitBuf cap entry buf outRev
  | Seq.length buf >= cap =
      case Seq.viewl buf of
        EmptyL -> (Seq.singleton entry, outRev)
        oldest :< rest -> (rest |> entry, beCmd oldest : outRev)
  | otherwise = (buf |> entry, outRev)

toListSeq :: Seq a -> [a]
toListSeq = foldr (:) []

insertCheckpoint :: Int -> Int -> Word64 -> Int -> CheckpointHash -> CheckpointHash
insertCheckpoint cap start fp off tbl =
  case findInsertSlot cap start fp tbl of
    Nothing -> tbl
    Just idx -> IM.insert idx (fp, off) tbl

findInsertSlot :: Int -> Int -> Word64 -> CheckpointHash -> Maybe Int
findInsertSlot cap start fp tbl = go start
  where
    go !i =
      case IM.lookup i tbl of
        Nothing -> Just i
        Just (foundFp, _)
          | foundFp == fp -> Nothing
          | otherwise ->
              let i' = if i + 1 == cap then 0 else i + 1
               in if i' == start then Nothing else go i'

lookupCheckpoint :: Int -> Int -> Word64 -> CheckpointHash -> Maybe Int
lookupCheckpoint cap start fp tbl = go start
  where
    go !i =
      case IM.lookup i tbl of
        Nothing -> Nothing
        Just (foundFp, off)
          | foundFp == fp -> Just off
          | otherwise ->
              let i' = if i + 1 == cap then 0 else i + 1
               in if i' == start then Nothing else go i'
