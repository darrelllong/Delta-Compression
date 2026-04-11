package delta

import java.nio.file.{Files, Path}

/**
 * CLI for differential compression (Ajtai et al. 2002).
 *
 * Usage:
 *   scala -cp delta.jar delta.Delta encode algorithm reference version delta  [options]
 *   scala -cp delta.jar delta.Delta decode reference delta output
 *   scala -cp delta.jar delta.Delta info delta
 *   scala -cp delta.jar delta.Delta inplace ref delta_in delta_out [--policy P]
 *
 * Algorithms: greedy, onepass, correcting
 * Options: --seed-len N, --table-size N, --max-table N (k/M/B ok),
 *          --inplace, --policy P, --verbose, --splay
 */
@main def Delta(args: String*): Unit =
  try run(args.toArray)
  catch {
    case e: IllegalArgumentException => System.err.println(e.getMessage); sys.exit(1)
    case e: java.io.IOException      => System.err.println(e.getMessage); sys.exit(1)
  }

/** Dispatch to the appropriate subcommand based on the first argument. */
private def run(args: Array[String]): Unit = {
  if args.isEmpty then usage()
  args(0) match {
    case "encode"  => cmdEncode(args)
    case "decode"  => cmdDecode(args)
    case "info"    => cmdInfo(args)
    case "inplace" => cmdInplace(args)
    case _         => usage()
  }
}

/** Parse a size string with optional k/M/B suffix (decimal multipliers). */
private def parseSizeSuffix(s: String): Int = {
  if s.isEmpty then throw new IllegalArgumentException("empty size value")
  val (mult, num) = s.last match {
    case 'k' | 'K' => (1_000L,         s.init)
    case 'm' | 'M' => (1_000_000L,     s.init)
    case 'b' | 'B' => (1_000_000_000L, s.init)
    case _         => (1L,             s)
  }
  val result = num.toLong * mult
  if result < 0L || result > Int.MaxValue then
    throw new IllegalArgumentException(s"size too large: $s")
  result.toInt
}

private def usage(): Nothing = throw new IllegalArgumentException(
  "Usage:\n" +
  "  delta encode <algorithm> <ref> <ver> <delta> [options]\n" +
  "  delta decode <ref> <delta> <output> [--ignore-hash]\n" +
  "  delta info <delta>\n" +
  "  delta inplace <ref> <delta_in> <delta_out> [--policy P]\n\n" +
  "Algorithms: greedy, onepass, correcting\n" +
  "Options: --seed-len N, --table-size N, --max-table N (k/M/B ok),\n" +
  "         --inplace, --policy P, --verbose, --splay"
)

/** encode: diff R→V with the chosen algorithm, write a binary delta file, and print statistics. */
private def cmdEncode(args: Array[String]): Unit = {
  if args.length < 5 then usage()

  val algo      = parseAlgorithm(args(1))
  val refPath   = args(2)
  val verPath   = args(3)
  val deltaPath = args(4)

  var opts    = DiffOptions()
  var inplace = false
  var policy  = CyclePolicy.Localmin

  var i = 5
  while i < args.length do {
    args(i) match {
      case "--seed-len" =>
        if i + 1 >= args.length then throw new IllegalArgumentException("--seed-len: missing value")
        i += 1; opts = opts.copy(p = args(i).toInt)
      case "--table-size" =>
        if i + 1 >= args.length then throw new IllegalArgumentException("--table-size: missing value")
        i += 1; opts = opts.copy(q = args(i).toInt)
      case "--max-table" =>
        if i + 1 >= args.length then throw new IllegalArgumentException("--max-table: missing value")
        i += 1; opts = opts.copy(maxTable = parseSizeSuffix(args(i)))
      case "--inplace"    => inplace = true
      case "--policy" =>
        if i + 1 >= args.length then throw new IllegalArgumentException("--policy: missing value")
        i += 1; policy = parsePolicy(args(i))
      case "--verbose"    => opts = opts.copy(verbose  = true)
      case "--splay"      => opts = opts.copy(useSplay = true)
      case other          => throw new IllegalArgumentException(s"Unknown option: $other")
    }
    i += 1
  }

  if opts.p < 1 then throw new IllegalArgumentException("--seed-len must be >= 1")

  val r      = readFile(refPath)
  val v      = readFile(verPath)
  val srcCrc = Crc64.hash8(r)
  val dstCrc = Crc64.hash8(v)

  val t0       = System.nanoTime()
  val commands = diff(algo, r, v, opts)
  val placed   = if inplace then makeInplace(r, commands, policy) else placeCommands(commands)
  val elapsed  = System.nanoTime() - t0

  val deltaBytes = encodeDeltaLarge(placed, inplace, v.length, srcCrc, dstCrc)
  writeFile(deltaPath, deltaBytes)

  val stats    = placedSummary(placed)
  val ratio    = if v.length > 0 then deltaBytes.length.toDouble / v.length else 0.0
  val algoName = algo.toString.toLowerCase
  val splayTag = if opts.useSplay then " [splay]" else ""
  if inplace then
    System.out.printf("Algorithm:    %s%s + in-place (%s)%n", algoName, splayTag, policy.toString.toLowerCase)
  else
    System.out.printf("Algorithm:    %s%s%n", algoName, splayTag)
  System.out.printf("Reference:    %s (%d bytes)%n", refPath, r.length)
  System.out.printf("Version:      %s (%d bytes)%n", verPath, v.length)
  System.out.printf("Delta:        %s (%d bytes)%n", deltaPath, deltaBytes.length)
  System.out.printf("Compression:  %.4f (delta/version)%n", ratio)
  System.out.printf("Commands:     %d copies, %d adds%n", stats.numCopies, stats.numAdds)
  System.out.printf("Copy bytes:   %d%n", stats.copyBytes)
  System.out.printf("Add bytes:    %d%n", stats.addBytes)
  System.out.printf("Src CRC:      %s%n", toHex(srcCrc))
  System.out.printf("Dst CRC:      %s%n", toHex(dstCrc))
  System.out.printf("Time:         %.3fs%n", elapsed / 1e9)
}

