package skiwi.web

import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom
import skiwi.web.views.*

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/**
 * Vue の画面から Scalactive の画面を描画する入口（frontend/src/scalactive/ScalactiveView.vue が呼ぶ）.
 * 移行中は、vue-router のルートごとに Scalactive の画面へ置き換えていく.
 */
object Main {

  /** nameの画面をelに描画し、片付ける関数を返します. paramsはルートのパラメーター. */
  @JSExportTopLevel("mountView")
  def mountView(name: String, el: dom.Element, params: js.Dictionary[String]): js.Function0[Unit] = {
    val r = name match {
      case "tokens" => mount(el) { TokensView() }
      case "audit" => mount(el) { AuditView() }
      case _ => Left(s"unknown view: $name")
    }
    r match {
      case Right(h) => () => h.unmount()
      case Left(e) =>
        dom.console.error(s"mountView($name): $e")
        () => ()
    }
  }

}
