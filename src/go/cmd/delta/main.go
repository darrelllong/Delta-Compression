// Command delta is a CLI for differential compression (Ajtai et al. 2002).
//
// Usage:
//
//	delta encode <algorithm> <ref> <ver> <delta> [options]
//	delta decode <ref> <delta> <output> [--ignore-hash]
//	delta info <delta>
//	delta inplace <ref> <delta_in> <delta_out> [--policy P]
//
// Algorithms: greedy, onepass, correcting
// Options: --seed-len N, --table-size N, --max-table N (k/M/B ok),
//
//	--inplace, --policy P, --verbose, --splay
package main

import (
	"encoding/hex"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"delta/delta"
)

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run(args []string) error {
	if len(args) == 0 {
		usage()
	}
	switch args[0] {
	case "encode":
		return cmdEncode(args)
	case "decode":
		return cmdDecode(args)
	case "info":
		return cmdInfo(args)
	case "inplace":
		return cmdInplace(args)
	default:
		usage()
	}
	return nil
}

func usage() {
	fmt.Fprintln(os.Stderr, `Usage:
  delta encode <algorithm> <ref> <ver> <delta> [options]
  delta decode <ref> <delta> <output> [--ignore-hash]
  delta info <delta>
  delta inplace <ref> <delta_in> <delta_out> [--policy P]

Algorithms: greedy, onepass, correcting
Options: --seed-len N, --table-size N, --max-table N (k/M/B ok),
         --inplace, --policy P, --verbose, --splay`)
	os.Exit(1)
}

// parseSizeSuffix parses a size string with optional k/M/B suffix.
func parseSizeSuffix(s string) (int, error) {
	if len(s) == 0 {
		return 0, fmt.Errorf("empty size value")
	}
	last := s[len(s)-1]
	var mult int64
	var num string
	switch last {
	case 'k', 'K':
		mult = 1_000
		num = s[:len(s)-1]
	case 'm', 'M':
		mult = 1_000_000
		num = s[:len(s)-1]
	case 'b', 'B':
		mult = 1_000_000_000
		num = s[:len(s)-1]
	default:
		mult = 1
		num = s
	}
	n, err := strconv.ParseInt(num, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("invalid size: %s", s)
	}
	return int(n * mult), nil
}

func parseAlgorithm(s string) (delta.Algorithm, error) {
	switch strings.ToLower(s) {
	case "greedy":
		return delta.AlgorithmGreedy, nil
	case "onepass":
		return delta.AlgorithmOnepass, nil
	case "correcting":
		return delta.AlgorithmCorrecting, nil
	default:
		return 0, fmt.Errorf("unknown algorithm: %s", s)
	}
}

func parsePolicy(s string) (delta.CyclePolicy, error) {
	switch strings.ToLower(s) {
	case "localmin":
		return delta.CyclePolicyLocalmin, nil
	case "constant":
		return delta.CyclePolicyConstant, nil
	default:
		return 0, fmt.Errorf("unknown policy: %s", s)
	}
}

func readFile(path string) ([]byte, error) {
	return os.ReadFile(path)
}

func writeFile(path string, data []byte) error {
	return os.WriteFile(path, data, 0644)
}

func toHex(b [8]byte) string {
	return hex.EncodeToString(b[:])
}

