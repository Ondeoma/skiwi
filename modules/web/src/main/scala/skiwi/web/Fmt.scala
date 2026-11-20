package skiwi.web

import skiwi.shared.Timestamp

object Fmt {

  /** 日時の表示（2026/11/13 10:48. sbirds-lib の Dates） */
  def date(t: Timestamp): String = sbirds.web.Dates.dateTime(t)

}
