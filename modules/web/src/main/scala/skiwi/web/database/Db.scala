package skiwi.web.database

import skiwi.shared.Titles

import io.circe.Json
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.web.Ui

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** 値の計算に使う情報（関連先のデータベース・ユーザー名） */
final case class Ctx(props: List[DbProp], targets: Map[String, RelatedDb], users: Map[String, String])

/** 値を計算するのに使う項目の情報 */
final case class RowLike(values: Map[String, Json], meta: Option[RowMeta] = None, title: Option[String] = None)

/** データベースの列・値の扱い（表示・並べ替え・絞り込み. frontend/src/database/db.ts と同じ） */
object Db {

  import Val.*

  /** 列の種類（追加するときの選択肢の順） */
  val Types: List[String] = List(
    "text",
    "number",
    "select",
    "multi_select",
    "date",
    "person",
    "checkbox",
    "url",
    "relation",
    "rollup",
    "formula",
    "created_time",
    "edited_time",
    "created_by",
    "edited_by",
    "unique_id"
  )

  val TypeLabel: Map[String, String] = Map(
    "text" -> "テキスト",
    "number" -> "数値",
    "select" -> "選択",
    "multi_select" -> "複数選択",
    "date" -> "日付",
    "person" -> "ユーザー",
    "checkbox" -> "チェック",
    "url" -> "URL",
    "relation" -> "関連（別のデータベース）",
    "rollup" -> "集計（関連先の値）",
    "formula" -> "数式",
    "created_time" -> "作成日時",
    "edited_time" -> "最終更新日時",
    "created_by" -> "作成者",
    "edited_by" -> "最終更新者",
    "unique_id" -> "ID（連番）"
  )

  /** 値を入れない（計算・自動で決まる）列 */
  val Computed: Set[String] =
    Set("rollup", "formula", "created_time", "edited_time", "created_by", "edited_by", "unique_id")

  val TypeIcon: Map[String, String] = Map(
    "text" -> "≡",
    "number" -> "#",
    "select" -> "◉",
    "multi_select" -> "☰",
    "date" -> "📅",
    "person" -> "👤",
    "checkbox" -> "☑",
    "url" -> "🔗",
    "relation" -> "↗",
    "rollup" -> "Σ",
    "formula" -> "ƒ",
    "created_time" -> "🕐",
    "edited_time" -> "🕑",
    "created_by" -> "👤",
    "edited_by" -> "✍",
    "unique_id" -> "№"
  )

  def icon(t: String): String = TypeIcon.getOrElse(t, "≡")

  /** 集計（関連先の値）のしかた */
  val Fns: List[(String, String)] = List(
    "count" -> "件数",
    "count_values" -> "値のある件数",
    "sum" -> "合計",
    "avg" -> "平均",
    "min" -> "最小",
    "max" -> "最大",
    "checked" -> "チェック済みの割合"
  )

  val Colors: List[String] = List("gray", "red", "orange", "yellow", "green", "blue", "purple", "pink")

  val ColorLabel: Map[String, String] = Map(
    "gray" -> "グレー",
    "red" -> "赤",
    "orange" -> "オレンジ",
    "yellow" -> "黄",
    "green" -> "緑",
    "blue" -> "青",
    "purple" -> "紫",
    "pink" -> "ピンク"
  )

  /** ビューの種類（追加するときの選択肢の順） */
  val ViewTypes: List[String] = List("table", "board", "calendar", "list", "gallery", "timeline")

  val ViewLabel: Map[String, String] = Map(
    "table" -> "表",
    "board" -> "ボード（状態などで列に分ける）",
    "calendar" -> "カレンダー（日付で並べる）",
    "list" -> "リスト（名前と主な値を一覧）",
    "gallery" -> "ギャラリー（カードで並べる。本文の画像を表紙に）",
    "timeline" -> "タイムライン（開始〜終了の期間を横棒で）"
  )

