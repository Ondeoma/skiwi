package skiwi.collab

import scodec.bits.ByteVector

class AwarenessSpec extends munit.FunSuite:
  private def frame(states: (Long, Long, String)*): ByteVector =
    val body = Protocol.write { w =>
      w.varUint(states.size.toLong)
      states.foreach((id, clock, st) => { w.varUint(id); w.varUint(clock); w.string(st) })
    }
    Protocol.write { w => w.varUint(Protocol.MsgAwareness); Protocol.writeVarBytes(w, body) }

  test("reads client ids and clocks from an awareness frame") {
    val f = frame((123456789L, 5L, """{"user":{"name":"山田"}}"""), (42L, 9L, "null"))
    assertEquals(Protocol.awarenessClients(f), List((123456789L, 5L, false), (42L, 9L, true)))
    assertEquals(Protocol.awarenessClients(ByteVector(1, 5, 0)), Nil) // 壊れたフレーム
  }

  test("removal frame bumps the clock and sets the state to null") {
    val f = Protocol.awarenessRemoval(Map(7L -> 3L))
    assertEquals(Protocol.parse(f), Protocol.Inbound.Awareness)
    assertEquals(Protocol.awarenessClients(f), List((7L, 4L, true)))
  }
