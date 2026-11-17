package skiwi.web

import skiwi.web.database.DatabaseView

import scala.scalajs.js

/** JS（エディタのノードビュー）から描画する Scalactive の部品（window.__skiwiHost.mountView の名前） */
object EmbedViews {

  private def str(p: js.Dictionary[js.Any], key: String): Option[String] =
    p.get(key).filter(v => v != null && !js.isUndefined(v)).map(_.toString)

  def register(): Unit = {
    // ページの中のデータベース（editor/DatabaseBlockView）. views: ビューの JSON（なければデータベースの既定）, onViews: ビューの変更
    Embeds.register("database") { (el, p) =>
      val onViews = p.get("onViews").map(_.asInstanceOf[js.Function1[String, Unit]])
      Embeds.render(el)(DatabaseView(pageId = str(p, "pageId").getOrElse(""), wid = str(p, "wid").getOrElse(""), embedded = true,
        localViews = str(p, "views"), onViews = v => onViews.foreach(_(v))))
    }
  }

}
