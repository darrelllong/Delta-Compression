package delta

import "math/bits"

// Karp-Rabin fingerprints (Section 2.1.3) are polynomials in HashBase
// evaluated modulo the Mersenne prime HashMod = 2^61-1. A fingerprint is
// always less than HashMod, so the top three bits of the uint64 are zero.

// ModMersenne reduces the 128-bit value hi:lo modulo 2^61-1.
// The value must be less than 2^122, as the product of two fingerprints is.
func ModMersenne(hi, lo uint64) uint64 {
	// 2^61 = 1 (mod 2^61-1), so the bits above bit 60 fold onto the low bits.
	r := (hi<<3 | lo>>61) + lo&HashMod
	if r >= HashMod {
		r -= HashMod
	}
	r = r>>61 + r&HashMod
	if r >= HashMod {
		r -= HashMod
	}
	return r
}

// mulmod returns a*b mod 2^61-1.
func mulmod(a, b uint64) uint64 {
	return ModMersenne(bits.Mul64(a, b))
}

// shiftIn returns h*HashBase + b mod 2^61-1.
func shiftIn(h uint64, b byte) uint64 {
	hi, lo := bits.Mul64(h, HashBase)
	lo, carry := bits.Add64(lo, uint64(b), 0)
	return ModMersenne(hi+carry, lo)
}

// Fingerprint returns the Karp-Rabin fingerprint of data[offset:offset+p]
// (Eq. 1).
func Fingerprint(data []byte, offset, p int) uint64 {
	var h uint64
	for _, b := range data[offset : offset+p] {
		h = shiftIn(h, b)
	}
	return h
}

// PrecomputeBp returns HashBase^(p-1) mod HashMod, the weight of the oldest
// byte in a p-byte window.
func PrecomputeBp(p int) uint64 {
	result, base := uint64(1), uint64(HashBase)
	for exp := p - 1; exp > 0; exp >>= 1 {
		if exp&1 == 1 {
			result = mulmod(result, base)
		}
		base = mulmod(base, base)
	}
	return result
}

// A rollingHash yields the fingerprint of the p-byte seed at successive
// positions of data. Moving forward one byte updates the fingerprint in
// constant time (Eq. 2); any other move recomputes it.
type rollingHash struct {
	data  []byte
	p     int
	pos   int    // position of the current seed
	value uint64 // its fingerprint
	bp    uint64 // HashBase^(p-1) mod HashMod
}

// newRollingHash returns a rollingHash positioned at 0. If data is shorter
// than p it has no seeds and at must not be called.
func newRollingHash(data []byte, p int) rollingHash {
	h := rollingHash{data: data, p: p, bp: PrecomputeBp(p)}
	if len(data) >= p {
		h.value = Fingerprint(data, 0, p)
	}
	return h
}

// at returns the fingerprint of data[pos:pos+p].
func (h *rollingHash) at(pos int) uint64 {
	switch pos {
	case h.pos:
	case h.pos + 1:
		// Remove the byte that leaves the seed, then shift in the new one.
		sub := mulmod(uint64(h.data[pos-1]), h.bp)
		v := h.value - sub
		if h.value < sub {
			v += HashMod
		}
		h.value = shiftIn(v, h.data[pos+h.p-1])
	default:
		h.value = Fingerprint(h.data, pos, h.p)
	}
	h.pos = pos
	return h.value
}

// powmod returns base^exp mod m.
func powmod(base, exp, m uint64) uint64 {
	result := uint64(1)
	base %= m
	for ; exp > 0; exp >>= 1 {
		if exp&1 == 1 {
			result = mulmodN(result, base, m)
		}
		base = mulmodN(base, base, m)
	}
	return result
}

// mulmodN returns a*b mod m for a, b < m.
func mulmodN(a, b, m uint64) uint64 {
	hi, lo := bits.Mul64(a, b)
	_, rem := bits.Div64(hi, lo, m) // hi < m because a, b < m
	return rem
}

// witness reports whether a proves the odd number n composite: either
// a^(n-1) != 1 (mod n), or squaring a^d toward a^(n-1) passes through a
// square root of 1 other than 1 and n-1. Here n-1 = d * 2^r with d odd.
func witness(a, n uint64) bool {
	r := bits.TrailingZeros64(n - 1)
	d := (n - 1) >> r
	x := powmod(a, d, n)
	for i := 0; i < r; i++ {
		y := mulmodN(x, x, n)
		if y == 1 && x != 1 && x != n-1 {
			return true
		}
		x = y
	}
	return x != 1
}

// mrWitnesses, the first twelve primes, make Miller-Rabin deterministic for
// every n < 318,665,857,834,031,151,167,461, and so for every uint64
// (Sorenson and Webster, Math. Comp. 86(304), 2017).
var mrWitnesses = [...]uint64{2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37}

// IsPrime reports whether n is prime.
func IsPrime(n int) bool {
	if n < 2 || (n != 2 && n%2 == 0) {
		return false
	}
	if n == 2 || n == 3 {
		return true
	}
	for _, a := range mrWitnesses {
		if a >= uint64(n) {
			break
		}
		if witness(a, uint64(n)) {
			return false
		}
	}
	return true
}

// NextPrime returns the smallest prime >= n.
func NextPrime(n int) int {
	if n <= 2 {
		return 2
	}
	n |= 1
	for !IsPrime(n) {
		n += 2
	}
	return n
}
