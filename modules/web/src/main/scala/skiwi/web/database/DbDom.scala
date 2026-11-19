package skiwi.web.database

import org.scalajs.dom
import skiwi.web.Nav

import scala.scalajs.js

/** 画面の部品で使う DOM の小さな補助 */
object DbDom {

  def value(e: dom.Event): String = e.target.asInstanceOf[js.Dynamic].value.asInstanceOf[String]

  def checked(e: dom.Event): Boolean = e.target.asInstanceOf[js.Dynamic].checked.asInstanceOf[Boolean]

  def composing(e: dom.Event): Boolean =
    e.asInstanceOf[js.Dynamic].isComposing.asInstanceOf[js.UndefOr[Boolean]].getOrElse(false)

  /** ページへのリンク（RouterLink と同じ: 修飾キーなしの左クリックは画面内で移動） */
  def link(e: dom.Event, href: String): Unit = e match {
    case m: dom.MouseEvent
        if !m.defaultPrevented && m.button == 0 && !m.ctrlKey && !m.metaKey && !m.shiftKey && !m.altKey =>
      m.preventDefault()
      Nav.go(href)
    case _ => ()
  }

  def pageHref(wid: String, id: String): String = Nav.pageHref(wid, id)

}
