package skiwi.notify

import skiwi.shared.Titles

import cats.effect.*
import io.circe.parser.parse
import skiwi.core.*
import skiwi.db.{OpsStore, OutboxItem, OutboxResult, Periodic, Store}
import sbirds.mail.{Mail, Mailer}

import java.util.UUID
import scala.concurrent.duration.*

/**
 * メール送信ワーカー。mail_outbox から期限の来たものを取り出し、送信時点の権限・タイトルで本文を組み立てて送る。
 * 複数ノードで動かしても SKIP LOCKED により重複送信しない。mailer が None ならメールは送らず、積まれた分は破棄する。
 */
final class Notifier(
  store: Store,
  ops: OpsStore,
  mailer: Option[Mailer],
  publicUrl: String,
  adminGroups: Set[String],
  interval: FiniteDuration = 30.seconds) {
  private val base = publicUrl.stripSuffix("/")

  private def roleLabel(
    r: String
  ) = if r == "edit" then "編集" else "閲覧"

  /** 送信時点で閲覧できるページ（権限変更・削除後のメール漏洩を防ぐ）。見られなければ Left(理由) */
  private def visiblePage(
    userId: UUID,
    pageId: UUID
  ): IO[Either[String, (User, Page)]] =
    store.userWithGroups(userId).flatMap {
      case None => IO.pure(Left("無効なユーザーです"))
      case Some((u, gs)) =>
        val s = AuthSession(u, gs, AgentAccounts.isAdmin(u.sub, gs.map(_.externalId), adminGroups))
        store.pageAccess(pageId, s).map(_.map(
          (
            p,
            _
          ) => (u, p)
        ).toRight("閲覧の権限がありません"))
    }

  /** 1件分のメールを組み立てる。送らない場合は Left(理由)。 */
  def render(
    it: OutboxItem
  ): IO[Either[String, Mail]] =
    it.email.filter(_.contains("@")) match {
      case _ if !it.active => IO.pure(Left("無効なユーザーです"))
      case None => IO.pure(Left("メールアドレスがありません"))
      case Some(to) =>
        val detail = parse(it.detail).toOption.map(_.hcursor)
        def field(
          k: String
        ) = detail.flatMap(_.downField(k).as[String].toOption)
        val role = roleLabel(field("role").getOrElse("view"))
        val sharedBy = field("by").getOrElse("")
        val by = field("by").filter(_.nonEmpty).getOrElse("誰か")
        val excerpt = field("excerpt").filter(_.nonEmpty)

        /** 本文: 「宛名さん」・空行・各行 */
        def mail(
          name: String,
          subject: String,
          lines: String*
        ): Either[String, Mail] =
          Right(Mail(to, name, subject, s"${name}さん\n\n" + lines.mkString("\n") + "\n"))

        /** 引用（前後に空行） */
        def quote(
          t: Option[String]
        ) = t.fold("")(e => s"\n> $e\n")

        def pageMail(
          kind: String,
          u: User,
          p: Page
        ): IO[Either[String, Mail]] = {
          val title = Titles.display(p.title)
          val link = s"$base/w/${p.workspaceId}/p/${p.id}"
          kind match {
            case "shared" =>
              IO.pure(mail(
                u.name,
                s"[skiwi] ${sharedBy}さんがページ「$title」を共有しました",
                s"${sharedBy}さんが、ページ「$title」をあなたと共有しました（$role）。",
                "",
                link
              ))
            case "page_edited" =>
              val ids = detail.flatMap(_.downField("editors").as[List[String]].toOption).getOrElse(Nil).flatMap(uuidOpt)
              store.userNames(ids).map { names =>
                val who = ids.flatMap(names.get).distinct match {
                  case Nil => "誰か"
                  case ns => ns.map(_ + "さん").mkString("、")
                }
                mail(
                  u.name,
                  s"[skiwi] 「$title」が更新されました",
                  s"ウォッチ中のページ「$title」が、${who}によって更新されました。",
                  "",
                  link,
                  "",
                  "※ 通知の受け取り方（本文の更新・コメント・子ページを含めるか）は、ページ右上の「🔔」から変更できます。"
                )
              }
            case "mention" | "comment" =>
              val anchor = field("threadId").fold("")(t => s"?comment=$t")
              val (subject, lead) =
                if kind == "mention" then
                  (
                    s"[skiwi] ${by}さんが「$title」であなたをメンションしました",
                    if excerpt.isDefined then s"${by}さんが、ページ「$title」のコメントであなたをメンションしました。"
                    else s"${by}さんが、ページ「$title」の本文であなたをメンションしました。"
                  )
                else (s"[skiwi] 「$title」にコメントがありました", s"${by}さんが、ページ「$title」にコメントしました。")
              IO.pure(mail(u.name, subject, lead, quote(excerpt), s"$link$anchor"))
            case "task_due" =>
              val text = excerpt.getOrElse("（ToDo）")
              IO.pure(mail(
                u.name,
                s"[skiwi] 期限が近いToDo: $text",
                s"あなたが担当のToDoの期限（${field("due").getOrElse("")}）が近づいています。",
                "",
                s"> $text",
                "",
                s"ページ「$title」: $link"
              ))
            case "access_request" =>
              IO.pure(mail(
                u.name,
                s"[skiwi] ${by}さんが「$title」の${role}権限を申請しました",
                s"${by}さんが、ページ「$title」の${role}権限を申請しました。",
                quote(excerpt),
                "承認・却下はページの「共有」画面、または画面内の通知から行えます。",
                s"$link?access=requests"
              ))
            case "access_granted" =>
              IO.pure(mail(
                u.name,
                s"[skiwi] 「$title」の${role}権限が承認されました",
                s"${by}さんが、ページ「$title」への${role}権限の申請を承認しました。",
                "",
                link
              ))
            case "automation" =>
              val rule = field("by").filter(_.nonEmpty).getOrElse("自動化ルール")
              val msg = excerpt.getOrElse(s"「$title」")
              IO.pure(mail(
                u.name,
                s"[skiwi] $msg",
                msg,
                "",
                s"ページ「$title」: $link",
                "",
                s"※ ワークスペースの自動化ルール「$rule」による通知です。"
              ))
            case other => IO.pure(Left(s"unknown kind: $other"))
          }
        }

        (it.kind, it.pageId) match {
          case ("shared", None) =>
            // ワークスペースの共有
            val ws = field("workspace").getOrElse("")
            IO.pure(mail(
              it.userName,
              s"[skiwi] ${sharedBy}さんがワークスペース「$ws」を共有しました",
              s"${sharedBy}さんが、ワークスペース「$ws」をあなたと共有しました（$role）。",
              "",
              s"$base/"
            ))
          case (kind, Some(pageId)) =>
            visiblePage(it.userId, pageId).flatMap {
              case Left(reason) => IO.pure(Left(reason))
              case Right((u, p)) => pageMail(kind, u, p)
            }
          case (other, None) => IO.pure(Left(s"unknown kind without page: $other"))
        }
    }

  /** 期限の来たメールを1回分処理する。処理件数を返す。 */
  val runOnce: IO[Int] =
    ops.processDue(20) { it =>
      render(it).flatMap {
        case Left(reason) => IO.pure(OutboxResult.Skip(reason))
        case Right(mail) =>
          mailer match {
            case None => IO.pure(OutboxResult.Skip("メールの送信が設定されていません"))
            case Some(m) =>
              sbirds.observe.Telemetry.span("mail.send", "kind" -> it.kind)(m.send(mail)).as(OutboxResult.Sent)
                .flatTap(_ => sbirds.observe.Metrics.count("skiwi.mails.sent", "kind" -> it.kind))
          }
      }
    }

  val loop: IO[Nothing] =
    Periodic.run(interval, interval, ErrorCode.NotifyWorkerFailed)(runOnce)
}
