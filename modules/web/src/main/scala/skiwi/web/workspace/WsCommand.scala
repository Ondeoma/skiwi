package skiwi.web.workspace

import org.scalajs.dom

/**
 * ⌘K のクイック切り替えなどからの操作（window の 'skiwi:command' イベント. detail が操作名）.
 * new-page / trash / toggle-side / notifications / search / new-workspace / edit-page / comments
 */
object WsCommand {

  val Event = "skiwi:command"

  def send(cmd: String): Unit =
    dom.window.dispatchEvent(new dom.CustomEvent(Event, new dom.CustomEventInit { detail = cmd }))

  def name(e: dom.Event): String = e.asInstanceOf[dom.CustomEvent].detail match {
    case s: String => s
    case _ => ""
  }

  def openPalette(): Unit = dom.window.dispatchEvent(new dom.Event("skiwi:palette"))

  def openShortcuts(): Unit = dom.window.dispatchEvent(new dom.Event("skiwi:shortcuts"))

}
