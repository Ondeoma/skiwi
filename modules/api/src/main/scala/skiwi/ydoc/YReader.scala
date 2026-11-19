package skiwi.ydoc

import io.circe.Json

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8

/** lib0 の符号化の読み書き（Yjs の update v1 に必要な分）。 */
final class YReader(bytes: Array[Byte]) {
  private var pos = 0

  /** 読んだ位置 */
  def position: Int = pos

  /** 残りのバイト数 */
  def remaining: Int = bytes.length - pos
  def u8(): Int = {
    if pos >= bytes.length then throw IllegalArgumentException("unexpected end of update")
    val b = bytes(pos) & 0xff
    pos += 1
    b
  }
  def varUint(): Long = {
    var num = 0L; var shift = 0
    var r = u8()
    while (r & 0x80) != 0 do {
      num |= (r & 0x7fL) << shift; shift += 7
      // 9バイト（63ビット）まで。それより長いものは Long に収まらないので壊れたものとして扱う
      if shift > 56 then throw IllegalArgumentException("varUint too long")
      r = u8()
    }
    num | (r.toLong << shift)
  }
  def varInt(): Long = {
    var r = u8()
    var num = (r & 0x3f).toLong
    var mult = 64L
    val sign = if (r & 0x40) != 0 then -1 else 1
    while (r & 0x80) != 0 do {
      r = u8()
      num += (r & 0x7f) * mult
      mult *= 128
    }
    sign * num
  }
  def bytes(n: Int): Array[Byte] = {
    if n < 0 || pos + n > bytes.length then throw IllegalArgumentException("unexpected end of update")
    val out = java.util.Arrays.copyOfRange(bytes, pos, pos + n)
    pos += n
    out
  }
  def varBytes(): Array[Byte] = {
    val n = varUint()
    if n > Int.MaxValue then throw IllegalArgumentException("unexpected end of update")
    bytes(n.toInt)
  }
  def string(): String = String(varBytes(), UTF_8)
  def id(): YId = YId(varUint(), varUint())

  /**
   * lib0 の readAny。Uint8Array・undefined・bigint は扱わない値として null にする。
   * 入れ子は MaxAnyDepth 段まで（細工した update でスタックを使い切らせない）。
   */
  def any(depth: Int = 0): Json = {
    if depth > YReader.MaxAnyDepth then throw IllegalArgumentException("any value is nested too deeply")
    (127 - u8()) match {
      case 0 => Json.Null // undefined
      case 1 => Json.Null
      case 2 => Json.fromLong(varInt())
      case 3 => Json.fromFloatOrNull(ByteBuffer.wrap(bytes(4)).getFloat)
      case 4 => Json.fromDoubleOrNull(ByteBuffer.wrap(bytes(8)).getDouble)
      case 5 => Json.fromLong(ByteBuffer.wrap(bytes(8)).getLong)
      case 6 => Json.False
      case 7 => Json.True
      case 8 => Json.fromString(string())
      case 9 => Json.fromFields((0L until varUint()).map(_ => string() -> any(depth + 1)))
      case 10 => Json.fromValues((0L until varUint()).map(_ => any(depth + 1)))
      case 11 => varBytes(); Json.Null
      case t => throw IllegalArgumentException(s"unknown any type: $t")
    }
  }
}

object YReader {
  val MaxAnyDepth = 100
}
