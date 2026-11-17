package skiwi.web.database

import io.circe.Json

import scala.collection.mutable
import scala.scalajs.js

/** 数式・列の値（数値・文字列・真偽・日付・一覧・空） */
sealed trait Val

object Val {

  case object Null extends Val

  final case class Num(v: Double) extends Val

  final case class Str(v: String) extends Val

  final case class Bool(v: Boolean) extends Val

  /** 日付（ミリ秒. 表示・計算はブラウザのタイムゾーン） */
  final case class Dt(ms: Double) extends Val

  final case class Arr(v: List[Val]) extends Val

  /** 保存した値（JSON）を Val に */
  def of(j: Json): Val = j.fold(
    Null,
    Bool(_),
    n => Num(n.toDouble),
    Str(_),
    a => Arr(a.toList.map(of)),
    o => Str(Json.fromJsonObject(o).noSpaces),
  )

  def of(j: Option[Json]): Val = j.fold(Null)(of)

  /** JS の Number(v) */
  def jsNumber(s: String): Double = js.Dynamic.global.Number(s).asInstanceOf[Double]

  def jsNumber(v: Val): Double = v match {
    case Num(d) => d
    case Str(s) => jsNumber(s)
    case Bool(b) => if (b) 1 else 0
    case Dt(ms) => ms
    case Null => 0
    case Arr(Nil) => 0
    case Arr(List(x)) => jsNumber(x)
    case _ => Double.NaN
  }

  /** JS の parseFloat */
  def parseFloat(s: String): Double = js.Dynamic.global.parseFloat(s).asInstanceOf[Double]

  /** JS の !!v */
  def truthy(v: Val): Boolean = v match {
    case Null => false
    case Num(d) => !(d == 0 || d.isNaN)
    case Str(s) => s.nonEmpty
    case Bool(b) => b
    case _ => true
  }

  /** JS の String(v) */
  def jsString(v: Val): String = v match {
    case Null => ""
    case Num(d) => d.toString
    case Str(s) => s
    case Bool(b) => b.toString
    case Dt(ms) => new js.Date(ms).toString
    case Arr(xs) => xs.map(jsString).mkString(",")
  }

}

/**
 * データベースの「数式」の列。表計算ソフトに近い書き方の小さな式の言語（frontend/src/database/formula.ts と同じ）.
 *
 *   prop("金額") * 1.1
 *   if(prop("状態") == "完了", "✅", "")
 *   dateBetween(prop("期限"), today(), "days")
 *   concat(prop("顧客"), " / ", prop("担当"))
 */
object Formula {

  import Val.*

  final class FormulaError(msg: String) extends Exception(msg)

  private enum Tok {
    case TNum(v: Double)
    case TStr(v: String)
    case TId(v: String)
    case TOp(v: String)
  }

  import Tok.*

  enum Node {
    case Lit(v: Val)
    case Call(name: String, args: List[Node])
    case Un(op: String, a: Node)
    case Bin(op: String, a: Node, b: Node)
  }

  import Node.*

  private val NumRe = """^\d*\.?\d+([eE][+-]?\d+)?""".r

  private def isSpace(c: Char): Boolean = c.isWhitespace || c == ' ' || c == '﻿'

  private def isIdStart(c: Char): Boolean = Character.isLetter(c) || c == '_'

  private def isIdPart(c: Char): Boolean = Character.isLetterOrDigit(c) || c == '_' || Character.getType(c) == Character.LETTER_NUMBER || Character.getType(c) == Character.OTHER_NUMBER

