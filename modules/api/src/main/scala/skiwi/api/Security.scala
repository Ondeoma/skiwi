package skiwi.api

import cats.effect.*
import org.http4s.*
import sbirds.http.{BodyLimit, Csrf, SecurityHeaders}
import sbirds.shared.AppError
import skiwi.core.ErrorCode

/** 全体にかける防御（CSRF、本文の大きさの上限、セキュリティヘッダ）。 */
object Security {

  /**
   * Cookie で認証する更新系のリクエストは、同じオリジンの画面から来たものだけを受け付ける（CSRF 対策）。
   * SameSite=Lax では、同じ親ドメインの別サービス（same-site）からの POST を防げないため。
   * 確かめ方は sbirds.http.Csrf:
   *  - Sec-Fetch-Site があれば same-origin か none であること
   *  - Origin（なければ Referer）があれば、PUBLIC_URL のオリジンか、Host ヘッダと同じオリジン（開発時のプロキシ）であること
   *  - どれもなければブラウザ以外とみなして通す
   * API トークン（Authorization ヘッダ）で認証するリクエストは対象外。
   * devLogin=true（DEV_LOGIN）のときは、画面の開発サーバー（http://localhost:… ・http://127.0.0.1:…）からも受ける。
   */
  def csrf(publicUrl: String, cookieName: String, devLogin: Boolean = false)(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    Csrf.routes(
      Csrf.Policy(publicUrl, devLocalhost = devLogin).copy(hostFallback = true, safeMethods = Csrf.SafeWithOptions),
      Csrf.cookieAuth(cookieName),
      ErrorCode.CrossSiteRequest
    )(routes)

  /** パスごとの本文の上限（None は各ルートに任せる）。サーバー（Ember）の既定には上限がないため、メモリを使い切らせないよう全体にかける。 */
  def bodyLimitFor(path: String): Option[Long] = {
    val MB = 1024L * 1024
    if path.matches("/api/pages/[^/]+/attachments") then None // 添付ファイル（AttachmentRoutes が 100MB で制限）
    else if path.matches("/api/pages/[^/]+/import") then Some(64 * MB)
    else if path.startsWith("/api/") || path.startsWith("/mcp") then Some(16 * MB)
    else Some(1 * MB) // /auth・/oauth・/scim など
  }

  /** 本文の上限を全体にかける（sbirds.http.BodyLimit. 超えたら RequestTooLarge の 413）. */
  def bodyLimit(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    BodyLimit(
      req => bodyLimitFor(req.uri.path.renderString),
      (_, limit) => AppError(ErrorCode.RequestTooLarge, s"リクエストの本文が大きすぎます（$limit バイトまで）")
    )(routes)

  /**
   * セキュリティヘッダ（sbirds.http.SecurityHeaders）。各ルートが自分で付けたもの（添付の CSP など）は上書きしない。
   * Referrer-Policy: 公開リンクの URL（/s/{key}）が、外部へのリンクの Referer で漏れないように。
   * X-Frame-Options: ほかのサイトの iframe に入れさせない（クリックジャッキング対策）。CSP は画面の配信が自分で付ける。
   */
  def headers(publicUrl: String)(app: HttpApp[IO]): HttpApp[IO] =
    SecurityHeaders.app(SecurityHeaders.Policy(
      hsts = SecurityHeaders.hsts(publicUrl.startsWith("https://")),
      frameOptions = Some("SAMEORIGIN"),
      csp = None,
      keepExisting = true
    ))(app)
}
