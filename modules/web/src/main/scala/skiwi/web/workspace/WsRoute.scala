package skiwi.web.workspace

import io.github.ondeoma.scalactive.routes.Router
import org.scalajs.dom

/** URL（/w/:wid/p/:pid）から開いているワークスペース・ページを読む */
object WsRoute {

  private val Re = """^/w/([^/]+)(?:/p/([^/]+))?/?$""".r

  /** ワークスペース・ページの ID の形（UUID）. URL の ID はそのまま API のパスに入るので、ほかの形（「/」「?」を含むものなど）は受けない */
  private val IdRe = """^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""".r

  def isId(s: String): Boolean = IdRe.matches(s)

  def path(href: String): String = new dom.URL(href).pathname

  /** (wid, pid). ID の形でないものが入っていれば、どちらも None（開いていない扱い） */
  def parse(href: String): (Option[String], Option[String]) = path(href) match {
    case Re(w, p) if isId(w) && (p == null || isId(p)) => (Some(w), Option(p))
    case _ => (None, None)
  }

  def wid(href: String): Option[String] = parse(href)._1

  def pid(href: String): Option[String] = parse(href)._2

  /** 今開いているページ（リアクティブに読む） */
  def currentPid: Option[String] = pid(Router.href.v)

  /** 今の URL のパスが path か（RouterLink の router-link-active と同じ判定. "/" は完全一致だけ） */
  def isActive(p: String): Boolean = {
    val cur = path(Router.href.v).stripSuffix("/")
    val t = p.stripSuffix("/")
    cur == t || (t.nonEmpty && cur.startsWith(t + "/"))
  }

}
