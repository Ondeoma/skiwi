package skiwi.api

import cats.effect.*
import io.circe.Encoder
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.*

/** URL のカード表示に使う情報（ページのタイトル・説明・画像・サイト名・アイコン） */
final case class UnfurlDto(url: String, title: Option[String], description: Option[String], image: Option[String], siteName: Option[String], icon: Option[String]) derives Encoder.AsObject

/** リンクの展開: URL のページを取りに行き、OGP などからタイトル・説明・画像を読む（ログインユーザーのみ）。
  * サーバーから社内のアドレスに接続させない（allowPrivate=false のとき。社内のサービスをカードにするなら true）。
  * 結果は1時間覚えておく。 */
final class UnfurlRoutes(protected val sessions: SessionAuth, allowPrivate: Boolean, cache: Ref[IO, Map[String, (Long, UnfurlDto)]]) extends RouteSupport:

  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
  private val MaxBytes = 512 * 1024

  /** 接続してよい URL か（http/https、社内のアドレスでない） */
  private def allowed(u: URI): IO[Boolean] =
    val scheme = Option(u.getScheme).map(_.toLowerCase)
    if !scheme.exists(Set("http", "https")) || Option(u.getHost).forall(_.isEmpty) then IO.pure(false)
    else if allowPrivate then IO.pure(true)
    else NetGuard.publicHost(u.getHost)

  private def fetch(u: URI, hops: Int = 0): IO[Option[(URI, String)]] =
    allowed(u).flatMap {
      case false => IO.pure(None)
      case true =>
        val req = HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(8))
          .header("User-Agent", "Mozilla/5.0 (compatible; skiwi-link-preview/1)").header("Accept", "text/html,application/xhtml+xml")
          .header("Accept-Language", "ja,en;q=0.8").GET().build()
        IO.fromCompletableFuture(IO(client.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream()))).flatMap { r =>
          val code = r.statusCode()
          if code / 100 == 3 && hops < 3 then
            IO.blocking(r.body().close()) *>
              r.headers().firstValue("location").map(l => Option(u.resolve(l))).orElse(None).fold(IO.pure(None))(next => fetch(next, hops + 1))
          else if code / 100 != 2 || !r.headers().firstValue("content-type").orElse("text/html").contains("html") then IO.blocking(r.body().close()).as(None)
          else
            IO.blocking {
              val in = r.body()
              try new String(in.readNBytes(MaxBytes), charset(r.headers().firstValue("content-type").orElse("")))
              finally in.close()
            }.map(html => Some((u, html)))
        }.timeout(12.seconds).handleError(_ => None)
    }

  private def charset(ct: String) =
    """charset=([\w-]+)""".r.findFirstMatchIn(ct.toLowerCase).flatMap(m => scala.util.Try(java.nio.charset.Charset.forName(m.group(1))).toOption)
      .getOrElse(java.nio.charset.StandardCharsets.UTF_8)

  private def unescape(s: String) =
    Html.unescape(s).replaceAll("&#(\\d+);", "").trim

  /** <meta property|name="key" content="…"> */
  private def meta(html: String, keys: String*): Option[String] =
    // 属性の正規表現を長い（壊れた）タグに使わない
    val tags = Html.tags(html, Set("meta")).filter(_.length <= 4000)
    def attr(tag: String, a: String) = s"""(?i)\\b$a\\s*=\\s*(?:"([^"]*)"|'([^']*)')""".r.findFirstMatchIn(tag).map(m => Option(m.group(1)).getOrElse(m.group(2)))
    keys.iterator.flatMap(k => tags.find(t => attr(t, "property").orElse(attr(t, "name")).exists(_.equalsIgnoreCase(k))).flatMap(attr(_, "content"))).map(unescape).find(_.nonEmpty)

  private def parseHtml(u: URI, html: String): UnfurlDto =
    val head = html.take(200000)
    val title = meta(head, "og:title", "twitter:title").orElse(Html.firstElementText(head, "title").map(t => unescape(t.replaceAll("\\s+", " "))))
    val abs = (x: String) => scala.util.Try(u.resolve(x.trim).toString).toOption.filter(s => s.startsWith("https://") || s.startsWith("http://"))
    val icon = Html.tags(head, Set("link")).filter(_.length <= 4000).find(t => """(?i)\brel\s*=\s*["'][^"']*icon""".r.findFirstIn(t).isDefined)
      .flatMap(t => """\bhref\s*=\s*["']([^"']+)["']""".r.findFirstMatchIn(t).map(_.group(1))).flatMap(abs)
      .orElse(abs("/favicon.ico"))
    UnfurlDto(u.toString, title.map(_.take(300)), meta(head, "og:description", "twitter:description", "description").map(_.take(500)),
      meta(head, "og:image", "twitter:image", "og:image:url").flatMap(abs), meta(head, "og:site_name").orElse(Option(u.getHost)), icon)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "unfurl" =>
      authed(req) { _ =>
        req.uri.query.params.get("url").map(_.trim).filter(_.length <= 2000).flatMap(x => scala.util.Try(URI.create(x)).toOption) match
          case None => err(Status.BadRequest, "invalid url")
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