  val ViewIcon: Map[String, String] =
    Map("table" -> "▦", "board" -> "▥", "calendar" -> "📅", "list" -> "☰", "gallery" -> "🖼", "timeline" -> "📊")

  /** 「（…）」の説明を除いた名前 */
  def shortLabel(s: String): String = s.replaceAll("（.*）", "")

  def like(r: DbRow): RowLike = RowLike(r.values, r.meta, Some(r.page.title))

  def newId(): String =
    js.Math.random().asInstanceOf[js.Dynamic].applyDynamic("toString")(36).asInstanceOf[String].slice(2, 10)

  def ymd(d: js.Date): String = Formula.ymd(d)

  def ymd(ms: Double): String = Formula.ymd(new js.Date(ms))

  /** カレンダーの最初の月（今月の1日） */
  def thisMonth(): Double = {
    val d = new js.Date()
    new js.Date(d.getFullYear().toInt, d.getMonth().toInt, 1).getTime()
  }

  /** タイムラインの左端（1週間前） */
  def weekAgo(): Double = {
    val d = new js.Date()
    d.setDate(d.getDate() - 7)
    d.setHours(0, 0, 0, 0)
    d.getTime()
  }

  private def fmtTime(iso: String): String = {
    val d = new js.Date(iso)
    def p(n: Double) = f"${n.toInt}%02d"
    s"${d.getFullYear().toInt}/${p(d.getMonth() + 1)}/${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}"
  }

  /** 新しいデータベースの列とビュー（状態・担当者・期限. 表・ボード・カレンダー） */
  def defaultDatabase(): (List[DbProp], List[DbView]) = {
    val status = newId()
    val due = newId()
    (
      List(
        DbProp(
          status,
          "状態",
          "select",
          options = Some(List(
            DbOption(newId(), "未着手", "gray"),
            DbOption(newId(), "進行中", "blue"),
            DbOption(newId(), "完了", "green")
          ))
        ),
        DbProp(newId(), "担当者", "person"),
        DbProp(due, "期限", "date"),
      ),
      List(
        DbView(newId(), "表", "table"),
        DbView(newId(), "ボード", "board", groupBy = Some(status)),
        DbView(newId(), "カレンダー", "calendar", dateProp = Some(due)),
      )
    )
  }

  def option(p: DbProp, v: Val): Option[DbOption] = v match {
    case Str(id) => p.opts.find(_.id == id)
    case _ => None
  }

  def arr(v: Val): List[String] = v match {
    case Arr(xs) => xs.collect { case Str(s) => s }
    case _ => Nil
  }

  private var depth = 0

  /** 数式の中の prop("列名") の値 */
  private def formulaValue(name: String, row: RowLike, ctx: Ctx): Val =
    if (name == "名前" || name == "title") Str(row.title.getOrElse(""))
    else {
      val p = ctx.props.find(_.name == name).getOrElse(throw Formula.FormulaError(s"「$name」という列はありません"))
      val v = cellValue(p, row, ctx)
      if (v == Null) Null
      else p.`type` match {
        case "select" => option(p, v).fold[Val](Null)(o => Str(o.name))
        case "multi_select" =>
          Arr(arr(v).map(id => p.opts.find(_.id == id).map(_.name).getOrElse("")).filter(_.nonEmpty).map(Str(_)))
        case "person" => Arr(arr(v).map(ctx.users.getOrElse(_, "")).filter(_.nonEmpty).map(Str(_)))
        case "relation" => Arr(relatedTitles(p, v, ctx).map(Str(_)))
        case "date" => v match {
            case Str(s) => Dt(new js.Date(s + "T00:00:00").getTime())
            case _ => Null
          }
        case "created_time" | "edited_time" => v match {
            case Str(s) => Dt(new js.Date(s).getTime())
            case _ => Null
          }
        case "number" => Num(jsNumber(v))
        case "checkbox" => Bool(truthy(v))
        case _ => v
      }
    }

