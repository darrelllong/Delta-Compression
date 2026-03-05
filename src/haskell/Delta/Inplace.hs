{-# LANGUAGE BangPatterns #-}

module Delta.Inplace
  ( InplaceStats(..)
  , makeInplace
  ) where

import Data.ByteString (ByteString)
import qualified Data.ByteString as BS
import Data.Foldable (toList)
import Data.List (foldl', minimumBy)
import Data.Ord (comparing)
import Data.Sequence (Seq, (|>))
import qualified Data.Sequence as Seq
import qualified Data.IntMap.Strict as IM
import qualified Data.IntSet as IS
import qualified Data.Map.Strict as M
import qualified Data.Set as Set
import Delta.Types

data CopyInfo = CopyInfo
  { ciSrc :: !Int
  , ciDst :: !Int
  , ciLen :: !Int
  }
  deriving (Eq, Show)

data InplaceStats = InplaceStats
  { ipsNumCopies :: !Int
  , ipsNumAdds :: !Int
  , ipsEdges :: !Int
  , ipsCyclesBroken :: !Int
  , ipsCopiesConverted :: !Int
  , ipsBytesConverted :: !Int
  }
  deriving (Eq, Show)

emptyStats :: InplaceStats
emptyStats =
  InplaceStats
    { ipsNumCopies = 0
    , ipsNumAdds = 0
    , ipsEdges = 0
    , ipsCyclesBroken = 0
    , ipsCopiesConverted = 0
    , ipsBytesConverted = 0
    }

makeInplace :: ByteString -> [Command] -> CyclePolicy -> ([PlacedCommand], InplaceStats)
makeInplace ref commands policy
  | null commands = ([], emptyStats)
  | null copyInfos =
      let adds = [PlacedAdd dst bytes | (dst, bytes) <- toList addInfo0]
          st = emptyStats {ipsNumAdds = length adds}
       in (adds, st)
  | otherwise =
      let n = length copyInfos
          (adj, indeg0, edgeCount) = buildCrwiDigraph copyInfos
          copyLens = IM.fromList (zip [0 ..] (map ciLen copyInfos))
          initialHeap =
            Set.fromList
              [ (copyLens IM.! i, i)
              | i <- [0 .. n - 1]
              , IM.findWithDefault 0 i indeg0 == 0
              ]
          (topoRev, addInfoFinal, stRun) = runKahn n adj indeg0 copyInfos copyLens initialHeap policy addInfo0
          topo = reverse topoRev
          placedCopies =
            [ let c = copyInfos !! i in PlacedCopy (ciSrc c) (ciDst c) (ciLen c)
            | i <- topo
            ]
          placedAdds = [PlacedAdd dst bytes | (dst, bytes) <- toList addInfoFinal]
          out = placedCopies ++ placedAdds
          st =
            stRun
              { ipsNumCopies = length placedCopies
              , ipsNumAdds = length placedAdds
              , ipsEdges = edgeCount
              }
       in (out, st)
  where
    (copyInfos, addInfo0) = collectInfo commands

collectInfo :: [Command] -> ([CopyInfo], Seq (Int, ByteString))
collectInfo = go 0 [] Seq.empty
  where
    go !_revDst copyAcc addAcc [] = (reverse copyAcc, addAcc)
    go !dst copyAcc addAcc (cmd : rest) =
      case cmd of
        Copy src len ->
          go (dst + len) (CopyInfo src dst len : copyAcc) addAcc rest
        Add bytes ->
          let len = BS.length bytes
           in go (dst + len) copyAcc (addAcc |> (dst, bytes)) rest

buildCrwiDigraph :: [CopyInfo] -> (IM.IntMap [Int], IM.IntMap Int, Int)
buildCrwiDigraph infos =
  foldl' step (IM.empty, IM.fromList [(i, 0) | i <- idxs], 0) idxs
  where
    idxs = [0 .. length infos - 1]

    step (!adj, !indeg, !edges) i =
      foldl' (addEdge i) (adj, indeg, edges) idxs

    addEdge i (!adj, !indeg, !edges) j
      | i == j = (adj, indeg, edges)
      | overlaps (infos !! i) (infos !! j) =
          let adj' = IM.insertWith (++) i [j] adj
              indeg' = IM.insertWith (+) j 1 indeg
           in (adj', indeg', edges + 1)
      | otherwise = (adj, indeg, edges)

    overlaps a b =
      let readStart = ciSrc a
          readEnd = ciSrc a + ciLen a
          writeStart = ciDst b
          writeEnd = ciDst b + ciLen b
       in readStart < writeEnd && writeStart < readEnd

runKahn
  :: Int
  -> IM.IntMap [Int]
  -> IM.IntMap Int
  -> [CopyInfo]
  -> IM.IntMap Int
  -> Set.Set (Int, Int)
  -> CyclePolicy
  -> Seq (Int, ByteString)
  -> ([Int], Seq (Int, ByteString), InplaceStats)
runKahn n adj indeg0 infos copyLens heap0 policy addInfo0 =
  go 0 IS.empty indeg0 heap0 [] addInfo0 emptyStats
  where
    go !processed !removed !indeg !heap !topoRev !addInfo !stats
      | processed >= n = (topoRev, addInfo, stats)
      | not (Set.null heap) =
          let ((_, v), heap1) = Set.deleteFindMin heap
           in if IS.member v removed
                then go processed removed indeg heap1 topoRev addInfo stats
                else
                  let removed' = IS.insert v removed
                      (indeg', heap2) = relaxOutEdges v removed' indeg heap1
                   in go (processed + 1) removed' indeg' heap2 (v : topoRev) addInfo stats
      | otherwise =
          let victim = pickVictim policy n infos adj removed
              ci = infos !! victim
              literal = BS.take (ciLen ci) (BS.drop (ciSrc ci) ref)
              addInfo' = addInfo |> (ciDst ci, literal)
              removed' = IS.insert victim removed
              (indeg', heap1) = relaxOutEdges victim removed' indeg heap
              stats' =
                stats
                  { ipsCyclesBroken = ipsCyclesBroken stats + 1
                  , ipsCopiesConverted = ipsCopiesConverted stats + 1
                  , ipsBytesConverted = ipsBytesConverted stats + ciLen ci
                  }
           in go (processed + 1) removed' indeg' heap1 topoRev addInfo' stats'

    relaxOutEdges v removed indeg heap =
      foldl' step (indeg, heap) (IM.findWithDefault [] v adj)
      where
        step (!d, !h) w
          | IS.member w removed = (d, h)
          | otherwise =
              let old = IM.findWithDefault 0 w d
                  new = old - 1
                  d' = IM.insert w new d
                  h' = if new == 0 then Set.insert (copyLens IM.! w, w) h else h
               in (d', h')

pickVictim :: CyclePolicy -> Int -> [CopyInfo] -> IM.IntMap [Int] -> IS.IntSet -> Int
pickVictim policy n infos adj removed =
  case policy of
    Constant -> firstRemaining
    Localmin ->
      case findCycle n adj removed of
        Just cycleNodes ->
          minimumBy (comparing key) cycleNodes
        Nothing -> firstRemaining
  where
    firstRemaining = head [i | i <- [0 .. n - 1], not (IS.member i removed)]
    key i = (ciLen (infos !! i), i)

findCycle :: Int -> IM.IntMap [Int] -> IS.IntSet -> Maybe [Int]
findCycle n adj removed = goStarts 0 M.empty
  where
    goStarts i color
      | i >= n = Nothing
      | IS.member i removed = goStarts (i + 1) color
      | M.findWithDefault 0 i color /= (0 :: Int) = goStarts (i + 1) color
      | otherwise =
          case dfs i [i] color of
            (Just cyc, _) -> Just cyc
            (Nothing, color') -> goStarts (i + 1) color'

    dfs v path color0 =
      let color1 = M.insert v 1 color0
       in walk (IM.findWithDefault [] v adj) color1
      where
        walk [] color = (Nothing, M.insert v 2 color)
        walk (w : ws) color
          | IS.member w removed = walk ws color
          | otherwise =
              case M.findWithDefault 0 w color of
                0 ->
                  let (res, color') = dfs w (w : path) color
                   in case res of
                        Just cyc -> (Just cyc, color')
                        Nothing -> walk ws color'
                1 ->
                  let seg = takeWhile (/= w) path
                   in (Just (reverse (w : seg)), color)
                _ -> walk ws color