func cmdEncode(args []string) error {
	if len(args) < 5 {
		usage()
	}

	algo, err := parseAlgorithm(args[1])
	if err != nil {
		return err
	}
	refPath := args[2]
	verPath := args[3]
	deltaPath := args[4]

	opts := delta.DefaultDiffOptions()
	inplace := false
	policy := delta.CyclePolicyLocalmin

	i := 5
	for i < len(args) {
		switch args[i] {
		case "--seed-len":
			if i+1 >= len(args) { return fmt.Errorf("--seed-len: missing value") }
			i++
			n, err := strconv.Atoi(args[i])
			if err != nil {
				return fmt.Errorf("--seed-len: %v", err)
			}
			opts.P = n
		case "--table-size":
			if i+1 >= len(args) { return fmt.Errorf("--table-size: missing value") }
			i++
			n, err := strconv.Atoi(args[i])
			if err != nil {
				return fmt.Errorf("--table-size: %v", err)
			}
			opts.Q = n
		case "--max-table":
			if i+1 >= len(args) { return fmt.Errorf("--max-table: missing value") }
			i++
			n, err := parseSizeSuffix(args[i])
			if err != nil {
				return fmt.Errorf("--max-table: %v", err)
			}
			opts.MaxTable = n
		case "--inplace":
			inplace = true
		case "--policy":
			if i+1 >= len(args) { return fmt.Errorf("--policy: missing value") }
			i++
			policy, err = parsePolicy(args[i])
			if err != nil {
				return err
			}
		case "--verbose":
			opts.Verbose = true
		case "--splay":
			opts.UseSplay = true
		default:
			return fmt.Errorf("unknown option: %s", args[i])
		}
		i++
	}

	if opts.P < 1 {
		return fmt.Errorf("--seed-len must be >= 1")
	}

	r, err := readFile(refPath)
	if err != nil {
		return err
	}
	v, err := readFile(verPath)
	if err != nil {
		return err
	}
	srcCrc := delta.Crc64XZ(r)
	dstCrc := delta.Crc64XZ(v)

	t0 := now()
	commands, err := delta.DiffErr(algo, r, v, opts)
	if err != nil {
		return err
	}
	var placed []delta.PlacedCommand
	if inplace {
		placed = delta.MakeInplace(r, commands, policy)
	} else {
		placed = delta.PlaceCommands(commands)
	}
	elapsed := since(t0)

	deltaBytes, err := delta.EncodeDelta(placed, inplace, len(v), srcCrc, dstCrc)
	if err != nil {
		return err
	}
	if err := writeFile(deltaPath, deltaBytes); err != nil {
		return err
	}

	stats := delta.PlacedSummaryOf(placed)
	var ratio float64
	if len(v) > 0 {
		ratio = float64(len(deltaBytes)) / float64(len(v))
	}
	algoName := algo.String()
	splayTag := ""
	if opts.UseSplay {
		splayTag = " [splay]"
	}
	if inplace {
		fmt.Printf("Algorithm:    %s%s + in-place (%s)\n", algoName, splayTag, policy.String())
	} else {
		fmt.Printf("Algorithm:    %s%s\n", algoName, splayTag)
	}
	fmt.Printf("Reference:    %s (%d bytes)\n", refPath, len(r))
	fmt.Printf("Version:      %s (%d bytes)\n", verPath, len(v))
	fmt.Printf("Delta:        %s (%d bytes)\n", deltaPath, len(deltaBytes))
	fmt.Printf("Compression:  %.4f (delta/version)\n", ratio)
	fmt.Printf("Commands:     %d copies, %d adds\n", stats.NumCopies, stats.NumAdds)
	fmt.Printf("Copy bytes:   %d\n", stats.CopyBytes)
	fmt.Printf("Add bytes:    %d\n", stats.AddBytes)
	fmt.Printf("Src CRC:      %s\n", toHex(srcCrc))
	fmt.Printf("Dst CRC:      %s\n", toHex(dstCrc))
	fmt.Printf("Time:         %.3fs\n", elapsed)
	return nil
}

func cmdDecode(args []string) error {
	if len(args) < 4 {
		usage()
	}

	refPath := args[1]
	deltaPath := args[2]
	outPath := args[3]
	ignoreHash := false
	for _, a := range args[4:] {
		if a == "--ignore-hash" {
			ignoreHash = true
		} else {
			return fmt.Errorf("unknown decode option: %s", a)
		}
	}

	r, err := readFile(refPath)
	if err != nil {
		return err
	}
	deltaBytes, err := readFile(deltaPath)
	if err != nil {
		return err
	}
	result, err := delta.DecodeDelta(deltaBytes)
	if err != nil {
		return err
	}

	// Pre-check: verify reference matches embedded src_crc.
	rCrc := delta.Crc64XZ(r)
	if rCrc != result.SrcCrc {
		if !ignoreHash {
			fmt.Fprintf(os.Stderr, "source file does not match delta: expected %s, got %s\n",
				toHex(result.SrcCrc), toHex(rCrc))
			os.Exit(1)
		}
		fmt.Fprintln(os.Stderr, "warning: skipping source CRC check (--ignore-hash)")
	}
	if err := delta.ValidatePlacedCommands(result.Commands, len(r), result.VersionSize, result.Inplace); err != nil {
		return err
	}

	t0 := now()
	var out []byte
	if result.Inplace {
		out = delta.ApplyDeltaInplace(r, result.Commands, result.VersionSize)
	} else {
		out = make([]byte, result.VersionSize)
		delta.ApplyPlacedTo(r, result.Commands, out)
	}
	elapsed := since(t0)

	if err := writeFile(outPath, out); err != nil {
		return err
	}
	outCrc := delta.Crc64XZ(out)

	// Post-check: verify output matches embedded dst_crc.
	if outCrc != result.DstCrc {
		if !ignoreHash {
			fmt.Fprintln(os.Stderr, "output integrity check failed")
			os.Exit(1)
		}
		fmt.Fprintln(os.Stderr, "warning: skipping output CRC check (--ignore-hash)")
	}

	fmtStr := "standard"
	if result.Inplace {
		fmtStr = "in-place"
	}
	fmt.Printf("Format:       %s\n", fmtStr)
	fmt.Printf("Reference:    %s (%d bytes)\n", refPath, len(r))
	fmt.Printf("Delta:        %s (%d bytes)\n", deltaPath, len(deltaBytes))
	fmt.Printf("Output:       %s (%d bytes)\n", outPath, len(out))
	if !ignoreHash {
		fmt.Printf("Src CRC:      %s  OK\n", toHex(result.SrcCrc))
		fmt.Printf("Dst CRC:      %s  OK\n", toHex(result.DstCrc))
	}
	fmt.Printf("Time:         %.3fs\n", elapsed)
	return nil
}

