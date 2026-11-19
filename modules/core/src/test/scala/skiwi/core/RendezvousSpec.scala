package skiwi.core

import munit.FunSuite

class RendezvousSpec extends FunSuite {
  private val keys = (1 to 3000).map(i => java.util.UUID.nameUUIDFromBytes(s"page-$i".getBytes).toString)
  private val three = List("node1", "node2", "node3")

  test("deterministic regardless of node order") {
    keys.take(200).foreach { k =>
      assertEquals(Rendezvous.pick(k, three)(identity), Rendezvous.pick(k, three.reverse)(identity))
    }
  }

  test("roughly balanced across 3 nodes") {
    val counts = keys.groupBy(k => Rendezvous.pick(k, three)(identity).get).view.mapValues(_.size).toMap
    assertEquals(counts.keySet, three.toSet)
    counts.values.foreach(c => assert(c > 800 && c < 1200, counts))
  }

  test("removing a node only moves the keys it owned") {
    val before = keys.map(k => k -> Rendezvous.pick(k, three)(identity).get).toMap
    val after = keys.map(k => k -> Rendezvous.pick(k, List("node1", "node3"))(identity).get).toMap
    keys.foreach { k => if before(k) != "node2" then assertEquals(after(k), before(k)) }
  }

  test("empty node list has no owner") {
    assertEquals(Rendezvous.pick("x", List.empty[String])(identity), None)
  }
}
