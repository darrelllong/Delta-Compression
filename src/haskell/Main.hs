{-# LANGUAGE BangPatterns #-}

module Main (main) where

import qualified Data.ByteString as BS
import Data.Char (toLower)
import Data.List (stripPrefix)
import Data.Time.Clock (diffUTCTime, getCurrentTime)
import Control.Exception (evaluate)
import Delta.Apply
import Delta.Diff
import Delta.Encoding
import Delta.Inplace
import Delta.Types
import Delta.Util (emitStats)
import System.Environment (getArgs)
import System.Exit (die)
import System.IO (hPutStrLn, stderr)
import Text.Printf (printf)

main :: IO ()
main = do
  args <- getArgs
  case args of
    "encode" : rest -> runEncode rest
    "decode" : rest -> runDecode rest
    "info" : rest -> runInfo rest
    "inplace" : rest -> runInplace rest
    _ -> die usage

runEncode :: [String] -> IO ()
runEncode args =
  case args of
    algoStr : reference : version : deltaFile : optArgs -> do
      algo <- parseAlgorithm algoStr
      cfg <- either die pure (parseEncodeOpts defaultEncodeCfg optArgs)

      r <- BS.readFile reference
      v <- BS.readFile version
      let srcCrc = crc64XZ r
          dstCrc = crc64XZ v

      t0 <- getCurrentTime
      let opts =
            defaultDiffOptions
              { optSeedLen = encSeedLen cfg
              , optTableSize = encTableSize cfg
              , optMaxTable = encMaxTable cfg
              , optVerbose = encVerbose cfg
              , optUseSplay = encSplay cfg
              }
          commands = diff algo r v opts
          policy = encPolicy cfg
          (placed, cyclesBroken) =
            if encInplace cfg
              then
                let (ipPlaced, st) = makeInplace r commands policy
                 in (ipPlaced, ipsCyclesBroken st)
              else (placeCommands commands, 0)
          deltaBytes = encodeDelta placed (encInplace cfg) (BS.length v) srcCrc dstCrc
          stats = placedSummary placed
      emitStats (encVerbose cfg) commands
      _ <- evaluate (BS.length deltaBytes)
      t1 <- getCurrentTime

      BS.writeFile deltaFile deltaBytes

      let ratio :: Double
          ratio
            | BS.null v = 0
            | otherwise = fromIntegral (BS.length deltaBytes) / fromIntegral (BS.length v)
          algoName = map toLower (show algo)
          splayTag = if encSplay cfg then " [splay]" else ""

      if encInplace cfg
        then putStrLn $ "Algorithm:    " <> algoName <> splayTag <> " + in-place (" <> map toLower (show policy) <> ")"
        else putStrLn $ "Algorithm:    " <> algoName <> splayTag

      putStrLn $ "Reference:    " <> reference <> " (" <> show (BS.length r) <> " bytes)"
      putStrLn $ "Version:      " <> version <> " (" <> show (BS.length v) <> " bytes)"
      putStrLn $ "Delta:        " <> deltaFile <> " (" <> show (BS.length deltaBytes) <> " bytes)"
      putStrLn $ "Compression:  " <> printf "%.4f" ratio
      putStrLn $ "Commands:     " <> show (psCopies stats) <> " copies, " <> show (psAdds stats) <> " adds"
      if encInplace cfg then putStrLn $ "Cycles broken: " <> show cyclesBroken else pure ()
      putStrLn $ "Copy bytes:   " <> show (psCopyBytes stats)
      putStrLn $ "Add bytes:    " <> show (psAddBytes stats)
      if encVerbose cfg
        then do
          putStrLn $ "Src CRC:      " <> hexCrc64 srcCrc
          putStrLn $ "Dst CRC:      " <> hexCrc64 dstCrc
        else pure ()
      putStrLn $ "Time:         " <> printf "%.3fs" (realToFrac (diffUTCTime t1 t0) :: Double)
    _ -> die usage

runDecode :: [String] -> IO ()
runDecode args =
  case args of
    reference : deltaFile : output : optArgs -> do
      cfg <- either die pure (parseDecodeOpts defaultDecodeCfg optArgs)

      r <- BS.readFile reference
      deltaBytes <- BS.readFile deltaFile

      t0 <- getCurrentTime
      dr <- either die pure (decodeDelta deltaBytes)

      let srcActual = crc64XZ r
      if srcActual /= drSrcCrc dr
        then
          if not (decIgnoreHash cfg)
            then
              die
                ( "error: source file does not match delta: expected "
                    <> hexCrc64 (drSrcCrc dr)
                    <> ", got "
                    <> hexCrc64 srcActual
                )
            else hPutStrLn stderr "warning: skipping source CRC check (--ignore-hash)"
        else pure ()

      case validatePlacedCommands (BS.length r) (drVersionSize dr) (drInplace dr) (drCommands dr) of
        Left err -> die ("Error validating delta: " <> err)
        Right () -> pure ()

      let outBytes =
            if drInplace dr
              then applyPlacedInplace r (drVersionSize dr) (drCommands dr)
              else applyPlaced r (drVersionSize dr) (drCommands dr)

      let outActual = crc64XZ outBytes
      if outActual /= drDstCrc dr
        then
          if not (decIgnoreHash cfg)
            then die "error: output integrity check failed"
            else hPutStrLn stderr "warning: skipping output CRC check (--ignore-hash)"
        else pure ()

      BS.writeFile output outBytes
      t1 <- getCurrentTime

      putStrLn $ "Format:       " <> if drInplace dr then "in-place" else "standard"
      putStrLn $ "Reference:    " <> reference <> " (" <> show (BS.length r) <> " bytes)"
      putStrLn $ "Delta:        " <> deltaFile <> " (" <> show (BS.length deltaBytes) <> " bytes)"
      putStrLn $ "Output:       " <> output <> " (" <> show (drVersionSize dr) <> " bytes)"
      putStrLn $ "Time:         " <> printf "%.3fs" (realToFrac (diffUTCTime t1 t0) :: Double)
    _ -> die usage

runInfo :: [String] -> IO ()
runInfo args =
  case args of
    [deltaFile] -> do
      deltaBytes <- BS.readFile deltaFile
      dr <- either die pure (decodeDelta deltaBytes)
      let stats = placedSummary (drCommands dr)
      putStrLn $ "Delta file:   " <> deltaFile <> " (" <> show (BS.length deltaBytes) <> " bytes)"
      putStrLn $ "Format:       " <> if drInplace dr then "in-place" else "standard"
      putStrLn $ "Version size: " <> show (drVersionSize dr) <> " bytes"
      putStrLn $ "Src CRC:      " <> hexCrc64 (drSrcCrc dr)
      putStrLn $ "Dst CRC:      " <> hexCrc64 (drDstCrc dr)
      putStrLn $ "Commands:     " <> show (psNum stats)
      putStrLn $ "  Copies:     " <> show (psCopies stats) <> " (" <> show (psCopyBytes stats) <> " bytes)"
      putStrLn $ "  Adds:       " <> show (psAdds stats) <> " (" <> show (psAddBytes stats) <> " bytes)"
      putStrLn $ "Output size:  " <> show (psOutputBytes stats) <> " bytes"
    _ -> die usage

runInplace :: [String] -> IO ()
runInplace args =
  case args of
    reference : deltaIn : deltaOut : optArgs -> do
      cfg <- either die pure (parseInplaceOpts defaultInplaceCfg optArgs)

      r <- BS.readFile reference
      deltaBytes <- BS.readFile deltaIn

      dr <- either die pure (decodeDelta deltaBytes)
      if drInplace dr
        then do
          BS.writeFile deltaOut deltaBytes
          putStrLn "Delta is already in-place format; copied unchanged."
        else do
          t0 <- getCurrentTime
          let commands = unplaceCommands (drCommands dr)
              (ipPlaced, ipStats) = makeInplace r commands (inplacePolicy cfg)
              ipDelta = encodeDelta ipPlaced True (drVersionSize dr) (drSrcCrc dr) (drDstCrc dr)
              stats = placedSummary ipPlaced

          BS.writeFile deltaOut ipDelta
          t1 <- getCurrentTime

          if inplaceVerbose cfg
            then do
              hPutStrLn stderr $
                "inplace: "
                  <> show (ipsNumCopies ipStats + ipsCopiesConverted ipStats)
                  <> " copies, "
                  <> show (ipsEdges ipStats)
                  <> " CRWI edges, "
                  <> show (ipsCyclesBroken ipStats)
                  <> " cycles broken"
              if ipsCopiesConverted ipStats > 0
                then
                  hPutStrLn stderr
                    ( "  converted "
                        <> show (ipsCopiesConverted ipStats)
                        <> " copies -> adds ("
                        <> show (ipsBytesConverted ipStats)
                        <> " bytes materialized)"
                    )
                else pure ()
            else pure ()

          putStrLn $ "Reference:    " <> reference <> " (" <> show (BS.length r) <> " bytes)"
          putStrLn $ "Input delta:  " <> deltaIn <> " (" <> show (BS.length deltaBytes) <> " bytes)"
          putStrLn $ "Output delta: " <> deltaOut <> " (" <> show (BS.length ipDelta) <> " bytes)"
          putStrLn $ "Format:       in-place (" <> map toLower (show (inplacePolicy cfg)) <> ")"
          putStrLn $ "Commands:     " <> show (psCopies stats) <> " copies, " <> show (psAdds stats) <> " adds"
          putStrLn $ "Copy bytes:   " <> show (psCopyBytes stats)
          putStrLn $ "Add bytes:    " <> show (psAddBytes stats)
          putStrLn $ "Time:         " <> printf "%.3fs" (realToFrac (diffUTCTime t1 t0) :: Double)
    _ -> die usage

usage :: String
usage =
  unlines
    [ "Usage:"
    , "  delta-hs encode <algorithm> <reference> <version> <delta> [--seed-len N] [--table-size N] [--max-table N|Nk|NM|NB] [--inplace] [--policy localmin|constant] [--verbose] [--splay]"
    , "  delta-hs decode <reference> <delta> <output> [--ignore-hash]"
    , "  delta-hs info <delta>"
    , "  delta-hs inplace <reference> <delta_in> <delta_out> [--policy localmin|constant] [--verbose]"
    , ""
    , "Algorithms: greedy | onepass | correcting"
    ]

parseAlgorithm :: String -> IO Algorithm
parseAlgorithm s =
  case map toLower s of
    "greedy" -> pure Greedy
    "onepass" -> pure Onepass
    "correcting" -> pure Correcting
    _ -> die ("unknown algorithm: " <> s)

parsePolicy :: String -> Either String CyclePolicy
parsePolicy s =
  case map toLower s of
    "localmin" -> Right Localmin
    "constant" -> Right Constant
    _ -> Left ("unknown policy: " <> s)

parsePosInt :: String -> Either String Int
parsePosInt s =
  case reads s of
    [(n, "")] | n >= 1 -> Right n
    _ -> Left ("invalid positive integer: " <> s)

parseSizeSuffix :: String -> Either String Int
parseSizeSuffix s0 =
  let s = trim s0
      (numStr, mult) =
        case reverse s of
          [] -> ("", 1)
          c : rest
            | c == 'k' || c == 'K' -> (reverse rest, 1_000)
            | c == 'm' || c == 'M' -> (reverse rest, 1_000_000)
            | c == 'b' || c == 'B' -> (reverse rest, 1_000_000_000)
            | otherwise -> (s, 1)
   in case reads numStr of
        [(n, "")] | n >= 0 ->
          let x = n * mult
           in if x > maxBound then Left ("'" <> s0 <> "' overflows Int") else Right x
        _ -> Left ("invalid number: '" <> numStr <> "'")

trim :: String -> String
trim = dropLeading . reverse . dropLeading . reverse
  where
    dropLeading = dropWhile (`elem` [' ', '\t', '\n', '\r'])

data EncodeCfg = EncodeCfg
  { encSeedLen :: !Int
  , encTableSize :: !Int
  , encMaxTable :: !Int
  , encInplace :: !Bool
  , encPolicy :: !CyclePolicy
  , encVerbose :: !Bool
  , encSplay :: !Bool
  }

defaultEncodeCfg :: EncodeCfg
defaultEncodeCfg =
  EncodeCfg
    { encSeedLen = seedLen
    , encTableSize = tableSize
    , encMaxTable = maxTableSize
    , encInplace = False
    , encPolicy = Localmin
    , encVerbose = False
    , encSplay = False
    }

parseEncodeOpts :: EncodeCfg -> [String] -> Either String EncodeCfg
parseEncodeOpts cfg [] = Right cfg
parseEncodeOpts cfg ("--seed-len" : v : xs) = parsePosInt v >>= \n -> parseEncodeOpts (cfg {encSeedLen = n}) xs
parseEncodeOpts cfg ("--table-size" : v : xs) = parsePosInt v >>= \n -> parseEncodeOpts (cfg {encTableSize = n}) xs
parseEncodeOpts cfg ("--max-table" : v : xs) = parseSizeSuffix v >>= \n -> parseEncodeOpts (cfg {encMaxTable = n}) xs
parseEncodeOpts cfg ("--policy" : v : xs) = parsePolicy v >>= \p -> parseEncodeOpts (cfg {encPolicy = p}) xs
parseEncodeOpts cfg ("--inplace" : xs) = parseEncodeOpts (cfg {encInplace = True}) xs
parseEncodeOpts cfg ("--verbose" : xs) = parseEncodeOpts (cfg {encVerbose = True}) xs
parseEncodeOpts cfg ("--splay" : xs) = parseEncodeOpts (cfg {encSplay = True}) xs
parseEncodeOpts cfg (x : xs)
  | Just v <- stripPrefix "--seed-len=" x = parsePosInt v >>= \n -> parseEncodeOpts (cfg {encSeedLen = n}) xs
  | Just v <- stripPrefix "--table-size=" x = parsePosInt v >>= \n -> parseEncodeOpts (cfg {encTableSize = n}) xs
  | Just v <- stripPrefix "--max-table=" x = parseSizeSuffix v >>= \n -> parseEncodeOpts (cfg {encMaxTable = n}) xs
  | Just v <- stripPrefix "--policy=" x = parsePolicy v >>= \p -> parseEncodeOpts (cfg {encPolicy = p}) xs
  | otherwise = Left ("unknown encode option: " <> x)

data DecodeCfg = DecodeCfg
  { decIgnoreHash :: !Bool
  }

defaultDecodeCfg :: DecodeCfg
defaultDecodeCfg = DecodeCfg False

parseDecodeOpts :: DecodeCfg -> [String] -> Either String DecodeCfg
parseDecodeOpts cfg [] = Right cfg
parseDecodeOpts cfg ("--ignore-hash" : xs) = parseDecodeOpts (cfg {decIgnoreHash = True}) xs
parseDecodeOpts _ (x : _) = Left ("unknown decode option: " <> x)

data InplaceCfg = InplaceCfg
  { inplacePolicy :: !CyclePolicy
  , inplaceVerbose :: !Bool
  }

defaultInplaceCfg :: InplaceCfg
defaultInplaceCfg = InplaceCfg Localmin False

parseInplaceOpts :: InplaceCfg -> [String] -> Either String InplaceCfg
parseInplaceOpts cfg [] = Right cfg
parseInplaceOpts cfg ("--policy" : v : xs) = parsePolicy v >>= \p -> parseInplaceOpts (cfg {inplacePolicy = p}) xs
parseInplaceOpts cfg ("--verbose" : xs) = parseInplaceOpts (cfg {inplaceVerbose = True}) xs
parseInplaceOpts cfg (x : xs)
  | Just v <- stripPrefix "--policy=" x = parsePolicy v >>= \p -> parseInplaceOpts (cfg {inplacePolicy = p}) xs
  | otherwise = Left ("unknown inplace option: " <> x)

data PlacedStats = PlacedStats
  { psNum :: !Int
  , psCopies :: !Int
  , psAdds :: !Int
  , psCopyBytes :: !Int
  , psAddBytes :: !Int
  , psOutputBytes :: !Int
  }

placedSummary :: [PlacedCommand] -> PlacedStats
placedSummary cmds =
  let (copies, adds, copyBytes, addBytes) = foldl' step (0, 0, 0, 0) cmds
      total = copyBytes + addBytes
   in PlacedStats
        { psNum = length cmds
        , psCopies = copies
        , psAdds = adds
        , psCopyBytes = copyBytes
        , psAddBytes = addBytes
        , psOutputBytes = total
        }
  where
    step (!cN, !aN, !cB, !aB) cmd =
      case cmd of
        PlacedCopy _ _ len -> (cN + 1, aN, cB + len, aB)
        PlacedAdd _ bytes -> (cN, aN + 1, cB, aB + BS.length bytes)
