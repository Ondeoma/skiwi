package skiwi.notify

import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import skiwi.core.*
import skiwi.db.*
import sbirds.mail.{Mail, Mailer, MemoryMailer}

import java.util.UUID

/** メール送信ワーカー: 本文の組み立て、権限のない宛先・メールなしのスキップ、送信済み記録、再試行。 */
class NotifierSpec extends DbSuite {

  private val res = cfg match {
    case None =>
      Resource.eval(IO.raiseError[doobie.Transactor[IO]](new IllegalStateException("TEST_DATABASE_URL not set")))
    case Some(c) => Resource.eval(Database.migrate(c)) *> Database.transactor(c)
  }

  private val fixture = ResourceSuiteLocalFixture("xa", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil

  /** 他テストのアウトボックスを巻き込まないよう、このテストのページに限定して処理させる。 */
  private def drainFor(xa: doobie.Transactor[IO], pages: Set[UUID]): IO[Unit] = {
    val arr = pages.toArray
    sql"update mail_outbox set not_before = now() + interval '1 day' where sent_at is null and not skipped and (page_id is null or not (page_id = any($arr)))"
      .update.run.transact(xa).void
  }

  test("renders and sends Japanese mails, skipping recipients without access or email") {
    run {
      val xa = fixture()
      val store = new Store(xa)
      val ops = new OpsStore(xa)
      val mailer = new MemoryMailer
      val n = new Notifier(store, ops, Some(mailer), "https://skiwi.example.com/", Set("skiwi-admins"))
      for {
        owner <- store.upsertUser(s"o-${UUID.randomUUID()}", Some("owner@example.com"), "持ち主")
        editor <- store.upsertUser(s"e-${UUID.randomUUID()}", Some("editor@example.com"), "編集者")
        outsider <- store.upsertUser(s"x-${UUID.randomUUID()}", Some("x@example.com"), "部外者")
        noMail <- store.upsertUser(s"n-${UUID.randomUUID()}", None, "メールなし")
        ws <- store.createWorkspace("通知テスト", owner.id)
        page <- store.createPage(ws.id, None, Placement.Last, "週報", None, owner.id).rethrow
        _ <- store.setWorkspaceAcl(
          ws.id,
          None,
          List(
            AclEntry(Principal(PrincipalType.User, owner.id), Role.Edit),
            AclEntry(Principal(PrincipalType.User, noMail.id), Role.View)
          )
        )
        _ <- List(owner.id, outsider.id, noMail.id).traverse_(u => ops.setWatch(page.id, u, on = true))
        _ <- ops.enqueuePageEdited(page.id, Set(editor.id), 0, 0)
        _ <- ops.enqueueShared(page.id, Set(owner.id), "編集者", "edit")
        _ <- drainFor(xa, Set(page.id))
        processed <- n.runOnce
        sent <- mailer.sent.get
        rows <-
          sql"select user_id, kind, sent_at is not null, skipped, last_error from mail_outbox where page_id = ${page.id}"
            .query[(UUID, String, Boolean, Boolean, Option[String])].to[List].transact(xa)
      } yield {
        assertEquals(processed, 4)
        assertEquals(sent.map(_.to).sorted, List("owner@example.com", "owner@example.com"))
        val edited = sent.find(_.subject.contains("更新されました")).get
        assertEquals(edited.subject, "[skiwi] 「週報」が更新されました")
        assert(edited.body.contains("編集者さん"), edited.body)
        assert(edited.body.contains(s"https://skiwi.example.com/w/${ws.id}/p/${page.id}"), edited.body)
        val shared = sent.find(_.subject.contains("共有")).get
        assert(shared.body.contains("（編集）"), shared.body)
        assertEquals(rows.find(_._1 == outsider.id).map(r => (r._4, r._5)), Some((true, Some("閲覧の権限がありません"))))
        assertEquals(rows.find(_._1 == noMail.id).map(r => (r._4, r._5)), Some((true, Some("メールアドレスがありません"))))
      }
    }
  }

  test("failed sends are retried with backoff") {
    run {
      val xa = fixture()
      val store = new Store(xa)
      val ops = new OpsStore(xa)
      val failing = new Mailer {
        def send(m: Mail) = IO.raiseError(new RuntimeException("SMTP down"))
      }
      val n = new Notifier(store, ops, Some(failing), "http://localhost", Set.empty)
      for {
        owner <- store.upsertUser(s"o-${UUID.randomUUID()}", Some("o@example.com"), "持ち主")
        ws <- store.createWorkspace("再試行", owner.id)
        page <- store.createPage(ws.id, None, Placement.Last, "p", None, owner.id).rethrow
        _ <- ops.enqueueShared(page.id, Set(owner.id), "誰か", "view")
        _ <- drainFor(xa, Set(page.id))
        _ <- n.runOnce
        row <-
          sql"select attempts, last_error, sent_at is null, not_before > now() from mail_outbox where page_id = ${page.id}"
            .query[(Int, String, Boolean, Boolean)].unique.transact(xa)
      } yield assertEquals(row, (1, "SMTP down", true, true))
    }
  }
}