  private def tokenize(src: String): List[Tok] = {
    val out = List.newBuilder[Tok]
    var i = 0
    while (i < src.length) {
      val c = src(i)
      if (isSpace(c)) i += 1
      else if ((c >= '0' && c <= '9') || c == '.') {
        NumRe.findPrefixOf(src.substring(i)) match {
          case Some(m) => out += TNum(m.toDouble); i += m.length
          case None => throw FormulaError(s"数値の書き方が正しくありません（${i + 1}文字目）")
        }
      } else if (c == '"' || c == '「' || c == '\'') {
        val close = if (c == '「') '」' else c
        var j = i + 1
        val sb = new StringBuilder
        while (j < src.length && src(j) != close) {
          if (src(j) == '\\' && j + 1 < src.length) { sb += src(j + 1); j += 2 }
          else { sb += src(j); j += 1 }
        }
        if (j >= src.length) throw FormulaError("文字列の終わり（\"）がありません")
        out += TStr(sb.result())
        i = j + 1
      } else {
        val two = src.slice(i, i + 2)
        if (Set("==", "!=", ">=", "<=", "&&", "||")(two)) { out += TOp(two); i += 2 }
        else if ("+-*/%(),<>!".indexOf(c) >= 0) { out += TOp(c.toString); i += 1 }
        else if (isIdStart(c)) {
          var j = i + 1
          while (j < src.length && isIdPart(src(j))) j += 1
          out += TId(src.substring(i, j))
          i = j
        } else throw FormulaError(s"使えない文字があります: 「$c」")
      }
    }
    out.result()
  }

  private val Prec: Map[String, Int] =
    Map("||" -> 1, "&&" -> 2, "==" -> 3, "!=" -> 3, "<" -> 4, ">" -> 4, "<=" -> 4, ">=" -> 4, "+" -> 5, "-" -> 5, "*" -> 6, "/" -> 6, "%" -> 6)

  def parse(src: String): Node = {
    val toks = tokenize(src).toVector
    var p = 0
    def peek: Option[Tok] = toks.lift(p)
    def isOp(v: String): Boolean = peek.contains(TOp(v))
    def expect(v: String): Unit = { if (!isOp(v)) throw FormulaError(s"「$v」が必要です"); p += 1 }
    def primary(): Node = {
      val t = toks.lift(p)
      p += 1
      t match {
        case None => throw FormulaError("式が途中で終わっています")
        case Some(TNum(v)) => Lit(Num(v))
        case Some(TStr(v)) => Lit(Str(v))
        case Some(TOp("(")) => val e = expr(0); expect(")"); e
        case Some(TOp(op @ ("-" | "!"))) => Un(op, primary())
        case Some(TId("true")) => Lit(Bool(true))
        case Some(TId("false")) => Lit(Bool(false))
        case Some(TId(name)) =>
          if (!isOp("(")) throw FormulaError(s"「$name」のあとに ( が必要です（列の値は prop(\"列名\") と書きます）")
          p += 1
          val args = List.newBuilder[Node]
          if (!isOp(")")) {
            args += expr(0)
            while (isOp(",")) { p += 1; args += expr(0) }
          }
          expect(")")
          Call(name, args.result())
        case Some(TOp(v)) => throw FormulaError(s"「$v」の位置が正しくありません")
      }
    }
    def expr(min: Int): Node = {
      var a = primary()
      var go = true
      while (go) peek match {
        case Some(TOp(op)) if Prec.get(op).exists(_ >= min) =>
          p += 1
          a = Bin(op, a, expr(Prec(op) + 1))
        case _ => go = false
      }
      a
    }
    val e = expr(0)
    if (p < toks.length) throw FormulaError("式の終わりに余分なものがあります")
    e
  }

  // ---------------------------------------------------------------- 評価

  private val Day = 86400000.0

  def ymd(d: js.Date): String = f"${d.getFullYear().toInt}%d-${d.getMonth().toInt + 1}%02d-${d.getDate().toInt}%02d"

  private val DateRe = """^\d{4}-\d{2}-\d{2}""".r

  private def toDate(v: Val): Option[Double] = v match {
    case Dt(ms) => Some(ms)
    case Str(s) if DateRe.findPrefixOf(s).isDefined => Some(new js.Date(if (s.length == 10) s + "T00:00:00" else s).getTime())
    case _ => None
  }

