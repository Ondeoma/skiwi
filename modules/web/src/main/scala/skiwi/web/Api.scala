package skiwi.web

import io.circe.*
import io.circe.parser.parse
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.shared.*
import skiwi.web.model.*

import java.util.UUID
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** API の呼び出しでのエラー（frontend/src/api.ts の ApiError と同じ）. body: エラー応答の本文（JSON） */
final case class ApiError(status: Int, message: String, body: Option[Json] = None) extends Exception(message)

/**
 * バックエンドの API（frontend/src/api.ts の request と同じ約束: 401 はログインへ、エラーは {"error": ...}）.
 * 画面ごとの API は、この get・send・del などを使って各画面のファイル（XxxApi）に書く.
 */
object Api {

  /**
   * redirectOn401=false: 未ログインでもログイン画面へ飛ばさず ApiError(401) にする（ゲスト表示など）.
   * body: JSON（Json）・ファイル（dom.Blob・dom.FormData）
   */
  def request(method: dom.HttpMethod, url: String, body: Option[Json | dom.Blob | dom.FormData] = None,
              redirectOn401: Boolean = true): Future[Option[Json]] = {
    val init = new dom.RequestInit {}
    init.method = method
    init.credentials = dom.RequestCredentials.`same-origin`
    body.foreach {
      case f: dom.FormData => init.body = f
      case b: dom.Blob =>
        init.headers = js.Dictionary("Content-Type" -> b.`type`)
        init.body = b
      case j: Json =>
        init.headers = js.Dictionary("Content-Type" -> "application/json")
        init.body = j.noSpaces
    }
    dom.fetch(url, init).toFuture.flatMap { res =>
      if (res.status == 401 && redirectOn401) {
        // 未ログイン: IdPへ
        dom.window.location.href = "/auth/login"
        Future.failed(ApiError(401, "unauthorized"))
      } else if (!res.ok) {
        res.text().toFuture.recover(_ => "").flatMap { t =>
          val j = parse(t).toOption
          val msg = j.flatMap(_.hcursor.get[String]("error").toOption).getOrElse(res.statusText)
          Future.failed(ApiError(res.status, msg, j))
        }
      } else if (res.status == 204) Future.successful(None)
      else res.text().toFuture.map(t => if (t.isEmpty) None else Some(parse(t).fold(throw _, identity)))
    }
  }

  def get[A: Decoder](url: String, redirectOn401: Boolean = true): Future[A] =
    request(dom.HttpMethod.GET, url, redirectOn401 = redirectOn401).map(as[A])

  def post[A: Decoder](url: String, body: Json = Json.obj()): Future[A] = send[A](dom.HttpMethod.POST, url, body)

  def put[A: Decoder](url: String, body: Json): Future[A] = send[A](dom.HttpMethod.PUT, url, body)

  def patch[A: Decoder](url: String, body: Json): Future[A] = send[A](dom.HttpMethod.PATCH, url, body)

  def send[A: Decoder](method: dom.HttpMethod, url: String, body: Json): Future[A] =
    request(method, url, Some(body)).map(as[A])

  /** 応答の本文を使わない呼び出し */
  def exec(method: dom.HttpMethod, url: String, body: Option[Json] = None): Future[Unit] =
    request(method, url, body).map(_ => ())

  def del(url: String): Future[Unit] = exec(dom.HttpMethod.DELETE, url)

  def as[A: Decoder](j: Option[Json]): A = j.getOrElse(Json.Null).as[A].fold(throw _, identity)

  /** 応答がエラー（権限なし・削除済みなど）なら None. 通信エラーなどはそのまま失敗にする */
  def okOrNone[A](f: Future[A]): Future[Option[A]] =
    f.map(Some(_)).recover { case _: ApiError => None }

  def enc(s: String): String = js.URIUtils.encodeURIComponent(s)

  /** クエリ文字列（値が None のものは付けない） */
  def query(params: (String, Option[String])*): String = {
    val q = params.collect { case (k, Some(v)) => s"$k=${enc(v)}" }
    if (q.isEmpty) "" else q.mkString("?", "&", "")
  }

  /** null の項目を落とした JSON（PATCH で「送らない」を表す） */
  def obj(fields: (String, Json)*): Json = Json.obj(fields*).dropNullValues

  // ---------------------------------------------------------------- 自分・ワークスペース・ページ

  def me(): Future[Me] = get[Me]("/api/me")

  def logout(): Future[Unit] = exec(dom.HttpMethod.POST, "/auth/logout")

  def setAvatar(image: dom.Blob): Future[Option[String]] =
    request(dom.HttpMethod.PUT, "/api/me/avatar", Some(image)).map(j => as[Json](j).hcursor.get[Option[String]]("avatarUrl").toOption.flatten)

  def deleteAvatar(): Future[Unit] = del("/api/me/avatar")

  def workspaces(): Future[List[Workspace]] = get[List[Workspace]]("/api/workspaces")

  def createWorkspace(name: String): Future[Workspace] = post[Workspace]("/api/workspaces", Json.obj("name" -> name.asJson))

  def setFavorite(id: String, favorite: Boolean): Future[Unit] =
    exec(dom.HttpMethod.PUT, s"/api/workspaces/$id/favorite", Some(Json.obj("favorite" -> favorite.asJson)))

  def renameWorkspace(id: String, name: String): Future[Unit] =
    exec(dom.HttpMethod.PATCH, s"/api/workspaces/$id", Some(Json.obj("name" -> name.asJson)))

  def pages(wsId: String): Future[List[Page]] = get[List[Page]](s"/api/workspaces/$wsId/pages")

  /** placement: afterId のページに対する置き方（api.ts の createPage と同じ） */
  def createPage(wsId: String, parentId: Option[String], title: String = "", afterId: Option[String] = None,
                 placement: Option[String] = None): Future[Page] =
    post[Page](s"/api/workspaces/$wsId/pages",
      obj("parentId" -> parentId.asJson, "title" -> title.asJson, "afterId" -> afterId.asJson, "placement" -> placement.asJson))

  /** 変える項目だけを渡す（例: Json.obj("title" -> "x".asJson)）. null は「消す」 */
  def updatePage(id: String, fields: Json): Future[Page] = patch[Page](s"/api/pages/$id", fields)

  def page(id: String, redirectOn401: Boolean = true): Future[Page] = get[Page](s"/api/pages/$id", redirectOn401)

  def deletePage(id: String): Future[Unit] = del(s"/api/pages/$id")

  def favoritePages(): Future[List[FavoritePage]] = get[List[FavoritePage]]("/api/favorites/pages")

  def setPageFavorite(id: String, favorite: Boolean): Future[Unit] =
    exec(dom.HttpMethod.PUT, s"/api/pages/$id/favorite", Some(Json.obj("favorite" -> favorite.asJson)))

  def directory(q: String): Future[List[DirectoryEntry]] = get[List[DirectoryEntry]](s"/api/directory${query("q" -> Some(q))}")

  // ---------------------------------------------------------------- APIトークン・監査ログ

  def tokens(): Future[List[TokenDto]] = get[List[TokenDto]]("/api/tokens")

  def createToken(req: CreateTokenReq): Future[CreatedTokenDto] =
    send[CreatedTokenDto](dom.HttpMethod.POST, "/api/tokens", req.asJson)

  def revokeToken(id: UUID): Future[Unit] = del(s"/api/tokens/$id")

  def audit(before: Option[Long], action: Option[String], limit: Int): Future[List[AuditDto]] =
    get[List[AuditDto]](s"/api/admin/audit${query("before" -> before.map(_.toString), "action" -> action, "limit" -> Some(limit.toString))}")

}
