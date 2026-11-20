package skiwi.web.database

import org.scalajs.dom

import scala.scalajs.js

/** 画面の部品で使う DOM の小さな補助 */
object DbDom {

  def composing(e: dom.Event): Boolean =
    e.asInstanceOf[js.Dynamic].isComposing.asInstanceOf[js.UndefOr[Boolean]].getOrElse(false)

}