  private def num(v: Val): Double = v match {
    case Num(d) => d
    case Bool(b) => if (b) 1 else 0
    case Str(s) if s.trim.nonEmpty && !jsNumber(s).isNaN => jsNumber(s)
    case Dt(ms) => ms
    case _ => Double.NaN
  }

  def show(v: Val): String = v match {
    case Null => ""
    case Dt(ms) => ymd(new js.Date(ms))
    case Arr(xs) => xs.map(show).mkString(", ")
    case Num(d) => if (d.isNaN || d.isInfinite) "" else (js.Math.round(d * 1e10) / 1e10).toString
    case Bool(b) => if (b) "✓" else ""
    case Str(s) => s
  }

  private def empty(v: Val): Boolean = v match {
    case Null | Str("") | Arr(Nil) => true
    case Num(d) => d.isNaN
    case _ => false
  }

  private def eq(a: Val, b: Val): Boolean = (a, b) match {
    case (_: Dt, _) | (_, _: Dt) => toDate(a) == toDate(b)
    case (_: Num, _) | (_, _: Num) => num(a) == num(b)
    case (Arr(xs), _) => xs.exists(eq(_, b))
    case _ => show(a) == show(b)
  }

  private def addUnit(ms: Double, n: Double, unit: String): Val = {
    val r = new js.Date(ms)
    if (unit.startsWith("year")) r.setFullYear(r.getFullYear() + n)
    else if (unit.startsWith("month")) r.setMonth(r.getMonth() + n)
    else if (unit.startsWith("week")) r.setDate(r.getDate() + n * 7)
    else if (unit.startsWith("hour")) r.setHours(r.getHours() + n)
    else r.setDate(r.getDate() + n)
    Dt(r.getTime())
  }

  private def truthy(v: Val): Boolean = !empty(v) && v != Bool(false) && v != Num(0)

  private def evaluate(n: Node, prop: String => Val): Val = {
    def ev(x: Node) = evaluate(x, prop)
    n match {
      case Lit(v) => v
      case Un(op, a) => val x = ev(a); if (op == "-") Num(-num(x)) else Bool(!truthy(x))
      case Bin("&&", a, b) => Bool(truthy(ev(a)) && truthy(ev(b)))
      case Bin("||", a, b) => Bool(truthy(ev(a)) || truthy(ev(b)))
      case Bin(op, an, bn) =>
        val a = ev(an)
        val b = ev(bn)
        op match {
          case "+" => (a, b) match {
            case (Dt(x), Num(y)) => Dt(x + y * Day)
            case (_: Str, _) | (_, _: Str) | (_: Arr, _) => Str(show(a) + show(b))
            case _ => Num(num(a) + num(b))
          }
          case "-" => (a, b) match {
            case (Dt(x), Dt(y)) => Num(js.Math.round((x - y) / Day))
            case (Dt(x), Num(y)) => Dt(x - y * Day)
            case _ => Num(num(a) - num(b))
          }
          case "*" => Num(num(a) * num(b))
          case "/" => if (num(b) == 0) Null else Num(num(a) / num(b))
          case "%" => Num(num(a) % num(b))
          case "==" => Bool(eq(a, b))
          case "!=" => Bool(!eq(a, b))
          case _ =>
            val r = (a, b) match {
              case (_: Dt, _) | (_, _: Dt) =>
                val x = toDate(a).getOrElse(Double.NaN)
                val y = toDate(b).getOrElse(Double.NaN)
                cmp(op, x < y, x > y, x <= y, x >= y)
              case (Str(x), Str(y)) => cmp(op, x < y, x > y, x <= y, x >= y)
              case _ =>
                val x = num(a)
                val y = num(b)
                cmp(op, x < y, x > y, x <= y, x >= y)
            }
            Bool(r)
        }
      case Call(name, args) =>
        val f = Funcs.get(name).orElse(Funcs.get(name.toLowerCase)).getOrElse(throw FormulaError(s"「$name」という関数はありません"))
        if (name == "if") {
          val c = args.lift(0).map(ev).getOrElse(Null)
          if (truthy(c)) args.lift(1).map(ev).getOrElse(Null) else args.lift(2).map(ev).getOrElse(Null)
        } else if (name == "prop") prop(args.headOption.map(ev).map(jsString).getOrElse("undefined"))
        else f(args.map(ev))
    }
  }

