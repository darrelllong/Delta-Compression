// Command delta computes and applies binary deltas.
//
// Usage:
//
//	delta encode <algorithm> <ref> <ver> <delta> [options]
//	delta decode <ref> <delta> <output> [--ignore-hash]
//	delta info <delta>
//	delta inplace <ref> <delta_in> <delta_out> [--policy P]
//
// The algorithms are greedy, onepass and correcting. The options of encode
// are --seed-len N, --table-size N, --max-table N (with an optional k, M or
// B suffix), --inplace, --large, --policy P, --verbose and --splay.
package main

import (
	"errors"
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
	}
	usage()
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
         --inplace, --large, --policy P, --verbose, --splay`)
	os.Exit(1)
}

// parseSize parses a decimal count with an optional suffix: k for thousand,
// M for million, B for billion.
func parseSize(s string) (int, error) {
	if s == "" {
		return 0, errors.New("empty size value")
	}
	mult := 1
	digits := s[:len(s)-1]
	switch s[len(s)-1] {
	case 'k', 'K':
		mult = 1_000
	case 'm', 'M':
		mult = 1_000_000
	case 'b', 'B':
		mult = 1_000_000_000
	default:
		digits = s
	}
	n, err := strconv.ParseInt(digits, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("invalid size: %s", s)
	}
	return int(n) * mult, nil
}

func parseAlgorithm(s string) (delta.Algorithm, error) {
	switch strings.ToLower(s) {
	case "greedy":
		return delta.AlgorithmGreedy, nil
	case "onepass":
		return delta.AlgorithmOnepass, nil
	case "correcting":
		return delta.AlgorithmCorrecting, nil
	}
	return 0, fmt.Errorf("unknown algorithm: %s", s)
}

func parsePolicy(s string) (delta.CyclePolicy, error) {
	switch strings.ToLower(s) {
	case "localmin":
		return delta.CyclePolicyLocalmin, nil
	case "constant":
		return delta.CyclePolicyConstant, nil
	}
	return 0, fmt.Errorf("unknown policy: %s", s)
}

// An optionArgs is the list of options that follow a subcommand's
// positional arguments.
type optionArgs []string

// next removes and returns the first argument.
func (a *optionArgs) next() string {
	s := (*a)[0]
	*a = (*a)[1:]
	return s
}

// value removes and returns the argument of the option name.
func (a *optionArgs) value(name string) (string, error) {
	if len(*a) == 0 {
		return "", fmt.Errorf("%s: missing value", name)
	}
	return a.next(), nil
}

// intValue removes the argument of the option name and returns it as
// parse converts it.
func (a *optionArgs) intValue(name string, parse func(string) (int, error)) (int, error) {
	s, err := a.value(name)
	if err != nil {
		return 0, err
	}
	n, err := parse(s)
	if err != nil {
		return 0, fmt.Errorf("%s: %v", name, err)
	}
	return n, nil
}

func formatName(inplace bool) string {
	if inplace {
		return "in-place"
	}
	return "standard"
}

func cmdEncode(args []string) error {
	if len(args) < 5 {
		usage()
	}
	algo, err := parseAlgorithm(args[1])
	if err != nil {
		return err
	}
	refPath, verPath, deltaPath := args[2], args[3], args[4]

	opts := delta.DefaultDiffOptions()
	inplace := false
	forceLarge := false
	policy := delta.CyclePolicyLocalmin
	for rest := optionArgs(args[5:]); len(rest) > 0; {
		var err error
		switch name := rest.next(); name {
		case "--seed-len":
			opts.P, err = rest.intValue(name, strconv.Atoi)
		case "--table-size":
			opts.Q, err = rest.intValue(name, strconv.Atoi)
		case "--max-table":
			opts.MaxTable, err = rest.intValue(name, parseSize)
		case "--inplace":
			inplace = true
		case "--large":
			forceLarge = true
		case "--policy":
			var s string
			if s, err = rest.value(name); err == nil {
				policy, err = parsePolicy(s)
			}
		case "--verbose":
			opts.Verbose = true
		case "--splay":
			opts.UseSplay = true
		default:
			err = fmt.Errorf("unknown option: %s", name)
		}
		if err != nil {
			return err
		}
	}
	if opts.P < 1 {
		return errors.New("--seed-len must be >= 1")
	}

	r, err := os.ReadFile(refPath)
	if err != nil {
		return err
	}
	v, err := os.ReadFile(verPath)
	if err != nil {
		return err
	}
	srcCrc := delta.Crc64XZ(r)
	dstCrc := delta.Crc64XZ(v)

	start := time.Now()
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
	elapsed := time.Since(start)

	deltaBytes := delta.EncodeDeltaLarge(placed, inplace, len(v), srcCrc, dstCrc, forceLarge)
	if err := os.WriteFile(deltaPath, deltaBytes, 0644); err != nil {
		return err
	}

	stats := delta.PlacedSummaryOf(placed)
	ratio := 0.0
	if len(v) > 0 {
		ratio = float64(len(deltaBytes)) / float64(len(v))
	}
	name := algo.String()
	if opts.UseSplay {
		name += " [splay]"
	}
	if inplace {
		name += fmt.Sprintf(" + in-place (%s)", policy)
	}
	fmt.Printf("Algorithm:    %s\n", name)
	fmt.Printf("Reference:    %s (%d bytes)\n", refPath, len(r))
	fmt.Printf("Version:      %s (%d bytes)\n", verPath, len(v))
	fmt.Printf("Delta:        %s (%d bytes)\n", deltaPath, len(deltaBytes))
	fmt.Printf("Compression:  %.4f (delta/version)\n", ratio)
	fmt.Printf("Commands:     %d copies, %d adds\n", stats.NumCopies, stats.NumAdds)
	fmt.Printf("Copy bytes:   %d\n", stats.CopyBytes)
	fmt.Printf("Add bytes:    %d\n", stats.AddBytes)
	fmt.Printf("Src CRC:      %x\n", srcCrc)
	fmt.Printf("Dst CRC:      %x\n", dstCrc)
	fmt.Printf("Time:         %.3fs\n", elapsed.Seconds())
	return nil
}

func cmdDecode(args []string) error {
	if len(args) < 4 {
		usage()
	}
	refPath, deltaPath, outPath := args[1], args[2], args[3]
	ignoreHash := false
	for _, a := range args[4:] {
		if a != "--ignore-hash" {
			return fmt.Errorf("unknown decode option: %s", a)
		}
		ignoreHash = true
	}

	r, err := os.ReadFile(refPath)
	if err != nil {
		return err
	}
	deltaBytes, err := os.ReadFile(deltaPath)
	if err != nil {
		return err
	}
	d, err := delta.DecodeDelta(deltaBytes)
	if err != nil {
		return err
	}

	if crc := delta.Crc64XZ(r); crc != d.SrcCrc {
		if !ignoreHash {
			return fmt.Errorf("source file does not match delta: expected %x, got %x", d.SrcCrc, crc)
		}
		fmt.Fprintln(os.Stderr, "warning: skipping source CRC check (--ignore-hash)")
	}
	if err := delta.ValidatePlacedCommands(d.Commands, len(r), d.VersionSize, d.Inplace); err != nil {
		return err
	}

	start := time.Now()
	var out []byte
	if d.Inplace {
		out = delta.ApplyDeltaInplace(r, d.Commands, d.VersionSize)
	} else {
		out = make([]byte, d.VersionSize)
		delta.ApplyPlacedTo(r, d.Commands, out)
	}
	elapsed := time.Since(start)

	// The output is written even if its checksum is wrong, so that it can
	// be examined.
	if err := os.WriteFile(outPath, out, 0644); err != nil {
		return err
	}
	if delta.Crc64XZ(out) != d.DstCrc {
		if !ignoreHash {
			return errors.New("output integrity check failed")
		}
		fmt.Fprintln(os.Stderr, "warning: skipping output CRC check (--ignore-hash)")
	}

	fmt.Printf("Format:       %s\n", formatName(d.Inplace))
	fmt.Printf("Reference:    %s (%d bytes)\n", refPath, len(r))
	fmt.Printf("Delta:        %s (%d bytes)\n", deltaPath, len(deltaBytes))
	fmt.Printf("Output:       %s (%d bytes)\n", outPath, len(out))
	if !ignoreHash {
		fmt.Printf("Src CRC:      %x  OK\n", d.SrcCrc)
		fmt.Printf("Dst CRC:      %x  OK\n", d.DstCrc)
	}
	fmt.Printf("Time:         %.3fs\n", elapsed.Seconds())
	return nil
}

func cmdInfo(args []string) error {
	if len(args) < 2 {
		usage()
	}
	deltaPath := args[1]
	deltaBytes, err := os.ReadFile(deltaPath)
	if err != nil {
		return err
	}
	d, err := delta.DecodeDelta(deltaBytes)
	if err != nil {
		return err
	}
	stats := delta.PlacedSummaryOf(d.Commands)

	fmt.Printf("Delta file:   %s (%d bytes)\n", deltaPath, len(deltaBytes))
	fmt.Printf("Format:       %s\n", formatName(d.Inplace))
	fmt.Printf("Version size: %d bytes\n", d.VersionSize)
	fmt.Printf("Src CRC:      %x\n", d.SrcCrc)
	fmt.Printf("Dst CRC:      %x\n", d.DstCrc)
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
	refPath, inPath, outPath := args[1], args[2], args[3]
	policy := delta.CyclePolicyLocalmin
	forceLarge := false
	for rest := optionArgs(args[4:]); len(rest) > 0; {
		switch name := rest.next(); name {
		case "--policy":
			s, err := rest.value(name)
			if err != nil {
				return err
			}
			if policy, err = parsePolicy(s); err != nil {
				return err
			}
		case "--large":
			forceLarge = true
		default:
			return fmt.Errorf("unknown inplace option: %s", name)
		}
	}

	r, err := os.ReadFile(refPath)
	if err != nil {
		return err
	}
	deltaBytes, err := os.ReadFile(inPath)
	if err != nil {
		return err
	}
	d, err := delta.DecodeDelta(deltaBytes)
	if err != nil {
		return err
	}
	if d.Inplace {
		if err := os.WriteFile(outPath, deltaBytes, 0644); err != nil {
			return err
		}
		fmt.Println("Delta is already in-place format; copied unchanged.")
		return nil
	}

	// The conversion reads the reference to turn copies into adds, so the
	// reference must be the one the delta was made from.
	if crc := delta.Crc64XZ(r); crc != d.SrcCrc {
		return fmt.Errorf("source file does not match delta: expected %x, got %x", d.SrcCrc, crc)
	}
	if err := delta.ValidatePlacedCommands(d.Commands, len(r), d.VersionSize, false); err != nil {
		return err
	}

	start := time.Now()
	placed := delta.MakeInplace(r, delta.UnplaceCommands(d.Commands), policy)
	elapsed := time.Since(start)

	out := delta.EncodeDeltaLarge(placed, true, d.VersionSize, d.SrcCrc, d.DstCrc, forceLarge)
	if err := os.WriteFile(outPath, out, 0644); err != nil {
		return err
	}

	stats := delta.PlacedSummaryOf(placed)
	fmt.Printf("Reference:    %s (%d bytes)\n", refPath, len(r))
	fmt.Printf("Input delta:  %s (%d bytes)\n", inPath, len(deltaBytes))
	fmt.Printf("Output delta: %s (%d bytes)\n", outPath, len(out))
	fmt.Printf("Format:       in-place (%s)\n", policy)
	fmt.Printf("Commands:     %d copies, %d adds\n", stats.NumCopies, stats.NumAdds)
	fmt.Printf("Copy bytes:   %d\n", stats.CopyBytes)
	fmt.Printf("Add bytes:    %d\n", stats.AddBytes)
	fmt.Printf("Time:         %.3fs\n", elapsed.Seconds())
	return nil
}
