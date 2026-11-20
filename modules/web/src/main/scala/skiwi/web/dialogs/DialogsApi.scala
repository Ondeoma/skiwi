package skiwi.web.dialogs

export skiwi.web.model.{AccessStatus, CssLayer, LockEditor, PageLockState, PageStyles, PublicShare}
export skiwi.web.database.{DbOption, DbProp}

import io.circe.*
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.web.Api
import skiwi.web.model.*

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 設定ダイアログ類（共有・公開・CSS・保護・Webhook・自動化・受信・バックアップ・点検）の API

// ---------------------------------------------------------------- 共有設定（権限）

/** expiresAt: 権限の期限（過ぎると自動で外れる） */
final case class AclEntry(principalType: String,
                          principalId: String,
                          role: String,
                          name: Option[String] = None,
                          detail: Option[String] = None,
                          expiresAt: Option[String] = None) derives Codec.AsObject

final case class PageAcl(inherit: Boolean, entries: List[AclEntry]) derives Codec.AsObject

final case class WorkspaceAcl(defaultRole: Option[String], entries: List[AclEntry]) derives Codec.AsObject

final case class AudienceEntry(userId: String, name: String, email: Option[String], role: String, reason: String)
  derives Codec.AsObject

final case class AccessRequest(id: String,
                               userId: String,
                               name: String,
                               email: Option[String],
                               role: String,
                               message: Option[String],
                               createdAt: String) derives Codec.AsObject

// ---------------------------------------------------------------- ゲスト向け公開

/** 祖先ページの公開リンクに含まれている場合. token: 閲覧できない上位のページなら空 */
final case class InheritedShare(pageId: Option[String],
                                title: Option[String],
                                token: Option[String],
                                slug: Option[String],
                                mode: String) derives Codec.AsObject

/** available=false: サーバー設定で公開機能が無効. pageSlug: このページのURL名 */
final case class ShareInfo(available: Boolean,
                           share: Option[PublicShare],
                           inherited: Option[InheritedShare],
                           pageSlug: Option[String]) derives Codec.AsObject

/** 公開の履歴（「現在の内容を公開」の1回分） */
final case class Release(id: String,
                         publishedAt: String,
                         publishedBy: Option[String],
                         note: Option[String],
                         pageCount: Int)
  derives Codec.AsObject

final case class ReleasePage(pageId: String,
                             parentId: Option[String],
                             title: String,
                             icon: Option[String],
                             content: Json,
                             text: String)
  derives Codec.AsObject

final case class ReleaseDetail(release: Release, pages: List[ReleasePage]) derives Codec.AsObject

final case class SlugResult(slug: Option[String]) derives Codec.AsObject

// ---------------------------------------------------------------- カスタムCSS・ゲスト向けの表示

final case class WorkspaceCss(css: Option[String]) derives Codec.AsObject

final case class GuestHtmlLayer(level: String, title: Option[String], top: Option[String], bottom: Option[String])
  derives Codec.AsObject

final case class GuestHtml(top: Option[String],
                           bottom: Option[String],
                           inherit: Option[Boolean] = None,
                           layers: Option[List[GuestHtmlLayer]] = None) derives Codec.AsObject

// ---------------------------------------------------------------- ページの保護

// ---------------------------------------------------------------- Webhook（外部への通知）・受信用の Webhook

/** format: generic / slack / teams. secret: 作成したときだけ返る（署名の確認用） */
final case class Webhook(id: String,
                         name: String,
                         url: String,
                         format: String,
                         events: List[String],
                         enabled: Boolean,
                         createdAt: String,
                         createdBy: Option[String],
                         lastStatus: Option[Int],
                         lastError: Option[String],
                         lastAt: Option[String],
                         secret: Option[String] = None) derives Codec.AsObject

final case class WebhookTest(ok: Boolean, status: Option[Int], error: Option[String]) derives Codec.AsObject

/** mode: row（データベースの項目を追加）| child（子ページを作る）| comment（コメントを付ける）. url は作成・作り直しのときだけ */
final case class InboundHook(id: String,
                             name: String,
                             mode: String,
                             targetPageId: String,
                             targetTitle: Option[String],
                             enabled: Boolean,
                             createdBy: Option[String],
                             createdAt: String,
                             lastAt: Option[String],
                             useCount: Int,
                             url: Option[String] = None) derives Codec.AsObject

