package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import skiwi.db.Notified
import sbirds.push.{PushMessage, PushService}
import skiwi.shared.NoticeText

/**
 * 画面内の通知（ベル）を作ったときに、同じ内容をプッシュ通知でも送る（この端末で受け取る設定にした人だけ）.
 * OpsStore の onNotified から呼ぶ（メンション・コメント・ウォッチ中のページの更新・共有・権限の申請・ToDo の期限・自動化ルール）.
 * 見出し・文・開く場所は画面のベルと同じ（skiwi.shared.NoticeText）.
 */
final class NoticePush(
  push: PushService) {

  def send(
    ns: List[Notified]
  ): IO[Unit] =
    ns.traverse_ { n =>
      val d = NotificationDto.of(n.row)
      val title = NoticeText.heading(d.kind, d.pageId.isDefined, d.pageIcon, d.pageTitle, d.workspaceName)
      val body = NoticeText.text(d.kind, d.actors, d.role, d.excerpt, d.due)
      val url =
        NoticeText.link(d.kind, d.pageId.map(_.toString), d.workspaceId.map(_.toString), d.threadId).getOrElse("/")
      // 同じページの同じ種類の通知は、端末の通知を置き換える（コメントはスレッドごと）
      val tag = s"${d.kind}:${d.pageId.orElse(d.workspaceId).fold("")(_.toString)}${d.threadId.fold("")(":" + _)}"
      push.send(n.userId, PushMessage(title, body, url, Some(tag)))
    }
}
