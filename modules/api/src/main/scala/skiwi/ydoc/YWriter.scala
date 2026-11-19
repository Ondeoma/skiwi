package skiwi.ydoc

import io.circe.Json

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8

final class YWriter {
  private val out = ByteArrayOutputStream()
  def u8(b: Int): Unit = out.write(b & 0xff)
  def varUint(n0: Long): Unit = {
    var n = n0
    while n > 0x7f do { u8(((n & 0x7f) | 0x80).toInt); n = n >>> 7 }
    u8(n.toInt)
  }
  def varInt(n0: Long): Unit = {
    val neg = n0 < 0
    var n = math.abs(n0)
    u8((if n > 0x3f then 0x80 else 0) | (if neg then 0x40 else 0) | (n & 0x3f).toInt)
    n = n / 64
    while n > 0 do {
      u8((if n > 0x7f then 0x80 else 0) | (n & 0x7f).toInt)
      n = n / 128
    }
  }
  def bytes(b: Array[Byte]): Unit = out.write(b)
  def string(s: String): Unit = {
    val b = s.getBytes(UTF_8)
    varUint(b.length.toLong); out.write(b)
  }
  def id(i: YId): Unit = { varUint(i.client); varUint(i.clock) }

  /** lib0 の writeAny（JSON で表せる値）。 */
  def any(j: Json): Unit = j.fold(
    u8(126),
    b => u8(if b then 120 else 121),
    n =>
      n.toLong.filter(l => math.abs(l) <= 0x7fffffffL) match {
        case Some(l) => u8(125); varInt(l)
        case None => u8(123); out.write(ByteBuffer.allocate(8).putDouble(n.toDouble).array())
      },
    s => { u8(119); string(s) },
    arr => { u8(117); varUint(arr.size.toLong); arr.foreach(any) },
    obj => {
      u8(118); varUint(obj.size.toLong);
      obj.toIterable.foreach((k, v) => { string(k); any(v) })
    }
  )
  def result: Array[Byte] = out.toByteArray
}
