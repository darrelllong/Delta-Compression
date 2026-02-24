package delta

import (
	"math/big"
	"math/bits"
)

// ── Karp-Rabin rolling hash (Karp & Rabin 1987; Section 2.1.3) ──
//
// Polynomial fingerprints over the Mersenne prime 2^61-1.

// ModMersenne reduces the 128-bit value (hi:lo) modulo 2^61-1.
func ModMersenne(hi, lo uint64) int64 {
	const P = uint64(HashMod)
	upper := (hi << 3) | (lo >> 61)
	lower := lo & P
	r := upper + lower
	if r >= P {
		r -= P
	}
	// Second reduction.
	upper2 := r >> 61
	lower2 := r & P
	r2 := upper2 + lower2
	if r2 >= P {
		r2 -= P
	}
	return int64(r2)
}

// mulmod computes (a * b) mod (2^61-1) using a 128-bit intermediate.
func mulmod(a, b int64) int64 {
	hi, lo := bits.Mul64(uint64(a), uint64(b))
	return ModMersenne(hi, lo)
}

// Fingerprint computes the Karp-Rabin fingerprint of data[offset..offset+p] (Eq. 1).
func Fingerprint(data []byte, offset, p int) int64 {
	var h int64
	for i := 0; i < p; i++ {
		b := int64(data[offset+i] & 0xFF)
		hi, lo := bits.Mul64(uint64(h), HashBase)
		newLo := lo + uint64(b)
		newHi := hi
		if newLo < lo {
			newHi++
		}
		h = ModMersenne(newHi, newLo)
	}
	return h
}

// PrecomputeBp computes HASH_BASE^{p-1} mod HASH_MOD.
func PrecomputeBp(p int) int64 {
	if p == 0 {
		return 1
	}
	result := int64(1)
	base := int64(HashBase)
	exp := p - 1
	for exp > 0 {
		if exp&1 == 1 {
			result = mulmod(result, base)
		}
		base = mulmod(base, base)
		exp >>= 1
	}
	return result
}

// ── Rolling hash ──

// RollingHash supports O(1) incremental fingerprint updates (Eq. 2).
type RollingHash struct {
	value int64
	bp    int64 // HASH_BASE^{p-1} mod HASH_MOD
}

// NewRollingHash initializes a rolling hash over data[offset..offset+p].
func NewRollingHash(data []byte, offset, p int) *RollingHash {
	return &RollingHash{
		bp:    PrecomputeBp(p),
		value: Fingerprint(data, offset, p),
	}
}

// Value returns the current fingerprint.
func (rh *RollingHash) Value() int64 { return rh.value }

// Roll slides the window: remove oldByte from left, add newByte to right.
func (rh *RollingHash) Roll(oldByte, newByte int) {
	sub := mulmod(int64(oldByte), rh.bp)
	var v int64
	if rh.value >= sub {
		v = rh.value - sub
	} else {
		v = HashMod - (sub - rh.value)
	}
	hi, lo := bits.Mul64(uint64(v), HashBase)
	newLo := lo + uint64(newByte)
	newHi := hi
	if newLo < lo {
		newHi++
	}
	rh.value = ModMersenne(newHi, newLo)
}

// ── Primality testing ──

// Fixed witnesses for deterministic Miller-Rabin.
// Sufficient for all n < 3,317,044,064,679,887,385,961,981 (> 2^81).
// Jaeschke, Math. Comp. 61(204), 1993.
var mrWitnesses = []int64{2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37}

// IsPrime reports whether n is prime using deterministic Miller-Rabin.
func IsPrime(n int64) bool {
	if n < 2 {
		return false
	}
	if n < 4 {
		return true
	}
	if n%2 == 0 {
		return false
	}
	bn := big.NewInt(n)
	nm1 := new(big.Int).Sub(bn, big.NewInt(1))
	r := nm1.TrailingZeroBits()
	d := new(big.Int).Rsh(nm1, r)

	for _, a := range mrWitnesses {
		if a >= n {
			break
		}
		x := new(big.Int).Exp(big.NewInt(a), d, bn)
		if x.Cmp(big.NewInt(1)) == 0 || x.Cmp(nm1) == 0 {
			continue
		}
		found := false
		for j := uint(0); j < r-1; j++ {
			x.Exp(x, big.NewInt(2), bn)
			if x.Cmp(nm1) == 0 {
				found = true
				break
			}
		}
		if !found {
			return false
		}
	}
	return true
}

// NextPrime returns the smallest prime >= n.
func NextPrime(n int64) int64 {
	if n <= 2 {
		return 2
	}
	if n%2 == 0 {
		n++
	}
	for !IsPrime(n) {
		n += 2
	}
	return n
}
