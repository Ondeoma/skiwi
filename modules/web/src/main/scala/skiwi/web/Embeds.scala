package skiwi.web

import io.github.ondeoma.scalactive.components.ComponentManager.*
import io.github.ondeoma.scalactive.controllers.ComponentController
import io.github.ondeoma.scalactive.models.AddMethod
import org.scalajs.dom

import scala.scalajs.js

/**
 * Scalactive の部品を JS（エディタのノードビューなど）から描画するための登録.
 * mount(el, params) は描画して、片付ける関数を返す.
 */
object Embeds {

  type Mount = (dom.Element, js.Dictionary[js.Any]) => (() => Unit)

  private var registry: Map[String, Mount] = Map.empty

  def register(name: String)(m: Mount): Unit = registry += name -> m

  def open(name: String, el: dom.Element, params: js.Dictionary[js.Any]): () => Unit = registry.get(name) match {
    case Some(m) => m(el, params)
    case None =>
      dom.console.error(s"mountView: unknown view $name")
      () => ()
  }

  /** 部品を el に描画し、片付ける関数を返す */
  def render(el: dom.Element)(cmp: (dom.HTMLElement, AddMethod) ?=> ComponentController[?]): () => Unit =
    mount(el, (p, am) => cmp(using p, am)) match {
      case Right(h) => () => h.unmount()
      case Left(e) =>
        dom.console.error(s"mount: $e")
        () => ()
    }

}
