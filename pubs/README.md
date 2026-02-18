# Papers

## Compactly Encoding Unstructured Inputs with Differential Compression

Miklos Ajtai, Randal Burns, Ronald Fagin, Darrell D. E. Long,
and Larry Stockmeyer.
*Journal of the ACM*, 49(3):318-367, May 2002.

**Abstract.**  The subject of this article is differential compression,
the algorithmic task of finding common strings between versions of data
and using them to encode one version compactly by describing it as a
set of changes from its companion.  A main goal of this work is to
present new differencing algorithms that (i) operate at a fine
granularity (the atomic unit of change), (ii) make no assumptions about
the format or alignment of input data, and (iii) in practice use linear
time, use constant space, and give good compression.  We present new
algorithms, which do not always compress optimally but use considerably
less time or space than existing algorithms.  One new algorithm runs in
O(n) time and O(1) space in the worst case (where each unit of space
contains ceil(log n) bits), as compared to algorithms that run in O(n)
time and O(n) space or in O(n^2) time and O(1) space.  We introduce two
new techniques for differential compression and apply these to give
additional algorithms that improve compression and time performance.
We experimentally explore the properties of our algorithms by running
them on actual versioned data.  Finally, we present theoretical results
that limit the compression power of differencing algorithms that are
restricted to making only a single pass over the data.

## In-Place Reconstruction of Version Differences

Randal C. Burns, Darrell D. E. Long, and Larry Stockmeyer.
*IEEE Transactions on Knowledge and Data Engineering*,
15(4):973-984, Jul/Aug 2003.

**Abstract.**  In-place reconstruction of differenced data allows
information on devices with limited storage capacity to be updated
efficiently over low-bandwidth channels.  Differencing encodes a
version of data compactly as a set of changes from a previous version.
Transmitting updates to data as a version difference saves both time
and bandwidth.  In-place reconstruction rebuilds the new version of the
data in the storage or memory the current version occupies -- no scratch
space is needed for a second version.  By combining these technologies,
we support highly mobile applications on space-constrained hardware.
We present an algorithm that modifies a differentially encoded version
to be in-place reconstructible.  The algorithm trades a small amount of
compression to achieve this property.  Our treatment includes
experimental results that show our implementation to be efficient in
space and time and verify that compression losses are small.  Also, we
give results on the computational complexity of performing this
modification while minimizing lost compression.
