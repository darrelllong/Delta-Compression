package delta

import "fmt"
import "os"

// diffOnepass implements the One-Pass algorithm (Section 4.1, Figure 3).
//
// Scans R and V concurrently with two hash tables (one per string).
// Each slot stores at most one offset per fingerprint (retain-existing
// policy: first entry wins, later collisions are discarded).
// Hash tables are logically flushed after each match via version counter.
// Time: O(np + q), space: O(q).
func diffOnepass(r, v []byte, opts DiffOptions) []Command {
	if len(v) == 0 {
		return nil
	}

	p := opts.P
	q := opts.Q
	verbose := opts.Verbose
	useSplay := opts.UseSplay

	// Auto-size hash table: one slot per p-byte chunk of R (floor = q).
	numSeeds := 0
	if len(r) >= p {
		numSeeds = len(r) - p + 1
	}
	q = int(NextPrime(max64(int64(q), int64(numSeeds/p))))

	if verbose {
		structName := "hash table"
		if useSplay {
			structName = "splay tree"
		}
		fmt.Fprintf(os.Stderr, "onepass: %s, q=%d, |R|=%d, |V|=%d, seed_len=%d\n",
			structName, q, len(r), len(v), p)
	}

	// Step (1): version-based logical flushing.
	// Hash table entries: parallel arrays for fp, offset, version.
	var htVFp, htRFp []int64
	var htVOff, htROff []int
	var htVVer, htRVer []int64
	var spV, spR *SplayTree[[2]int64] // value = [offset, version]

	if useSplay {
		spV = &SplayTree[[2]int64]{}
		spR = &SplayTree[[2]int64]{}
	} else {
		htVFp = make([]int64, q)
		htVOff = make([]int, q)
		htVVer = make([]int64, q)
		htRFp = make([]int64, q)
		htROff = make([]int, q)
		htRVer = make([]int64, q)
		for i := range htVVer {
			htVVer[i] = -1
			htRVer[i] = -1
		}
	}

	htPut := func(fps []int64, offs []int, vers []int64, fp int64, off int, ver int64) {
		idx := int(fp % int64(q))
		if idx < 0 {
			idx += q
		}
		if vers[idx] == ver {
			return // retain-existing
		}
		fps[idx] = fp
		offs[idx] = off
		vers[idx] = ver
	}

	htGet := func(fps []int64, offs []int, vers []int64, fp int64, ver int64) int {
		idx := int(fp % int64(q))
		if idx < 0 {
			idx += q
		}
		if vers[idx] == ver && fps[idx] == fp {
			return offs[idx]
		}
		return -1
	}

	// Step (2): initialize scan pointers.
	ver := int64(0)
	rC, vC, vS := 0, 0, 0

	var rhV, rhR *RollingHash
	rhVPos, rhRPos := 0, 0
	if len(v) >= p {
		rhV = NewRollingHash(v, 0, p)
	}
	if len(r) >= p {
		rhR = NewRollingHash(r, 0, p)
	}

	var commands []Command

	for {
		// Step (3): check for end of V and R.
		canV := vC+p <= len(v)
		canR := rC+p <= len(r)
		if !canV && !canR {
			break
		}

		fpV := int64(-1)
		fpR := int64(-1)
		hasFpV := false
		hasFpR := false

		if canV && rhV != nil {
			if vC == rhVPos {
				// already positioned
			} else if vC == rhVPos+1 {
				rhV.Roll(int(v[vC-1]&0xFF), int(v[vC+p-1]&0xFF))
				rhVPos = vC
			} else {
				rhV = NewRollingHash(v, vC, p)
				rhVPos = vC
			}
			fpV = rhV.Value()
			hasFpV = true
		}
		if canR && rhR != nil {
			if rC == rhRPos {
				// already positioned
			} else if rC == rhRPos+1 {
				rhR.Roll(int(r[rC-1]&0xFF), int(r[rC+p-1]&0xFF))
				rhRPos = rC
			} else {
				rhR = NewRollingHash(r, rC, p)
				rhRPos = rC
			}
			fpR = rhR.Value()
			hasFpR = true
		}

		// Step (4a): store offsets (retain-existing policy).
		if hasFpV {
			if useSplay {
				entry, ok := spV.Find(fpV)
				if !ok || entry[1] != ver {
					spV.Insert(fpV, [2]int64{int64(vC), ver})
				}
			} else {
				htPut(htVFp, htVOff, htVVer, fpV, vC, ver)
			}
		}
		if hasFpR {
			if useSplay {
				entry, ok := spR.Find(fpR)
				if !ok || entry[1] != ver {
					spR.Insert(fpR, [2]int64{int64(rC), ver})
				}
			} else {
				htPut(htRFp, htROff, htRVer, fpR, rC, ver)
			}
		}

		// Step (4b): look for a matching seed in the other table.
		matchFound := false
		rM, vM := 0, 0

		if hasFpR {
			var vCand int
			if useSplay {
				entry, ok := spV.Find(fpR)
				if ok && entry[1] == ver {
					vCand = int(entry[0])
				} else {
					vCand = -1
				}
			} else {
				vCand = htGet(htVFp, htVOff, htVVer, fpR, ver)
			}
			if vCand >= 0 && regionEquals(r, rC, v, vCand, p) {
				rM = rC
				vM = vCand
				matchFound = true
			}
		}

		if !matchFound && hasFpV {
			var rCand int
			if useSplay {
				entry, ok := spR.Find(fpV)
				if ok && entry[1] == ver {
					rCand = int(entry[0])
				} else {
					rCand = -1
				}
			} else {
				rCand = htGet(htRFp, htROff, htRVer, fpV, ver)
			}
			if rCand >= 0 && regionEquals(v, vC, r, rCand, p) {
				vM = vC
				rM = rCand
				matchFound = true
			}
		}

		if !matchFound {
			vC++
			rC++
			continue
		}

		// Step (5): extend match forward.
		ml := 0
		for vM+ml < len(v) && rM+ml < len(r) && v[vM+ml] == r[rM+ml] {
			ml++
		}

		if ml < p {
			vC++
			rC++
			continue
		}

		// Step (6): encode.
		if vS < vM {
			data := make([]byte, vM-vS)
			copy(data, v[vS:vM])
			commands = append(commands, AddCmd{Data: data})
		}
		commands = append(commands, CopyCmd{Offset: rM, Length: ml})
		vS = vM + ml

		// Step (7): advance pointers and flush tables.
		vC = vM + ml
		rC = rM + ml
		ver++
	}

	// Step (8): trailing add.
	if vS < len(v) {
		data := make([]byte, len(v)-vS)
		copy(data, v[vS:])
		commands = append(commands, AddCmd{Data: data})
	}

	if verbose {
		printStats(commands)
	}
	return commands
}

func max64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}
