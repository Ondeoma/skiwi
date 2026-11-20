package skiwi.web

import io.github.ondeoma.scalactive.routes.Router
import org.scalajs.dom

/** 画面の移動 */
object Nav {

  /** 画面を移動する（履歴に積む） */
  def go(path: String): Unit = Router.go(path)

  def pageHref(workspaceId: String, pageId: String): String = s"/w/$workspaceId/p/$pageId"

  /** 修飾キーなしの左クリックか（Ctrl/⌘クリックなどはブラウザに任せて新しいタブで開く） */
  def plainClick(e: dom.Event): Boolean = e match {
    case m: dom.MouseEvent => !m.defaultPrevented && m.button == 0 && !m.ctrlKey && !m.metaKey && !m.shiftKey &&
      !m.altKey
    case _ => true
  }

  /**
   * 自前の <a :href> のクリックで画面を移動する（RouterLink には class・data-testid・title などの属性を渡せないため）.
   * 例: <a :href="to" @click="Nav.link(event, to)">
   */
  def link(e: dom.Event, to: String): Unit =
    if (plainClick(e)) {
      e.preventDefault()
      go(to)
    }

}
