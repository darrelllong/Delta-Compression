{-# LANGUAGE BangPatterns #-}

module Delta.Inplace
  ( InplaceStats(..)
  , makeInplace
  ) where

import Data.Array (Array, (!), accumArray, bounds, listArray)
import Data.ByteString (ByteString)
import qualified Data.ByteString as BS
import Data.Foldable (toList)
import Data.List (foldl', minimumBy, sortBy)
import Data.Ord (comparing)
import Data.Sequence (Seq, (|>))
import qualified Data.Sequence as Seq
import qualified Data.Graph as G
import qualified Data.IntMap.Strict as IM
import qualified Data.IntSet as IS
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
  | n == 0 =
      let adds = [PlacedAdd dst bytes | (dst, bytes) <- toList addInfo0]
          st = emptyStats {ipsNumAdds = length adds}
       in (adds, st)
  | otherwise =
      let (adj, indeg0, edgeCount) = buildCrwiDigraph copyArr n
          copyLens = IM.fromList [(i, ciLen (copyArr ! i)) | i <- [0 .. n - 1]]
          initialHeap =
            Set.fromList
              [ (copyLens IM.! i, i)
              | i <- [0 .. n - 1]
              , IM.findWithDefault 0 i indeg0 == 0
              ]
          (topoRev, addInfoFinal, stRun) =
            runKahn ref n adj indeg0 copyArr copyLens initialHeap policy addInfo0
          topo = reverse topoRev
          placedCopies =
            [ let c = copyArr ! i in PlacedCopy (ciSrc c) (ciDst c) (ciLen c)
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
    n = length copyInfos
    copyArr = listArray (0, n - 1) copyInfos

collectInfo :: [Command] -> ([CopyInfo], Seq (Int, ByteString))
collectInfo = go 0 [] Seq.empty
  where
    go !_ copyAcc addAcc [] = (reverse copyAcc, addAcc)
    go !dst copyAcc addAcc (cmd : rest) =
      case cmd of
        Copy src len ->
          go (dst + len) (CopyInfo src dst len : copyAcc) addAcc rest
        Add bytes ->
          let len = BS.length bytes
           in go (dst + len) copyAcc (addAcc |> (dst, bytes)) rest

-- O(n log n + E) CRWI build via interval sweep (matches Rust/C++ approach).
buildCrwiDigraph :: Array Int CopyInfo -> Int -> (IM.IntMap [Int], IM.IntMap Int, Int)
buildCrwiDigraph copyArr n =
  foldl' step (IM.empty, IM.fromList [(i, 0) | i <- idxs], 0) idxs
  where
    idxs = [0 .. n - 1]
    writeSortedList = sortBy (comparing (\j -> ciDst (copyArr ! j))) idxs
    writeSorted = listArray (0, n - 1) writeSortedList :: Array Int Int
    writeStarts = listArray (0, n - 1) [ciDst (copyArr ! (writeSorted ! k)) | k <- idxs] :: Array Int Int

    step (!adj, !indeg, !edges) i =
      let ci = copyArr ! i
          src = ciSrc ci
          readEnd = src + ciLen ci
          lo = lowerBound writeStarts n src
          hi = lowerBound writeStarts n readEnd
          back =
            if lo > 0
              then collectBack i src (lo - 1) []
              else []
          forward = [j | k <- [lo .. hi - 1], let j = writeSorted ! k, j /= i]
          outs = back ++ forward
          adj' =
            if null outs
              then adj
              else IM.insert i outs adj
          indeg' = foldl' (\d j -> IM.insertWith (+) j 1 d) indeg outs
          edges' = edges + length outs
       in (adj', indeg', edges')

    collectBack i src !k acc
      | k < 0 = reverse acc
      | otherwise =
          let j = writeSorted ! k
              cj = copyArr ! j
           in if not (overlapsLeft src cj)
                then reverse acc
                else
                  if j == i
                    then collectBack i src (k - 1) acc
                    else collectBack i src (k - 1) (j : acc)

    overlapsLeft src cj = ciDst cj + ciLen cj > src

lowerBound :: Array Int Int -> Int -> Int -> Int
lowerBound arr n target = go 0 n
  where
    go !lo !hi
      | lo >= hi = lo
      | otherwise =
          let mid = lo + (hi - lo) `div` 2
           in if arr ! mid < target
                then go (mid + 1) hi
                else go lo mid

runKahn
  :: ByteString
  -> Int
  -> IM.IntMap [Int]
  -> IM.IntMap Int
  -> Array Int CopyInfo
  -> IM.IntMap Int
  -> Set.Set (Int, Int)
  -> CyclePolicy
  -> Seq (Int, ByteString)
  -> ([Int], Seq (Int, ByteString), InplaceStats)
runKahn ref n adj indeg0 copyArr copyLens heap0 policy addInfo0 =
  go 0 IS.empty indeg0 heap0 [] addInfo0 emptyStats scc0
  where
    scc0 = buildSccState n adj

    go !processed !removed !indeg !heap !topoRev !addInfo !stats !scc
      | processed >= n = (topoRev, addInfo, stats)
      | not (Set.null heap) =
          let ((_, v), heap1) = Set.deleteFindMin heap
           in if IS.member v removed
                then go processed removed indeg heap1 topoRev addInfo stats scc
                else
                  let removed' = IS.insert v removed
                      scc' = markRemoved v scc
                      (indeg', heap2) = relaxOutEdges v removed' indeg heap1
                   in go (processed + 1) removed' indeg' heap2 (v : topoRev) addInfo stats scc'
      | otherwise =
          let (victim, scc1) = pickVictim policy n copyArr adj removed scc
              ci = copyArr ! victim
              literal = BS.take (ciLen ci) (BS.drop (ciSrc ci) ref)
              addInfo' = addInfo |> (ciDst ci, literal)
              removed' = IS.insert victim removed
              scc2 = markRemoved victim scc1
              (indeg', heap1) = relaxOutEdges victim removed' indeg heap
              stats' =
                stats
                  { ipsCyclesBroken = ipsCyclesBroken stats + 1
                  , ipsCopiesConverted = ipsCopiesConverted stats + 1
                  , ipsBytesConverted = ipsBytesConverted stats + ciLen ci
                  }
           in go (processed + 1) removed' indeg' heap1 topoRev addInfo' stats' scc2

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

data SccState = SccState
  { ssSccs :: !(Array Int (Array Int Int))
  , ssCount :: !Int
  , ssId :: !(Array Int Int)
  , ssActive :: !(IM.IntMap Int)
  , ssPtr :: !Int
  , ssScan :: !Int
  , ssColor :: !(IM.IntMap Int)
  }

buildSccState :: Int -> IM.IntMap [Int] -> SccState
buildSccState n adj =
  let sccsRaw = G.stronglyConnComp [(i, i, IM.findWithDefault [] i adj) | i <- [0 .. n - 1]]
      sccLists = [vs | G.CyclicSCC vs <- sccsRaw, length vs > 1]
      sccCount = length sccLists
      sccArr = listArray (0, sccCount - 1) (map toArray sccLists)
      sidArr =
        accumArray
          (\_ sid -> sid)
          (-1)
          (0, n - 1)
          [(v, sid) | (sid, vs) <- zip [0 ..] sccLists, v <- vs]
      active = IM.fromList [(sid, length vs) | (sid, vs) <- zip [0 ..] sccLists]
   in SccState
        { ssSccs = sccArr
        , ssCount = sccCount
        , ssId = sidArr
        , ssActive = active
        , ssPtr = 0
        , ssScan = 0
        , ssColor = IM.empty
        }
  where
    toArray vs = listArray (0, length vs - 1) vs

markRemoved :: Int -> SccState -> SccState
markRemoved v st =
  let sid = ssId st ! v
   in if sid < 0
        then st
        else st {ssActive = IM.adjust (\x -> x - 1) sid (ssActive st)}

pickVictim :: CyclePolicy -> Int -> Array Int CopyInfo -> IM.IntMap [Int] -> IS.IntSet -> SccState -> (Int, SccState)
pickVictim policy n copyArr adj removed st0 =
  case policy of
    Constant -> (firstRemaining, st0)
    Localmin -> choose st0
  where
    choose st
      | ssPtr st >= ssCount st = (firstRemaining, st)
      | IM.findWithDefault 0 (ssPtr st) (ssActive st) == 0 =
          choose st {ssPtr = ssPtr st + 1, ssScan = 0}
      | otherwise =
          let sid = ssPtr st
              sccVerts = ssSccs st ! sid
              (mCycle, color', scan') =
                findCycleInScc sid sccVerts (ssId st) adj removed (ssColor st) (ssScan st)
              st' = st {ssColor = color', ssScan = scan'}
           in case mCycle of
                Just cycleNodes -> (minimumBy (comparing key) cycleNodes, st')
                Nothing -> choose st' {ssPtr = sid + 1, ssScan = 0}

    firstRemaining =
      let go !i
            | i >= n = error "pickVictim: no remaining vertices"
            | IS.member i removed = go (i + 1)
            | otherwise = i
       in go 0

    key i = (ciLen (copyArr ! i), i)

findCycleInScc
  :: Int
  -> Array Int Int
  -> Array Int Int
  -> IM.IntMap [Int]
  -> IS.IntSet
  -> IM.IntMap Int
  -> Int
  -> (Maybe [Int], IM.IntMap Int, Int)
findCycleInScc sid sccVerts sccId adj removed color0 scan0 = goScan scan0 color0
  where
    !sccLen = let (_, hi) = bounds sccVerts in hi + 1

    goScan !scan color
      | scan >= sccLen = (Nothing, color, scan)
      | otherwise =
          let start = sccVerts ! scan
           in if IS.member start removed || colorAt start color /= 0
                then goScan (scan + 1) color
                else
                  case dfs start [start] color of
                    (Just cyc, color') -> (Just cyc, color', scan)
                    (Nothing, color') -> goScan (scan + 1) color'

    dfs v path color0 =
      let color1 = IM.insert v 1 color0
       in walk (IM.findWithDefault [] v adj) color1
      where
        walk [] color = (Nothing, IM.insert v 2 color)
        walk (w : ws) color
          | IS.member w removed || sccId ! w /= sid = walk ws color
          | otherwise =
              case colorAt w color of
                0 ->
                  let (res, color') = dfs w (w : path) color
                   in case res of
                        Just cyc -> (Just cyc, color')
                        Nothing -> walk ws color'
                1 ->
                  let seg = takeWhile (/= w) path
                      color' = clearPath path color
                   in (Just (reverse (w : seg)), color')
                _ -> walk ws color

    colorAt v color = IM.findWithDefault 0 v color
    clearPath path color = foldl' (flip IM.delete) color path
