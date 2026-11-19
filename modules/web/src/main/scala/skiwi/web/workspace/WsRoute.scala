package skiwi.web.workspace

import io.github.ondeoma.scalactive.routes.Router
import org.scalajs.dom

import scala.scalajs.js

/** URL（/w/:wid/p/:pid）から開いているワークスペース・ページを読む */
object WsRoute {

  private val Re = """^/w/([^/]+)(?:/p/([^/]+))?/?$""".r

  def path(
    href: String
  ): String = new dom.URL(href).pathname

  /** (wid, pid) */
  def parse(
    href: String
  ): (Option[String], Option[String]) = path(href) match {
    case Re(w, p) => (Some(js.URIUtils.decodeURIComponent(w)), Option(p).map(js.URIUtils.decodeURIComponent))
    case _ => (None, None)
  }

  def wid(
    href: String
  ): Option[String] = parse(href)._1

  def pid(
    href: String
  ): Option[String] = parse(href)._2

  /** 今開いているページ（リアクティブに読む） */
  def currentPid: Option[String] = pid(Router.href.v)

  /** 今の URL のパスが path か（RouterLink の router-link-active と同じ判定. "/" は完全一致だけ） */
  def isActive(
    p: String
  ): Boolean = {
    val cur = path(Router.href.v).stripSuffix("/")
    val t = p.stripSuffix("/")
    cur == t || (t.nonEmpty && cur.startsWith(t + "/"))
  }

}
