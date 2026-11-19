package skiwi.collab

import scodec.bits.ByteVector

class AwarenessSpec extends munit.FunSuite {
  private def frame(
    states: (Long, Long, String)*
  ): ByteVector = {
    val body = Protocol.write { w =>
      w.varUint(states.size.toLong)
      states.foreach(
        (
          id,
          clock,
          st
        ) => { w.varUint(id); w.varUint(clock); w.string(st) }
      )
    }
    Protocol.write { w => w.varUint(Protocol.MsgAwareness); Protocol.writeVarBytes(w, body) }
  }

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

  test("在席の名前は、クライアントが名乗ったものではなく接続を認証したユーザーにする（色などはそのまま）") {
    def states(
      f: ByteVector
    ): List[String] = {
      val r = skiwi.ydoc.YReader(f.toArray); r.varUint()
      val u = skiwi.ydoc.YReader(r.varBytes()); val n = u.varUint()
      (0L until n).map(_ => { u.varUint(); u.varUint(); u.string() }).toList
    }
    val f = frame(
      (1L, 2L, """{"user":{"name":"管理者 山田","color":"#123456"},"mode":"edit"}"""),
      (3L, 4L, "null"),
      (5L, 6L, "[1]")
    )
    val out = Protocol.awarenessAsUser(f, "u-1", "佐藤").get
    assertEquals(Protocol.awarenessClients(out), List((1L, 2L, false), (3L, 4L, true), (5L, 6L, true)))
    val st = io.circe.parser.parse(states(out).head).toOption.get.hcursor
    assertEquals(st.downField("user").get[String]("name"), Right("佐藤"))
    assertEquals(st.downField("user").get[String]("id"), Right("u-1"))
    assertEquals(st.downField("user").get[String]("color"), Right("#123456"))
    assertEquals(st.get[String]("mode"), Right("edit"))
    assertEquals(Protocol.awarenessAsUser(ByteVector(1, 5, 0), "u-1", "佐藤"), None) // 壊れたフレーム
  }
}