// ---------------------------------------------------------------- 自動化ルール

final case class AutoTrigger(`type`: String,
                             under: Option[String] = None,
                             to: Option[String] = None,
                             label: Option[String] = None,
                             database: Option[String] = None,
                             prop: Option[String] = None,
                             value: Option[String] = None,
                             offset: Option[Int] = None,
                             unlessProp: Option[String] = None,
                             unlessValue: Option[String] = None)
  derives Codec.AsObject

final case class AutoAction(`type`: String,
                            to: Option[String] = None,
                            users: Option[List[String]] = None,
                            prop: Option[String] = None,
                            webhook: Option[String] = None,
                            status: Option[String] = None,
                            label: Option[String] = None,
                            message: Option[String] = None) derives Codec.AsObject

final case class Automation(id: String,
                            name: String,
                            enabled: Boolean,
                            trigger: AutoTrigger,
                            action: AutoAction,
                            createdBy: Option[String],
                            createdAt: String,
                            lastRunAt: Option[String],
                            lastError: Option[String],
                            runCount: Int) derives Codec.AsObject

final case class DbProps(props: List[DbProp]) derives Codec.AsObject

// ---------------------------------------------------------------- 定期バックアップ・点検

final case class BackupConfig(enabled: Boolean, hour: Int, keep: Int) derives Codec.AsObject

/** status: running / done / failed */
final case class Backup(id: String,
                        createdAt: String,
                        finishedAt: Option[String],
                        status: String,
                        size: Option[Double],
                        pages: Option[Int],
                        attachments: Option[Int],
                        error: Option[String],
                        manual: Boolean) derives Codec.AsObject

final case class BackupList(config: Option[BackupConfig], backups: List[Backup], storage: Boolean)
  derives Codec.AsObject

/** reason: missing / deleted / no_access */
final case class BrokenLink(sourceId: String,
                            sourceTitle: String,
                            targetId: String,
                            targetTitle: Option[String],
                            reason: String)
  derives Codec.AsObject

/** incoming: ほかのページの本文からのリンク数 / lastEditedAt: 最終更新 */
final case class PageHealth(page: Page, incoming: Int, lastEditedAt: String, lastEditor: Option[String], children: Int)
  derives Codec.AsObject

object DialogsApi {

  import Api.*

  private def put0(url: String, body: Json): Future[Unit] = send(dom.HttpMethod.PUT, url, Some(body))

  // ---- 共有設定
  private def strip(es: List[AclEntry]): Json = es.map(e =>
    Json.obj(
      "principalType" -> e.principalType.asJson,
      "principalId" -> e.principalId.asJson,
      "role" -> e.role.asJson,
      "expiresAt" -> e.expiresAt.asJson
    )
  ).asJson

  def pageAcl(id: String): Future[PageAcl] = get[PageAcl](s"/api/pages/${Api.seg(id)}/acl")

  def setPageAcl(id: String, inherit: Boolean, entries: List[AclEntry]): Future[Unit] =
    put0(s"/api/pages/${Api.seg(id)}/acl", Json.obj("inherit" -> inherit.asJson, "entries" -> strip(entries)))

  def workspaceAcl(id: String): Future[WorkspaceAcl] = get[WorkspaceAcl](s"/api/workspaces/${Api.seg(id)}/acl")

  def setWorkspaceAcl(id: String, defaultRole: Option[String], entries: List[AclEntry]): Future[Unit] =
    put0(
      s"/api/workspaces/${Api.seg(id)}/acl",
      Json.obj("defaultRole" -> defaultRole.asJson, "entries" -> strip(entries))
    )

  def audience(pageId: String): Future[List[AudienceEntry]] =
    get[List[AudienceEntry]](s"/api/pages/${Api.seg(pageId)}/audience")

  def accessRequests(pageId: String): Future[List[AccessRequest]] =
    get[List[AccessRequest]](s"/api/pages/${Api.seg(pageId)}/access-requests")

  /** action: approve / deny */
  def decide(rid: String, action: String, role: Option[String], expiresAt: Option[String]): Future[AccessStatus] =
    post[AccessStatus](
      s"/api/access-requests/${Api.seg(rid)}/${Api.seg(action)}",
      obj("role" -> role.asJson, "expiresAt" -> expiresAt.asJson)
    )

