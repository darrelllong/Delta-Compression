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
      forM_ [0 .. len - 1] $ \i ->
        writeArray out (dst + i) (byteAt ref (src + i))
    applyOne out (PlacedAdd dst bytes) =
      forM_ [0 .. BS.length bytes - 1] $ \i ->
        writeArray out (dst + i) (byteAt bytes i)

applyPlacedInplace :: ByteString -> Int -> [PlacedCommand] -> ByteString
applyPlacedInplace ref versionSize cmds
  | versionSize <= 0 = BS.empty
  | otherwise = BS.pack (take versionSize (elems arr))
  where
    bufSize = max (BS.length ref) versionSize

    arr :: UArray Int Word8
    arr = runSTUArray $ do
      out <- newArray (0, bufSize - 1) 0
      forM_ [0 .. BS.length ref - 1] $ \i ->
        writeArray out i (byteAt ref i)
      forM_ cmds (applyOne out)
      pure out

    applyOne :: STUArray s Int Word8 -> PlacedCommand -> ST s ()
    applyOne out (PlacedCopy src dst len)
      | len <= 0 = pure ()
      | dst <= src || dst >= src + len =
          forM_ [0 .. len - 1] $ \i -> do
            b <- readArray out (src + i)
            writeArray out (dst + i) b
      | otherwise =
          forM_ [len - 1, len - 2 .. 0] $ \i -> do
            b <- readArray out (src + i)
            writeArray out (dst + i) b
    applyOne out (PlacedAdd dst bytes) =
      forM_ [0 .. BS.length bytes - 1] $ \i ->
        writeArray out (dst + i) (byteAt bytes i)

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
