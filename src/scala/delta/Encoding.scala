package delta

import scala.annotation.switch

/**
 * Binary delta format encode/decode (DLT\x03 small and DLT\x04 large).
 *
 * DLT\x03 (small) — 25-byte header, u32 fields, COPY/ADD only:
 *   magic(4)+flags(1)+version_size(u32 BE)+src_crc(8)+dst_crc(8)
 *
 * DLT\x04 (large) — 29-byte header, u64 fields, adds BIGCOPY/BIGADD/MOVE/BIGMOVE:
 *   magic(4)+flags(1)+version_size(u64 BE)+src_crc(8)+dst_crc(8)
 */

case class DecodeResult(
  commands:    List[PlacedCommand],
  inplace:     Boolean,
  versionSize: Int,
  srcCrc:      Array[Byte],
  dstCrc:      Array[Byte],
)

/**
 * Encode placed commands to DLT\x03 format (u32 fields, max 4 GiB).
 * Throws if a PlacedCommand.Move is present.
 * Use encodeDeltaLarge for DLT\x04 (u64 fields, Move support).
 */
def encodeDelta(commands: List[PlacedCommand], inplace: Boolean,
                versionSize: Int, srcCrc: Array[Byte], dstCrc: Array[Byte]): Array[Byte] = {
  var est = deltaHeaderSize + 1
  for cmd <- commands do est += (cmd match {
    case _: PlacedCommand.Copy => 1 + deltaCopyPayload
    case c: PlacedCommand.Add  => 1 + deltaAddHeader + c.data.length
    case _: PlacedCommand.Move => throw new IllegalArgumentException(
      "PlacedCommand.Move requires DLT\\x04 format; use encodeDeltaLarge")
  })
  val out = new Array[Byte](est)
  var pos = 0

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
    case _: PlacedCommand.Move => throw new IllegalArgumentException(
      "PlacedCommand.Move requires DLT\\x04 format; use encodeDeltaLarge")
  }

  out(pos) = deltaCmdEnd.toByte; pos += 1
  if pos != out.length then out.take(pos) else out
}

/**
 * Encode placed commands to DLT\x04 format (u64 fields, Move support).
 * Scala/JVM Int fields always fit in u32, so COPY/ADD/MOVE variants are emitted.
 */
def encodeDeltaLarge(commands: List[PlacedCommand], inplace: Boolean,
                     versionSize: Int, srcCrc: Array[Byte], dstCrc: Array[Byte]): Array[Byte] = {
  var est = deltaHeaderSizeLarge + 1
  for cmd <- commands do est += (cmd match {
    case _: PlacedCommand.Copy => 1 + deltaCopyPayload
    case c: PlacedCommand.Add  => 1 + deltaAddHeader + c.data.length
    case _: PlacedCommand.Move => 1 + deltaCopyPayload
  })
  val out = new Array[Byte](est)
  var pos = 0

  deltaMagicLarge.copyToArray(out, pos); pos += deltaMagicLarge.length
  out(pos) = if inplace then deltaFlagInplace else 0; pos += 1
  putU64BE(out, pos, Integer.toUnsignedLong(versionSize)); pos += deltaU64Size
  srcCrc.copyToArray(out, pos); pos += deltaCrcSize
  dstCrc.copyToArray(out, pos); pos += deltaCrcSize

  for cmd <- commands do cmd match {
    case c: PlacedCommand.Copy =>
      // Scala/JVM Int <= Integer.MAX_VALUE <= UINT32_MAX; always emit COPY form
      out(pos) = deltaCmdCopy.toByte; pos += 1
      putU32BE(out, pos, c.src);    pos += deltaU32Size
      putU32BE(out, pos, c.dst);    pos += deltaU32Size
      putU32BE(out, pos, c.length); pos += deltaU32Size
    case c: PlacedCommand.Add =>
      out(pos) = deltaCmdAdd.toByte; pos += 1
      putU32BE(out, pos, c.dst);         pos += deltaU32Size
      putU32BE(out, pos, c.data.length); pos += deltaU32Size
      c.data.copyToArray(out, pos); pos += c.data.length
    case c: PlacedCommand.Move =>
      out(pos) = deltaCmdMove.toByte; pos += 1
      putU32BE(out, pos, c.src);    pos += deltaU32Size
      putU32BE(out, pos, c.dst);    pos += deltaU32Size
      putU32BE(out, pos, c.length); pos += deltaU32Size
  }

  out(pos) = deltaCmdEnd.toByte; pos += 1
  if pos != out.length then out.take(pos) else out
}

/**
 * Decode DLT\x03 or DLT\x04 format. Dispatches on magic bytes.
 * CRC validation is the caller's responsibility.
 */
def decodeDelta(data: Array[Byte]): DecodeResult =
  if data.length < deltaMagic.length then throw new IllegalArgumentException("not a delta file")
  else if matchesMagic(data, deltaMagic)      then decodeSmall(data)
  else if matchesMagic(data, deltaMagicLarge) then decodeLarge(data)
  else throw new IllegalArgumentException("not a delta file")

