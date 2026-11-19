package skiwi.api

import cats.effect.*
import io.circe.Encoder
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*

import org.http4s.client.Client

import java.net.URI
import scala.concurrent.duration.*
import skiwi.core.ErrorCode

/** URL のカード表示に使う情報（ページのタイトル・説明・画像・サイト名・アイコン） */
final case class UnfurlDto(
  url: String,
  title: Option[String],
  description: Option[String],
  image: Option[String],
  siteName: Option[String],
  icon: Option[String]) derives Encoder.AsObject

/**
 * リンクの展開: URL のページを取りに行き、OGP などからタイトル・説明・画像を読む（ログインユーザーのみ）。
 * http: NetGuard.client（UNFURL_ALLOW_PRIVATE でなければ、社内のアドレスには接続しない）。
 * 結果は1時間覚えておく。
 */
final class UnfurlRoutes(
  protected val sessions: SessionAuth,
  http: Client[IO],
  cache: Ref[IO, Map[String, (Long, UnfurlDto)]]) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "unfurl" => getUnfurl(req)
  }

  private def getUnfurl(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req) { _ =>
      req.uri.query.params.get("url").map(_.trim).filter(_.length <= 2000).flatMap(x =>
        scala.util.Try(URI.create(x)).toOption
      ) match {
        case None => err(ErrorCode.UnfurlUrlInvalid)
        case Some(u) =>
          IO.realTime.map(_.toMillis).flatMap { now =>
            cache.get.map(_.get(u.toString).filter(_._1 > now - 3600000).map(_._2)).flatMap {
              case Some(hit) => Ok(hit)
              case None =>
                fetch(u).flatMap {
                  // 取れなかったときも、URL とドメインだけのカードにできるように返す
                  case None => Ok(UnfurlDto(u.toString, None, None, None, Option(u.getHost), None))
                  case Some((finalUrl, html)) =>
                    val d = parseHtml(finalUrl, html).copy(url = u.toString)
                    cache.update(m => (if m.size > 2000 then Map.empty else m).updated(u.toString, now -> d)) *> Ok(d)
                }
            }
          }
      }
    }

  private val MaxBytes = 512 * 1024

  private enum Got {
    case Redirect(
      to: URI)
    case Page(
      html: String)
    case Nothing
  }

  private def fetch(
    u: URI,
    hops: Int = 0
  ): IO[Option[(URI, String)]] = {
    val scheme = Option(u.getScheme).map(_.toLowerCase)
    Uri.fromString(u.toString).toOption.filter(_ =>
      scheme.exists(Set("http", "https")) && Option(u.getHost).exists(_.nonEmpty)
    ) match {
      case None => IO.pure(None)
      case Some(uri) =>
        val req = Request[IO](Method.GET, uri).putHeaders(
          "User-Agent" -> "Mozilla/5.0 (compatible; skiwi-link-preview/1)",
          "Accept" -> "text/html,application/xhtml+xml",
          "Accept-Language" -> "ja,en;q=0.8"
        )
        http.run(req).use { r =>
          val ct = r.headers.get(
            org.typelevel.ci.CIString("Content-Type")
          ).map(_.head.value.toLowerCase).getOrElse("text/html")
          if r.status.code / 100 == 3 then
            IO.pure(r.headers.get[headers.Location].flatMap(l =>
              scala.util.Try(u.resolve(l.uri.renderString)).toOption
            ).fold(Got.Nothing)(Got.Redirect(_)))
          else if !r.status.isSuccess || !ct.contains("html") then IO.pure(Got.Nothing)
          else r.body.take(MaxBytes.toLong).compile.to(Array).map(b => Got.Page(new String(b, charset(ct))))
        }.timeout(12.seconds).handleError(_ => Got.Nothing).flatMap {
          case Got.Redirect(next) if hops < 3 => fetch(next, hops + 1) // 移動先も同じように確かめる
          case Got.Page(html) => IO.pure(Some((u, html)))
          case _ => IO.pure(None)
        }
    }
  }

  private def charset(
    ct: String
  ) =
    """charset=([\w-]+)""".r.findFirstMatchIn(ct.toLowerCase).flatMap(m =>
      scala.util.Try(java.nio.charset.Charset.forName(m.group(1))).toOption
    )
      .getOrElse(java.nio.charset.StandardCharsets.UTF_8)

  private def unescape(
    s: String
  ) =
    Html.unescape(s).replaceAll("&#(\\d+);", "").trim

  /** <meta property|name="key" content="…"> */
  private def meta(
    html: String,
    keys: String*
  ): Option[String] = {
    // 属性の正規表現を長い（壊れた）タグに使わない
    val tags = Html.tags(html, Set("meta")).filter(_.length <= 4000)
    def attr(
      tag: String,
      a: String
    ) = s"""(?i)\\b$a\\s*=\\s*(?:"([^"]*)"|'([^']*)')""".r.findFirstMatchIn(tag).map(m =>
      Option(m.group(1)).getOrElse(m.group(2))
    )
    keys.iterator.flatMap(k =>
      tags.find(t => attr(t, "property").orElse(attr(t, "name")).exists(_.equalsIgnoreCase(k))).flatMap(attr(
        _,
        "content"
      ))
    ).map(unescape).find(_.nonEmpty)
  }

  private def parseHtml(
    u: URI,
    html: String
  ): UnfurlDto = {
    val head = html.take(200000)
    val title = meta(head, "og:title", "twitter:title").orElse(Html.firstElementText(head, "title").map(t =>
      unescape(t.replaceAll("\\s+", " "))
    ))
    val abs = (x: String) =>
      scala.util.Try(u.resolve(x.trim).toString).toOption.filter(s =>
        s.startsWith("https://") || s.startsWith("http://")
      )
    val icon = Html.tags(head, Set("link")).filter(_.length <= 4000).find(t =>
      """(?i)\brel\s*=\s*["'][^"']*icon""".r.findFirstIn(t).isDefined
    )
      .flatMap(t => """\bhref\s*=\s*["']([^"']+)["']""".r.findFirstMatchIn(t).map(_.group(1))).flatMap(abs)
      .orElse(abs("/favicon.ico"))
    UnfurlDto(
      u.toString,
      title.map(_.take(300)),
      meta(head, "og:description", "twitter:description", "description").map(_.take(500)),
      meta(head, "og:image", "twitter:image", "og:image:url").flatMap(abs),
      meta(head, "og:site_name").orElse(Option(u.getHost)),
      icon
    )
  }

}
