package skiwi.core

import java.util.UUID
import scala.util.Random

class FractionalIndexSpec extends munit.FunSuite:
  test("between respects bounds") {
    val k1 = FractionalIndex.between(None, None)
    val k2 = FractionalIndex.between(Some(k1), None)
    val k0 = FractionalIndex.between(None, Some(k1))
    val km = FractionalIndex.between(Some(k1), Some(k2))
    assert(k0 < k1 && k1 < km && km < k2, List(k0, k1, km, k2).toString)
  }

  test("random insertions keep strict order and no trailing zero") {
    val rnd  = new Random(42)
    var keys = Vector.empty[String]
    for _ <- 1 to 2000 do
      val i  = rnd.nextInt(keys.size + 1)
      val lo = if i == 0 then None else Some(keys(i - 1))
      val hi = keys.lift(i)
      val k  = FractionalIndex.between(lo, hi)
      assert(!k.endsWith("0"), k)
      keys = keys.patch(i, Vector(k), 0)
    assertEquals(keys, keys.sorted)
    assertEquals(keys.distinct.size, keys.size)
  }

  test("place: first / last / after") {
    val a, b = UUID.randomUUID()
    val ka   = FractionalIndex.between(None, None)
    val kb   = FractionalIndex.between(Some(ka), None)
    val sib  = List(a -> ka, b -> kb)
    val first = FractionalIndex.place(sib, Placement.First).toOption.get
    val last  = FractionalIndex.place(sib, Placement.Last).toOption.get
    val mid   = FractionalIndex.place(sib, Placement.After(a)).toOption.get
    assert(first < ka && last > kb && mid > ka && mid < kb)
    assert(FractionalIndex.place(sib, Placement.After(UUID.randomUUID())).isLeft)
    assert(FractionalIndex.place(Nil, Placement.Last).isRight)
  }
