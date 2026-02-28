package delta

/**
 * Unified binary delta format (Section 2.1.1).
 *
 * Header: magic(4) + flags(1) + version_size(u32 BE) + src_crc(8) + dst_crc(8)
 * Commands:
 *   END:  type=0
 *   COPY: type=1, src:u32, dst:u32, len:u32
 *   ADD:  type=2, dst:u32, len:u32, data
 */

case class DecodeResult(
  commands:    List[PlacedCommand],
  inplace:     Boolean,
  versionSize: Int,
  srcCrc:      Array[Byte],
  dstCrc:      Array[Byte],
)

/** Encode placed commands to the unified binary delta format. */
def encodeDelta(commands: List[PlacedCommand], inplace: Boolean,
                versionSize: Int, srcCrc: Array[Byte], dstCrc: Array[Byte]): Array[Byte] = {
  // Estimate size: header + commands + END(1)
  var est = deltaHeaderSize + 1
  for cmd <- commands do est += (cmd match {
    case _: PlacedCommand.Copy => 1 + deltaCopyPayload
    case c: PlacedCommand.Add  => 1 + deltaAddHeader + c.data.length
  })
  val out = new Array[Byte](est)
  var pos = 0

  // Header
  deltaMagic.copyToArray(out, pos); pos += deltaMagic.length
  out(pos) = if inplace then deltaFlagInplace else 0; pos += 1
  putU32BE(out, pos, versionSize); pos += deltaU32Size
  srcCrc.copyToArray(out, pos); pos += deltaCrcSize
  dstCrc.copyToArray(out, pos); pos += deltaCrcSize

  for cmd <- commands do cmd match {
    case c: PlacedCommand.Copy =>
      out(pos) = deltaCmdCopy.toByte; pos += 1
      putU32BE(out, pos, c.src);    pos += deltaU32Size
      putU32BE(out, pos, c.dst);    pos += deltaU32Size
      putU32BE(out, pos, c.length); pos += deltaU32Size
    case c: PlacedCommand.Add =>
      out(pos) = deltaCmdAdd.toByte; pos += 1
      putU32BE(out, pos, c.dst);         pos += deltaU32Size
      putU32BE(out, pos, c.data.length); pos += deltaU32Size
      c.data.copyToArray(out, pos); pos += c.data.length
  }

  out(pos) = deltaCmdEnd.toByte; pos += 1

  if pos != out.length then out.take(pos) else out
}

/** Decode the unified binary delta format. */
def decodeDelta(data: Array[Byte]): DecodeResult = {
  if data.length < deltaHeaderSize then throw new IllegalArgumentException("not a delta file")
  for i <- deltaMagic.indices do
    if data(i) != deltaMagic(i) then throw new IllegalArgumentException("not a delta file")

  val inplace     = (data(deltaMagic.length).toInt & deltaFlagInplace.toInt) != 0
  val versionSize = getU32BE(data, deltaMagic.length + 1)
  val crcOff      = deltaMagic.length + 1 + deltaU32Size
  val srcCrc      = data.slice(crcOff, crcOff + deltaCrcSize)
  val dstCrc      = data.slice(crcOff + deltaCrcSize, crcOff + 2 * deltaCrcSize)
  var pos         = deltaHeaderSize
  val commands    = scala.collection.mutable.ListBuffer[PlacedCommand]()
  var sawEnd      = false

  while pos < data.length && !sawEnd do {
    val t = data(pos).toInt & 0xFF
    pos += 1
    if t == deltaCmdEnd then sawEnd = true
    else t match {
      case `deltaCmdCopy` =>
        if pos + deltaCopyPayload > data.length then throw new IllegalArgumentException("unexpected EOF")
        val src = getU32BE(data, pos); pos += deltaU32Size
        val dst = getU32BE(data, pos); pos += deltaU32Size
        val len = getU32BE(data, pos); pos += deltaU32Size
        validatePlacedRange(dst, len, versionSize, "COPY")
        commands += PlacedCommand.Copy(src, dst, len)
      case `deltaCmdAdd` =>
        if pos + deltaAddHeader > data.length then throw new IllegalArgumentException("unexpected EOF")
        val dst = getU32BE(data, pos); pos += deltaU32Size
        val len = getU32BE(data, pos); pos += deltaU32Size
        if pos + len > data.length then throw new IllegalArgumentException("unexpected EOF")
        validatePlacedRange(dst, len, versionSize, "ADD")
        val payload = data.slice(pos, pos + len); pos += len
        commands += PlacedCommand.Add(dst, payload)
      case other =>
        throw new IllegalArgumentException(s"unknown command type: $other")
    }
  }
  if !sawEnd then throw new IllegalArgumentException("missing END command")
  if pos != data.length then throw new IllegalArgumentException("trailing data after END")

  DecodeResult(commands.toList, inplace, versionSize, srcCrc, dstCrc)
}

/** Check if binary data is an in-place delta. */
def isInplaceDelta(data: Array[Byte]): Boolean = {
  if data.length < deltaMagic.length + 1 then return false
  for i <- deltaMagic.indices do if data(i) != deltaMagic(i) then return false
  (data(deltaMagic.length).toInt & deltaFlagInplace.toInt) != 0
}

private def putU32BE(buf: Array[Byte], off: Int, value: Int): Unit = {
  buf(off)     = (value >>> 24).toByte
  buf(off + 1) = (value >>> 16).toByte
  buf(off + 2) = (value >>> 8).toByte
  buf(off + 3) = value.toByte
}

private def getU32BE(buf: Array[Byte], off: Int): Int =
  ((buf(off).toInt     & 0xFF) << 24) |
  ((buf(off + 1).toInt & 0xFF) << 16) |
  ((buf(off + 2).toInt & 0xFF) << 8)  |
   (buf(off + 3).toInt & 0xFF)

private def validatePlacedRange(dst: Int, len: Int, versionSize: Int, kind: String): Unit =
  if dst < 0 || len < 0 || dst > versionSize || len > versionSize - dst then
    throw new IllegalArgumentException(s"$kind extends past version size")