func cmdInfo(args []string) error {
	if len(args) < 2 {
		usage()
	}

	deltaPath := args[1]
	deltaBytes, err := readFile(deltaPath)
	if err != nil {
		return err
	}
	result, err := delta.DecodeDelta(deltaBytes)
	if err != nil {
		return err
	}
	stats := delta.PlacedSummaryOf(result.Commands)

	fmtStr := "standard"
	if result.Inplace {
		fmtStr = "in-place"
	}
	fmt.Printf("Delta file:   %s (%d bytes)\n", deltaPath, len(deltaBytes))
	fmt.Printf("Format:       %s\n", fmtStr)
	fmt.Printf("Version size: %d bytes\n", result.VersionSize)
	fmt.Printf("Src CRC:      %s\n", toHex(result.SrcCrc))
	fmt.Printf("Dst CRC:      %s\n", toHex(result.DstCrc))
	fmt.Printf("Commands:     %d\n", stats.NumCommands)
	fmt.Printf("  Copies:     %d (%d bytes)\n", stats.NumCopies, stats.CopyBytes)
	fmt.Printf("  Adds:       %d (%d bytes)\n", stats.NumAdds, stats.AddBytes)
	fmt.Printf("Output size:  %d bytes\n", stats.TotalOutputBytes)
	return nil
}

func cmdInplace(args []string) error {
	if len(args) < 4 {
		usage()
	}

	refPath := args[1]
	deltaInPath := args[2]
	deltaOutPath := args[3]
	policy := delta.CyclePolicyLocalmin
	policyStr := "localmin"

	i := 4
	for i < len(args) {
		switch args[i] {
		case "--policy":
			if i+1 >= len(args) { return fmt.Errorf("--policy: missing value") }
			i++
			var err error
			policy, err = parsePolicy(args[i])
			if err != nil {
				return err
			}
			policyStr = policy.String()
		default:
			return fmt.Errorf("unknown inplace option: %s", args[i])
		}
		i++
	}

	r, err := readFile(refPath)
	if err != nil {
		return err
	}
	deltaBytes, err := readFile(deltaInPath)
	if err != nil {
		return err
	}
	result, err := delta.DecodeDelta(deltaBytes)
	if err != nil {
		return err
	}

	if result.Inplace {
		if err := writeFile(deltaOutPath, deltaBytes); err != nil {
			return err
		}
		fmt.Println("Delta is already in-place format; copied unchanged.")
		return nil
	}

	// Verify reference matches the delta's embedded source CRC before converting.
	rCrc := delta.Crc64XZ(r)
	if rCrc != result.SrcCrc {
		return fmt.Errorf("source file does not match delta: expected %s, got %s",
			toHex(result.SrcCrc), toHex(rCrc))
	}
	if err := delta.ValidatePlacedCommands(result.Commands, len(r), result.VersionSize, false); err != nil {
		return err
	}

	t0 := now()
	commands := delta.UnplaceCommands(result.Commands)
	ipPlaced := delta.MakeInplace(r, commands, policy)
	elapsed := since(t0)

	ipDelta, err := delta.EncodeDelta(ipPlaced, true, result.VersionSize, result.SrcCrc, result.DstCrc)
	if err != nil {
		return err
	}
	if err := writeFile(deltaOutPath, ipDelta); err != nil {
		return err
	}

	stats := delta.PlacedSummaryOf(ipPlaced)
	fmt.Printf("Reference:    %s (%d bytes)\n", refPath, len(r))
	fmt.Printf("Input delta:  %s (%d bytes)\n", deltaInPath, len(deltaBytes))
	fmt.Printf("Output delta: %s (%d bytes)\n", deltaOutPath, len(ipDelta))
	fmt.Printf("Format:       in-place (%s)\n", policyStr)
	fmt.Printf("Commands:     %d copies, %d adds\n", stats.NumCopies, stats.NumAdds)
	fmt.Printf("Copy bytes:   %d\n", stats.CopyBytes)
	fmt.Printf("Add bytes:    %d\n", stats.AddBytes)
	fmt.Printf("Time:         %.3fs\n", elapsed)
	return nil
}

// ── timing helpers ──

func now() time.Time            { return time.Now() }
func since(t time.Time) float64 { return time.Since(t).Seconds() }
