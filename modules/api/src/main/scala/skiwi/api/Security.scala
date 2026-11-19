package skiwi.api

import cats.data.{Kleisli, OptionT}
import cats.effect.*
import fs2.{Pipe, Pull, Stream}
import org.http4s.*
import org.http4s.headers.Authorization
import sbirds.http.Errors
import skiwi.core.ErrorCode

/** 全体にかける防御（CSRF、本文の大きさの上限、セキュリティヘッダ）。 */
object Security {

  private val safeMethods = Set(Method.GET, Method.HEAD, Method.OPTIONS)

  /** "https://wiki.example.com/" → "https://wiki.example.com" */
  private def originOf(url: String): Option[String] =
    Uri.fromString(url).toOption.flatMap(u =>
      (u.scheme, u.authority).match {
        case (Some(s), Some(a)) => Some(s"${s.value}://${a.renderString}".toLowerCase)
        case _ => None
      }
    )

  /**
   * Cookie で認証する更新系のリクエストは、同じオリジンの画面から来たものだけを受け付ける（CSRF 対策）。
   * SameSite=Lax では、同じ親ドメインの別サービス（same-site）からの POST を防げないため。
   *  - Origin があれば、PUBLIC_URL のオリジンか、Host ヘッダと同じオリジン（開発時のプロキシ）であること
   *  - Origin がなければ Sec-Fetch-Site を見る。どちらもなければブラウザ以外とみなして通す
   * API トークン（Authorization ヘッダ）で認証するリクエストは対象外。
   * devLogin=true（DEV_LOGIN）のときは、画面の開発サーバー（http://localhost:… ・http://127.0.0.1:…）からも受ける。
   */
  def csrf(publicUrl: String, cookieName: String, devLogin: Boolean = false)(routes: HttpRoutes[IO]): HttpRoutes[IO] = {
    val own = originOf(publicUrl)
    def devOrigin(o: String) = devLogin && Uri.fromString(o).toOption.exists(u =>
      u.scheme.exists(_.value.toString == "http") && u.host.exists(h => Set("localhost", "127.0.0.1").contains(h.value))
    )
    def sameOrigin(req: Request[IO]): Boolean =
      req.headers.get(org.typelevel.ci.CIString("Origin")).map(_.head.value.trim.toLowerCase) match {
        case Some(o) =>
          own.contains(o) || devOrigin(o) || req.headers.get(org.typelevel.ci.CIString("Host")).exists(h =>
            originOf(o).exists(_.endsWith(s"://${h.head.value.trim.toLowerCase}"))
          )
        case None =>
          req.headers.get(org.typelevel.ci.CIString("Sec-Fetch-Site")).map(_.head.value).forall(v =>
            v == "same-origin" || v == "none"
          )
      }
    Kleisli { (req: Request[IO]) =>
      val cookieAuth = req.cookies.exists(_.name == cookieName) && req.headers.get[Authorization].isEmpty
      if safeMethods.contains(req.method) || !cookieAuth || sameOrigin(req) then routes(req)
      else OptionT.liftF(Errors.respond(ErrorCode.CrossSiteRequest))
    }
  }

  /** 本文が上限を超えた（ErrorHandling で 413 にする） */
  final case class BodyTooLarge(limit: Long) extends Exception(s"リクエストの本文が大きすぎます（$limit バイトまで）")

  /** パスごとの本文の上限（None は各ルートに任せる）。サーバー（Ember）の既定には上限がないため、メモリを使い切らせないよう全体にかける。 */
  def bodyLimitFor(path: String): Option[Long] = {
    val MB = 1024L * 1024
    if path.matches("/api/pages/[^/]+/attachments") then None // 添付ファイル（AttachmentRoutes が 100MB で制限）
    else if path.matches("/api/pages/[^/]+/import") then Some(64 * MB)
    else if path.startsWith("/api/") || path.startsWith("/mcp") then Some(16 * MB)
    else Some(1 * MB) // /auth・/oauth・/scim など
  }

  /** 本文を limit バイトまでに限る（Content-Length のない chunked の本文も数える。超えたら BodyTooLarge） */
  def limitStream(limit: Long): Pipe[IO, Byte, Byte] = {
    def go(s: Stream[IO, Byte], seen: Long): Pull[IO, Byte, Unit] = s.pull.uncons.flatMap {
      case None => Pull.done
      case Some((chunk, rest)) =>
        val n = seen + chunk.size
        if n > limit then Pull.raiseError[IO](BodyTooLarge(limit)) else Pull.output(chunk) >> go(rest, n)
    }
    in => go(in, 0L).stream
  }

  def bodyLimit(routes: HttpRoutes[IO]): HttpRoutes[IO] = Kleisli { (req: Request[IO]) =>
    bodyLimitFor(req.uri.path.renderString) match {
      case None => routes(req)
      case Some(limit) if req.contentLength.exists(_ > limit) => OptionT.liftF(IO.raiseError(BodyTooLarge(limit)))
      case Some(limit) => routes(req.withBodyStream(req.body.through(limitStream(limit))))
    }
  }

  /** セキュリティヘッダ。各ルートが自分で付けたもの（添付の CSP など）は上書きしない。 */
  def headers(publicUrl: String)(app: HttpApp[IO]): HttpApp[IO] = {
    val base = List(
      "X-Content-Type-Options" -> "nosniff",
      // 公開リンクの URL（/s/{key}）が、外部へのリンクの Referer で漏れないように
      "Referrer-Policy" -> "strict-origin-when-cross-origin",
      // ほかのサイトの iframe に入れさせない（クリックジャッキング対策）
      "X-Frame-Options" -> "SAMEORIGIN"
    ) ++ Option.when(publicUrl.startsWith("https://"))("Strict-Transport-Security" -> "max-age=31536000")
    Kleisli { (req: Request[IO]) =>
      app(req).map { res =>
        base.foldLeft(res) { case (r, (k, v)) =>
          if r.headers.get(org.typelevel.ci.CIString(k)).isDefined then r else r.putHeaders(k -> v)
        }
      }
    }
  }
}
