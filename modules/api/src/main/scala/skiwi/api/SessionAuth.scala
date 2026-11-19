package skiwi.api

import cats.effect.*
import org.http4s.*
import org.http4s.headers.Authorization
import sbirds.auth.Crypto
import skiwi.core.*
import skiwi.db.{OpsStore, Store}
import sbirds.shared.AppError

import java.time.Instant
import scala.concurrent.duration.*

final case class ApiConfig(
  adminGroups: Set[String],
  cookieSecure: Boolean,
  sessionTtl: FiniteDuration = 7.days,
  cookieName: String = "skiwi_session",
  /** SCIMを使う場合、ログイン時のgroupsクレームでグループ所属を上書きしない（SCIMを正とする）。 */
  scimManagesGroups: Boolean = false,
  /** ゲスト向けの公開リンクを使えるようにする（PUBLIC_SHARING=false で無効）。 */
  publicSharing: Boolean = true)

/** 読み取り専用(read)スコープのAPIトークンで、更新系のリクエストが来た。 */
val ReadOnlyTokenError: AppError = AppError(ErrorCode.ApiTokenReadOnly)

/** 認証: Cookieセッション、または `Authorization: Bearer <APIトークン>`。 */
final class SessionAuth(
  store: Store,
  ops: OpsStore,
  cfg: ApiConfig) {

  private val safeMethods = Set(Method.GET, Method.HEAD, Method.OPTIONS)

  /** POST だが読むだけのもの（読み取り専用のトークンでも使える） */
  private val readOnlyPosts = Set("/api/pages/lookup")
  private def isRead(
    req: Request[IO]
  ) =
    safeMethods.contains(req.method) || (req.method == Method.POST && readOnlyPosts.contains(req.uri.path.renderString))

  private def isAdmin(
    u: User,
    gs: List[Group]
  ) = AgentAccounts.isAdmin(u.sub, gs.map(_.externalId), cfg.adminGroups)

  def fromRequest(
    req: Request[IO]
  ): IO[Option[AuthSession]] =
    bearer(req) match {
      case Some(token) =>
        ops.findToken(Crypto.sha256(token), Instant.now()).flatMap {
          case None => IO.pure(None)
          // 外部サービス連携のトークンは、ページの一括参照にだけ使える（それ以外は認証なしと同じ扱い）
          case Some((_, _, _, "lookup"))
              if !(req.method == Method.POST && readOnlyPosts.contains(req.uri.path.renderString)) =>
            IO.pure(None)
          case Some((u, gs, tid, scope)) =>
            val ro = scope != "write"
            if ro && !isRead(req) then IO.raiseError(ReadOnlyTokenError)
            // API トークンには管理者の権限を付けない（漏れたトークンで管理の操作・全ページの閲覧をさせない）
            else IO.pure(Some(AuthSession(u, gs, isAdmin = false, AuthVia.Token(tid, ro))))
        }
      case None =>
        req.cookies.find(_.name == cfg.cookieName).map(_.content) match {
          case None => IO.pure(None)
          case Some(token) =>
            store.findSession(Crypto.sha256(token), Instant.now()).map(_.map(
              (
                u,
                gs
              ) => AuthSession(u, gs, isAdmin(u, gs))
            ))
        }
    }

  /** 別のユーザーとしての権限確認用（メンションの通知先が閲覧できるかなど）。無効なユーザーは None。 */
  def sessionFor(
    userId: java.util.UUID
  ): IO[Option[AuthSession]] =
    store.userWithGroups(userId).map(_.map(
      (
        u,
        gs
      ) => AuthSession(u, gs, isAdmin(u, gs))
    ))

  def adminGroups: Set[String] = cfg.adminGroups
  def cookieSecure: Boolean = cfg.cookieSecure

  private def bearer(
    req: Request[IO]
  ): Option[String] =
    req.headers.get[Authorization].collect {
      case Authorization(Credentials.Token(AuthScheme.Bearer, t)) => t
    }

  /** 新しいセッションを発行し、Set-Cookie用のクッキーを返す。 */
  def issue(
    user: User,
    idToken: Option[String] = None
  ): IO[ResponseCookie] = {
    val token = Crypto.randomToken()
    store.createSession(
      Crypto.sha256(token),
      user.id,
      Instant.now().plusSeconds(cfg.sessionTtl.toSeconds),
      idToken
    ).as(cookie(token, cfg.sessionTtl.toSeconds))
  }

  /** sbirds のアプリの表示名（ログインしたときの ID トークンにある. API キーや開発用のログインでは空）. */
  def appNames(
    req: Request[IO]
  ): IO[Map[String, String]] =
    req.cookies.find(_.name == cfg.cookieName) match {
      case Some(c) =>
        store.sessionIdToken(Crypto.sha256(c.content)).map(_.fold(Map.empty)(sbirds.auth.AppNames.fromIdToken))
      case None => IO.pure(Map.empty)
    }

  /** セッションを消す. IdP からもログアウトするための id_token（OIDC でログインしていたとき）と、Cookie を消す指示を返す. */
  def revoke(
    req: Request[IO]
  ): IO[(Option[String], ResponseCookie)] =
    req.cookies.find(_.name == cfg.cookieName) match {
      case Some(c) => store.deleteSession(Crypto.sha256(c.content)).map(_ -> cookie("", 0))
      case None => IO.pure(None -> cookie("", 0))
    }

  private def cookie(
    value: String,
    maxAge: Long
  ): ResponseCookie =
    ResponseCookie(
      cfg.cookieName,
      value,
      maxAge = Some(maxAge),
      path = Some("/"),
      httpOnly = true,
      secure = cfg.cookieSecure,
      sameSite = Some(SameSite.Lax)
    )
}