/** decode: apply a binary delta file to R and write the reconstructed version. */
private def cmdDecode(args: Array[String]): Unit = {
  if args.length < 4 then usage()

  val refPath    = args(1)
  val deltaPath  = args(2)
  val outPath    = args(3)
  var ignoreHash = false
  for j <- 4 until args.length do
    args(j) match {
      case "--ignore-hash" => ignoreHash = true
      case other => throw new IllegalArgumentException(s"Unknown decode option: $other")
    }

  val r          = readFile(refPath)
  val deltaBytes = readFile(deltaPath)
  val result     = decodeDelta(deltaBytes)

  // Pre-check: verify reference matches embedded src_crc.
  val rCrc = Crc64.hash8(r)
  if !rCrc.sameElements(result.srcCrc) then {
    if !ignoreHash then {
      System.err.printf("source file does not match delta: expected %s, got %s%n",
        toHex(result.srcCrc), toHex(rCrc))
      sys.exit(1)
    }
    System.err.println("warning: skipping source CRC check (--ignore-hash)")
  }
  validatePlacedCommands(result.commands, r.length, result.versionSize, result.inplace)

  val t0 = System.nanoTime()
  val out =
    if result.inplace then applyDeltaInplace(r, result.commands, result.versionSize)
    else { val o = new Array[Byte](result.versionSize); applyPlacedTo(r, result.commands, o); o }
  val elapsed = System.nanoTime() - t0

  writeFile(outPath, out)
  val outCrc = Crc64.hash8(out)

  // Post-check: verify output matches embedded dst_crc.
  if !outCrc.sameElements(result.dstCrc) then {
    if !ignoreHash then {
      System.err.println("output integrity check failed")
      sys.exit(1)
    }
    System.err.println("warning: skipping output CRC check (--ignore-hash)")
  }

  val fmt = if result.inplace then "in-place" else "standard"
  System.out.printf("Format:       %s%n", fmt)
  System.out.printf("Reference:    %s (%d bytes)%n", refPath, r.length)
  System.out.printf("Delta:        %s (%d bytes)%n", deltaPath, deltaBytes.length)
  System.out.printf("Output:       %s (%d bytes)%n", outPath, out.length)
  if !ignoreHash then {
    System.out.printf("Src CRC:      %s  OK%n", toHex(result.srcCrc))
    System.out.printf("Dst CRC:      %s  OK%n", toHex(result.dstCrc))
  }
  System.out.printf("Time:         %.3fs%n", elapsed / 1e9)
}