/** Check if binary data is an in-place delta (DLT\x03 or DLT\x04). */
def isInplaceDelta(data: Array[Byte]): Boolean =
  data.length >= deltaMagic.length + 1 &&
  (matchesMagic(data, deltaMagic) || matchesMagic(data, deltaMagicLarge)) &&
  (data(deltaMagic.length).toInt & deltaFlagInplace.toInt) != 0

// ── Private decoders ──────────────────────────────────────────────────────────

private def decodeSmall(data: Array[Byte]): DecodeResult = {
  if data.length < deltaHeaderSize then throw new IllegalArgumentException("not a delta file")

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
    // @switch: consecutive 0-6 → JVM tableswitch
    (t: @switch) match {
      case 0 => // END
        sawEnd = true
      case 1 => // COPY
        if pos + deltaCopyPayload > data.length then throw new IllegalArgumentException("unexpected EOF")
        val src = getU32BE(data, pos); pos += deltaU32Size
        val dst = getU32BE(data, pos); pos += deltaU32Size
        val len = getU32BE(data, pos); pos += deltaU32Size
        validatePlacedRange(dst, len, versionSize, "COPY")
        commands += PlacedCommand.Copy(src, dst, len)
      case 2 => // ADD
        if pos + deltaAddHeader > data.length then throw new IllegalArgumentException("unexpected EOF")
        val dst = getU32BE(data, pos); pos += deltaU32Size
        val len = getU32BE(data, pos); pos += deltaU32Size
        if pos + len > data.length then throw new IllegalArgumentException("unexpected EOF")
        validatePlacedRange(dst, len, versionSize, "ADD")
        val payload = data.slice(pos, pos + len); pos += len
        commands += PlacedCommand.Add(dst, payload)
      case 3 | 4 | 5 | 6 => // BIGCOPY/BIGADD/MOVE/BIGMOVE require DLT\x04
        throw new IllegalArgumentException(s"command type $t requires DLT\\x04 format")
      case _ =>
        throw new IllegalArgumentException(s"unknown command type: $t")
    }
  }
  if !sawEnd then throw new IllegalArgumentException("missing END command")
  if pos != data.length then throw new IllegalArgumentException("trailing data after END")
  DecodeResult(commands.toList, inplace, versionSize, srcCrc, dstCrc)
}

