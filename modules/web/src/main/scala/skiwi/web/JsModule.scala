package skiwi.web

import scala.concurrent.Future
import scala.scalajs.js

// frontend/src/bridge.ts の window.skiwiLoad

/**
 * JS のままの機能（frontend/src/bridge の各ファイルに登録したモジュール）を読み込む.
 * 例: JsModule.load("editor").map(m => m.create(el, opts))
 */
object JsModule {

  private var cache: Map[String, Future[js.Dynamic]] = Map.empty

  def load(name: String): Future[js.Dynamic] = cache.getOrElse(name, {
    val f = js.Dynamic.global.skiwiLoad(name).asInstanceOf[js.Promise[js.Dynamic]].toFuture
    cache += name -> f
    f
  })

}
