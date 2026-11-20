package skiwi.web

import io.circe.*
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.shared.*
import skiwi.web.model.*

import java.util.UUID
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/**
 * バックエンドの API（sbirds-lib の ApiBase. 401 はログインへ、エラーは {"error": ...} の ApiError）.
 * エディタ（JS）の取得・送信もここを通す（editor.EditorLib）.
 * 画面ごとの API は、この get・call・delete などを使って各画面のファイル（XxxApi）に書く.
 */
object Api extends sbirds.web.ApiBase {

  // 401 のときは戻り先を付けずに IdP へ（どの画面でも）
  override protected def loginUrl(back: String): String = "/auth/login"
  override protected def stayOnUnauthorized: Boolean = false

  /**
   * redirectOn401=false: 未ログインでもログイン画面へ飛ばさず ApiError(401) にする（ゲスト表示など）.
   * body: JSON（Json）・ファイル（dom.Blob・dom.FormData）
   */
  def call(method: dom.HttpMethod,
           url: String,
           body: Option[Json | dom.Blob | dom.FormData] = None,
           redirectOn401: Boolean = true): Future[Option[Json]] =
    raw(method, url, body).flatMap(res => respond(res, redirectOn401))

  /** fetch だけを行い、応答をそのまま返す */
  def raw(method: dom.HttpMethod, url: String, body: Option[Json | dom.Blob | dom.FormData] = None)
    : Future[dom.Response] = {
    val req = init(method, body.collect { case j: Json => j.noSpaces })
    body.foreach {
      case f: dom.FormData => req.body = f
      case b: dom.Blob =>
        req.headers.asInstanceOf[js.Dictionary[String]]("Content-Type") = b.`type`
        req.body = b
      case _: Json => ()
    }
    dom.fetch(url, req).toFuture
  }

  /** 応答を本文の JSON か ApiError にする（redirectOn401=false なら 401 でもログイン画面へ移さない） */
  def respond(res: dom.Response, redirectOn401: Boolean = true): Future[Option[Json]] =
    if (res.status == 401 && !redirectOn401) Future.failed(ApiError(401, "ログインしてください")) else handle(res)

  def get[A: Decoder](url: String, redirectOn401: Boolean): Future[A] =
    call(dom.HttpMethod.GET, url, redirectOn401 = redirectOn401).map(as[A])

  override def as[A: Decoder](j: Option[Json]): A = super.as[A](j)

  /** 応答がエラー（権限なし・削除済みなど）なら None. 通信エラーなどはそのまま失敗にする */
  def okOrNone[A](f: Future[A]): Future[Option[A]] =
    f.map(Some(_)).recover { case _: ApiError => None }

  /** クエリ文字列（値が None のものは付けない. 空の値は付ける: 検索の q は必須） */
  override def query(params: (String, Option[String])*): String = {
    val q = params.collect { case (k, Some(v)) => s"$k=${enc(v)}" }
    if (q.isEmpty) "" else q.mkString("?", "&", "")
  }

  /** null の項目を落とした JSON（PATCH で「送らない」を表す） */
  def obj(fields: (String, Json)*): Json = Json.obj(fields*).dropNullValues

  // ---------------------------------------------------------------- 自分・ワークスペース・ページ

  def me(): Future[Me] = get[Me]("/api/me")

  /** ログアウトして、次に開く URL（IdP のログアウト画面か「/」）を返す. */
  def logout(): Future[String] = post[Json]("/auth/logout").map(_.hcursor.get[String]("redirect").getOrElse("/"))

  def setAvatar(image: dom.Blob): Future[Option[String]] =
    call(dom.HttpMethod.PUT, "/api/me/avatar", Some(image)).map(j =>
      as[Json](j).hcursor.get[Option[String]]("avatarUrl").toOption.flatten
    )

  def deleteAvatar(): Future[Unit] = delete("/api/me/avatar")

  def workspaces(): Future[List[Workspace]] = get[List[Workspace]]("/api/workspaces")

  def createWorkspace(name: String): Future[Workspace] =
    post[Workspace]("/api/workspaces", Json.obj("name" -> name.asJson))

  def setFavorite(id: String, favorite: Boolean): Future[Unit] =
    send(dom.HttpMethod.PUT, s"/api/workspaces/${Api.seg(id)}/favorite", Some(Json.obj("favorite" -> favorite.asJson)))

  def renameWorkspace(id: String, name: String): Future[Unit] =
    send(dom.HttpMethod.PATCH, s"/api/workspaces/${Api.seg(id)}", Some(Json.obj("name" -> name.asJson)))

  def pages(wsId: String): Future[List[Page]] = get[List[Page]](s"/api/workspaces/${Api.seg(wsId)}/pages")

  /** placement: afterId のページに対する置き方 */
  def createPage(wsId: String,
                 parentId: Option[String],
                 title: String = "",
                 afterId: Option[String] = None,
                 placement: Option[String] = None): Future[Page] =
    post[Page](
      s"/api/workspaces/${Api.seg(wsId)}/pages",
      obj(
        "parentId" -> parentId.asJson,
        "title" -> title.asJson,
        "afterId" -> afterId.asJson,
        "placement" -> placement.asJson
      )
    )

  /** 変える項目だけを渡す（例: Json.obj("title" -> "x".asJson)）. null は「消す」 */
  def updatePage(id: String, fields: Json): Future[Page] = patch[Page](s"/api/pages/${Api.seg(id)}", fields)

  def page(id: String, redirectOn401: Boolean = true): Future[Page] =
    get[Page](s"/api/pages/${Api.seg(id)}", redirectOn401)

  def deletePage(id: String): Future[Unit] = delete(s"/api/pages/${Api.seg(id)}")

  def favoritePages(): Future[List[FavoritePage]] = get[List[FavoritePage]]("/api/favorites/pages")

  def setPageFavorite(id: String, favorite: Boolean): Future[Unit] =
    send(dom.HttpMethod.PUT, s"/api/pages/${Api.seg(id)}/favorite", Some(Json.obj("favorite" -> favorite.asJson)))

  def directory(q: String): Future[List[DirectoryEntry]] =
    get[List[DirectoryEntry]](s"/api/directory${query("q" -> Some(q))}")

  // ---------------------------------------------------------------- APIトークン・監査ログ

  def tokens(): Future[List[TokenDto]] = get[List[TokenDto]]("/api/tokens")

  def createToken(req: CreateTokenReq): Future[CreatedTokenDto] =
    post[CreatedTokenDto]("/api/tokens", req.asJson)

  def revokeToken(id: UUID): Future[Unit] = delete(s"/api/tokens/${Api.seg(id)}")

  def audit(before: Option[Long], action: Option[String], limit: Int): Future[List[AuditDto]] =
    get[List[AuditDto]](
      s"/api/admin/audit${query("before" -> before.map(_.toString), "action" -> action, "limit" -> Some(limit.toString))}"
    )

}