  private def cmp(op: String, lt: Boolean, gt: Boolean, le: Boolean, ge: Boolean): Boolean =
    op match {
      case "<" => lt
      case ">" => gt
      case "<=" => le
      case _ => ge
    }

  private def flat(a: List[Val]): List[Val] = a.flatMap {
    case Arr(xs) => xs
    case x => List(x)
  }

  private def arg(a: List[Val], i: Int): Val = a.lift(i).getOrElse(Null)

  /** a[i] ?? 既定値 */
  private def argOr(a: List[Val], i: Int, d: Val): Val = a.lift(i).filter(_ != Null).getOrElse(d)

  private def dateNum(a: List[Val], f: js.Date => Double): Val = toDate(arg(a, 0)).fold[Val](Null)(ms => Num(f(new js.Date(ms))))

  private def replaceOnce(s: String, from: String, to: String): String = {
    val i = s.indexOf(from)
    if (i < 0) s else s.substring(0, i) + to + s.substring(i + from.length)
  }

  private def p2(d: Double): String = f"${d.toInt}%02d"

  private val Funcs: Map[String, List[Val] => Val] = Map(
    "if" -> (_ => Null), "prop" -> (_ => Null),
    "and" -> (a => Bool(a.forall(truthy))), "or" -> (a => Bool(a.exists(truthy))), "not" -> (a => Bool(!truthy(arg(a, 0)))),
    "empty" -> (a => Bool(empty(arg(a, 0)))), "concat" -> (a => Str(a.map(show).mkString)),
    "join" -> (a => arg(a, 0) match {
      case Arr(xs) => Str(xs.map(show).mkString(show(argOr(a, 1, Str(", ")))))
      case x => Str(show(x))
    }),
    "length" -> (a => arg(a, 0) match {
      case Arr(xs) => Num(xs.size)
      case x => Num(show(x).length)
    }),
    "contains" -> (a => arg(a, 0) match {
      case Arr(xs) => Bool(xs.exists(eq(_, arg(a, 1))))
      case x => Bool(show(x).contains(show(arg(a, 1))))
    }),
    "replace" -> { a =>
      val s = show(arg(a, 0))
      val from = show(arg(a, 1))
      val to = show(arg(a, 2))
      Str(if (from.isEmpty) s.map(_.toString).mkString(to) else s.replace(from, to))
    },
    "upper" -> (a => Str(show(arg(a, 0)).toUpperCase)), "lower" -> (a => Str(show(arg(a, 0)).toLowerCase)),
    "format" -> (a => Str(show(arg(a, 0)))), "toNumber" -> (a => Num(num(arg(a, 0)))),
    "round" -> { a =>
      val d0 = num(argOr(a, 1, Num(0)))
      val d = if (d0.isNaN || d0 == 0) 0.0 else d0
      val m = js.Math.pow(10, d)
      Num(js.Math.round(num(arg(a, 0)) * m) / m)
    },
    "floor" -> (a => Num(js.Math.floor(num(arg(a, 0))))), "ceil" -> (a => Num(js.Math.ceil(num(arg(a, 0))))),
    "abs" -> (a => Num(js.Math.abs(num(arg(a, 0))))),
    "min" -> (a => Num(js.Math.min(flat(a).map(num)*))), "max" -> (a => Num(js.Math.max(flat(a).map(num)*))),
    "sum" -> (a => Num(flat(a).map(num).filterNot(_.isNaN).sum)),
    "now" -> (_ => Dt(js.Date.now())),
    "today" -> { _ =>
      val d = new js.Date()
      d.setHours(0, 0, 0, 0)
      Dt(d.getTime())
    },
    "dateBetween" -> { a =>
      (toDate(arg(a, 0)), toDate(arg(a, 1))) match {
        case (Some(xm), Some(ym)) =>
          val unit = show(argOr(a, 2, Str("days")))
          val x = new js.Date(xm)
          val y = new js.Date(ym)
          if (unit.startsWith("year")) Num(x.getFullYear() - y.getFullYear())
          else if (unit.startsWith("month")) Num((x.getFullYear() - y.getFullYear()) * 12 + x.getMonth() - y.getMonth())
          else if (unit.startsWith("week")) Num(((xm - ym) / (7 * Day)).toLong.toDouble)
          else if (unit.startsWith("hour")) Num(((xm - ym) / 3600000).toLong.toDouble)
          else Num(js.Math.round((xm - ym) / Day))
        case _ => Null
      }
    },
    "dateAdd" -> (a => toDate(arg(a, 0)).fold[Val](Null)(ms => addUnit(ms, num(arg(a, 1)), show(argOr(a, 2, Str("days")))))),
    "dateSubtract" -> (a => toDate(arg(a, 0)).fold[Val](Null)(ms => addUnit(ms, -num(arg(a, 1)), show(argOr(a, 2, Str("days")))))),
    "formatDate" -> { a =>
      toDate(arg(a, 0)).fold[Val](Null) { ms =>
        val d = new js.Date(ms)
        var s = show(argOr(a, 1, Str("YYYY/MM/DD")))
        s = replaceOnce(s, "YYYY", d.getFullYear().toInt.toString)
        s = replaceOnce(s, "MM", p2(d.getMonth() + 1))
        s = replaceOnce(s, "DD", p2(d.getDate()))
        s = replaceOnce(s, "HH", p2(d.getHours()))
        Str(replaceOnce(s, "mm", p2(d.getMinutes())))
      }
    },
    "year" -> (a => dateNum(a, _.getFullYear())), "month" -> (a => dateNum(a, _.getMonth() + 1)), "day" -> (a => dateNum(a, _.getDate())),
    "weekday" -> (a => toDate(arg(a, 0)).fold[Val](Null)(ms => Str("日月火水木金土"(new js.Date(ms).getDay().toInt).toString))),
  )

