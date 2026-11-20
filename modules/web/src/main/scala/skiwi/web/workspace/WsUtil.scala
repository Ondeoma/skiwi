package skiwi.web.workspace

import scala.scalajs.js

object WsText {

  /** 日本語の並び（localeCompare(b, 'ja')）で a が先か */
  def jaLess(a: String, b: String): Boolean = a.asInstanceOf[js.Dynamic].localeCompare(b, "ja").asInstanceOf[Double] < 0

}