  /** ユーザー・グループの検索（失敗は空） */
  def users(q: String): Future[List[DirectoryEntry]] =
    if (q.trim.isEmpty) Future.successful(Nil)
    else Api.directory(q.trim).recover(_ => Nil).map(_.filter(_.principalType == "user"))

  // ---- ゲスト向け公開
  def share(pageId: String): Future[ShareInfo] = get[ShareInfo](s"/api/pages/${Api.seg(pageId)}/share")

  /** opts: 変える項目だけ（null は解除） */
  def enableShare(pageId: String, opts: Json): Future[PublicShare] =
    put[PublicShare](s"/api/pages/${Api.seg(pageId)}/share", opts)

  def disableShare(pageId: String): Future[Unit] = delete(s"/api/pages/${Api.seg(pageId)}/share")

  /** 公開版を作り直す（現在の内容を公開する） */
  def publish(pageId: String, note: Option[String]): Future[PublicShare] =
    post[PublicShare](s"/api/pages/${Api.seg(pageId)}/share/publish", obj("note" -> note.asJson))

  def releases(pageId: String): Future[List[Release]] =
    get[List[Release]](s"/api/pages/${Api.seg(pageId)}/share/releases")

  def release(pageId: String, rid: String): Future[ReleaseDetail] =
    get[ReleaseDetail](s"/api/pages/${Api.seg(pageId)}/share/releases/${Api.seg(rid)}")

  def setReleaseNote(pageId: String, rid: String, note: Option[String]): Future[Unit] =
    send(
      dom.HttpMethod.PATCH,
      s"/api/pages/${Api.seg(pageId)}/share/releases/${Api.seg(rid)}",
      Some(Json.obj("note" -> note.asJson))
    )

  def setPageSlug(pageId: String, slug: Option[String]): Future[SlugResult] =
    put[SlugResult](s"/api/pages/${Api.seg(pageId)}/guest-slug", Json.obj("slug" -> slug.asJson))

  /** key: 公開リンクのトークンまたはURL名、page: ページのURL名またはID */
  def shareUrl(key: String, page: String = ""): String =
    s"${dom.window.location.origin}/s/$key${if (page.isEmpty) "" else s"/$page"}"

  // ---- カスタムCSS・ゲスト向けの表示
  def setPageCss(id: String, css: Option[String], inherit: Boolean): Future[Unit] =
    put0(s"/api/pages/${Api.seg(id)}/css", Json.obj("css" -> css.asJson, "inherit" -> inherit.asJson))

  def workspaceCss(id: String): Future[WorkspaceCss] = get[WorkspaceCss](s"/api/workspaces/${Api.seg(id)}/css")

  def setWorkspaceCss(id: String, css: Option[String]): Future[Unit] =
    put0(s"/api/workspaces/${Api.seg(id)}/css", Json.obj("css" -> css.asJson))

  /** kind: page / workspace */
  def guestHtml(kind: String, id: String): Future[GuestHtml] =
    get[GuestHtml](if (kind == "page") s"/api/pages/${Api.seg(id)}/guest-html"
    else s"/api/workspaces/${Api.seg(id)}/guest-html")

  def setGuestHtml(kind: String,
                   id: String,
                   top: Option[String],
                   bottom: Option[String],
                   inherit: Boolean): Future[Unit] =
    if (kind == "page") put0(
      s"/api/pages/${Api.seg(id)}/guest-html",
      Json.obj("top" -> top.asJson, "bottom" -> bottom.asJson, "inherit" -> inherit.asJson)
    )
    else put0(s"/api/workspaces/${Api.seg(id)}/guest-html", Json.obj("top" -> top.asJson, "bottom" -> bottom.asJson))

  // ---- ページの保護
  def setLock(id: String, reason: Option[String], editors: List[String]): Future[PageLockState] =
    put[PageLockState](
      s"/api/pages/${Api.seg(id)}/lock",
      Json.obj("reason" -> reason.asJson, "editors" -> editors.asJson)
    )

  def unlock(id: String): Future[Unit] = delete(s"/api/pages/${Api.seg(id)}/lock")