  /** 使える関数（数式の入力画面の説明用） */
  val Help: String =
    """列の値: prop("列名")　演算: + - * / %　比較: == != > < >= <=　論理: && || !
      |関数: if(条件, 真, 偽)・and・or・not・empty(値)・concat(…)・length・contains(文字, 部分)・replace・upper・lower・round(数, 桁)・floor・ceil・abs・min・max・sum・toNumber・format
      |日付: today()・now()・dateBetween(日付1, 日付2, "days"|"weeks"|"months"|"years"|"hours")・dateAdd(日付, 数, 単位)・dateSubtract・formatDate(日付, "YYYY/MM/DD")・year・month・day・weekday
      |例: prop("金額") * 1.1　／　if(prop("状態") == "完了", "✅", "")　／　dateBetween(prop("期限"), today(), "days")""".stripMargin

  private val cache = mutable.Map.empty[String, Either[String, Node]]

  private def message(e: Throwable): String = Option(e.getMessage).getOrElse(e.toString)

  /** 式を計算する（解析した結果は覚えておく）。エラーは「⚠ …」の文字にする */
  def run(expr: String, prop: String => Val): Val = {
    val ast = cache.getOrElseUpdate(expr, try Right(parse(expr)) catch { case e: Throwable => Left(message(e)) })
    ast match {
      case Left(err) => Str(s"⚠ $err")
      case Right(node) => try evaluate(node, prop) catch { case e: Throwable => Str(s"⚠ ${message(e)}") }
    }
  }

  /** 式を読めるか（読めなければその理由） */
  def check(expr: String): Option[String] = try { parse(expr); None } catch { case e: Throwable => Some(message(e)) }

}
