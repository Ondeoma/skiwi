package skiwi.web

import skiwi.shared.Timestamp

import scala.scalajs.js

object Fmt {

  /** 日時の表示（2026/11/13 10:48） */
  def date(
    t: Timestamp
  ): String = {
    t.toDate.asInstanceOf[js.Dynamic]
      .toLocaleString(
        "ja-JP",
        js.Dynamic.literal(year = "numeric", month = "2-digit", day = "2-digit", hour = "2-digit", minute = "2-digit")
      )
      .asInstanceOf[String]
  }

}