  // ---- Webhook
  val WebhookEvents: List[(String, String)] = List(
    "page.created" -> "ページの作成",
    "page.updated" -> "ページの更新（編集が落ち着いてからまとめて）",
    "page.deleted" -> "ページの削除",
    "comment.created" -> "コメント",
    "page.published" -> "ゲスト向けの公開"
  )

  def webhooks(wsId: String): Future[List[Webhook]] = get[List[Webhook]](s"/api/workspaces/${Api.seg(wsId)}/webhooks")

  def createWebhook(wsId: String, name: String, url: String, format: String, events: List[String]): Future[Webhook] =
    post[Webhook](
      s"/api/workspaces/${Api.seg(wsId)}/webhooks",
      Json.obj("name" -> name.asJson, "url" -> url.asJson, "format" -> format.asJson, "events" -> events.asJson)
    )

  /** fields: 変える項目だけ */
  def updateWebhook(id: String, fields: Json): Future[Webhook] = patch[Webhook](s"/api/webhooks/${Api.seg(id)}", fields)

  def removeWebhook(id: String): Future[Unit] = delete(s"/api/webhooks/${Api.seg(id)}")

  def testWebhook(id: String): Future[WebhookTest] = post[WebhookTest](s"/api/webhooks/${Api.seg(id)}/test")

  // ---- 自動化ルール
  def automations(wsId: String): Future[List[Automation]] =
    get[List[Automation]](s"/api/workspaces/${Api.seg(wsId)}/automations")

  def createAutomation(wsId: String, body: Json): Future[Automation] =
    post[Automation](s"/api/workspaces/${Api.seg(wsId)}/automations", body)

  def updateAutomation(id: String, body: Json): Future[Automation] =
    patch[Automation](s"/api/automations/${Api.seg(id)}", body)

  def removeAutomation(id: String): Future[Unit] = delete(s"/api/automations/${Api.seg(id)}")

  def dbProps(pageId: String): Future[List[DbProp]] =
    get[DbProps](s"/api/pages/${Api.seg(pageId)}/database").map(_.props)

  // ---- 受信用の Webhook
  def inboundHooks(wsId: String): Future[List[InboundHook]] =
    get[List[InboundHook]](s"/api/workspaces/${Api.seg(wsId)}/inbound-hooks")

  def createInbound(wsId: String, name: String, targetPageId: String, mode: String): Future[InboundHook] =
    post[InboundHook](
      s"/api/workspaces/${Api.seg(wsId)}/inbound-hooks",
      Json.obj("name" -> name.asJson, "targetPageId" -> targetPageId.asJson, "mode" -> mode.asJson)
    )

  def updateInbound(id: String, enabled: Boolean): Future[InboundHook] =
    patch[InboundHook](s"/api/inbound-hooks/${Api.seg(id)}", Json.obj("enabled" -> enabled.asJson))

  def rotateInbound(id: String): Future[InboundHook] = post[InboundHook](s"/api/inbound-hooks/${Api.seg(id)}/rotate")

  def removeInbound(id: String): Future[Unit] = delete(s"/api/inbound-hooks/${Api.seg(id)}")

  // ---- 定期バックアップ（管理者のみ）
  def backups(wsId: String): Future[BackupList] = get[BackupList](s"/api/admin/workspaces/${Api.seg(wsId)}/backups")

  def setBackupConfig(wsId: String, c: BackupConfig): Future[BackupConfig] =
    put[BackupConfig](s"/api/admin/workspaces/${Api.seg(wsId)}/backups/config", c.asJson)

  def runBackup(wsId: String): Future[Unit] =
    send(dom.HttpMethod.POST, s"/api/admin/workspaces/${Api.seg(wsId)}/backups", Some(Json.obj()))

  def removeBackup(id: String): Future[Unit] = delete(s"/api/admin/backups/${Api.seg(id)}")

  def backupDownloadUrl(id: String): String = s"/api/admin/backups/${Api.seg(id)}/download"

  // ---- 点検
  def brokenLinks(wsId: String): Future[List[BrokenLink]] =
    get[List[BrokenLink]](s"/api/workspaces/${Api.seg(wsId)}/broken-links")

  def health(wsId: String): Future[List[PageHealth]] = get[List[PageHealth]](s"/api/workspaces/${Api.seg(wsId)}/health")

}
