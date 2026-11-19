package skiwi.web.workspace

import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom
import skiwi.web.Prefs

/**
 * サイドメニューの開閉. 広い画面では横に置き、開閉をこのブラウザに保存する.
 * 狭い画面（スマートフォンなど）では閉じた状態から始め、狭くなったときも閉じる（保存はしない）.
 */
object SideMenu {

  private val Key = "skiwi.sideOpen"
  private val mq = dom.window.matchMedia("(max-width: 768px)")

  val narrow: RV[Boolean] = RV(mq.matches)
  val open: RV[Boolean] = RV(if (mq.matches) false else !Prefs.get(Key).contains("0"))

  mq.addEventListener(
    "change",
    (_: dom.Event) => {
      narrow := mq.matches
      if (mq.matches) open := false
    }
  )

  open.addWatcher(_ => if (!narrow.v) Prefs.set(Key, if (open.v) "1" else "0"))

  def toggle(): Unit = open := !open.v

}
