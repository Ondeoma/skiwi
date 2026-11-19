package skiwi.web

import io.github.ondeoma.scalactive.routes.Router
import org.scalajs.dom

import scala.scalajs.js

/** 画面の移動 */
object Nav {

  /** 画面を移動する（履歴に積む） */
  def go(
    path: String
  ): Unit = Router.go(path)

  /** 画面を移動する（履歴を置き換える） */
  def replace(
    path: String
  ): Unit = {
    dom.window.history.replaceState(js.Object(), "", path)
    Router.href := dom.window.location.href
  }

  /**
   * URL だけを書き換え、画面は描き直さない（クエリ文字列の変更など）.
   * RouterComponent はクエリ文字列が変わると画面を作り直すため、表示の状態を URL に残すだけのときはこれを使う.
   */
  def replaceQuiet(
    path: String
  ): Unit = dom.window.history.replaceState(js.Object(), "", path)

  /** 今の URL のクエリ文字列の値 */
  def query(
    name: String
  ): Option[String] = Option(new dom.URL(dom.window.location.href).searchParams.get(name))

  def pageHref(
    workspaceId: String,
    pageId: String
  ): String = s"/w/$workspaceId/p/$pageId"

  /** 修飾キーなしの左クリックか（Ctrl/⌘クリックなどはブラウザに任せて新しいタブで開く） */
  def plainClick(
    e: dom.Event
  ): Boolean = e match {
    case m: dom.MouseEvent => !m.defaultPrevented && m.button == 0 && !m.ctrlKey && !m.metaKey && !m.shiftKey &&
      !m.altKey
    case _ => true
  }

  /**
   * 自前の <a :href> のクリックで画面を移動する（RouterLink の a にはスコープ付き CSS が届かないため）.
   * 例: <a :href="to" @click="Nav.link(event, to)">
   */
  def link(
    e: dom.Event,
    to: String
  ): Unit =
    if (plainClick(e)) {
      e.preventDefault()
      go(to)
    }

}
