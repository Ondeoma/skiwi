package skiwi.web.workspace

import io.github.ondeoma.scalactive.components.ComponentManager.*
import skiwi.web.Prefs

import scala.scalajs.js

object WsText {

  /** 日本語の並び（localeCompare(b, 'ja')）で a が先か */
  def jaLess(
    a: String,
    b: String
  ): Boolean = a.asInstanceOf[js.Dynamic].localeCompare(b, "ja").asInstanceOf[Double] < 0

}

/**
 * このブラウザに保存する RV（Prefs.stored と同じ. 監視をどのコンポーネントにも結び付けないため addWatcher で保存する）.
 * serialize が None なら保存しない
 */
object WsPrefs {

  def stored[A](
    key: String,
    parse: Option[String] => A,
    serialize: A => Option[String] = (a: A) => Some(a.toString)
  ): RV[A] = {
    val r = RV(parse(Prefs.get(key)))
    r.addWatcher(_ => serialize(r.v).foreach(Prefs.set(key, _)))
    r
  }

}