/** info: print the header fields and command summary of a binary delta file. */
private def cmdInfo(args: Array[String]): Unit = {
  if args.length < 2 then usage()

  val deltaPath  = args(1)
  val deltaBytes = readFile(deltaPath)
  val result     = decodeDelta(deltaBytes)
  val stats      = placedSummary(result.commands)

  val fmt = if result.inplace then "in-place" else "standard"
  System.out.printf("Delta file:   %s (%d bytes)%n", deltaPath, deltaBytes.length)
  System.out.printf("Format:       %s%n", fmt)
  System.out.printf("Version size: %d bytes%n", result.versionSize)
  System.out.printf("Src CRC:      %s%n", toHex(result.srcCrc))
  System.out.printf("Dst CRC:      %s%n", toHex(result.dstCrc))
  System.out.printf("Commands:     %d%n", stats.numCommands)
  System.out.printf("  Copies:     %d (%d bytes)%n", stats.numCopies, stats.copyBytes)
  System.out.printf("  Adds:       %d (%d bytes)%n", stats.numAdds, stats.addBytes)
  System.out.printf("Output size:  %d bytes%n", stats.totalOutputBytes)
}

/** inplace: convert a standard delta file to in-place format using the CRWI algorithm. */
private def cmdInplace(args: Array[String]): Unit = {
  if args.length < 4 then usage()

  val refPath      = args(1)
  val deltaInPath  = args(2)
  val deltaOutPath = args(3)
  var policy       = CyclePolicy.Localmin
  var policyStr    = "localmin"

  var i = 4
  while i < args.length do {
    args(i) match {
      case "--policy" =>
        if i + 1 >= args.length then throw new IllegalArgumentException("--policy: missing value")
        i += 1
        policy    = parsePolicy(args(i))
        policyStr = policy.toString.toLowerCase
      case other => throw new IllegalArgumentException(s"Unknown inplace option: $other")
    }
    i += 1
  }

  val r          = readFile(refPath)
  val deltaBytes = readFile(deltaInPath)
  val result     = decodeDelta(deltaBytes)

  if result.inplace then {
    writeFile(deltaOutPath, deltaBytes)
    println("Delta is already in-place format; copied unchanged.")
    return
  }

  // Verify reference matches the delta's embedded source CRC before converting.
  val rCrc = Crc64.hash8(r)
  if !rCrc.sameElements(result.srcCrc) then {
    System.err.printf("source file does not match delta: expected %s, got %s%n",
      toHex(result.srcCrc), toHex(rCrc))
    sys.exit(1)
  }
  validatePlacedCommands(result.commands, r.length, result.versionSize, false)

  val t0       = System.nanoTime()
  val commands = unplaceCommands(result.commands)
  val ipPlaced = makeInplace(r, commands, policy)
  val elapsed  = System.nanoTime() - t0

  val ipDelta = encodeDeltaLarge(ipPlaced, true, result.versionSize, result.srcCrc, result.dstCrc)
  writeFile(deltaOutPath, ipDelta)

  val stats = placedSummary(ipPlaced)
  System.out.printf("Reference:    %s (%d bytes)%n", refPath, r.length)
  System.out.printf("Input delta:  %s (%d bytes)%n", deltaInPath, deltaBytes.length)
  System.out.printf("Output delta: %s (%d bytes)%n", deltaOutPath, ipDelta.length)
  System.out.printf("Format:       in-place (%s)%n", policyStr)
  System.out.printf("Commands:     %d copies, %d adds%n", stats.numCopies, stats.numAdds)
  System.out.printf("Copy bytes:   %d%n", stats.copyBytes)
  System.out.printf("Add bytes:    %d%n", stats.addBytes)
  System.out.printf("Time:         %.3fs%n", elapsed / 1e9)
}

private def parseAlgorithm(s: String): Algorithm = s.toLowerCase match {
  case "greedy"     => Algorithm.Greedy
  case "onepass"    => Algorithm.Onepass
  case "correcting" => Algorithm.Correcting
  case _            => throw new IllegalArgumentException(s"Unknown algorithm: $s")
}

private def parsePolicy(s: String): CyclePolicy = s.toLowerCase match {
  case "localmin" => CyclePolicy.Localmin
  case "constant" => CyclePolicy.Constant
  case _          => throw new IllegalArgumentException(s"Unknown policy: $s")
}

private def readFile(path: String): Array[Byte]          = Files.readAllBytes(Path.of(path))
private def writeFile(path: String, data: Array[Byte]) = Files.write(Path.of(path), data)

private def toHex(bytes: Array[Byte]): String =
  bytes.map(b => "%02x".format(b.toInt & 0xFF)).mkString
