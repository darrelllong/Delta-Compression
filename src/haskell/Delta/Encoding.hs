{-# LANGUAGE BangPatterns #-}
{-# LANGUAGE NumericUnderscores #-}

module Delta.Encoding
  ( DecodeResult(..)
  , encodeDelta
  , decodeDelta
  , isInplaceDelta
  , crc64XZ
  , hexCrc64
  ) where

-- WHAT:
--   Binary delta wire-format encoder/decoder plus CRC64/XZ helpers.
-- WHY:
--   The format must be byte-identical across all language implementations so
--   any encoder output can be decoded by any decoder.

import Control.Monad (unless)
import Data.Array.Unboxed (UArray, (!), listArray)
import Data.Binary.Get
import Data.Binary.Put
import Data.Bits ((.&.), shiftR, xor)
import Data.ByteString (ByteString)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BSL
import Data.Char (toLower)
import Numeric (showHex)
import Data.Word (Word64, Word8)
import Delta.Types

deltaMagic :: ByteString
deltaMagic = BS.pack [0x44, 0x4C, 0x54, 0x03]

deltaFlagInplace :: Word8
deltaFlagInplace = 0x01

deltaCmdEnd, deltaCmdCopy, deltaCmdAdd :: Word8
deltaCmdEnd = 0
deltaCmdCopy = 1
deltaCmdAdd = 2

data DecodeResult = DecodeResult
  { drCommands :: ![PlacedCommand]
  , drInplace :: !Bool
  , drVersionSize :: !Int
  , drSrcCrc :: !Word64
  , drDstCrc :: !Word64
  }
  deriving (Eq, Show)

encodeDelta :: [PlacedCommand] -> Bool -> Int -> Word64 -> Word64 -> ByteString
encodeDelta commands inplace versionSize srcCrc dstCrc =
  BSL.toStrict . runPut $ do
    putByteString deltaMagic
    putWord8 (if inplace then deltaFlagInplace else 0)
    putWord32be (fromIntegral versionSize)
    putWord64be srcCrc
    putWord64be dstCrc
    mapM_ putCommand commands
    putWord8 deltaCmdEnd
  where
    putCommand (PlacedCopy src dst len) = do
      putWord8 deltaCmdCopy
      putWord32be (fromIntegral src)
      putWord32be (fromIntegral dst)
      putWord32be (fromIntegral len)
    putCommand (PlacedAdd dst bytes) = do
      putWord8 deltaCmdAdd
      putWord32be (fromIntegral dst)
      putWord32be (fromIntegral (BS.length bytes))
      putByteString bytes

decodeDelta :: ByteString -> Either String DecodeResult
decodeDelta raw =
  case runGetOrFail parser (BSL.fromStrict raw) of
    Left (_, _, err) -> Left err
    Right (rest, _, result)
      | BSL.null rest -> Right result
      | otherwise -> Left "trailing bytes after END"
  where
    parser :: Get DecodeResult
    parser = do
      magic <- getByteString 4
      unless (magic == deltaMagic) (fail "not a delta file")
      flags <- getWord8
      let inplace = (flags .&. deltaFlagInplace) /= 0
      versionSize <- fromIntegral <$> getWord32be
      srcCrc <- getWord64be
      dstCrc <- getWord64be
      cmds <- loop versionSize []
      pure
        DecodeResult
          { drCommands = reverse cmds
          , drInplace = inplace
          , drVersionSize = versionSize
          , drSrcCrc = srcCrc
          , drDstCrc = dstCrc
          }

    loop :: Int -> [PlacedCommand] -> Get [PlacedCommand]
    loop versionSize acc = do
      t <- getWord8
      case t of
        _ | t == deltaCmdEnd -> pure acc
          | t == deltaCmdCopy -> do
              src <- fromIntegral <$> getWord32be
              dst <- fromIntegral <$> getWord32be
              len <- fromIntegral <$> getWord32be
              unless (validRange dst len versionSize) (fail "copy command exceeds version size")
              loop versionSize (PlacedCopy src dst len : acc)
          | t == deltaCmdAdd -> do
              dst <- fromIntegral <$> getWord32be
              len <- fromIntegral <$> getWord32be
              unless (validRange dst len versionSize) (fail "add command exceeds version size")
              payload <- getByteString len
              loop versionSize (PlacedAdd dst payload : acc)
          | otherwise -> fail ("unknown command type: " <> show t)

    validRange start len limit =
      start >= 0 && len >= 0 && start <= limit && len <= limit - start

isInplaceDelta :: ByteString -> Bool
isInplaceDelta dataBytes =
  BS.length dataBytes >= 5
    && BS.take 4 dataBytes == deltaMagic
    && ((BS.index dataBytes 4 .&. deltaFlagInplace) /= 0)

crc64XZ :: ByteString -> Word64
crc64XZ = finalize . BS.foldl' crcStep 0xFFFF_FFFF_FFFF_FFFF
  where
    -- CRC64/XZ uses init/final xor with all-ones.
    finalize crc = crc `xor` 0xFFFF_FFFF_FFFF_FFFF
    crcStep crc b =
      let idx = fromIntegral ((crc `xor` fromIntegral b) .&. 0xFF)
       in (crc64Table ! idx) `xor` (crc `shiftR` 8)

crc64Poly :: Word64
crc64Poly = 0xC96C_5795_D787_0F42

-- WHAT: precomputed 256-entry CRC lookup table.
-- WHY: keeps per-byte CRC updates O(1) with one table load.
{-# NOINLINE crc64Table #-}
crc64Table :: UArray Int Word64
crc64Table = listArray (0, 255) [crc64Entry i | i <- [0 .. 255]]

crc64Entry :: Int -> Word64
crc64Entry i = iter 8 (fromIntegral i)
  where
    iter :: Int -> Word64 -> Word64
    iter 0 c = c
    iter n c
      | c .&. 1 == 1 = iter (n - 1) ((c `shiftR` 1) `xor` crc64Poly)
      | otherwise = iter (n - 1) (c `shiftR` 1)

hexCrc64 :: Word64 -> String
hexCrc64 w = pad16 (map toLower (showHex w ""))
  where
    pad16 s
      | length s >= 16 = s
      | otherwise = replicate (16 - length s) '0' <> s
