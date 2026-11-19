package skiwi.web.page

export skiwi.web.model.{AccessStatus, CssLayer, LockEditor, PageLockState, PageStyles}

import io.circe.*
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.web.Api
import skiwi.web.model.Page

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// ページの画面（PageView・コメント・版履歴・権限の申請）が使う API

/** inherited: 「子ページも含める」でウォッチしている祖先（このページに設定がなければ、その設定で通知が届く） */
final case class WatchInherited(
  pageId: String,
  title: String,
  edits: Boolean,
  comments: Boolean) derives Codec.AsObject

final case class WatchState(
  watching: Boolean,
  edits: Boolean,
  comments: Boolean,
  subtree: Boolean,
  inherited: Option[WatchInherited] = None) derives Codec.AsObject

final case class Version(
  id: String,
  createdAt: String,
  label: Option[String] = None,
  createdBy: Option[String] = None,
  authors: List[String] = Nil) derives Codec.AsObject

final case class VersionContent(
  version: Version,
  content: Json,
  text: String = "") derives Codec.AsObject

final case class PageComment(
  id: String,
  threadId: Option[String] = None,
  anchor: Option[String] = None,
  quote: Option[String] = None,
  body: String,
  authorId: Option[String] = None,
  authorName: Option[String] = None,
  authorAvatarUrl: Option[String] = None,
  createdAt: String,
  updatedAt: String,
  resolvedAt: Option[String] = None,
  resolvedBy: Option[String] = None,
  deleted: Boolean = false,
  canEdit: Boolean = false,
  canDelete: Boolean = false) derives Codec.AsObject

/** このページを開いているほかの人（エディタの onPresence）. editing: 編集中 */
final case class Person(
  name: String,
  color: String = "",
  editing: Boolean = false,
  avatar: Option[String] = None) derives Codec.AsObject

/** 版の差分の1行（editor/diff.ts の Row）. kind: same・add・del（text）/ change（pieces）/ fold（count: 省いたブロック数） */
final case class DiffPiece(
  kind: String,
  text: String = "") derives Codec.AsObject

final case class DiffRow(
  kind: String,
  text: Option[String] = None,
  pieces: Option[List[DiffPiece]] = None,
  count: Option[Int] = None) derives Codec.AsObject

/** 新しいコメントの対象: 本文の選択範囲（text）・ホワイトボード（wb）・ページ全体（page） */
final case class CommentDraft(
  kind: String,
  from: Option[Int] = None,
  to: Option[Int] = None,
  quote: Option[String] = None,
  boardId: Option[String] = None)

object PageApi {

  private def put[A: Decoder](
    url: String,
    body: Json
  ): Future[A] = Api.put[A](url, body)

  // ---------------------------------------------------------------- ウォッチ

  def watch(
    id: String
  ): Future[WatchState] = Api.get[WatchState](s"/api/pages/$id/watch")

  def setWatch(
    id: String,
    watching: Boolean
  ): Future[WatchState] = put[WatchState](s"/api/pages/$id/watch", Json.obj("watching" -> watching.asJson))

  def setWatchOpts(
    id: String,
    edits: Option[Boolean] = None,
    comments: Option[Boolean] = None,
    subtree: Option[Boolean] = None
  ): Future[WatchState] =
    put[WatchState](
      s"/api/pages/$id/watch",
      Api.obj("edits" -> edits.asJson, "comments" -> comments.asJson, "subtree" -> subtree.asJson)
    )

  // ---------------------------------------------------------------- 版

  def versions(
    id: String
  ): Future[List[Version]] = Api.get[List[Version]](s"/api/pages/$id/versions")

  def version(
    id: String,
    vid: String
  ): Future[VersionContent] = Api.get[VersionContent](s"/api/pages/$id/versions/$vid")

  /** state: エディタの getState()（{ydoc, content, text}）を JSON にしたもの. label は任意 */
  def saveVersion(
    id: String,
    state: Json
  ): Future[Version] = Api.post[Version](s"/api/pages/$id/versions", state)

  def restoreVersion(
    id: String,
    vid: String,
    state: Json
  ): Future[VersionContent] =
    Api.post[VersionContent](s"/api/pages/$id/versions/$vid/restore", state)

  // ---------------------------------------------------------------- コメント

  def comments(
    pageId: String
  ): Future[List[PageComment]] = Api.get[List[PageComment]](s"/api/pages/$pageId/comments")

  def createComment(
    pageId: String,
    body: String,
    threadId: Option[String] = None,
    anchor: Option[String] = None,
    quote: Option[String] = None
  ): Future[PageComment] =
    Api.post[PageComment](
      s"/api/pages/$pageId/comments",
      Api.obj("body" -> body.asJson, "threadId" -> threadId.asJson, "anchor" -> anchor.asJson, "quote" -> quote.asJson)
    )

  def editComment(
    id: String,
    body: String
  ): Future[PageComment] = Api.patch[PageComment](s"/api/comments/$id", Json.obj("body" -> body.asJson))

  def removeComment(
    id: String
  ): Future[Unit] = Api.del(s"/api/comments/$id")

  def resolveComment(
    id: String,
    resolved: Boolean
  ): Future[PageComment] =
    Api.post[PageComment](s"/api/comments/$id/resolve", Json.obj("resolved" -> resolved.asJson))

  // ---------------------------------------------------------------- ラベル・被リンク・閲覧の記録

  def backlinks(
    id: String
  ): Future[List[Page]] = Api.get[List[Page]](s"/api/pages/$id/backlinks")

  def setLabels(
    id: String,
    labels: List[String]
  ): Future[List[String]] =
    put[Json](
      s"/api/pages/$id/labels",
      Json.obj("labels" -> labels.asJson)
    ).map(_.hcursor.get[List[String]]("labels").getOrElse(Nil))

  def visit(
    id: String
  ): Future[Unit] = Api.exec(dom.HttpMethod.POST, s"/api/pages/$id/visit")

  // ---------------------------------------------------------------- 閲覧権限の申請

  def myRequest(
    id: String
  ): Future[AccessStatus] = Api.get[AccessStatus](s"/api/pages/$id/access-requests/mine")

  def requestAccess(
    id: String,
    role: String,
    message: String
  ): Future[AccessStatus] =
    Api.post[AccessStatus](
      s"/api/pages/$id/access-requests",
      Json.obj("role" -> role.asJson, "message" -> message.asJson)
    )

  // ---------------------------------------------------------------- カスタムCSS・保護・データベース

  def pageStyles(
    id: String
  ): Future[PageStyles] = Api.get[PageStyles](s"/api/pages/$id/styles")

  def lock(
    id: String
  ): Future[PageLockState] = Api.get[PageLockState](s"/api/pages/$id/lock")

}