private def decodeLarge(data: Array[Byte]): DecodeResult = {
  if data.length < deltaHeaderSizeLarge then throw new IllegalArgumentException("not a delta file")

  val inplace     = (data(deltaMagic.length).toInt & deltaFlagInplace.toInt) != 0
  val vsLong      = getU64Long(data, deltaMagic.length + 1)
  val versionSize = checkFitsInt(vsLong, "version_size")
  val crcOff      = deltaMagic.length + 1 + deltaU64Size
  val srcCrc      = data.slice(crcOff, crcOff + deltaCrcSize)
  val dstCrc      = data.slice(crcOff + deltaCrcSize, crcOff + 2 * deltaCrcSize)
  var pos         = deltaHeaderSizeLarge
  val commands    = scala.collection.mutable.ListBuffer[PlacedCommand]()
  var sawEnd      = false

  while pos < data.length && !sawEnd do {
    val t = data(pos).toInt & 0xFF
    pos += 1
    // @switch: consecutive 0-6 → JVM tableswitch
    (t: @switch) match {
      case 0 => // END
        sawEnd = true
      case 1 => // COPY (u32 fields)
        if pos + deltaCopyPayload > data.length then throw new IllegalArgumentException("unexpected EOF")
        val src = getU32BE(data, pos); pos += deltaU32Size
        val dst = getU32BE(data, pos); pos += deltaU32Size
        val len = getU32BE(data, pos); pos += deltaU32Size
        validatePlacedRange(dst, len, versionSize, "COPY")
        commands += PlacedCommand.Copy(src, dst, len)
      case 2 => // ADD (u32 fields)
        if pos + deltaAddHeader > data.length then throw new IllegalArgumentException("unexpected EOF")
        val dst = getU32BE(data, pos); pos += deltaU32Size
        val len = getU32BE(data, pos); pos += deltaU32Size
        if pos + len > data.length then throw new IllegalArgumentException("unexpected EOF")
        validatePlacedRange(dst, len, versionSize, "ADD")
        val payload = data.slice(pos, pos + len); pos += len
        commands += PlacedCommand.Add(dst, payload)
      case 3 => // BIGCOPY (u64 fields)
        if pos + deltaBigcopyPayload > data.length then throw new IllegalArgumentException("unexpected EOF")
        val src = checkFitsInt(getU64Long(data, pos), "BIGCOPY src"); pos += deltaU64Size
        val dst = checkFitsInt(getU64Long(data, pos), "BIGCOPY dst"); pos += deltaU64Size
        val len = checkFitsInt(getU64Long(data, pos), "BIGCOPY length"); pos += deltaU64Size
        validatePlacedRange(dst, len, versionSize, "BIGCOPY")
        commands += PlacedCommand.Copy(src, dst, len)
      case 4 => // BIGADD (u64 header)
        if pos + deltaBigaddHeader > data.length then throw new IllegalArgumentException("unexpected EOF")
        val dst = checkFitsInt(getU64Long(data, pos), "BIGADD dst"); pos += deltaU64Size
        val len = checkFitsInt(getU64Long(data, pos), "BIGADD length"); pos += deltaU64Size
        if pos + len > data.length then throw new IllegalArgumentException("unexpected EOF")
        validatePlacedRange(dst, len, versionSize, "BIGADD")
        val payload = data.slice(pos, pos + len); pos += len
        commands += PlacedCommand.Add(dst, payload)
      case 5 => // MOVE (u32 fields; src+length <= dst)
        if pos + deltaCopyPayload > data.length then throw new IllegalArgumentException("unexpected EOF")
        val src = getU32BE(data, pos); pos += deltaU32Size
        val dst = getU32BE(data, pos); pos += deltaU32Size
        val len = getU32BE(data, pos); pos += deltaU32Size
        if src < 0 then throw new IllegalArgumentException("MOVE src field exceeds Int.MAX_VALUE")
        validatePlacedRange(dst, len, versionSize, "MOVE")
        if src.toLong + len.toLong > dst.toLong then
          throw new IllegalArgumentException("MOVE src+length > dst: encoder ordering constraint violated")
        commands += PlacedCommand.Move(src, dst, len)
      case 6 => // BIGMOVE (u64 fields; src+length <= dst)
        if pos + deltaBigcopyPayload > data.length then throw new IllegalArgumentException("unexpected EOF")
        val src = checkFitsInt(getU64Long(data, pos), "BIGMOVE src"); pos += deltaU64Size
        val dst = checkFitsInt(getU64Long(data, pos), "BIGMOVE dst"); pos += deltaU64Size
        val len = checkFitsInt(getU64Long(data, pos), "BIGMOVE length"); pos += deltaU64Size
        validatePlacedRange(dst, len, versionSize, "BIGMOVE")
        if src.toLong + len.toLong > dst.toLong then
          throw new IllegalArgumentException("BIGMOVE src+length > dst: encoder ordering constraint violated")
        commands += PlacedCommand.Move(src, dst, len)
      case _ =>
        throw new IllegalArgumentException(s"unknown command type: $t")
    }
  }
  if !sawEnd then throw new IllegalArgumentException("missing END command")
  if pos != data.length then throw new IllegalArgumentException("trailing data after END")
  DecodeResult(commands.toList, inplace, versionSize, srcCrc, dstCrc)
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private def matchesMagic(data: Array[Byte], magic: Array[Byte]): Boolean =
  data.length >= magic.length &&
  java.util.Arrays.mismatch(data, 0, magic.length, magic, 0, magic.length) == -1

private def putU32BE(buf: Array[Byte], off: Int, value: Int): Unit = {
  buf(off)     = (value >>> 24).toByte
  buf(off + 1) = (value >>> 16).toByte
  buf(off + 2) = (value >>> 8).toByte
  buf(off + 3) = value.toByte
}

private def putU64BE(buf: Array[Byte], off: Int, value: Long): Unit = {
  buf(off)     = (value >>> 56).toByte
  buf(off + 1) = (value >>> 48).toByte
  buf(off + 2) = (value >>> 40).toByte
  buf(off + 3) = (value >>> 32).toByte
  buf(off + 4) = (value >>> 24).toByte
  buf(off + 5) = (value >>> 16).toByte
  buf(off + 6) = (value >>> 8).toByte
  buf(off + 7) = value.toByte
}

private def getU32BE(buf: Array[Byte], off: Int): Int =
  ((buf(off).toInt     & 0xFF) << 24) |
  ((buf(off + 1).toInt & 0xFF) << 16) |
  ((buf(off + 2).toInt & 0xFF) << 8)  |
   (buf(off + 3).toInt & 0xFF)

private def getU64Long(buf: Array[Byte], off: Int): Long =
  ((buf(off).toLong     & 0xFF) << 56) |
  ((buf(off + 1).toLong & 0xFF) << 48) |
  ((buf(off + 2).toLong & 0xFF) << 40) |
  ((buf(off + 3).toLong & 0xFF) << 32) |
  ((buf(off + 4).toLong & 0xFF) << 24) |
  ((buf(off + 5).toLong & 0xFF) << 16) |
  ((buf(off + 6).toLong & 0xFF) << 8)  |
   (buf(off + 7).toLong & 0xFF)

/** Guard against u64 values that exceed Int.MAX_VALUE (JVM array limit). */
private def checkFitsInt(value: Long, field: String): Int =
  if value < 0 || value > Int.MaxValue then
    throw new IllegalArgumentException(s"$field value $value exceeds Int.MaxValue")
  else value.toInt

private def validatePlacedRange(dst: Int, len: Int, versionSize: Int, kind: String): Unit =
  if outOfBounds(dst, len, versionSize) then
    throw new IllegalArgumentException(s"$kind extends past version size")
