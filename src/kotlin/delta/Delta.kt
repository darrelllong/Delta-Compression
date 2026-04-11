@file:JvmName("Delta")

package delta

import java.nio.file.Files
import java.nio.file.Path

/**
 * CLI for differential compression (Ajtai et al. 2002).
 *
 * Usage:
 *   java delta.Delta encode algorithm reference version delta  [options]
 *   java delta.Delta decode reference delta output
 *   java delta.Delta info delta
 *   java delta.Delta inplace ref delta_in delta_out [--policy P]
 *
 * Algorithms: greedy, onepass, correcting
 * Options: --seed-len N, --table-size N, --max-table N (k/M/B ok),
 *          --inplace, --policy P, --verbose, --splay
 */
fun main(args: Array<String>) {
    try {
        run(args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.exit(1)
    } catch (e: java.io.IOException) {
        System.err.println(e.message)
        System.exit(1)
    }
}

/** Dispatch to the appropriate subcommand based on the first argument. */
private fun run(args: Array<String>) {
    if (args.isEmpty()) usage()
    when (args[0]) {
        "encode"  -> encode(args)
        "decode"  -> decode(args)
        "info"    -> info(args)
        "inplace" -> inplace(args)
        else      -> usage()
    }
}

/** Parse a size string with optional k/M/B suffix (decimal multipliers). */
private fun parseSizeSuffix(s: String): Int {
    if (s.isEmpty()) throw IllegalArgumentException("empty size value")
    val last = s.last()
    val (mult, num) = when (last) {
        'k', 'K' -> 1_000L         to s.dropLast(1)
        'm', 'M' -> 1_000_000L     to s.dropLast(1)
        'b', 'B' -> 1_000_000_000L to s.dropLast(1)
        else     -> 1L             to s
    }
    val result = num.toLong() * mult
    if (result < 0L || result > Int.MAX_VALUE)
        throw IllegalArgumentException("size too large: $s")
    return result.toInt()
}

private fun usage(): Nothing = throw IllegalArgumentException(
    "Usage:\n" +
    "  java delta.Delta encode <algorithm> <ref> <ver> <delta> [options]\n" +
    "  java delta.Delta decode <ref> <delta> <output> [--ignore-hash]\n" +
    "  java delta.Delta info <delta>\n" +
    "  java delta.Delta inplace <ref> <delta_in> <delta_out> [--policy P]\n\n" +
    "Algorithms: greedy, onepass, correcting\n" +
    "Options: --seed-len N, --table-size N, --max-table N (k/M/B ok),\n" +
    "         --inplace, --policy P, --verbose, --splay"
)

/** encode: diff R→V with the chosen algorithm, write a binary delta file, and print statistics. */
private fun encode(args: Array<String>) {
    if (args.size < 5) usage()

    val algo      = parseAlgorithm(args[1])
    val refPath   = args[2]
    val verPath   = args[3]
    val deltaPath = args[4]

    var opts   = DiffOptions()
    var inplace = false
    var policy  = CyclePolicy.LOCALMIN

    var i = 5
    while (i < args.size) {
        when (args[i]) {
            "--seed-len" -> {
                if (i + 1 >= args.size) throw IllegalArgumentException("--seed-len: missing value")
                opts = opts.copy(p = args[++i].toInt())
            }
            "--table-size" -> {
                if (i + 1 >= args.size) throw IllegalArgumentException("--table-size: missing value")
                opts = opts.copy(q = args[++i].toInt())
            }
            "--max-table" -> {
                if (i + 1 >= args.size) throw IllegalArgumentException("--max-table: missing value")
                opts = opts.copy(maxTable = parseSizeSuffix(args[++i]))
            }
            "--inplace"    -> { inplace = true }
            "--policy" -> {
                if (i + 1 >= args.size) throw IllegalArgumentException("--policy: missing value")
                policy = parsePolicy(args[++i])
            }
            "--verbose"    -> { opts = opts.copy(verbose  = true) }
            "--splay"      -> { opts = opts.copy(useSplay = true) }
            else           -> throw IllegalArgumentException("Unknown option: ${args[i]}")
        }
        i++
    }

    if (opts.p < 1) throw IllegalArgumentException("--seed-len must be >= 1")

    val r      = readFile(refPath)
    val v      = readFile(verPath)
    val srcCrc = Crc64.hash8(r)
    val dstCrc = Crc64.hash8(v)

    val t0       = System.nanoTime()
    val commands = diff(algo, r, v, opts)
    val placed   = if (inplace) makeInplace(r, commands, policy) else placeCommands(commands)
    val elapsed  = System.nanoTime() - t0

    val deltaBytes = encodeDeltaLarge(placed, inplace, v.size, srcCrc, dstCrc)
    writeFile(deltaPath, deltaBytes)

    val stats    = placedSummary(placed)
    val ratio    = if (v.isNotEmpty()) deltaBytes.size.toDouble() / v.size else 0.0
    val algoName = algo.name.lowercase()
    val splayTag = if (opts.useSplay) " [splay]" else ""
    if (inplace) {
        System.out.printf("Algorithm:    %s%s + in-place (%s)%n", algoName, splayTag, policy.name.lowercase())
    } else {
        System.out.printf("Algorithm:    %s%s%n", algoName, splayTag)
    }
    System.out.printf("Reference:    %s (%d bytes)%n", refPath, r.size)
    System.out.printf("Version:      %s (%d bytes)%n", verPath, v.size)
    System.out.printf("Delta:        %s (%d bytes)%n", deltaPath, deltaBytes.size)
    System.out.printf("Compression:  %.4f (delta/version)%n", ratio)
    System.out.printf("Commands:     %d copies, %d adds%n", stats.numCopies, stats.numAdds)
    System.out.printf("Copy bytes:   %d%n", stats.copyBytes)
    System.out.printf("Add bytes:    %d%n", stats.addBytes)
    System.out.printf("Src CRC:      %s%n", toHex(srcCrc))
    System.out.printf("Dst CRC:      %s%n", toHex(dstCrc))
    System.out.printf("Time:         %.3fs%n", elapsed / 1e9)
}

/** decode: apply a binary delta file to R and write the reconstructed version. */
private fun decode(args: Array<String>) {
    if (args.size < 4) usage()

    val refPath   = args[1]
    val deltaPath = args[2]
    val outPath   = args[3]
    var ignoreHash = false
    for (j in 4 until args.size) {
        when (args[j]) {
            "--ignore-hash" -> ignoreHash = true
            else -> throw IllegalArgumentException("Unknown decode option: ${args[j]}")
        }
    }

    val r          = readFile(refPath)
    val deltaBytes = readFile(deltaPath)
    val result     = decodeDelta(deltaBytes)

    // Pre-check: verify reference matches embedded src_crc.
    val rCrc = Crc64.hash8(r)
    if (!rCrc.contentEquals(result.srcCrc)) {
        if (!ignoreHash) {
            System.err.printf("source file does not match delta: expected %s, got %s%n",
                toHex(result.srcCrc), toHex(rCrc))
            System.exit(1)
        }
        System.err.println("warning: skipping source CRC check (--ignore-hash)")
    }
    validatePlacedCommands(result.commands, r.size, result.versionSize, result.inplace)

    val t0 = System.nanoTime()
    val out = if (result.inplace) {
        applyDeltaInplace(r, result.commands, result.versionSize)
    } else {
        ByteArray(result.versionSize).also { applyPlacedTo(r, result.commands, it) }
    }
    val elapsed = System.nanoTime() - t0

    writeFile(outPath, out)
    val outCrc = Crc64.hash8(out)

    // Post-check: verify output matches embedded dst_crc.
    if (!outCrc.contentEquals(result.dstCrc)) {
        if (!ignoreHash) {
            System.err.println("output integrity check failed")
            System.exit(1)
        }
        System.err.println("warning: skipping output CRC check (--ignore-hash)")
    }

    val fmt = if (result.inplace) "in-place" else "standard"
    System.out.printf("Format:       %s%n", fmt)
    System.out.printf("Reference:    %s (%d bytes)%n", refPath, r.size)
    System.out.printf("Delta:        %s (%d bytes)%n", deltaPath, deltaBytes.size)
    System.out.printf("Output:       %s (%d bytes)%n", outPath, out.size)
    if (!ignoreHash) {
        System.out.printf("Src CRC:      %s  OK%n", toHex(result.srcCrc))
        System.out.printf("Dst CRC:      %s  OK%n", toHex(result.dstCrc))
    }
    System.out.printf("Time:         %.3fs%n", elapsed / 1e9)
}

/** info: print the header fields and command summary of a binary delta file. */
private fun info(args: Array<String>) {
    if (args.size < 2) usage()

    val deltaPath  = args[1]
    val deltaBytes = readFile(deltaPath)
    val result     = decodeDelta(deltaBytes)
    val stats      = placedSummary(result.commands)

    val fmt = if (result.inplace) "in-place" else "standard"
    System.out.printf("Delta file:   %s (%d bytes)%n", deltaPath, deltaBytes.size)
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
private fun inplace(args: Array<String>) {
    if (args.size < 4) usage()

    val refPath      = args[1]
    val deltaInPath  = args[2]
    val deltaOutPath = args[3]
    var policy       = CyclePolicy.LOCALMIN
    var policyStr    = "localmin"

    var i = 4
    while (i < args.size) {
        when (args[i]) {
            "--policy" -> {
                if (i + 1 >= args.size) throw IllegalArgumentException("--policy: missing value")
                policy    = parsePolicy(args[++i])
                policyStr = policy.name.lowercase()
            }
            else -> throw IllegalArgumentException("Unknown inplace option: ${args[i]}")
        }
        i++
    }

    val r          = readFile(refPath)
    val deltaBytes = readFile(deltaInPath)
    val result     = decodeDelta(deltaBytes)

    if (result.inplace) {
        writeFile(deltaOutPath, deltaBytes)
        println("Delta is already in-place format; copied unchanged.")
        return
    }
    validatePlacedCommands(result.commands, r.size, result.versionSize, result.inplace)

    val t0       = System.nanoTime()
    val commands = unplaceCommands(result.commands)
    val ipPlaced = makeInplace(r, commands, policy)
    val elapsed  = System.nanoTime() - t0

    val ipDelta = encodeDeltaLarge(ipPlaced, true, result.versionSize, result.srcCrc, result.dstCrc)
    writeFile(deltaOutPath, ipDelta)

    val stats = placedSummary(ipPlaced)
    System.out.printf("Reference:    %s (%d bytes)%n", refPath, r.size)
    System.out.printf("Input delta:  %s (%d bytes)%n", deltaInPath, deltaBytes.size)
    System.out.printf("Output delta: %s (%d bytes)%n", deltaOutPath, ipDelta.size)
    System.out.printf("Format:       in-place (%s)%n", policyStr)
    System.out.printf("Commands:     %d copies, %d adds%n", stats.numCopies, stats.numAdds)
    System.out.printf("Copy bytes:   %d%n", stats.copyBytes)
    System.out.printf("Add bytes:    %d%n", stats.addBytes)
    System.out.printf("Time:         %.3fs%n", elapsed / 1e9)
}

private fun parseAlgorithm(s: String): Algorithm = when (s.lowercase()) {
    "greedy"     -> Algorithm.GREEDY
    "onepass"    -> Algorithm.ONEPASS
    "correcting" -> Algorithm.CORRECTING
    else         -> throw IllegalArgumentException("Unknown algorithm: $s")
}

private fun parsePolicy(s: String): CyclePolicy = when (s.lowercase()) {
    "localmin" -> CyclePolicy.LOCALMIN
    "constant" -> CyclePolicy.CONSTANT
    else       -> throw IllegalArgumentException("Unknown policy: $s")
}

private fun readFile(path: String): ByteArray  = Files.readAllBytes(Path.of(path))
private fun writeFile(path: String, data: ByteArray) = Files.write(Path.of(path), data)

private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
