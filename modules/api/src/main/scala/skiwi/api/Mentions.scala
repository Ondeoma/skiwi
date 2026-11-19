package skiwi.api

import cats.effect.*
import skiwi.db.{OpsStore, Store}

import java.util.UUID
import skiwi.core.ErrorCode

/** メンション・コメントの通知。対象のページを閲覧できる人にだけ届ける。 */
final class Mentions(
  store: Store,
  ops: OpsStore,
  sessions: SessionAuth) {

  /** 閲覧できる人だけに絞る */
  def canView(
    pageId: UUID,
    users: Set[UUID]
  ): IO[Set[UUID]] =
    store.userRoles(pageId, users, sessions.adminGroups).map(_.keySet)

  def notify(
    kind: String,
    pageId: UUID,
    users: Set[UUID],
    byName: String,
    excerpt: String,
    threadId: Option[UUID] = None
  ): IO[Unit] =
    canView(pageId, users).flatMap(us =>
      ops.enqueuePageNotice(kind, pageId, us, byName, excerpt.take(200), threadId).void
    )
      .handleErrorWith(e => sbirds.observe.Log.report(ErrorCode.NotifyEnqueueFailed, s"$kind: $e", e))
}

object Mentions {
  private val Markup = """@\[([^\]]*)\]\([0-9a-fA-F-]{36}\)""".r

  /** 通知・メール用の抜粋: メンションの記法を「@名前」にして、空白をまとめる */
  def excerpt(
    body: String
  ): String = Markup.replaceAllIn(
    body,
    m => java.util.regex.Matcher.quoteReplacement("@" + m.group(1))
  ).replaceAll("\\s+", " ").trim.take(200)
}
