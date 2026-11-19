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

/** API の呼び出しでのエラー. body: エラー応答の本文（JSON） */
final case class ApiError(
  status: Int,
  message: String,
  body: Option[Json] = None) extends Exception(message)

/**
 * バックエンドの API（401 はログインへ、エラーは {"error": ...}）. エディタ（JS）の取得・送信もここを通す（editor.EditorLib）.
 * 画面ごとの API は、この get・send・del などを使って各画面のファイル（XxxApi）に書く.
 */
object Api {

  /** fetch の設定（同じオリジンの Cookie を付ける. body は JSON の文字列）. */
  def init(
    method: dom.HttpMethod,
    body: Option[String] = None
  ): dom.RequestInit = {
    val i = new dom.RequestInit {}
    i.method = method
    i.credentials = dom.RequestCredentials.`same-origin`
    body.foreach { b =>
      i.headers = js.Dictionary("Content-Type" -> "application/json")
      i.body = b
    }
    i
  }

  /**
   * redirectOn401=false: 未ログインでもログイン画面へ飛ばさず ApiError(401) にする（ゲスト表示など）.
   * body: JSON（Json）・ファイル（dom.Blob・dom.FormData）
   */
  def request(
    method: dom.HttpMethod,
    url: String,
    body: Option[Json | dom.Blob | dom.FormData] = None,
    redirectOn401: Boolean = true
  ): Future[Option[Json]] = {
    val req = init(method, body.collect { case j: Json => j.noSpaces })
    body.foreach {
      case f: dom.FormData => req.body = f
      case b: dom.Blob =>
        req.headers = js.Dictionary("Content-Type" -> b.`type`)
        req.body = b
      case _: Json => ()
    }
    dom.fetch(url, req).toFuture.flatMap { res =>
      if (res.status == 401 && redirectOn401) {
        // 未ログイン: IdPへ
        dom.window.location.href = "/auth/login"
        Future.failed(ApiError(401, "ログインしてください"))
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

  def get[A: Decoder](
    url: String,
    redirectOn401: Boolean = true
  ): Future[A] =
    request(dom.HttpMethod.GET, url, redirectOn401 = redirectOn401).map(as[A])

  def post[A: Decoder](
    url: String,
    body: Json = Json.obj()
  ): Future[A] = send[A](dom.HttpMethod.POST, url, body)

  def put[A: Decoder](
    url: String,
    body: Json
  ): Future[A] = send[A](dom.HttpMethod.PUT, url, body)

  def patch[A: Decoder](
    url: String,
    body: Json
  ): Future[A] = send[A](dom.HttpMethod.PATCH, url, body)

  def send[A: Decoder](
    method: dom.HttpMethod,
    url: String,
    body: Json
  ): Future[A] =
    request(method, url, Some(body)).map(as[A])

  /** 応答の本文を使わない呼び出し */
  def exec(
    method: dom.HttpMethod,
    url: String,
    body: Option[Json] = None
  ): Future[Unit] =
    request(method, url, body).map(_ => ())

  def del(
    url: String
  ): Future[Unit] = exec(dom.HttpMethod.DELETE, url)

  def as[A: Decoder](
    j: Option[Json]
  ): A = j.getOrElse(Json.Null).as[A].fold(throw _, identity)

  /** 応答がエラー（権限なし・削除済みなど）なら None. 通信エラーなどはそのまま失敗にする */
  def okOrNone[A](
    f: Future[A]
  ): Future[Option[A]] =
    f.map(Some(_)).recover { case _: ApiError => None }

  def enc(
    s: String
  ): String = js.URIUtils.encodeURIComponent(s)

  /** クエリ文字列（値が None のものは付けない） */
  def query(
    params: (String, Option[String])*
  ): String = {
    val q = params.collect { case (k, Some(v)) => s"$k=${enc(v)}" }
    if (q.isEmpty) "" else q.mkString("?", "&", "")
  }

  /** null の項目を落とした JSON（PATCH で「送らない」を表す） */
  def obj(
    fields: (String, Json)*
  ): Json = Json.obj(fields*).dropNullValues

  // ---------------------------------------------------------------- 自分・ワークスペース・ページ

  def me(): Future[Me] = get[Me]("/api/me")

  /** ログアウトして、次に開く URL（IdP のログアウト画面か「/」）を返す. */
  def logout(): Future[String] = post[Json]("/auth/logout").map(_.hcursor.get[String]("redirect").getOrElse("/"))

  def setAvatar(
    image: dom.Blob
  ): Future[Option[String]] =
    request(dom.HttpMethod.PUT, "/api/me/avatar", Some(image)).map(j =>
      as[Json](j).hcursor.get[Option[String]]("avatarUrl").toOption.flatten
    )

  def deleteAvatar(): Future[Unit] = del("/api/me/avatar")

  def workspaces(): Future[List[Workspace]] = get[List[Workspace]]("/api/workspaces")

  def createWorkspace(
    name: String
  ): Future[Workspace] = post[Workspace]("/api/workspaces", Json.obj("name" -> name.asJson))

  def setFavorite(
    id: String,
    favorite: Boolean
  ): Future[Unit] =
    exec(dom.HttpMethod.PUT, s"/api/workspaces/$id/favorite", Some(Json.obj("favorite" -> favorite.asJson)))

  def renameWorkspace(
    id: String,
    name: String
  ): Future[Unit] =
    exec(dom.HttpMethod.PATCH, s"/api/workspaces/$id", Some(Json.obj("name" -> name.asJson)))

  def pages(
    wsId: String
  ): Future[List[Page]] = get[List[Page]](s"/api/workspaces/$wsId/pages")

  /** placement: afterId のページに対する置き方 */
  def createPage(
    wsId: String,
    parentId: Option[String],
    title: String = "",
    afterId: Option[String] = None,
    placement: Option[String] = None
  ): Future[Page] =
    post[Page](
      s"/api/workspaces/$wsId/pages",
      obj(
        "parentId" -> parentId.asJson,
        "title" -> title.asJson,
        "afterId" -> afterId.asJson,
        "placement" -> placement.asJson
      )
    )

  /** 変える項目だけを渡す（例: Json.obj("title" -> "x".asJson)）. null は「消す」 */
  def updatePage(
    id: String,
    fields: Json
  ): Future[Page] = patch[Page](s"/api/pages/$id", fields)

  def page(
    id: String,
    redirectOn401: Boolean = true
  ): Future[Page] = get[Page](s"/api/pages/$id", redirectOn401)

  def deletePage(
    id: String
  ): Future[Unit] = del(s"/api/pages/$id")

  def favoritePages(): Future[List[FavoritePage]] = get[List[FavoritePage]]("/api/favorites/pages")

  def setPageFavorite(
    id: String,
    favorite: Boolean
  ): Future[Unit] =
    exec(dom.HttpMethod.PUT, s"/api/pages/$id/favorite", Some(Json.obj("favorite" -> favorite.asJson)))

  def directory(
    q: String
  ): Future[List[DirectoryEntry]] = get[List[DirectoryEntry]](s"/api/directory${query("q" -> Some(q))}")

  // ---------------------------------------------------------------- APIトークン・監査ログ

  def tokens(): Future[List[TokenDto]] = get[List[TokenDto]]("/api/tokens")

  def createToken(
    req: CreateTokenReq
  ): Future[CreatedTokenDto] =
    send[CreatedTokenDto](dom.HttpMethod.POST, "/api/tokens", req.asJson)

  def revokeToken(
    id: UUID
  ): Future[Unit] = del(s"/api/tokens/$id")

  def audit(
    before: Option[Long],
    action: Option[String],
    limit: Int
  ): Future[List[AuditDto]] =
    get[List[AuditDto]](
      s"/api/admin/audit${query("before" -> before.map(_.toString), "action" -> action, "limit" -> Some(limit.toString))}"
    )

}
