{-# LANGUAGE BangPatterns #-}

module Delta.Algorithms.Onepass (diffOnepass) where

import Data.ByteString (ByteString)
import qualified Data.ByteString as BS
import qualified Data.IntMap.Strict as IM
import qualified Data.Map.Strict as M
import Data.Word (Word64)
import Delta.Hash
import Delta.Types
import Delta.Util

type Entry = (Word64, Int, Int)
type HashTable = IM.IntMap Entry
type SplayTable = M.Map Word64 (Int, Int)

diffOnepass :: ByteString -> ByteString -> DiffOptions -> [Command]
diffOnepass r v opts
  | BS.null v = []
  | otherwise = go 0 0 0 0 IM.empty IM.empty M.empty M.empty rhV0 rhR0 0 0 []
  where
    p = optSeedLen opts
    useSplay = optUseSplay opts
    qFloor = max 2 (optTableSize opts)
    numSeeds = max 0 (BS.length r - p + 1)
    q = nextPrime (max qFloor (numSeeds `div` max 1 p))
    vLen = BS.length v
    rLen = BS.length r

    rhV0
      | vLen >= p = Just (initRollingHash v 0 p)
      | otherwise = Nothing
    rhR0
      | rLen >= p = Just (initRollingHash r 0 p)
      | otherwise = Nothing

    go !ver !rC !vC !vS !tableVh !tableRh !tableVs !tableRs !mRhV !mRhR !rhVPos !rhRPos !accRev
      | not canV && not canR =
          finalize vS accRev
      | otherwise =
          let (!mV, !mRhV1, !rhVPos1) =
                if canV then calcFingerprint v p vC mRhV rhVPos else (Nothing, mRhV, rhVPos)
              (!mR, !mRhR1, !rhRPos1) =
                if canR then calcFingerprint r p rC mRhR rhRPos else (Nothing, mRhR, rhRPos)

              !tableVh1 =
                if useSplay then tableVh else
                  case mV of
                    Nothing -> tableVh
                    Just fp -> putEntryHash q ver fp vC tableVh
              !tableRh1 =
                if useSplay then tableRh else
                  case mR of
                    Nothing -> tableRh
                    Just fp -> putEntryHash q ver fp rC tableRh
              !tableVs1 =
                if not useSplay then tableVs else
                  case mV of
                    Nothing -> tableVs
                    Just fp -> putEntrySplay ver fp vC tableVs
              !tableRs1 =
                if not useSplay then tableRs else
                  case mR of
                    Nothing -> tableRs
                    Just fp -> putEntrySplay ver fp rC tableRs
           in case findMatch ver q useSplay mR mV tableVh1 tableRh1 tableVs1 tableRs1 of
                Nothing ->
                  go ver (rC + 1) (vC + 1) vS
                    tableVh1 tableRh1 tableVs1 tableRs1
                    mRhV1 mRhR1 rhVPos1 rhRPos1 accRev
                Just (!rM, !vM) ->
                  let !ml = extendForward r v rM vM 0
                   in if ml < p
                        then go ver (rC + 1) (vC + 1) vS
                               tableVh1 tableRh1 tableVs1 tableRs1
                               mRhV1 mRhR1 rhVPos1 rhRPos1 accRev
                        else
                          let !accRev' =
                                if vS < vM
                                  then Copy rM ml : Add (sliceV vS vM) : accRev
                                  else Copy rM ml : accRev
                              !vNext = vM + ml
                              !rNext = rM + ml
                           in go (ver + 1) rNext vNext vNext
                                tableVh1 tableRh1 tableVs1 tableRs1
                                mRhV1 mRhR1 rhVPos1 rhRPos1 accRev'
      where
        canV = vC + p <= vLen
        canR = rC + p <= rLen

        finalize !vSFinal !acc
          | vSFinal < vLen = reverse (Add (sliceV vSFinal vLen) : acc)
          | otherwise = reverse acc

        sliceV a b = BS.take (b - a) (BS.drop a v)

        findMatch verNow qNow useS mFpR mFpV tvh trh tvs trs =
          case mFpR of
            Just fpR ->
              case getOtherV fpR of
                Just vCand
                  | regionEquals r rC v vCand p -> Just (rC, vCand)
                _ -> lookupFromV mFpV
            Nothing -> lookupFromV mFpV
          where
            getOtherV fp =
              if useS
                then getEntrySplay verNow fp tvs
                else getEntryHash qNow verNow fp tvh
            getOtherR fp =
              if useS
                then getEntrySplay verNow fp trs
                else getEntryHash qNow verNow fp trh

            lookupFromV Nothing = Nothing
            lookupFromV (Just fpV) =
              case getOtherR fpV of
                Just rCand
                  | regionEquals v vC r rCand p -> Just (rCand, vC)
                _ -> Nothing

slot :: Int -> Word64 -> Int
slot q fp = fromIntegral (fp `mod` fromIntegral q)

putEntryHash :: Int -> Int -> Word64 -> Int -> HashTable -> HashTable
putEntryHash q ver fp off table =
  let idx = slot q fp
   in case IM.lookup idx table of
        Just (_, _, entryVer)
          | entryVer == ver -> table
        _ -> IM.insert idx (fp, off, ver) table

getEntryHash :: Int -> Int -> Word64 -> HashTable -> Maybe Int
getEntryHash q ver fp table =
  let idx = slot q fp
   in case IM.lookup idx table of
        Just (entryFp, off, entryVer)
          | entryVer == ver && entryFp == fp -> Just off
        _ -> Nothing

putEntrySplay :: Int -> Word64 -> Int -> SplayTable -> SplayTable
putEntrySplay ver fp off table =
  case M.lookup fp table of
    Just (_, entryVer)
      | entryVer == ver -> table
    _ -> M.insert fp (off, ver) table

getEntrySplay :: Int -> Word64 -> SplayTable -> Maybe Int
getEntrySplay ver fp table =
  case M.lookup fp table of
    Just (off, entryVer)
      | entryVer == ver -> Just off
    _ -> Nothing

calcFingerprint :: ByteString -> Int -> Int -> Maybe RollingHash -> Int -> (Maybe Word64, Maybe RollingHash, Int)
calcFingerprint _ _ _ Nothing rhPos = (Nothing, Nothing, rhPos)
calcFingerprint bs p pos (Just rh) rhPos
  | pos == rhPos = (Just (rhValue rh), Just rh, rhPos)
  | pos == rhPos + 1 =
      let !oldB = fromIntegral (byteAt bs (pos - 1)) :: Word64
          !newB = fromIntegral (byteAt bs (pos + p - 1)) :: Word64
          !rh' = rollHash oldB newB rh
       in (Just (rhValue rh'), Just rh', pos)
  | otherwise =
      let !rh' = initRollingHash bs pos p
       in (Just (rhValue rh'), Just rh', pos)
