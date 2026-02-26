package delta

import "fmt"
import "os"

// diffCorrecting implements the Correcting 1.5-Pass algorithm (Section 7, Figure 8)
// with fingerprint-based checkpointing (Section 8).
//
// |C| = cap (hash table capacity, auto-sized from input).
// |F| = next_prime(2 * num_R_seeds) (footprint universe, Section 8.1).
// m  = ceil(|F| / |C|) (checkpoint spacing, p. 348).
// k  = checkpoint class (Eq. 3, p. 348).
func diffCorrecting(r, v []byte, opts DiffOptions) []Command {
	if len(v) == 0 {
		return nil
	}

	p := opts.P
	q := opts.Q
	bufCap := opts.BufCap
	verbose := opts.Verbose
	useSplay := opts.UseSplay

	// ── Checkpointing parameters (Section 8.1, pp. 347-348) ──
	numSeeds := 0
	if len(r) >= p {
		numSeeds = len(r) - p + 1
	}
	maxTable := opts.MaxTable
	if maxTable <= 0 {
		maxTable = MaxTableSize
	}

	var cap_ int
	if numSeeds > 0 {
		twoSeedsOverP := 2 * numSeeds / p
		cap_ = int(NextPrime(int64(min64(int64(maxTable), max64(int64(q), int64(twoSeedsOverP))))))
	} else {
		cap_ = int(NextPrime(int64(min64(int64(q), int64(maxTable)))))
	}

	fSize := int64(1)
	if numSeeds > 0 {
		fSize = NextPrime(int64(2 * numSeeds))
	}
	m := int64(1)
	if fSize > int64(cap_) {
		m = (fSize + int64(cap_) - 1) / int64(cap_)
	}

	k := int64(0)
	if len(v) >= p {
		mid := len(v) / 2
		if mid > len(v)-p {
			mid = len(v) - p
		}
		k = Fingerprint(v, mid, p) % fSize % m
	}

	if verbose {
		structName := "hash table"
		if useSplay {
			structName = "splay tree"
		}
		var expected int64
		if m > 0 {
			expected = int64(numSeeds) / m
		}
		var occEst int64
		if cap_ > 0 {
			occEst = expected * 100 / int64(cap_)
		}
		fmt.Fprintf(os.Stderr,
			"correcting: %s, |C|=%d |F|=%d m=%d k=%d\n"+
				"  checkpoint gap=%d bytes, expected fill ~%d (~%d%% table occupancy)\n",
			structName, cap_, fSize, m, k, m, expected, occEst)
	}

	// Step (1): build lookup structure for R (first-found / insert-if-absent policy).
	var htFp []int64
	var htOff []int
	var splayR *SplayTree[[2]int64] // value = [full_fp, offset]

	if useSplay {
		splayR = &SplayTree[[2]int64]{}
	} else {
		htFp = make([]int64, cap_)
		for i := range htFp { htFp[i] = -1 }
		htOff = make([]int, cap_)
	}

	if numSeeds > 0 {
		rhR := NewRollingHash(r, 0, p)
		for a := 0; a < numSeeds; a++ {
			var fp int64
			if a == 0 {
				fp = rhR.Value()
			} else {
				rhR.Roll(int(r[a-1]&0xFF), int(r[a+p-1]&0xFF))
				fp = rhR.Value()
			}
			f := fp % fSize
			if f%m != k {
				continue // not a checkpoint seed
			}
			if useSplay {
				_, ok := splayR.Find(fp)
				if !ok {
					splayR.Insert(fp, [2]int64{fp, int64(a)})
				}
			} else {
				i := int(f / m)
				i0 := i
				for {
					if htFp[i] == -1 { break }          // empty — store here
					if htFp[i] == fp { i = -1; break }  // dup fp — skip
					i++
					if i == cap_ { i = 0 }
					if i == i0 { i = -1; break }         // table full
				}
				if i >= 0 {
					htFp[i] = fp
					htOff[i] = a
				}
			}
		}
	}

	if verbose && numSeeds > 0 {
		// Build phase stats.
		storedCount := 0
		if useSplay {
			storedCount = splayR.Len()
		} else {
			for _, fp := range htFp {
				if fp != -1 {
					storedCount++
				}
			}
		}
		// We'll compute exact counts by re-scanning; just emit table occupancy.
		tableOcc := int64(storedCount)
		fmt.Fprintf(os.Stderr, "  build: table occupancy %d/%d (%.1f%%)\n",
			tableOcc, cap_, float64(tableOcc)*100/float64(cap_))
	}

	// Lookback buffer (Section 5.2).
	//
	// The correcting algorithm may discover that a newly found match overlaps
	// commands already emitted. The buffer holds the most recent bufCap tentative
	// commands so they can be trimmed or cancelled (tail correction) when a better
	// match is found. Commands are flushed to the output list as they age out.
	type bufEntry struct {
		vStart int     // First V byte covered by this entry.
		vEnd   int     // One past the last V byte covered.
		cmd    Command // The tentative command (CopyCmd or AddCmd).
		dummy  bool    // Reserved; always false in the current implementation.
	}
	buf := make([]bufEntry, 0, bufCap)

	// Step (2): initialize scan pointers.
	vC, vS := 0, 0
	var rhV *RollingHash
	rhVPos := 0
	vSeeds := 0
	if len(v) >= p {
		vSeeds = len(v) - p + 1
		rhV = NewRollingHash(v, 0, p)
	}
	_ = vSeeds

	var commands []Command

	emitOldest := func() {
		if len(buf) >= bufCap {
			oldest := buf[0]
			buf = buf[1:]
			if !oldest.dummy {
				commands = append(commands, oldest.cmd)
			}
		}
	}

	for vC+p <= len(v) { // Step (3)
		// Step (4): fingerprint at vC, apply checkpoint test.
		if rhV == nil {
			break
		}
		var fpV int64
		if vC == rhVPos {
			fpV = rhV.Value()
		} else if vC == rhVPos+1 {
			rhV.Roll(int(v[vC-1]&0xFF), int(v[vC+p-1]&0xFF))
			rhVPos = vC
			fpV = rhV.Value()
		} else {
			rhV = NewRollingHash(v, vC, p)
			rhVPos = vC
			fpV = rhV.Value()
		}

		fV := fpV % fSize
		if fV%m != k {
			vC++
			continue
		}

		// Checkpoint passed — look up R.
		var storedFp int64
		var rOffset int
		if useSplay {
			entry, ok := splayR.Find(fpV)
			if !ok {
				vC++
				continue
			}
			storedFp = entry[0]
			rOffset = int(entry[1])
		} else {
			i := int(fV / m)
			i0 := i
			found := -1
			for {
				if htFp[i] == -1 { break }            // empty — chain ends
				if htFp[i] == fpV { found = i; break }
				i++
				if i == cap_ { i = 0 }
				if i == i0 { break }                   // full table — not found
			}
			if found < 0 {
				vC++
				continue
			}
			storedFp = htFp[found]
			rOffset = htOff[found]
		}

		if storedFp != fpV {
			vC++
			continue
		}
		if !regionEquals(r, rOffset, v, vC, p) {
			vC++
			continue
		}

		// Step (5): extend match forwards and backwards.
		fwd := p
		for vC+fwd < len(v) && rOffset+fwd < len(r) && v[vC+fwd] == r[rOffset+fwd] {
			fwd++
		}
		bwd := 0
		for vC >= bwd+1 && rOffset >= bwd+1 && v[vC-bwd-1] == r[rOffset-bwd-1] {
			bwd++
		}

		vM := vC - bwd
		rM := rOffset - bwd
		ml := bwd + fwd
		matchEnd := vM + ml

		if ml < p {
			vC++
			continue
		}

		// Step (6): encode with correction.
		if vS <= vM {
			// (6a) match in unencoded suffix.
			if vS < vM {
				emitOldest()
				data := make([]byte, vM-vS)
				copy(data, v[vS:vM])
				buf = append(buf, bufEntry{vStart: vS, vEnd: vM, cmd: AddCmd{Data: data}})
			}
			emitOldest()
			buf = append(buf, bufEntry{vStart: vM, vEnd: matchEnd, cmd: CopyCmd{Offset: rM, Length: ml}})
			vS = matchEnd
		} else {
			// (6b) tail correction (Section 5.1, p. 339).
			effectiveStart := vS

			for len(buf) > 0 {
				tail := &buf[len(buf)-1]
				if tail.dummy {
					buf = buf[:len(buf)-1]
					continue
				}
				if tail.vStart >= vM && tail.vEnd <= matchEnd {
					if tail.vStart < effectiveStart {
						effectiveStart = tail.vStart
					}
					buf = buf[:len(buf)-1]
					continue
				}
				if tail.vEnd > vM && tail.vStart < vM {
					if _, isAdd := tail.cmd.(AddCmd); isAdd {
						keep := vM - tail.vStart
						if keep > 0 {
							data := make([]byte, keep)
							copy(data, v[tail.vStart:vM])
							tail.cmd = AddCmd{Data: data}
							tail.vEnd = vM
						} else {
							buf = buf[:len(buf)-1]
						}
						if vM < effectiveStart {
							effectiveStart = vM
						}
					}
					break
				}
				break
			}

			adj := effectiveStart - vM
			newLen := matchEnd - effectiveStart
			if newLen > 0 {
				emitOldest()
				buf = append(buf, bufEntry{vStart: effectiveStart, vEnd: matchEnd,
					cmd: CopyCmd{Offset: rM + adj, Length: newLen}})
			}
			vS = matchEnd
		}

		// Step (7): advance past matched region.
		vC = matchEnd
	}

	// Step (8): flush buffer and trailing add.
	for _, entry := range buf {
		if !entry.dummy {
			commands = append(commands, entry.cmd)
		}
	}
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

func min64(a, b int64) int64 {
	if a < b {
		return a
	}
	return b
}