  /** 列の値（集計・数式・自動の列は計算する） */
  def cellValue(p: DbProp, row: RowLike, ctx: Ctx): Val = {
    def str(o: Option[String]): Val = o.fold[Val](Null)(Str(_))
    p.`type` match {
      case "created_time" => str(row.meta.map(_.createdAt))
      case "edited_time" => str(row.meta.map(_.editedAt))
      case "created_by" => str(row.meta.flatMap(_.createdBy))
      case "edited_by" => str(row.meta.flatMap(_.editedBy))
      case "unique_id" => Val.of(row.values.get("_seq")) match {
          case Num(n) => Str(p.prefix.filter(_.nonEmpty).fold("")(_ + "-") + n.toString)
          case _ => Null
        }
      case "formula" =>
        p.expr.filter(_.nonEmpty) match {
          case Some(expr) if depth <= 8 =>
            depth += 1
            try Formula.run(expr, formulaValue(_, row, ctx))
            finally depth -= 1
          case _ => Null
        }
      case "rollup" => rollup(p, row, ctx)
      case _ => Val.of(row.values.get(p.id))
    }
  }

  private def round2(r: Double): Double = js.Math.round(r * 100) / 100

  private def rollup(p: DbProp, row: RowLike, ctx: Ctx): Val = {
    val rel = ctx.props.find(x => p.relation.contains(x.id) && x.`type` == "relation")
    val db = rel.flatMap(_.target).flatMap(ctx.targets.get)
    (rel, db) match {
      case (Some(rel), Some(db)) =>
        val ids = arr(Val.of(row.values.get(rel.id)))
        val rows = db.rows.filter(r => ids.contains(r.id))
        val tp = db.props.find(x => p.prop.contains(x.id))
        val vals = tp.fold(List.empty[Val])(t => rows.map(r => Val.of(r.values.get(t.id))))
        p.fn.getOrElse("") match {
          case "count" => Num(rows.size)
          case "count_values" => Num(tp.fold(rows.size)(t => vals.count(v => !isEmpty(t, v))))
          case "checked" =>
            if (rows.isEmpty) Null else Str(js.Math.round(vals.count(truthy).toDouble / rows.size * 100).toString + "%")
          case fn =>
            val ns = vals.filter {
              case Null | Str("") => false
              case v => val n = jsNumber(v); !n.isNaN && !n.isInfinite
            }.map(jsNumber)
            if (ns.isEmpty) Null
            else Num(round2(fn match {
              case "sum" => ns.sum
              case "avg" => ns.sum / ns.size
              case "min" => ns.min
              case _ => ns.max
            }))
        }
      case _ => Null
    }
  }

  /** 関連先の項目の名前 */
  private def relatedTitles(p: DbProp, v: Val, ctx: Ctx): List[String] =
    arr(v).flatMap(id => ctx.targets.get(p.target.getOrElse("")).flatMap(_.rows.find(_.id == id))).map(r =>
      Titles.display(r.title)
    )

  /** 値の文字表現（検索・並べ替え・CSVなど） */
  def text(p: DbProp, v: Val, users: Map[String, String], ctx: Option[Ctx] = None): String =
    if (v == Null) ""
    else p.`type` match {
      case "relation" => ctx.fold("")(c => relatedTitles(p, v, c).mkString(", "))
      case "rollup" => jsString(v)
      case "formula" => Formula.show(v)
      case "created_time" | "edited_time" => v match {
          case Str(s) => fmtTime(s)
          case _ => ""
        }
      case "select" => option(p, v).fold("")(_.name)
      case "multi_select" => arr(v).flatMap(id => p.opts.find(_.id == id).map(_.name)).filter(_.nonEmpty).mkString(", ")
      case "person" => arr(v).map(users.getOrElse(_, "")).filter(_.nonEmpty).mkString(", ")
      case "checkbox" => if (truthy(v)) "✓" else ""
      case "date" => v match {
          case Str(s) => s
          case _ => ""
        }
      case _ => jsString(v)
    }

