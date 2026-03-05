{-# LANGUAGE NumericUnderscores #-}

module Delta.Types
  ( seedLen
  , tableSize
  , maxTableSize
  , hashBase
  , hashMod
  , deltaBufCap
  , Command(..)
  , PlacedCommand(..)
  , Algorithm(..)
  , CyclePolicy(..)
  , DiffOptions(..)
  , defaultDiffOptions
  ) where

import Data.ByteString (ByteString)
import Data.Word (Word64)

-- | Default seed length @p@ used by rolling-hash based match detection.
seedLen :: Int
seedLen = 16

-- | Floor for hash table size; large inputs auto-grow beyond this.
tableSize :: Int
tableSize = 1_048_573

-- | Hard cap used by correcting auto-sizing to bound memory use.
maxTableSize :: Int
maxTableSize = 1_073_741_827

-- | Polynomial base for Karp-Rabin fingerprints.
hashBase :: Word64
hashBase = 263

-- | Prime modulus @2^61 - 1@ used for rolling hash arithmetic.
hashMod :: Word64
hashMod = 2_305_843_009_213_693_951

-- | Lookback buffer size for correcting tail-correction.
deltaBufCap :: Int
deltaBufCap = 256

-- | Unplaced edit script command.
-- Copy carries source offset and length; destination is implicit.
data Command
  = Copy !Int !Int
  | Add !ByteString
  deriving (Eq, Show)

-- | Placed command with explicit destination offset in the output stream.
data PlacedCommand
  = PlacedCopy !Int !Int !Int
  | PlacedAdd !Int !ByteString
  deriving (Eq, Show)

-- | Diff algorithm selector.
data Algorithm
  = Greedy
  | Onepass
  | Correcting
  deriving (Eq, Show, Read)

-- | Cycle-breaking policy for in-place conversion.
data CyclePolicy
  = Localmin
  | Constant
  deriving (Eq, Show, Read)

-- | Shared tunables passed to all diff algorithms.
data DiffOptions = DiffOptions
  { optSeedLen :: !Int
  , optTableSize :: !Int
  , optBufCap :: !Int
  , optVerbose :: !Bool
  , optUseSplay :: !Bool
  , optMaxTable :: !Int
  }
  deriving (Eq, Show)

defaultDiffOptions :: DiffOptions
defaultDiffOptions =
  DiffOptions
    { optSeedLen = seedLen
    , optTableSize = tableSize
    , optBufCap = deltaBufCap
    , optVerbose = False
    , optUseSplay = False
    , optMaxTable = maxTableSize
    }
