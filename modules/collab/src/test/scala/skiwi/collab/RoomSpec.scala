package skiwi.collab

import cats.effect.*
import cats.effect.std.Semaphore
import cats.syntax.all.*
import munit.CatsEffectSuite
import scodec.bits.ByteVector

import java.util.UUID
import scala.concurrent.duration.*

/** ルームの配信: 読まない接続がほかの人への配信を止めない。受信の頻度の上限。 */
class RoomSpec extends CatsEffectSuite {

  private def room: IO[Room] =
    (
      Ref.of[IO, Map[Long, Subscriber]](Map.empty),
      Semaphore[IO](1),
      Ref.of[IO, Set[Long]](Set.empty),
      Ref.of[IO, Int](0),
      Ref.of[IO, Option[Pending]](None),
      Ref.of[IO, Option[Fiber[IO, Throwable, Unit]]](None),
      Ref.of[IO, Set[UUID]](Set.empty),
      Ref.of[IO, Set[UUID]](Set.empty)
    ).mapN(new Room(UUID.randomUUID(), _, _, _, _, _, _, _, _))

  private def frame(n: Int) = ByteVector.fill(n.toLong)(1)

  test("読まない接続は、送信待ちがあふれたらその接続だけ切られ、ほかの接続への配信は止まらない") {
    for {
      r <- room
      kicked <- Ref.of[IO, List[Long]](Nil)
      slow <- Subscriber.create(1L, maxItems = 4, maxBytes = 1 << 20, kicked.update(1L :: _))
      fast <- Subscriber.create(2L, maxItems = 1000, maxBytes = 1 << 20, kicked.update(2L :: _))
      _ <- r.subs.set(Map(1L -> slow, 2L -> fast))
      // slow は1通も読まない。配る側は待たない（待つならここで時間切れになる）
      _ <- (1 to 100).toList.traverse_(i => r.publish(Out(9L, None, frame(i)))).timeout(5.seconds)
      got <- fast.stream.take(100).compile.toList.timeout(5.seconds)
      ks <- kicked.get
      left <- r.subs.get.map(_.keySet)
    } yield {
      assertEquals(got.map(_.frame.size), (1L to 100L).toList) // 順序どおり全部届く
      assertEquals(ks, List(1L)) // 切るのは1回だけ
      assertEquals(left, Set(2L))
    }
  }

  test("送信待ちはバイト数でも制限する（1通だけなら大きくても受け取る）。宛先・送信者で絞る") {
    for {
      r <- room
      kicked <- Ref.of[IO, Boolean](false)
      a <- Subscriber.create(1L, maxItems = 100, maxBytes = 1000, kicked.set(true))
      _ <- r.subs.set(Map(1L -> a))
      _ <- r.publish(Out(1L, None, frame(10))) // 自分が送ったものは自分には配らない
      _ <- r.publish(Out(0L, Some(2L), frame(10))) // ほかの接続あて
      _ <- r.publish(Out(0L, None, frame(5000))) // 1通目は上限より大きくても受け取る
      k1 <- kicked.get
      first <- a.stream.take(1).compile.lastOrError
      _ <- r.publish(Out(0L, Some(1L), frame(600)))
      _ <- r.publish(Out(0L, None, frame(600))) // 合わせて上限を超える
      k2 <- kicked.get
    } yield {
      assertEquals(first.frame.size, 5000L)
      assert(!k1)
      assert(k2)
    }
  }

  test("受信の頻度の上限: まとめて送れる分を使い切ったら、足りない分だけ待たせる") {
    for {
      t <- Throttle.create(perSecond = 10, burst = 5)
      free <- (1 to 5).toList.traverse(_ => t.take(1))
      wait <- t.take(10)
      off <- Throttle.create(perSecond = 0, burst = 0)
      none <- off.take(1e9)
    } yield {
      assert(free.forall(_ == Duration.Zero), free.toString)
      assert(wait > 800.millis && wait <= 1100.millis, wait.toString)
      assertEquals(none, Duration.Zero: FiniteDuration)
    }
  }
}