  def isEmpty(p: DbProp, v: Val): Boolean = v match {
    case Null | Str("") | Arr(Nil) => true
    case _ => (p.`type` == "checkbox" && !truthy(v)) || (p.`type` == "select" && option(p, v).isEmpty)
  }

  // ---------------------------------------------------------------- 並べ替え・絞り込み

  /** 並べ替えのキー（数値か文字列） */
  private type Key = Either[Double, String]

  private def key(p: Either[String, DbProp], r: DbRow, users: Map[String, String], ctx: Ctx): Option[Key] = p match {
    case Left("title") => Some(Right(r.page.title))
    case Left(_) => Some(Right(r.page.updatedAt))
    case Right(p) =>
      val v = cellValue(p, like(r), ctx)
      p.`type` match {
        case "rollup" => v match {
            case Null => None
            case Num(n) => Some(Left(n))
            case x => Some(Left(parseFloat(jsString(x))))
          }
        case "formula" => v match {
            case Null | Str("") => None
            case Num(n) => Some(Left(n))
            case Dt(ms) => Some(Left(ms))
            case x => Some(Right(Formula.show(x).toLowerCase))
          }
        case "created_time" | "edited_time" => v match {
            case Str(s) => Some(Right(s))
            case _ => None
          }
        case "unique_id" => Val.of(r.values.get("_seq")) match {
            case Num(n) => Some(Left(n))
            case _ => None
          }
        case _ if isEmpty(p, v) => None
        case "number" => Some(Left(jsNumber(v)))
        case "checkbox" => Some(Left(if (truthy(v)) 1 else 0))
        case "select" => Some(Left(p.opts.indexWhere(o => v == Str(o.id)).toDouble))
        case _ => Some(Right(text(p, v, users, Some(ctx)).toLowerCase))
      }
  }

  private def compareKey(x: Key, y: Key): Int = (x, y) match {
    case (Right(a), Right(b)) => math.signum(a.compareTo(b))
    case _ =>
      val a = x.fold(identity, jsNumber)
      val b = y.fold(identity, jsNumber)
      if (a < b) -1 else if (a > b) 1 else 0
  }

  private def num(s: Option[String]): Double = s.fold(Double.NaN)(jsNumber)

  private def finite(d: Double) = !d.isNaN && !d.isInfinite

