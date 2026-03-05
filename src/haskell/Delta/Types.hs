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

seedLen :: Int
seedLen = 16

tableSize :: Int
tableSize = 1_048_573

maxTableSize :: Int
maxTableSize = 1_073_741_827

hashBase :: Word64
hashBase = 263

hashMod :: Word64
hashMod = 2_305_843_009_213_693_951

deltaBufCap :: Int
deltaBufCap = 256

data Command
  = Copy !Int !Int
  | Add !ByteString
  deriving (Eq, Show)

data PlacedCommand
  = PlacedCopy !Int !Int !Int
  | PlacedAdd !Int !ByteString
  deriving (Eq, Show)

data Algorithm
  = Greedy
  | Onepass
  | Correcting
  deriving (Eq, Show, Read)

data CyclePolicy
  = Localmin
  | Constant
  deriving (Eq, Show, Read)

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
