{-# LANGUAGE BangPatterns #-}

module Delta.Util
  ( byteAt
  , regionEquals
  , extendForward
  , extendBackward
  , emitStats
  ) where

import Data.ByteString (ByteString)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Unsafe as BSU
import Data.List (sort)
import Data.Word (Word8)
import Delta.Types
import System.IO (hPutStrLn, stderr)

byteAt :: ByteString -> Int -> Word8
byteAt = BSU.unsafeIndex

regionEquals :: ByteString -> Int -> ByteString -> Int -> Int -> Bool
regionEquals a aOff b bOff len
  | len <= 0 = True
  | otherwise =
      -- ByteString equality delegates to a low-level memcmp path.
      BS.take len (BS.drop aOff a) == BS.take len (BS.drop bOff b)

chunkSize :: Int
chunkSize = 32

extendForward :: ByteString -> ByteString -> Int -> Int -> Int -> Int
extendForward r v rOff vOff start = goChunk start
  where
    maxN = min (BS.length v - vOff) (BS.length r - rOff)
    -- Fast-path fixed-size chunks, then finish byte-by-byte.
    goChunk !n
      | n + chunkSize <= maxN
      , regionEquals v (vOff + n) r (rOff + n) chunkSize =
          goChunk (n + chunkSize)
      | otherwise = goByte n

    goByte !n
      | n >= maxN = n
      | byteAt v (vOff + n) /= byteAt r (rOff + n) = n
      | otherwise = goByte (n + 1)

extendBackward :: ByteString -> ByteString -> Int -> Int -> Int
extendBackward r v rOff vOff = goChunk 0
  where
    maxN = min vOff rOff
    -- Walk backwards in chunks from the current seed boundary.
    goChunk !n
      | n + chunkSize <= maxN
      , regionEquals v (vOff - n - chunkSize) r (rOff - n - chunkSize) chunkSize =
          goChunk (n + chunkSize)
      | otherwise = goByte n

    goByte !n
      | n >= maxN = n
      | byteAt v (vOff - n - 1) /= byteAt r (rOff - n - 1) = n
      | otherwise = goByte (n + 1)

emitStats :: Bool -> [Command] -> IO ()
emitStats False _ = pure ()
emitStats True cmds = do
  let copyLens = [len | Copy _ len <- cmds]
      totalCopy = sum copyLens
      addLens = [BS.length bytes | Add bytes <- cmds]
      totalAdd = sum addLens
      numCopies = length copyLens
      numAdds = length addLens
      totalOut = totalCopy + totalAdd
      copyPct :: Double
      copyPct
        | totalOut == 0 = 0
        | otherwise = fromIntegral totalCopy * 100.0 / fromIntegral totalOut
  hPutStrLn stderr $
    "  result: "
      <> show numCopies
      <> " copies ("
      <> show totalCopy
      <> " bytes), "
      <> show numAdds
      <> " adds ("
      <> show totalAdd
      <> " bytes)"
  hPutStrLn stderr $
    "  result: copy coverage "
      <> showFF copyPct
      <> "%, output "
      <> show totalOut
      <> " bytes"
  case sort copyLens of
    [] -> pure ()
    sorted@(minLen : rest) -> do
      let copyCount = length sorted
          maxLen = foldl' (\_ x -> x) minLen rest
          mid = copyCount `div` 2
          median = listIndexDefault minLen mid sorted
      let mean :: Double
          mean = fromIntegral totalCopy / fromIntegral copyCount
      hPutStrLn stderr $
        "  copies: "
          <> show copyCount
          <> " regions, min="
          <> show minLen
          <> " max="
          <> show maxLen
          <> " mean="
          <> showFF mean
          <> " median="
          <> show median
          <> " bytes"
  where
    showFF :: Double -> String
    showFF x =
      let rounded = fromIntegral (round (x * 10) :: Int) / 10.0 :: Double
       in show rounded

    listIndexDefault :: a -> Int -> [a] -> a
    listIndexDefault def idx = go idx
      where
        go !n (y : ys)
          | n <= 0 = y
          | otherwise = go (n - 1) ys
        go _ [] = def