  def matches(f: DbFilter,
              props: List[DbProp],
              r: DbRow,
              users: Map[String, String],
              me: Option[String],
              ctx: Option[Ctx] = None): Boolean = {
    val fv = f.value.getOrElse("")
    def norm(s: String) = Ui.normalizeForSearch(s)
    if (f.prop == "title") {
      val t = norm(r.page.title)
      f.op match {
        case "empty" => t.isEmpty
        case "notEmpty" => t.nonEmpty
        case _ => t.contains(norm(fv))
      }
    } else props.find(_.id == f.prop) match {
      case None => true
      case Some(p) =>
        val c = ctx.getOrElse(Ctx(props, Map.empty, users))
        val v = cellValue(p, like(r), c)
        val tpe = p.`type`
        val isTime = tpe == "created_time" || tpe == "edited_time"
        if (Set("formula", "created_by", "edited_by", "unique_id", "created_time", "edited_time")(tpe)) {
          val t = text(p, v, users, Some(c))
          lazy val n = v match {
            case Num(x) => x
            case _ => parseFloat(t)
          }
          lazy val b = num(f.value)
          f.op match {
            case "empty" => t.isEmpty
            case "notEmpty" => t.nonEmpty
            case "contains" => norm(t).contains(norm(fv))
            case "is" => if (finite(n) && finite(b)) n == b else t == fv
            case "isNot" => t != fv
            case "gt" | "lt" =>
              if (isTime) {
                val x = t.take(10).replace("/", "-")
                if (f.op == "gt") x > fv else x < fv
              } else if (f.op == "gt") n > b else n < b
            case _ => true
          }
        } else if (tpe == "relation" && (f.op == "contains" || f.op == "is"))
          arr(v).contains(fv) || norm(text(p, v, users, Some(c))).contains(norm(fv))
        else if (tpe == "rollup" && (f.op == "gt" || f.op == "lt" || f.op == "is")) {
          val n = parseFloat(jsString(v))
          val b = num(f.value)
          if (!finite(n) || fv.isEmpty) false
          else if (f.op == "gt") n > b else if (f.op == "lt") n < b else n == b
        } else f.op match {
          case "empty" => isEmpty(p, v)
          case "notEmpty" => !isEmpty(p, v)
          case "contains" =>
            if (tpe == "person" && fv == "@me") arr(v).contains(me.getOrElse(""))
            else if (tpe == "multi_select") arr(v).contains(fv)
            else norm(text(p, v, users, Some(c))).contains(norm(fv))
          case "is" | "isNot" =>
            val eq =
              if (tpe == "checkbox") truthy(v) == (fv == "true")
              else if (tpe == "person") arr(v).contains(if (fv == "@me") me.getOrElse("") else fv)
              else if (tpe == "multi_select") arr(v).contains(fv)
              else if (tpe == "number") v != Null && jsNumber(v) == num(f.value)
              else v match {
                case Null => fv.isEmpty
                case Str(s) => s == fv
                case _ => false
              }
            if (f.op == "is") eq else !eq
          case "gt" | "lt" =>
            if (isEmpty(p, v) || fv.isEmpty) false
            else if (tpe == "number") { val a = jsNumber(v); val b = num(f.value); if (f.op == "gt") a > b else a < b }
            else { val a = jsString(v); if (f.op == "gt") a > fv else a < fv }
          case _ => true
        }
    }
  }

  /** ビューの絞り込み・並べ替えを当てた項目 */
  def applyView(v: DbView,
                props: List[DbProp],
                rows: List[DbRow],
                users: Map[String, String],
                me: Option[String],
                q: String,
                targets: Map[String, RelatedDb] = Map.empty): List[DbRow] = {
    val ctx = Ctx(props, targets, users)
    val nq = Ui.normalizeForSearch(q.trim)
    var out = rows.filter(r => v.filterList.forall(f => matches(f, props, r, users, me, Some(ctx))))
    if (nq.nonEmpty) out = out.filter(r =>
      Ui.normalizeForSearch((r.page.title ::
        props.map(p => text(p, cellValue(p, like(r), ctx), users, Some(ctx)))).mkString(" ")).contains(nq)
    )
    v.sort.foreach { s =>
      val p: Option[Either[String, DbProp]] =
        if (s.prop == "title" || s.prop == "updated") Some(Left(s.prop)) else props.find(_.id == s.prop).map(Right(_))
      p.foreach { p =>
        val dir = if (s.dir == "desc") -1 else 1
        // 並べ替えのキーは先に1回ずつ計算しておく（数式・集計は比較のたびに計算すると重い）
        val keyed = out.map(r => r -> key(p, r, users, ctx))
        out = keyed.sortWith { case ((_, x), (_, y)) =>
          val c = (x, y) match {
            case (None, None) => 0
            case (None, _) => 1 // 空は常に後ろ
            case (_, None) => -1
            case (Some(a), Some(b)) => compareKey(a, b) * dir
          }
          c < 0
        }.map(_._1)
      }
    }
    out
  }

