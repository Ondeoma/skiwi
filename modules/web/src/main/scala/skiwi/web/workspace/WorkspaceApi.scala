package skiwi.web.workspace

import io.circe.*
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.web.Api.*
import skiwi.web.model.*

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// ワークスペースの枠（サイドバー・検索・通知・ゴミ箱・一括操作など）で使う API

/** 添付ファイル（PDF・Office など）の中身で見つかったとき、その添付ファイル */
final case class SearchAttachment(id: String, filename: String, url: String) derives Codec.AsObject

/** 検索結果. fuzzy: 表記の近さで見つかった / snippet: PGroonga がHTMLエスケープ済み（キーワードのみ span で装飾） */
final case class SearchResult(page: Page,
                              snippet: Option[String] = None,
                              fuzzy: Boolean = false,
                              attachment: Option[SearchAttachment] = None) derives Codec.AsObject

final case class WorkspaceHit(id: String, name: String, role: String, fuzzy: Boolean = false) derives Codec.AsObject

/** 検索の絞り込み. label: ラベル、days: この日数以内に更新、mine: 自分が作成、status: 状態（none は状態なし）、ownerMe: 自分が所有者 */
final case class SearchFilter(label: Option[String] = None,
                              days: Option[Int] = None,
                              mine: Boolean = false,
                              status: Option[String] = None,
                              ownerMe: Boolean = false)

final case class TrashedPage(id: String,
                             title: String,
                             icon: Option[String] = None,
                             deletedAt: String,
                             deletedBy: Option[String] = None,
                             count: Int = 1,
                             purgeAt: String) derives Codec.AsObject

final case class TrashedWorkspace(id: String,
                                  name: String,
                                  deletedAt: String,
                                  deletedBy: Option[String] = None,
                                  purgeAt: String)
  derives Codec.AsObject

final case class RestoredPage(id: String, workspaceId: String, parentId: Option[String] = None) derives Codec.AsObject

final case class RecentItem(page: Page, workspaceName: String, at: String) derives Codec.AsObject

/**
 * 画面内の通知.
 * kind: page_edited / shared / shared_workspace / mention / comment / task_due / access_request / access_granted / access_denied / automation
 */
final case class Notif(id: Long,
                       kind: String,
                       actors: List[String] = Nil,
                       role: Option[String] = None,
                       excerpt: Option[String] = None,
                       threadId: Option[String] = None,
                       due: Option[String] = None,
                       requestId: Option[String] = None,
                       pageId: Option[String] = None,
                       pageTitle: Option[String] = None,
                       pageIcon: Option[String] = None,
                       workspaceId: Option[String] = None,
                       workspaceName: Option[String] = None,
                       createdAt: String,
                       read: Boolean = false)
  derives Codec.AsObject

final case class NotifList(unread: Int, items: List[Notif]) derives Codec.AsObject

final case class Unread(unread: Int) derives Codec.AsObject

/** 権限の申請の状態（status: none / pending / has_access / approved / denied） */
final case class AccessDecision(status: String, role: Option[String] = None) derives Codec.AsObject

/** 自分に割り当てられた ToDo. due: YYYY-MM-DD */
final case class TaskItem(pageId: String,
                          workspaceId: String,
                          workspaceName: String,
                          pageTitle: String,
                          pageIcon: Option[String] = None,
                          seq: Int,
                          text: String,
                          checked: Boolean,
                          due: Option[String] = None) derives Codec.AsObject

final case class BulkFailure(id: String, title: Option[String] = None, error: String) derives Codec.AsObject

/** done: 処理したページ / skipped: 選んだ親と一緒に移動・削除されたページ / failed: できなかったページと理由 */
final case class BulkResult(done: List[String] = Nil, skipped: List[String] = Nil, failed: List[BulkFailure] = Nil)
  derives Codec.AsObject

object WorkspaceApi {

  /** workspaceId を省くと全ワークスペースを検索する. q が空でも絞り込みがあれば一覧を返す */
  def searchPages(q: String,
                  workspaceId: Option[String] = None,
                  f: SearchFilter = SearchFilter()): Future[List[SearchResult]] =
    get[List[SearchResult]]("/api/search" + query(
      "q" -> Some(q),
      "workspaceId" -> workspaceId,
      "label" -> f.label,
      "days" -> f.days.map(_.toString),
      "mine" -> Option.when(f.mine)("true"),
      "status" -> f.status,
      "owner" -> Option.when(f.ownerMe)("me"),
    ))

  def searchWorkspaces(q: String): Future[List[WorkspaceHit]] =
    get[List[WorkspaceHit]]("/api/search/workspaces" + query("q" -> Some(q)))

  def deleteWorkspace(id: String): Future[Unit] = del(s"/api/workspaces/$id")

  def trash(wid: String): Future[List[TrashedPage]] = get[List[TrashedPage]](s"/api/workspaces/$wid/trash")

  def restorePage(id: String): Future[RestoredPage] = post[RestoredPage](s"/api/trash/pages/$id/restore")

  def purgePage(id: String): Future[Unit] = del(s"/api/trash/pages/$id")

  def trashedWorkspaces(): Future[List[TrashedWorkspace]] = get[List[TrashedWorkspace]]("/api/trash/workspaces")

  def restoreWorkspace(id: String): Future[Unit] =
    exec(dom.HttpMethod.POST, s"/api/trash/workspaces/$id/restore", Some(Json.obj()))

  def purgeWorkspace(id: String): Future[Unit] = del(s"/api/trash/workspaces/$id")

  /** kind: visited / edited */
  def recent(kind: String): Future[List[RecentItem]] = get[List[RecentItem]](s"/api/recent?kind=$kind&limit=30")

  def notifications(): Future[NotifList] = get[NotifList]("/api/notifications")

  /** 既読にする（ids が None ならすべて）. 戻り値: 残りの未読の数 */
  def markRead(ids: Option[List[Long]] = None): Future[Int] =
    post[Unread]("/api/notifications/read", ids.fold(Json.obj())(xs => Json.obj("ids" -> xs.asJson))).map(_.unread)

  /** 権限の申請への返事. action: approve / deny */
  def decide(requestId: String, action: String): Future[AccessDecision] =
    post[AccessDecision](s"/api/access-requests/$requestId/$action")

  def myTasks(done: Boolean): Future[List[TaskItem]] =
    get[List[TaskItem]](s"/api/tasks${if (done) "?done=true" else ""}")

  /** 一括操作. action は {"action": "move", ...} などの JSON */
  def bulk(ids: List[String], action: JsonObject): Future[BulkResult] =
    post[BulkResult]("/api/pages/bulk", Json.fromJsonObject(action.add("ids", ids.asJson)))

  /** ページの移動. body: parentId（null はワークスペースの直下）・afterId・placement・workspaceId */
  def movePage(id: String, body: Json): Future[Page] = post[Page](s"/api/pages/$id/move", body)

}
