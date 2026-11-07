package skiwi.web

import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

/**
 * Vue 側（frontend/src/scalactive/bridge.ts）が用意する機能.
 * 画面の移動やダイアログは、移行が終わるまで Vue の仕組みを使う.
 */
@js.native
@JSGlobal("__skiwiHost")
private object HostFacade extends js.Object {
  def navigate(path: String): Unit = js.native
  def confirm(message: String, opts: js.Object): js.Promise[Boolean] = js.native
}

object Host {

  /** 画面を移動します（vue-router の push） */
  def navigate(path: String): Unit = HostFacade.navigate(path)

  /** 画面内の確認ダイアログ（ui/dialog.ts の confirmDialog） */
  def confirm(message: String, okLabel: String = "OK", danger: Boolean = false): Future[Boolean] =
    HostFacade.confirm(message, js.Dynamic.literal(okLabel = okLabel, danger = danger)).toFuture

}