  /** 絞り込みの条件として選べるもの（値, 表示） */
  def opsFor(t: String): List[(String, String)] = {
    val empty = List("empty" -> "が空", "notEmpty" -> "が空でない")
    t match {
      case "title" | "text" | "url" => ("contains" -> "に含む") :: empty
      case "number" => List("is" -> "＝", "gt" -> "より大きい", "lt" -> "より小さい") ++ empty
      case "date" => List("is" -> "が", "gt" -> "より後", "lt" -> "より前") ++ empty
      case "select" => List("is" -> "が", "isNot" -> "が次でない") ++ empty
      case "multi_select" | "person" | "relation" | "created_by" | "edited_by" => ("contains" -> "に含む") :: empty
      case "checkbox" => List("is" -> "が")
      case "rollup" => List("is" -> "＝", "gt" -> "より大きい", "lt" -> "より小さい")
      case "formula" | "unique_id" => List("contains" -> "に含む", "is" -> "＝", "gt" -> "より大きい", "lt" -> "より小さい") ++ empty
      case "created_time" | "edited_time" => List("gt" -> "より後", "lt" -> "より前")
      case _ => ("contains" -> "に含む") :: empty
    }
  }

  def needsValue(op: String): Boolean = op != "empty" && op != "notEmpty"

  // ---------------------------------------------------------------- CSV

