{-# LANGUAGE BangPatterns #-}

module Delta.Algorithms.Greedy (diffGreedy) where

import Data.ByteString (ByteString)
import qualified Data.ByteString as BS
import qualified Data.Map.Strict as M
import Data.Word (Word64)
import Delta.Hash
import Delta.Types
import Delta.Util

diffGreedy :: ByteString -> ByteString -> DiffOptions -> [Command]
diffGreedy r v opts
  | BS.null v = []
  | otherwise = reverse (go 0 0 rhStart 0 [])
  where
    p = optSeedLen opts
    vLen = BS.length v
    idx = buildIndex r p
    rhStart
      | vLen >= p = Just (initRollingHash v 0 p)
      | otherwise = Nothing

    go !vC !vS !mRh !rhPos !accRev
      | vC + p > vLen =
          if vS < vLen
            then Add (sliceV vS vLen) : accRev
            else accRev
      | otherwise =
          case fingerprintAt v p vC mRh rhPos of
            Nothing ->
              if vS < vLen
                then Add (sliceV vS vLen) : accRev
                else accRev
            Just (!fpV, !rh', !rhPos') ->
              let (!bestRm, !bestLen) = bestMatch fpV vC
               in if bestLen < p
                    then go (vC + 1) vS (Just rh') rhPos' accRev
                    else
                      let !accRev' =
                            if vS < vC
                              then Copy bestRm bestLen : Add (sliceV vS vC) : accRev
                              else Copy bestRm bestLen : accRev
                          !next = vC + bestLen
                       in go next next (Just rh') rhPos' accRev'

    sliceV a b = BS.take (b - a) (BS.drop a v)

    bestMatch :: Word64 -> Int -> (Int, Int)
    bestMatch fpV vC =
      foldl' pick (-1, 0) offsets
      where
        offsets = M.findWithDefault [] fpV idx
        pick (!bestOff, !bestLen) rCand
          | not (regionEquals r rCand v vC p) = (bestOff, bestLen)
          | otherwise =
              let !ml = extendForward r v rCand vC p
               in if ml > bestLen then (rCand, ml) else (bestOff, bestLen)

buildIndex :: ByteString -> Int -> M.Map Word64 [Int]
buildIndex r p
  | BS.length r < p = M.empty
  | otherwise = go 0 (initRollingHash r 0 p) M.empty
  where
    numSeeds = BS.length r - p + 1
    go !a !rh !m
      | a >= numSeeds = m
      | otherwise =
          let !fp = rhValue rh
              -- Preserve ascending encounter order, matching other implementations.
              !m' = M.insertWith (flip (++)) fp [a] m
           in if a + 1 >= numSeeds
                then go (a + 1) rh m'
                else
                  let !oldB = fromIntegral (byteAt r a) :: Word64
                      !newB = fromIntegral (byteAt r (a + p)) :: Word64
                      !rh' = rollHash oldB newB rh
                   in go (a + 1) rh' m'

fingerprintAt :: ByteString -> Int -> Int -> Maybe RollingHash -> Int -> Maybe (Word64, RollingHash, Int)
fingerprintAt _ _ _ Nothing _ = Nothing
fingerprintAt bs p pos (Just rh) rhPos
  | pos == rhPos = Just (rhValue rh, rh, rhPos)
  | pos == rhPos + 1 =
      let !oldB = fromIntegral (byteAt bs (pos - 1)) :: Word64
          !newB = fromIntegral (byteAt bs (pos + p - 1)) :: Word64
          !rh' = rollHash oldB newB rh
       in Just (rhValue rh', rh', pos)
  | otherwise =
      let !rh' = initRollingHash bs pos p
       in Just (rhValue rh', rh', pos)
