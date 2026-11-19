package skiwi.web.content

import io.circe.Json

import scala.scalajs.js

/** JS と同じ扱いをするための小さな補助（String()・trim・真偽など） */
private[content] object JsLike {

  /** JS の String(x)（項目がなければ "undefined"） */
  def str(v: Option[Json]): String = v match {
    case None => "undefined"
    case Some(j) => j.asString.getOrElse(js.Dynamic.global.String(js.JSON.parse(j.noSpaces)).asInstanceOf[String])
  }

  /** JS の String(x ?? '') */
  def strOrEmpty(v: Option[Json]): String = v.filterNot(_.isNull).fold("")(j => str(Some(j)))

  /** JS で真になる値か */
  def truthy(v: Option[Json]): Boolean = v.exists(j =>
    j.fold(false, b => b, n => { val d = n.toDouble; d != 0 && !d.isNaN }, s => s.nonEmpty, _ => true, _ => true)
  )

  /** JS の Number(x ?? d) */
  def num(v: Option[Json], default: Double): Double = v.filterNot(_.isNull) match {
    case None => default
    case Some(j) => js.Dynamic.global.Number(js.JSON.parse(j.noSpaces)).asInstanceOf[Double]
  }

  /** 数を JS と同じ書き方で文字列にする */
  def numStr(d: Double): String = js.Dynamic.global.String(d).asInstanceOf[String]

  def trim(s: String): String = s.asInstanceOf[js.Dynamic].trim().asInstanceOf[String]

  def trimEnd(s: String): String = s.asInstanceOf[js.Dynamic].trimEnd().asInstanceOf[String]

  /** JS の s.split(sep)（末尾の空も残す） */
  def lines(s: String): List[String] = s.split("\n", -1).toList

  /** JS の s.replace(a, b)（文字列の最初の1つだけ） */
  def replaceFirst(s: String, a: String, b: String): String = {
    val i = s.indexOf(a)
    if (i < 0) s else s.substring(0, i) + b + s.substring(i + a.length)
  }

}
