{-# LANGUAGE BangPatterns #-}

module Delta.Apply
  ( outputSize
  , placeCommands
  , unplaceCommands
  , applyCommands
  , applyPlaced
  , applyPlacedInplace
  , validatePlacedCommands
  ) where

-- WHAT:
--   Placement, apply, and validation for command streams.
-- WHY:
--   Encoding and decoding are defined over placed commands; this module is the
--   canonical executor used by tests for cross-language byte compatibility.

import Control.Monad (forM_)
import Control.Monad.ST (ST)
import Data.Array.ST (STUArray, newArray, readArray, runSTUArray, writeArray)
import Data.Array.Unboxed (UArray, elems)
import Data.ByteString (ByteString)
import qualified Data.ByteString as BS
import Data.List (mapAccumL, sortOn)
import Data.Word (Word8)
import Delta.Types
import Delta.Util (byteAt)

outputSize :: [Command] -> Int
outputSize = sum . map sizeOf
  where
    sizeOf (Copy _ len) = len
    sizeOf (Add bytes) = BS.length bytes

placeCommands :: [Command] -> [PlacedCommand]
placeCommands cmds = snd (mapAccumL step 0 cmds)
  where
    step !dst (Copy src len) =
      let next = dst + len
       in (next, PlacedCopy src dst len)
    step !dst (Add bytes) =
      let len = BS.length bytes
          next = dst + len
       in (next, PlacedAdd dst bytes)

unplaceCommands :: [PlacedCommand] -> [Command]
unplaceCommands = map strip . sortOn dstOf
  where
    dstOf (PlacedCopy _ dst _) = dst
    dstOf (PlacedAdd dst _) = dst
    strip (PlacedCopy src _ len) = Copy src len
    strip (PlacedAdd _ bytes) = Add bytes

applyCommands :: ByteString -> [Command] -> ByteString
applyCommands ref = BS.concat . map toChunk
  where
    toChunk (Copy src len) = BS.take len (BS.drop src ref)
    toChunk (Add bytes) = bytes

applyPlaced :: ByteString -> Int -> [PlacedCommand] -> ByteString
applyPlaced ref versionSize cmds
  | versionSize <= 0 = BS.empty
  | otherwise = BS.pack (elems arr)
  where
    arr :: UArray Int Word8
    arr = runSTUArray $ do
      out <- newArray (0, versionSize - 1) 0
      forM_ cmds (applyOne out)
      pure out

    applyOne :: STUArray s Int Word8 -> PlacedCommand -> ST s ()
    applyOne out (PlacedCopy src dst len) =
      copyFromRef 0
      where
        copyFromRef !i
          | i >= len = pure ()
          | otherwise = do
              writeArray out (dst + i) (byteAt ref (src + i))
              copyFromRef (i + 1)
    applyOne out (PlacedAdd dst bytes) =
      copyBytes 0
      where
        !len = BS.length bytes
        copyBytes !i
          | i >= len = pure ()
          | otherwise = do
              writeArray out (dst + i) (byteAt bytes i)
              copyBytes (i + 1)

applyPlacedInplace :: ByteString -> Int -> [PlacedCommand] -> ByteString
applyPlacedInplace ref versionSize cmds
  | versionSize <= 0 = BS.empty
  | otherwise = BS.pack (take versionSize (elems arr))
  where
    bufSize = max (BS.length ref) versionSize

    arr :: UArray Int Word8
    arr = runSTUArray $ do
      out <- newArray (0, bufSize - 1) 0
      copyRefPrefix out 0
      forM_ cmds (applyOne out)
      pure out

    copyRefPrefix :: STUArray s Int Word8 -> Int -> ST s ()
    copyRefPrefix out !i
      | i >= BS.length ref = pure ()
      | otherwise = do
          writeArray out i (byteAt ref i)
          copyRefPrefix out (i + 1)

    applyOne :: STUArray s Int Word8 -> PlacedCommand -> ST s ()
    applyOne out (PlacedCopy src dst len)
      | len <= 0 = pure ()
      | dst <= src || dst >= src + len =
          -- WHAT: non-overlapping or forward-safe copy.
          -- WHY: forward traversal cannot clobber unread source bytes.
          copyForward 0
      | otherwise =
          -- WHAT: overlapping move with destination inside source range.
          -- WHY: reverse traversal preserves source bytes (memmove semantics).
          copyBackward (len - 1)
      where
        copyForward !i
          | i >= len = pure ()
          | otherwise = do
              b <- readArray out (src + i)
              writeArray out (dst + i) b
              copyForward (i + 1)

        copyBackward !i
          | i < 0 = pure ()
          | otherwise = do
              b <- readArray out (src + i)
              writeArray out (dst + i) b
              copyBackward (i - 1)
    applyOne out (PlacedAdd dst bytes) =
      copyBytes 0
      where
        !len = BS.length bytes
        copyBytes !i
          | i >= len = pure ()
          | otherwise = do
              writeArray out (dst + i) (byteAt bytes i)
              copyBytes (i + 1)

validatePlacedCommands :: Int -> Int -> Bool -> [PlacedCommand] -> Either String ()
validatePlacedCommands referenceSize versionSize inplace cmds =
  foldr step (Right ()) cmds
  where
    sourceLimit
      | inplace = max referenceSize versionSize
      | otherwise = referenceSize

    step _ (Left err) = Left err
    step cmd (Right ()) =
      case cmd of
        PlacedCopy src dst len
          | not (validRange dst len versionSize) -> Left "copy destination out of range"
          | not (validRange src len sourceLimit) -> Left "copy source out of range"
          | otherwise -> Right ()
        PlacedAdd dst bytes
          | not (validRange dst (BS.length bytes) versionSize) -> Left "add destination out of range"
          | otherwise -> Right ()

    validRange start len limit =
      start >= 0 && len >= 0 && start <= limit && len <= limit - start
