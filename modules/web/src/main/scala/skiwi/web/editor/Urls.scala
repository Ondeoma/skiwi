package skiwi.web.editor

import org.scalajs.dom

import scala.concurrent.Future
import scala.scalajs.js
import scala.util.Try

/**
 * 本文の属性などに入った URL の検査. 本文は同時編集（Yjs）や取り込みで好きな値にできるため、
 * 画面に出す直前に必ずここを通す（javascript: などを href・src に入れない）.
 */
object SafeUrl {

  private def parse(u: Any): Option[dom.URL] = u match {
    case s: String if s.trim.nonEmpty => Try(new dom.URL(s.trim, dom.window.location.origin)).toOption
    case _ => None
  }

  /** リンクとして出してよい URL（http・https・mailto、または同じサイトのパス） */
  def link(u: Any): Option[String] =
    parse(u).filter(p => Set("http:", "https:", "mailto:").contains(p.protocol)).map(_ => u.toString.trim)

  /** 外部のページとして iframe に埋め込める URL（https で、skiwi 自身のオリジンではないもの） */
  def embed(u: Any): Option[String] =
    parse(u).filter(p => p.protocol == "https:" && p.origin != dom.window.location.origin).map(_.toString)

  private val Attachment = "(?i)^/api/(public/[\\w-]+/)?attachments/[0-9a-f-]{36}$".r

  /** 添付ファイルのパス（/api/attachments/{id}、公開ページでは /api/public/{key}/attachments/{id}）か */
  def isAttachmentPath(u: Any): Boolean = u match {
    case s: String => Attachment.matches(s)
    case _ => false
  }

  /** 本文の属性に入った添付ファイルを取りに行く（添付ファイルのパス以外へは、ログインの Cookie 付きで送らない） */
  def fetchAttachment(u: Any): Future[dom.Response] =
    if (!isAttachmentPath(u)) Future.failed(new Exception("添付ファイルのパスではありません"))
    else dom.fetch(u.toString, skiwi.web.Api.init(dom.HttpMethod.GET)).toFuture

}

/** 埋め込み（iframe）の URL. 対応しているサービスの共有 URL は、埋め込み用の URL にする */
object EmbedUrl {

  private val YouTubePath = "^/(?:shorts|embed|live)/([\\w-]+).*".r
  private val VimeoPath   = "^/(\\d+).*".r
  private val FigmaPath   = "^/(file|design|proto|board)/.*".r
  private val GDocsPath   = "^/(document|spreadsheets|presentation|forms)/d/(e/)?([\\w-]+).*".r
  private val DrivePath   = "^/file/d/([\\w-]+).*".r
  private val LoomPath    = "^/(?:share|embed)/(\\w+).*".r
  private val MiroPath    = "^/app/board/([^/]+).*".r
  private val CodepenPath = "^/([^/]+)/(?:pen|embed)/(\\w+).*".r
  private val SpotifyPath = "^/(track|album|playlist|episode|show)/(\\w+).*".r

  /** 埋め込みに対応しているサービスの URL なら、埋め込み用の URL */
  def known(raw: String): Option[String] =
    Try(new dom.URL(raw.trim)).toOption.filter(_.protocol == "https:").flatMap { u =>
      val host = u.hostname.stripPrefix("www.")
      val path = u.pathname
      (host, path) match {
        case ("youtube.com" | "m.youtube.com", _) =>
          Option(u.searchParams.get("v")).filter(_.nonEmpty).orElse(path match { case YouTubePath(id) => Some(id); case _ => None })
            .map(id => s"https://www.youtube-nocookie.com/embed/$id")
        case ("youtu.be", p) if p.length > 1 => Some(s"https://www.youtube-nocookie.com/embed/${p.drop(1)}")
        case ("vimeo.com", VimeoPath(id)) => Some(s"https://player.vimeo.com/video/$id")
        // Figma（ファイル・デザイン・プロトタイプ・FigJam）
        case ("figma.com", FigmaPath(_)) => Some(s"https://www.figma.com/embed?embed_host=skiwi&url=${js.URIUtils.encodeURIComponent(u.toString)}")
        // Google ドキュメント・スプレッドシート・スライド・フォーム・ドライブ
        case ("docs.google.com", GDocsPath(kind, e, id)) =>
          val base = s"https://docs.google.com/$kind/d/${Option(e).getOrElse("")}$id"
          Some(kind match {
            case "presentation" => s"$base/embed"
            case "forms" => s"$base/viewform?embedded=true"
            case _ => s"$base/preview"
          })
        case ("drive.google.com", DrivePath(id)) => Some(s"https://drive.google.com/file/d/$id/preview")
        case ("loom.com", LoomPath(id)) => Some(s"https://www.loom.com/embed/$id")
        case ("miro.com", MiroPath(id)) => Some(s"https://miro.com/app/live-embed/$id/")
        case ("codepen.io", CodepenPath(user, id)) => Some(s"https://codepen.io/$user/embed/$id?default-tab=result")
        case ("open.spotify.com", SpotifyPath(kind, id)) => Some(s"https://open.spotify.com/embed/$kind/$id")
        case _ => None
      }
    }

  /** 共有 URL を埋め込み用の URL にする. https 以外は拒否. 対応サービス以外はそのまま埋め込む */
  def toEmbed(raw: String): Option[String] =
    known(raw).orElse(Try(new dom.URL(raw.trim)).toOption.filter(_.protocol == "https:").flatMap(u => SafeUrl.embed(u.toString)))

}
