{-# LANGUAGE BangPatterns #-}
{-# LANGUAGE ScopedTypeVariables #-}

module Delta.Algorithms.Onepass (diffOnepass) where

-- WHAT:
--   One-pass differencing (Ajtai et al., JACM 2002, Section 4.1 / Figure 3).
-- WHY:
--   This is the default fast path: linear scan, constant working memory, and
--   byte-compatible output with the other language implementations.

import Control.Monad.ST (ST, runST)
import Data.Array.ST (STUArray, newArray, readArray, writeArray)
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

-- | WHAT:
--   Scan reference (R) and version (V) in lockstep, match on seed fingerprints,
--   extend forward, emit Add/Copy, then logically flush lookup tables.
-- WHY:
--   Flushing after each accepted match enforces the "next-match policy"
--   described in Ajtai et al. Figure 3.
diffOnepass :: ByteString -> ByteString -> DiffOptions -> [Command]
diffOnepass r v opts
  | BS.null v = []
  | optUseSplay opts = diffOnepassPure r v opts
  | otherwise = diffOnepassMutable r v opts

-- WHAT:
--   Mutable STUArray implementation of onepass hash tables.
-- WHY:
--   Direct-slot mutable arrays avoid persistent-map allocation overhead and
--   match the reference C/Rust hot-path behavior.
diffOnepassMutable :: ByteString -> ByteString -> DiffOptions -> [Command]
diffOnepassMutable r v opts = runST $ do
  let p = optSeedLen opts
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

      sliceV a b = BS.take (b - a) (BS.drop a v)
      canAt bsLen pos = pos + p <= bsLen

      finalize !vSFinal !acc
        | vSFinal < vLen = reverse (Add (sliceV vSFinal vLen) : acc)
        | otherwise = reverse acc

  fpV <- newArray (0, q - 1) (0 :: Word64) :: ST s (STUArray s Int Word64)
  offV <- newArray (0, q - 1) (0 :: Int) :: ST s (STUArray s Int Int)
  verV <- newArray (0, q - 1) (-1 :: Int) :: ST s (STUArray s Int Int)

  fpR <- newArray (0, q - 1) (0 :: Word64) :: ST s (STUArray s Int Word64)
  offR <- newArray (0, q - 1) (0 :: Int) :: ST s (STUArray s Int Int)
  verR <- newArray (0, q - 1) (-1 :: Int) :: ST s (STUArray s Int Int)

  let putEntry !ver !fp !off fpArr offArr verArr = do
        let !idx = slot q fp
        curVer <- readArray verArr idx
        if curVer == ver
          then pure ()
          else do
            -- WHAT: retain-first per logical version.
            -- WHY: matches the paper's one-entry-per-slot behavior.
            writeArray fpArr idx fp
            writeArray offArr idx off
            writeArray verArr idx ver

      getEntry !ver !fp fpArr offArr verArr = do
        let !idx = slot q fp
        curVer <- readArray verArr idx
        if curVer /= ver
          then pure Nothing
          else do
            curFp <- readArray fpArr idx
            if curFp == fp
              then Just <$> readArray offArr idx
              else pure Nothing

      lookupFromV !ver !vC mFpV =
        case mFpV of
          Nothing -> pure Nothing
          Just fpV' -> do
            mr <- getEntry ver fpV' fpR offR verR
            case mr of
              Just rCand
                | regionEquals v vC r rCand p -> pure (Just (rCand, vC))
              _ -> pure Nothing

      findMatch !ver !rC !vC mFpR mFpV =
        case mFpR of
          Just fpR' -> do
            mv <- getEntry ver fpR' fpV offV verV
            case mv of
              Just vCand
                | regionEquals r rC v vCand p -> pure (Just (rC, vCand))
              _ -> lookupFromV ver vC mFpV
          Nothing -> lookupFromV ver vC mFpV

      loop !ver !rC !vC !vS !mRhV !mRhR !rhVPos !rhRPos !accRev = do
        let !canV = canAt vLen vC
            !canR = canAt rLen rC

        if not canV && not canR
          then pure (finalize vS accRev)
          else do
            let (!mV, !mRhV1, !rhVPos1) =
                  if canV then calcFingerprint v p vC mRhV rhVPos else (Nothing, mRhV, rhVPos)
                (!mR, !mRhR1, !rhRPos1) =
                  if canR then calcFingerprint r p rC mRhR rhRPos else (Nothing, mRhR, rhRPos)

            case mV of
              Just fp -> putEntry ver fp vC fpV offV verV
              Nothing -> pure ()
            case mR of
              Just fp -> putEntry ver fp rC fpR offR verR
              Nothing -> pure ()

            mMatch <- findMatch ver rC vC mR mV
            case mMatch of
              Nothing ->
                loop ver (rC + 1) (vC + 1) vS mRhV1 mRhR1 rhVPos1 rhRPos1 accRev
              Just (!rM, !vM) -> do
                let !ml = extendForward r v rM vM 0
                if ml < p
                  then loop ver (rC + 1) (vC + 1) vS mRhV1 mRhR1 rhVPos1 rhRPos1 accRev
                  else
                    let !accRev' =
                          if vS < vM
                            then Copy rM ml : Add (sliceV vS vM) : accRev
                            else Copy rM ml : accRev
                        !vNext = vM + ml
                        !rNext = rM + ml
                        -- WHAT: bump logical table version.
                        -- WHY: O(1) "flush" with no physical clearing.
                     in loop (ver + 1) rNext vNext vNext mRhV1 mRhR1 rhVPos1 rhRPos1 accRev'

  loop 0 0 0 0 rhV0 rhR0 0 0 []

-- WHAT:
--   Pure fallback path used for --splay mode.
-- WHY:
--   Keeping this path pure simplifies behavior matching with tree-backed
--   variants while the default mutable hash-table path targets throughput.
diffOnepassPure :: ByteString -> ByteString -> DiffOptions -> [Command]
diffOnepassPure r v opts
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
            lookupFromV (Just fpV') =
              case getOtherR fpV' of
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
      -- WHAT: reinitialize rolling state after a non-adjacent jump.
      -- WHY: jumps happen after accepted matches and keep state exact.
      let !rh' = initRollingHash bs pos p
       in (Just (rhValue rh'), Just rh', pos)
