package skiwi.core

import java.util.UUID

/** 兄弟ページの並び順用 fractional index。
  * 文字列のバイト順(DBでは collate "C")で比較する。キーは末尾に '0' を持たない。
  * rocicorp/fractional-indexing の midpoint アルゴリズムの移植。
  */
object FractionalIndex:
  private val digits = (('0' to '9') ++ ('A' to 'Z') ++ ('a' to 'z')).mkString
  private val zero   = digits.head
  private def idx(c: Char): Int = digits.indexOf(c)

  /** lo < result < hi（lo, hi が None の場合はそれぞれ下限・上限なし）。 */
  def between(lo: Option[String], hi: Option[String]): String =
    val a = lo.getOrElse("")
    require(a.isEmpty || a.last != zero, s"invalid key: $a")
    hi.foreach { b =>
      require(a < b, s"lo must be < hi: $a, $b")
      require(b.last != zero, s"invalid key: $b")
    }
    midpoint(a, hi)

  private def midpoint(a: String, b: Option[String]): String =
    b match
      case Some(bb) =>
        var n = 0
        while n < bb.length && (if n < a.length then a(n) else zero) == bb(n) do n += 1
        if n > 0 then return bb.take(n) + midpoint(a.drop(n), Some(bb.drop(n)))
      case None => ()
    val digitA = if a.nonEmpty then idx(a(0)) else 0
    val digitB = b.map(x => idx(x(0))).getOrElse(digits.length)
    if digitB - digitA > 1 then digits(math.round(0.5 * (digitA + digitB)).toInt).toString
    else if b.exists(_.length > 1) then b.get.take(1)
    else digits(digitA).toString + midpoint(a.drop(1), None)

  /** siblings は position 昇順の (id, position)。placement に応じた新しい position を返す。 */
  def place(siblings: List[(UUID, String)], placement: Placement): Either[String, String] =
    placement match
      case Placement.First => Right(between(None, siblings.headOption.map(_._2)))
      case Placement.Last  => Right(between(siblings.lastOption.map(_._2), None))
      case Placement.After(id) =>
        val i = siblings.indexWhere(_._1 == id)
        if i < 0 then Left(s"reference page not found among siblings: $id")
        else Right(between(Some(siblings(i)._2), siblings.lift(i + 1).map(_._2)))
