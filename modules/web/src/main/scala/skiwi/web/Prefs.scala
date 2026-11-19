package skiwi.web

import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom

import scala.util.Try

/** このブラウザに保存する設定（localStorage. 使えない環境では読めない・保存しないだけ） */
object Prefs {

  def get(key: String): Option[String] = Try(Option(dom.window.localStorage.getItem(key))).toOption.flatten

  def set(key: String, value: String): Unit = Try(dom.window.localStorage.setItem(key, value))

  def remove(key: String): Unit = Try(dom.window.localStorage.removeItem(key))

  /**
   * 保存する RV. 値が変わると保存する（serialize が None なら保存しない）.
   * 部品の mounted の中で作っても、その部品の片付けで保存が止まらないよう addWatcher で監視する
   */
  def stored[A](key: String,
                parse: Option[String] => A,
                serialize: A => Option[String] = (a: A) => Some(a.toString)): RV[A] = {
    val r = RV(parse(get(key)))
    r.addWatcher { case (_, v) => serialize(v).foreach(set(key, _)) }
    r
  }

}
