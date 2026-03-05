{-# LANGUAGE BangPatterns #-}

module Delta.Hash
  ( RollingHash(..)
  , initRollingHash
  , rollHash
  , fingerprint
  , nextPrime
  ) where

import Data.Bits ((.&.), shiftR)
import Data.ByteString (ByteString)
import qualified Data.ByteString as BS
import Data.Word (Word64)
import Delta.Types

-- | Rolling hash state for a fixed seed length.
data RollingHash = RollingHash
  { rhValue :: !Word64
  , rhBp :: !Word64
  , rhSeedLen :: !Int
  }
  deriving (Eq, Show)

initRollingHash :: ByteString -> Int -> Int -> RollingHash
initRollingHash bs off p =
  RollingHash
    { rhValue = fingerprint bs off p
    , rhBp = powMod hashBase (p - 1)
    , rhSeedLen = p
    }

rollHash :: Word64 -> Word64 -> RollingHash -> RollingHash
rollHash oldByte newByte rh =
  rh {rhValue = addMod (mulMod stripped hashBase) newByte}
  where
    stripped = subMod (rhValue rh) (mulMod oldByte (rhBp rh))

fingerprint :: ByteString -> Int -> Int -> Word64
fingerprint bs off p = go 0 0
  where
    go !i !h
      | i >= p = h
      | otherwise =
          let b = fromIntegral (BS.index bs (off + i)) :: Word64
              h' = addMod (mulMod h hashBase) b
           in go (i + 1) h'

mulMod :: Word64 -> Word64 -> Word64
mulMod a b =
  fromInteger $
    (toInteger a * toInteger b) `mod` toInteger hashMod

addMod :: Word64 -> Word64 -> Word64
addMod a b
  | s >= hashMod = s - hashMod
  | otherwise = s
  where
    s = a + b

subMod :: Word64 -> Word64 -> Word64
subMod a b
  | a >= b = a - b
  | otherwise = hashMod - (b - a)

powMod :: Word64 -> Int -> Word64
powMod base exp0 = go 1 base exp0
  where
    go !acc !_ 0 = acc
    go !acc !b e
      | e .&. 1 == 1 = go (mulMod acc b) (mulMod b b) (e `shiftR` 1)
      | otherwise = go acc (mulMod b b) (e `shiftR` 1)

nextPrime :: Int -> Int
nextPrime n
  | n <= 2 = 2
  | even n = go (n + 1)
  | otherwise = go n
  where
    go !m
      | isPrime m = m
      | otherwise = go (m + 2)

isPrime :: Int -> Bool
isPrime k
  | k < 2 = False
  | k == 2 = True
  | even k = False
  | otherwise = all (\a -> not (isWitness a k)) witnesses
  where
    witnesses = takeWhile (< k) [2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37]

isWitness :: Int -> Int -> Bool
isWitness a n =
  let (d, r) = factorTwos (n - 1)
      n' = toInteger n
      a' = toInteger a
      x0 = powModInteger a' (toInteger d) n'
   in if x0 == 1 || x0 == n' - 1
        then False
        else loop (r - 1) x0
  where
    loop 0 _ = True
    loop i x =
      let x' = (x * x) `mod` toInteger n
       in if x' == toInteger n - 1 then False else loop (i - 1) x'

factorTwos :: Int -> (Int, Int)
factorTwos n = go n 0
  where
    go !d !r
      | odd d = (d, r)
      | otherwise = go (d `div` 2) (r + 1)

powModInteger :: Integer -> Integer -> Integer -> Integer
powModInteger base exp0 m = go 1 (base `mod` m) exp0
  where
    go !acc !_ 0 = acc
    go !acc !b e
      | e .&. 1 == 1 = go ((acc * b) `mod` m) ((b * b) `mod` m) (e `shiftR` 1)
      | otherwise = go acc ((b * b) `mod` m) (e `shiftR` 1)
