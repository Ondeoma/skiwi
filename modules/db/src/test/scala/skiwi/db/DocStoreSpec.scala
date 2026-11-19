package skiwi.db

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import skiwi.core.*

import java.util.UUID
import scala.concurrent.duration.*

/** 圧縮の安全性（複数ノード対応）: 届けたと分かっているものだけを消す。 */
class DocStoreSpec extends DbSuite {

  private val res = cfg match {
    case None =>
      Resource.eval(IO.raiseError[doobie.Transactor[IO]](new IllegalStateException("TEST_DATABASE_URL not set")))
    case Some(c) => Resource.eval(Database.migrate(c)) *> Database.transactor(c)
  }
  private val fixture = ResourceSuiteLocalFixture("xa", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil

  private def setup: IO[(DocStore, UUID, UUID)] = {
    val xa = fixture()
    val store = new Store(xa)
    for {
      u <- store.upsertUser(s"doc-${UUID.randomUUID()}", None, "圧縮")
      ws <- store.createWorkspace("圧縮テスト", u.id)
      p <- store.createPage(ws.id, None, Placement.Last, "p", None, u.id).rethrow
    } yield (new DocStore(xa), p.id, u.id)
  }

  private def b(
    i: Int
  ) = Array[Byte](i.toByte)

  test("compaction removes only what the compacting client is known to have, keeping concurrent work") {
    run {
      for {
        (docs, page, user) <- setup
        s1 <- docs.appendUpdate(page, b(1))
        s2 <- docs.appendUpdate(page, b(2)) // 例: 他ノードで保存され、まだこのクライアントに届いていない
        // ノードAのクライアントは s1 だけを受け取っていた
        r1 <- docs.saveCompaction(page, b(10), Json.obj(), "A", Set(s1), Set.empty, user)
        p1 <- docs.load(page)
        _ = assertEquals(r1.removedUpdates, 1)
        _ = assertEquals(p1.updates.map(_._1), List(s2)) // 届いていない s2 は残る
        _ = assertEquals(p1.snapshots.map(_._1), List(r1.snapshotId))
        // ノードBのクライアントは s2 を持つが、Aのスナップショットは知らない → Aのスナップショットは残る（part に格下げ）
        r2 <- docs.saveCompaction(page, b(20), Json.obj(), "B", Set(s2), Set.empty, user)
        p2 <- docs.load(page)
        _ = assertEquals(p2.updates, Nil)
        _ = assertEquals(p2.snapshots.map(_._1).toSet, Set(r1.snapshotId, r2.snapshotId))
        text <- docs.plainText(page)
        _ = assertEquals(text, Some("B")) // 本文（検索用）は最新の圧縮
        // 両方のスナップショットを受け取ったクライアントの圧縮で1つにまとまる
        r3 <- docs.saveCompaction(page, b(30), Json.obj(), "C", Set.empty, Set(r1.snapshotId, r2.snapshotId), user)
        p3 <- docs.load(page)
      } yield {
        assertEquals(r3.removedSnapshots, 2)
        assertEquals(p3.snapshots.map(_._1), List(r3.snapshotId))
      }
    }
  }

  test("getUpdate returns None once compacted away") {
    run {
      for {
        (docs, page, user) <- setup
        s1 <- docs.appendUpdate(page, b(1))
        before <- docs.getUpdate(page, s1)
        _ <- docs.saveCompaction(page, b(9), Json.obj(), "x", Set(s1), Set.empty, user)
        after <- docs.getUpdate(page, s1)
      } yield {
        assert(before.exists(_.sameElements(b(1))))
        assertEquals(after, None)
      }
    }
  }

  test("cluster registry: heartbeat, alive window, leave") {
    run {
      val cs = new ClusterStore(fixture())
      val id = s"test-node-${UUID.randomUUID().toString.take(6)}"
      for {
        _ <- cs.heartbeat(id, "http://10.0.0.9:8080")
        a <- cs.alive(15.seconds)
        _ <- cs.remove(id)
        b <- cs.alive(15.seconds)
      } yield {
        assert(a.exists(n => n.id == id && n.url == "http://10.0.0.9:8080"))
        assert(!b.exists(_.id == id))
      }
    }
  }

  test("PgBus delivers notifications across connections") {
    run {
      val c = cfg.get
      val bus = new PgBus(c, fixture())
      for {
        got <- Deferred[IO, String]
        conn <- Deferred[IO, Unit]
        fib <- bus.subscribe(List("skiwi_test"), conn.complete(()).void)(
          (
            ch,
            p
          ) => got.complete(s"$ch:$p").void
        ).start
        _ <- conn.get.timeout(10.seconds)
        _ <- bus.publish("skiwi_test", "こんにちは|1")
        v <- got.get.timeout(10.seconds)
        _ <- fib.cancel
      } yield assertEquals(v, "skiwi_test:こんにちは|1")
    }
  }
}