  private def csvCell(s: String): String =
    if (s.exists(c => c == '"' || c == ',' || c == '\r' || c == '\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s

  /** 表示している列で CSV を作る（Excel で開けるよう先頭に BOM を付ける） */
  def toCsv(props: List[DbProp], rows: List[DbRow], ctx: Ctx): String = {
    val head = "名前" :: props.map(_.name)
    val lines = rows.map(r =>
      r.page.title :: props.map { p =>
        val v = cellValue(p, like(r), ctx)
        p.`type` match {
          case "checkbox" => if (truthy(v)) "TRUE" else "FALSE"
          case "multi_select" | "person" | "relation" =>
            text(p, v, ctx.users, Some(ctx)).split(", ").filter(_.nonEmpty).mkString("; ")
          case _ => text(p, v, ctx.users, Some(ctx))
        }
      }
    )
    "﻿" + (head :: lines).map(_.map(csvCell).mkString(",")).mkString("\r\n") + "\r\n"
  }

  /** CSV を読む（引用符・改行を含む値に対応） */
  def parseCsv(src: String): List[List[String]] = {
    val s = if (src.startsWith("﻿")) src.substring(1) else src
    val rows = List.newBuilder[List[String]]
    var row = List.newBuilder[String]
    var rowLen = 0
    val cell = new StringBuilder
    var q = false
    var i = 0
    while (i < s.length) {
      val c = s(i)
      if (q) {
        if (c == '"' && i + 1 < s.length && s(i + 1) == '"') { cell += '"'; i += 1 }
        else if (c == '"') q = false
        else cell += c
      } else if (c == '"') q = true
      else if (c == ',') { row += cell.result(); rowLen += 1; cell.clear() }
      else if (c == '\n' || c == '\r') {
        if (c == '\r' && i + 1 < s.length && s(i + 1) == '\n') i += 1
        row += cell.result(); rows += row.result(); row = List.newBuilder; rowLen = 0; cell.clear()
      } else cell += c
      i += 1
    }
    if (cell.nonEmpty || rowLen > 0) { row += cell.result(); rows += row.result() }
    rows.result().filter(_.exists(_.trim.nonEmpty))
  }

  /** ファイルの中身を文字列に: UTF-8（読めなければ Excel などの Shift_JIS）。先頭の BOM は除く */
  def decodeText(data: js.Any): String = {
    val TD = js.Dynamic.global.TextDecoder
    try js.Dynamic.newInstance(TD)(
        "utf-8",
        js.Dynamic.literal(fatal = true)
      ).decode(data).asInstanceOf[String].stripPrefix("﻿")
    catch { case _: Throwable => js.Dynamic.newInstance(TD)("shift_jis").decode(data).asInstanceOf[String] }
  }

  /** CSV ファイルを読む（UTF-8 として読めなければ Excel の CSV＝Shift_JIS として） */
  def readCsv(f: dom.File): Future[List[List[String]]] =
    f.asInstanceOf[js.Dynamic].arrayBuffer().asInstanceOf[js.Promise[js.Any]].toFuture.map(b => parseCsv(decodeText(b)))

  /** ファイルを選んでもらう（取り消したら空） */
  def pickFiles(accept: String, multiple: Boolean = false): Future[List[dom.File]] = {
    val p = Promise[List[dom.File]]()
    val input = dom.document.createElement("input").asInstanceOf[dom.HTMLInputElement]
    input.`type` = "file"
    input.multiple = multiple
    input.accept = accept
    input.onchange =
      _ => p.trySuccess(Option(input.files).fold(List.empty[dom.File])(fs => (0 until fs.length).map(fs(_)).toList))
    input.asInstanceOf[js.Dynamic].oncancel = (_: dom.Event) => p.trySuccess(Nil)
    input.click()
    p.future
  }

  /** CSV の取り込みの計画. props: 追加・変更後の列 / added: 追加した列の名前 / items: 項目の名前と値 */
  final case class CsvPlan(props: List[DbProp], added: List[String], items: List[(String, Map[String, Json])])

  val Truthy: Set[String] = Set("true", "1", "yes", "y", "✓", "✔", "はい", "済", "完了", "on", "x")

  private val UrlRe = "(?i)^(https?://|mailto:).*".r

  /**
   * CSV（1行目は列名）から取り込む項目を作る。「名前」（なければ1列目）を項目名にし、同じ名前の列に値を入れる
   * （ない列はテキストの列として追加。選択肢は足りない分を追加）
   */
  def planCsvImport(csv: List[List[String]], db: Database): CsvPlan = {
    val head = csv.headOption.getOrElse(Nil)
    val body = csv.drop(1)
    val titleCol = math.max(0, head.indexWhere(h => Set("名前", "タイトル", "name", "title")(h.trim.toLowerCase)))
    val ps = mutable.ArrayBuffer.from(db.props)
    val added = List.newBuilder[String]
    val colProp: List[Option[String]] = head.zipWithIndex.map {
      (h, i) =>
        if (i == titleCol || h.trim.isEmpty) None
        else {
          val p = ps.find(_.name == h.trim).getOrElse {
            val np = DbProp(newId(), h.trim, "text")
            ps += np
            added += np.name
            np
          }
          if (Computed(p.`type`)) None else Some(p.id)
        }
    }
    def propOf(id: String) = ps.find(_.id == id).get
    // 選択肢: 足りないものを足す
    def optId(pid: String, name: String): Option[String] = {
      val n = name.trim
      if (n.isEmpty) None
      else {
        val p = propOf(pid)
        p.opts.find(_.name == n).map(_.id).orElse {
          val o = DbOption(newId(), n, Colors(p.opts.size % Colors.size))
          ps(ps.indexWhere(_.id == pid)) = p.copy(options = Some(p.opts :+ o))
          Some(o.id)
        }
      }
    }
    val userByName = db.users.map((id, n) => n -> id)
    val Sep = "[;；,、]"
    val items = body.map { r =>
      val values = mutable.LinkedHashMap.empty[String, Json]
      colProp.zipWithIndex.foreach {
        case (Some(pid), i) =>
          val c = r.lift(i).getOrElse("").trim
          if (c.nonEmpty) propOf(pid).`type` match {
            case "number" =>
              val n = jsNumber(c.replaceAll("[,，¥$]", ""))
              if (finite(n)) values(pid) = Json.fromDoubleOrNull(n)
            case "checkbox" => values(pid) = Json.fromBoolean(Truthy(c.toLowerCase))
            case "date" => parseDate(c).foreach(d => values(pid) = Json.fromString(d))
            case "select" => values(pid) = optId(pid, c).fold(Json.Null)(Json.fromString)
            case "multi_select" => values(pid) = c.split(Sep).toList.flatMap(optId(pid, _)).asJson
            case "person" => values(pid) = c.split(Sep).toList.flatMap(x => userByName.get(x.trim)).asJson
            case "relation" =>
              val t = db.targets.get(propOf(pid).target.getOrElse(""))
              values(pid) =
                c.split("[;；]").toList.flatMap(x => t.flatMap(_.rows.find(_.title == x.trim)).map(_.id)).asJson
            case "url" => if (UrlRe.matches(c.takeWhile(_ != '\n'))) values(pid) = Json.fromString(c)
            case _ => values(pid) = Json.fromString(c)
          }
        case _ => ()
      }
      (r.lift(titleCol).getOrElse("").trim, values.toMap)
    }
    CsvPlan(ps.toList, added.result(), items)
  }

  private val DateRe = """^(\d{4})[-/年.](\d{1,2})[-/月.](\d{1,2})日?""".r

  /** 日付の表記（2026/1/5、2026-01-05、2026年1月5日）を YYYY-MM-DD に */
  def parseDate(s: String): Option[String] =
    DateRe.findPrefixMatchOf(s.trim).flatMap { m =>
      val (y, mo, d) = (m.group(1).toInt, m.group(2).toInt, m.group(3).toInt)
      if (mo < 1 || mo > 12 || d < 1 || d > 31) None else Some(f"$y%d-$mo%02d-$d%02d")
    }

  // ---------------------------------------------------------------- 集計行（表の列の下）

  val CalcLabel: Map[String, String] = Map(
    "count" -> "件数",
    "count_values" -> "値のある件数",
    "count_empty" -> "空の件数",
    "sum" -> "合計",
    "avg" -> "平均",
    "min" -> "最小",
    "max" -> "最大",
    "checked" -> "チェックあり",
    "unchecked" -> "チェックなし"
  )

  /** 列の種類で使える集計 */
  def calcsFor(t: String): List[String] =
    if (Set("number", "rollup", "formula", "unique_id")(t))
      List("count", "count_values", "count_empty", "sum", "avg", "min", "max")
    else if (t == "checkbox") List("count", "checked", "unchecked")
    else if (Set("date", "created_time", "edited_time")(t)) List("count", "count_values", "count_empty", "min", "max")
    else List("count", "count_values", "count_empty")

  def calc(fn: String, p: Option[DbProp], rows: List[DbRow], ctx: Ctx): String =
    if (fn == "count") rows.size.toString
    else p match {
      case None => ""
      case Some(p) =>
        val vals = rows.map(r => cellValue(p, like(r), ctx))
        val filled = vals.filterNot {
          case Null | Str("") | Arr(Nil) => true
          case v => p.`type` == "checkbox" && !truthy(v)
        }
        val isDate = Set("date", "created_time", "edited_time")(p.`type`)
        fn match {
          case "count_values" => filled.size.toString
          case "count_empty" => (vals.size - filled.size).toString
          case "checked" =>
            val n = vals.count(truthy)
            s"$n（${if (vals.isEmpty) 0 else js.Math.round(n.toDouble / vals.size * 100).toInt}%）"
          case "unchecked" => vals.count(v => !truthy(v)).toString
          case "min" | "max" if isDate =>
            val ds = filled.map(jsString).sorted
            if (ds.isEmpty) "" else text(p, Str(if (fn == "min") ds.head else ds.last), ctx.users, Some(ctx))
          case _ =>
            val ns = vals.map {
              case Num(n) => n
              case Str(s) => parseFloat(s.replaceAll("^[^0-9-]*", ""))
              case _ => Double.NaN
            }.filter(finite)
            if (ns.isEmpty) ""
            else round2(fn match {
              case "sum" => ns.sum
              case "avg" => ns.sum / ns.size
              case "min" => ns.min
              case _ => ns.max
            }).toString
        }
    }

}
