package skiwi.db

import sbirds.db.*
import cats.effect.*

import java.util.UUID

/** 画面内の通知を作ったら、作ったものだけを onNotified に知らせる（プッシュ通知に使う）. TEST_DATABASE_URL があるときだけ */
class NotifiedSpec extends DbSuite {

  test("通知を作るたびに、受け取る人と通知の行を知らせる（未読にまとめた更新の通知は知らせない）") {
    run {
      val c = cfg.get
      Database.migrate(c) *> Database.transactor(c).use { xa =>
        for {
          got <- Ref.of[IO, List[Notified]](Nil)
          done <- Ref.of[IO, Int](0)
          store = new Store(xa)
          ops = new OpsStore(xa, ns => got.update(_ ++ ns) *> done.update(_ + 1))
          a <- store.upsertUser(s"a-${UUID.randomUUID()}", None, "通知する人")
          b <- store.upsertUser(s"b-${UUID.randomUUID()}", None, "受け取る人")
          ws <- store.createWorkspace("通知の確認", a.id)
          page <- store.createPage(ws.id, None, skiwi.core.Placement.Last, "通知のページ", None, a.id).map(_.toOption.get)
          _ <- ops.setWatch(page.id, b.id, on = true)
          n1 <- ops.enqueuePageNotice("mention", page.id, Set(b.id), a.name, "こんにちは", None)
          n2 <- ops.enqueuePageEdited(page.id, Set(a.id), 300, 1800)
          // 同じページの未読の更新通知にまとめたときは、もう知らせてあるので知らせない
          n3 <- ops.enqueuePageEdited(page.id, Set(a.id), 300, 1800)
          n4 <- ops.enqueueSharedWorkspace(Set(b.id), a.name, "edit", ws.name, Some(ws.id))
          _ <- done.get.iterateUntil(_ >= 3).timeout(scala.concurrent.duration.DurationInt(5).seconds)
          ns <- got.get
        } yield {
          assertEquals((n1, n2, n3, n4), (1, 1, 0, 1))
          assertEquals(
            ns.map(n => (n.userId, n.row.kind)).sortBy(_._2),
            List((b.id, "mention"), (b.id, "page_edited"), (b.id, "shared_workspace"))
          )
          val mention = ns.find(_.row.kind == "mention").get.row
          assertEquals(
            (mention.pageTitle, mention.pageWorkspaceId, mention.actors),
            (Some("通知のページ"), Some(ws.id), List("通知する人"))
          )
          assertEquals(ns.find(_.row.kind == "shared_workspace").get.row.workspaceName, Some("通知の確認"))
        }
      }
    }
  }
}
