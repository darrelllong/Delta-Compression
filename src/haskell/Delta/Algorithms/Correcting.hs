{-# LANGUAGE BangPatterns #-}
{-# LANGUAGE ScopedTypeVariables #-}

module Delta.Algorithms.Correcting (diffCorrecting) where

import Control.Monad (when)
import Control.Monad.ST (ST, runST)
import Data.Array.MArray (freeze)
import Data.Array.ST (STUArray, newArray, readArray, writeArray)
import Data.Array.Unboxed (UArray, (!))
import Data.ByteString (ByteString)
import qualified Data.ByteString as BS
import Data.Foldable (toList)
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

data CheckpointHash = CheckpointHash
  { chCap :: !Int
  , chFp :: !(UArray Int Word64)
  , chOff :: !(UArray Int Int)
  }

type CheckpointSplay = M.Map Word64 Int

diffCorrecting :: ByteString -> ByteString -> DiffOptions -> [Command]
diffCorrecting r v opts
  | BS.null v = []
  | otherwise =
      let (tblHash, tblSplay) = buildTables
          (outRev, buf, vSFinal) = scanV tblHash tblSplay
          baseOut = reverse outRev ++ map beCmd (toList buf)
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

    buildTables :: (Maybe CheckpointHash, CheckpointSplay)
    buildTables
      | numSeeds <= 0 = (Nothing, M.empty)
      | useSplay = (Nothing, buildSplay 0 (initRollingHash r 0 p) M.empty)
      | otherwise = (Just buildHash, M.empty)
      where
        buildSplay !a !rh !tblSplay
          | a >= numSeeds = tblSplay
          | otherwise =
              let !fp = rhValue rh
                  !f = fromIntegral (fp `mod` fromIntegral fSize) :: Int
                  !tblSplay' =
                    if f `mod` m /= k
                      then tblSplay
                      else M.insertWith (\_ old -> old) fp a tblSplay
               in if a + 1 >= numSeeds
                    then buildSplay (a + 1) rh tblSplay'
                    else
                      let !oldB = fromIntegral (byteAt r a) :: Word64
                          !newB = fromIntegral (byteAt r (a + p)) :: Word64
                          !rh' = rollHash oldB newB rh
                       in buildSplay (a + 1) rh' tblSplay'

        buildHash = runST buildHashST

        buildHashST :: forall s. ST s CheckpointHash
        buildHashST = do
            fpArr <- newArray (0, cap - 1) (0 :: Word64) :: ST s (STUArray s Int Word64)
            offArr <- newArray (0, cap - 1) (-1 :: Int) :: ST s (STUArray s Int Int)
            let insertSlot !start !fp !off = probe start
                  where
                    probe !i = do
                      curOff <- readArray offArr i
                      if curOff == -1
                        then do
                          writeArray offArr i off
                          writeArray fpArr i fp
                        else do
                          curFp <- readArray fpArr i
                          if curFp == fp
                            then pure ()
                            else do
                              let i' = if i + 1 == cap then 0 else i + 1
                              if i' == start then pure () else probe i'
                go !a !rh
                  | a >= numSeeds = pure ()
                  | otherwise = do
                      let !fp = rhValue rh
                          !f = fromIntegral (fp `mod` fromIntegral fSize) :: Int
                      when (f `mod` m == k) $ insertSlot (f `div` m) fp a
                      if a + 1 >= numSeeds
                        then go (a + 1) rh
                        else do
                          let !oldB = fromIntegral (byteAt r a) :: Word64
                              !newB = fromIntegral (byteAt r (a + p)) :: Word64
                              !rh' = rollHash oldB newB rh
                          go (a + 1) rh'
            go 0 (initRollingHash r 0 p)
            fpFrozen <- freeze fpArr
            offFrozen <- freeze offArr
            pure (CheckpointHash cap fpFrozen offFrozen)

    scanV :: Maybe CheckpointHash -> CheckpointSplay -> ([Command], Seq BufEntry, Int)
    scanV tblHash tblSplay
      | vLen < p = ([], Seq.empty, 0)
      | otherwise = go 0 0 (initRollingHash v 0 p) 0 Seq.empty []
      where
        lookupSeed fp fV
          | useSplay = M.lookup fp tblSplay
          | otherwise =
              case tblHash of
                Just ht -> lookupCheckpoint ht (fV `div` m) fp
                Nothing -> Nothing

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
            rest :> tailEntry
              | beStart tailEntry >= vM && beEnd tailEntry <= matchEnd ->
                  go (min effectiveStart (beStart tailEntry)) rest
              | beEnd tailEntry > vM && beStart tailEntry < vM ->
                  case beCmd tailEntry of
                    Add payload ->
                      let keep = vM - beStart tailEntry
                          effective' = min effectiveStart vM
                       in if keep > 0
                            then
                              let trimmed = BufEntry (beStart tailEntry) vM (Add (BS.take keep payload))
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

lookupCheckpoint :: CheckpointHash -> Int -> Word64 -> Maybe Int
lookupCheckpoint ht start fp = go start
  where
    cap = chCap ht
    fpArr = chFp ht
    offArr = chOff ht

    go !i =
      let off = offArr ! i
       in if off == -1
            then Nothing
            else
              let foundFp = fpArr ! i
               in if foundFp == fp
                    then Just off
                    else
                      let i' = if i + 1 == cap then 0 else i + 1
                       in if i' == start then Nothing else go i'
