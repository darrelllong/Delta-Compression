module Main (main) where

import qualified Data.ByteString as BS
import Data.Char (toLower)
import Data.Word (Word64)
import Delta.Apply
import Delta.Diff
import Delta.Encoding
import Delta.Types
import Delta.Util (emitStats)
import System.Environment (getArgs)
import System.Exit (die)

main :: IO ()
main = do
  args <- getArgs
  case args of
    ["encode", algoStr, refPath, verPath, deltaPath] -> do
      algo <- parseAlgorithm algoStr
      ref <- BS.readFile refPath
      ver <- BS.readFile verPath
      let opts = defaultDiffOptions
          commands = diff algo ref ver opts
      emitStats (optVerbose opts) commands
      let placed = placeCommands commands
          delta = encodeDelta placed False (BS.length ver) (crc64XZ ref) (crc64XZ ver)
      BS.writeFile deltaPath delta
    ["decode", refPath, deltaPath, outPath] -> do
      ref <- BS.readFile refPath
      delta <- BS.readFile deltaPath
      result <- either die pure (decodeDelta delta)
      if drInplace result
        then die "in-place deltas are not yet supported in Haskell decode"
        else pure ()
      if crc64XZ ref /= drSrcCrc result
        then die "reference CRC mismatch"
        else pure ()
      case validatePlacedCommands (BS.length ref) (drVersionSize result) False (drCommands result) of
        Left err -> die err
        Right () -> pure ()
      let out = applyPlaced ref (drVersionSize result) (drCommands result)
      if crc64XZ out /= drDstCrc result
        then die "output CRC mismatch"
        else BS.writeFile outPath out
    ["info", deltaPath] -> do
      delta <- BS.readFile deltaPath
      result <- either die pure (decodeDelta delta)
      putStrLn $ "inplace: " <> show (drInplace result)
      putStrLn $ "version_size: " <> show (drVersionSize result)
      putStrLn $ "commands: " <> show (length (drCommands result))
      putStrLn $ "src_crc64_xz: " <> showHex64 (drSrcCrc result)
      putStrLn $ "dst_crc64_xz: " <> showHex64 (drDstCrc result)
    _ ->
      die usage

usage :: String
usage =
  unlines
    [ "Usage:"
    , "  delta-hs encode <algorithm> <reference> <version> <delta>"
    , "  delta-hs decode <reference> <delta> <output>"
    , "  delta-hs info <delta>"
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

showHex64 :: Word64 -> String
showHex64 w = "0x" <> pad 16 (showHex w)
  where
    showHex 0 = "0"
    showHex n = reverse (go n)
    go 0 = []
    go x =
      let (q, r) = x `quotRem` 16
       in hexDigit r : go q
    hexDigit = ("0123456789abcdef" !!) . fromIntegral
    pad n s
      | length s >= n = s
      | otherwise = replicate (n - length s) '0' <> s
