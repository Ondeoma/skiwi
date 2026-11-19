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
