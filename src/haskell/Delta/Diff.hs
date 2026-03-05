module Delta.Diff
  ( diff
  , diffDefault
  ) where

import Data.ByteString (ByteString)
import Delta.Algorithms.Correcting
import Delta.Algorithms.Greedy
import Delta.Algorithms.Onepass
import Delta.Types

diff :: Algorithm -> ByteString -> ByteString -> DiffOptions -> [Command]
diff algo r v opts =
  case algo of
    Greedy -> diffGreedy r v opts
    Onepass -> diffOnepass r v opts
    Correcting -> diffCorrecting r v opts

diffDefault :: Algorithm -> ByteString -> ByteString -> [Command]
diffDefault algo r v = diff algo r v defaultDiffOptions
