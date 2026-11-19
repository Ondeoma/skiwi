package skiwi.web.workspace

import org.scalajs.dom

import scala.scalajs.js

/** 表示している間だけ付けるイベントリスナー（mounted で on、unmounted で clear） */
final class Listeners {

  private var offs: List[() => Unit] = Nil

  def on[E <: dom.Event](
    target: dom.EventTarget,
    tpe: String,
    capture: Boolean = false
  )(
    f: E => Unit
  ): Unit = {
    val h: js.Function1[dom.Event, Unit] = (e: dom.Event) => f(e.asInstanceOf[E])
    target.addEventListener(tpe, h, capture)
    offs ::= (() => target.removeEventListener(tpe, h, capture))
  }

  def timer(
    ms: Int
  )(
    f: => Unit
  ): Unit = {
    val id = dom.window.setInterval(() => f, ms)
    offs ::= (() => dom.window.clearInterval(id))
  }

  def add(
    off: () => Unit
  ): Unit = offs ::= off

  def clear(): Unit = {
    offs.foreach(_())
    offs = Nil
  }

}
