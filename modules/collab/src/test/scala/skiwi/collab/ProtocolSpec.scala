package skiwi.collab

import scodec.bits.ByteVector
import skiwi.ydoc.YReader

class ProtocolSpec extends munit.FunSuite {
  import Protocol.Inbound.*

  private def varUint(
    n: Long
  ) = Protocol.write(_.varUint(n))
  private def varString(
    s: String
  ) = Protocol.write(_.string(s))
  private def varBytes(
    b: ByteVector
  ) = Protocol.write(Protocol.writeVarBytes(_, b))

  /** 読めなければ None。読めたら (値, 読んだバイト数) */
  private def readVarUint(
    b: ByteVector
  ) =
    scala.util.Try { val r = YReader(b.toArray); (r.varUint(), r.position) }.toOption

  test("varuint roundtrip across sizes") {
    for n <- List(0L, 1L, 127L, 128L, 300L, 16383L, 16384L, 1L << 31, (1L << 40) + 5) do {
      val enc = varUint(n)
      assertEquals(readVarUint(enc), Some((n, enc.size.toInt)))
    }
  }

  test("varuint known encodings (lib0)") {
    assertEquals(varUint(300), ByteVector(0xac, 0x02))
    assertEquals(varUint(127), ByteVector(0x7f))
  }

  test("truncated or oversized varuint is rejected") {
    assertEquals(readVarUint(ByteVector(0x80)), None)
    assertEquals(readVarUint(ByteVector.fill(10)(0xff)), None)
  }

  test("varString roundtrip with Japanese") {
    val s = "日本語のテキスト😀"
    assertEquals(YReader(varString(s).toArray).string(), s)
  }

  test("parse sync update / step2 frames") {
    val payload = ByteVector(1, 2, 3, 4)
    assertEquals(Protocol.parse(Protocol.syncUpdate(payload)), Update(payload))
    assertEquals(Protocol.parse(Protocol.syncStep2(payload)), Update(payload))
    assertEquals(Protocol.parse(Protocol.syncDoneMarker), Update(ByteVector(0, 0)))
  }

  test("step1 is ignored; unknown types ignored; truncated frames malformed") {
    assertEquals(Protocol.parse(ByteVector(0, 0, 1, 0)), Ignored)
    assertEquals(Protocol.parse(ByteVector(77, 1, 2)), Ignored)
    assertEquals(Protocol.parse(ByteVector(0, 2, 10, 1)), Malformed) // 長さ10だが1バイトしかない
    assertEquals(Protocol.parse(ByteVector(0, 2, 1, 9, 9)), Malformed) // 余剰バイト
    assertEquals(Protocol.parse(ByteVector.empty), Malformed)
  }

  test("parse awareness") {
    assertEquals(Protocol.parse(ByteVector(1, 3, 1, 2, 3)), Awareness)
  }

  test("parse compaction response") {
    val frame = varUint(Protocol.MsgCompactResp) ++ varString("tok") ++
      varBytes(ByteVector(9, 9)) ++ varString("""{"type":"doc"}""") ++ varString("本文")
    assertEquals(Protocol.parse(frame), CompactResponse("tok", ByteVector(9, 9), """{"type":"doc"}""", "本文"))
    assertEquals(Protocol.parse(frame.dropRight(1)), Malformed)
  }
}
