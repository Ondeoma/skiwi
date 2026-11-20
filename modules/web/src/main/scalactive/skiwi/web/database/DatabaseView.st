<script lang="scala">
import skiwi.web.Nav
import sbirds.web.Dom
import io.circe.Json
import io.github.ondeoma.scalactive.reactive.Computed
import org.scalajs.dom
import skiwi.web.{Choice, Dialog, Prefs, Store}
import skiwi.web.parts.HelpLink

import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.{Failure, Success}

// データベース: 子ページを項目として、表・ボード・カレンダーで見る。列（項目の種類）とビューはデータベースのページに保存する
// embedded: ほかのページの中に置いたデータベース（localViews: その場所だけのビューの JSON。変えると onViews でビューの JSON を知らせる）
case class Props(pageId: String, wid: String, embedded: Boolean = false, localViews: Option[String] = None, onViews: String => Unit = _ => ())

/** 表の行（depth: サブ項目は 1） */
final case class GRow(row: DbRow, depth: Int)

/** グループ（表・リスト）: キー, 見出し, 色, 行 */
final case class Group(key: String, label: String, color: Option[String], items: List[GRow])

/** ボードの列 */
final case class Column(option: DbOption, rows: List[DbRow])

val Groupable: Set[String] = Set("select", "checkbox", "person", "multi_select")

final class State(props: Props) {
  val db: RV[Option[Database]] = RV(None)
  val error: RV[String] = RV("")
  /** 表示するビュー（ページの中に置いたものは保存しない） */
  val activeId: RV[Option[String]] =
    if (props.embedded) RV(None) else Prefs.stored[Option[String]](s"skiwi.dbView.${props.pageId}", identity, identity)
  /** ページの中に置いたデータベースの、その場所のビュー */
  val local: RV[List[DbView]] = RV(props.localViews.map(DbView.parseList).getOrElse(Nil))
  val q: RV[String] = RV("")
  val openSubs: RV[Set[String]] = RV(Set.empty)
  val collapsed: RV[Set[String]] = RV(Set.empty)
  val newTitle: RV[String] = RV("")
  val colMenu: RV[Option[String]] = RV(None)
  val colMenuAt: RV[(Double, Double)] = RV((0.0, 0.0))
  val filterOpen: RV[Boolean] = RV(false)
  val dragRow: RV[Option[String]] = RV(None)
  val dropCol: RV[Option[String]] = RV(None)
  // タイムライン・カレンダーの表示範囲（ビューを切り替えても残るようここで持つ）
  val tlZoom: RV[String] = RV("day")
  val tlStart: RV[Double] = RV(Db.weekAgo())
  val month: RV[Double] = RV(Db.thisMonth())
  val formsOpen: RV[Boolean] = RV(false)
  /** 開いているセルの選択の一覧（複数選択・関連） */
  val pop: RV[Option[String]] = RV(None)
  val saver: DebouncedFn[() => Unit] = useDebounce[() => Unit](400)(_())

  // ---- 計算した値 ----
  val views: CRV[List[DbView]] = Computed.make(() => if (props.embedded && local.v.nonEmpty) local.v else db.v.fold(List.empty[DbView])(_.views))
  val view: CRV[Option[DbView]] = Computed.make { () =>
    val vs = views.v
    vs.find(v => activeId.v.contains(v.id)).orElse(vs.headOption)
  }
  val ctx: CRV[Ctx] = Computed.make(() => db.v.fold(Ctx(Nil, Map.empty, Map.empty))(d => Ctx(d.props, d.targets, d.users)))
  /** セルの値（数式・集計を含む）の計算結果. 表示のたびに計算し直さないよう覚えておく（データベースが変わったら作り直す） */
  val cache: CRV[mutable.Map[(String, String), Val]] = Computed.make { () =>
    db.v
    mutable.Map.empty
  }
  val props_ : CRV[List[DbProp]] = Computed.make(() => db.v.fold(List.empty[DbProp])(_.props))
  val visibleProps: CRV[List[DbProp]] = Computed.make(() => props_.v.filterNot(p => view.v.exists(_.hiddenList.contains(p.id))))
  val hiddenProps: CRV[List[DbProp]] = Computed.make(() => props_.v.filter(p => view.v.exists(_.hiddenList.contains(p.id))))
  /** 表のサブ項目を親の下に入れる（既定） */
  val nest: CRV[Boolean] = Computed.make(() => view.v.exists(v => v.tpe == "table" && !v.subItems.contains(false)))
  val filtered: CRV[List[DbRow]] = Computed.make { () =>
    (db.v, view.v) match {
      case (Some(d), Some(v)) => Db.applyView(v, d.props, d.rows, d.users, untracked(Store.me.v).map(_.id), q.v, d.targets)
      case _ => Nil
    }
  }
  /** 表示する項目（表でサブ項目を入れるときは、最上位の項目だけ） */
  val rows: CRV[List[DbRow]] = Computed.make(() => if (nest.v) filtered.v.filter(_.page.parentId.contains(props.pageId)) else filtered.v)
  val parentIds: CRV[Set[String]] = Computed.make(() => db.v.fold(Set.empty[String])(_.rows.flatMap(_.page.parentId).toSet))
  /** 表の行（サブ項目を開いていれば、その下に入れる） */
  val tableRows: CRV[List[GRow]] = Computed.make { () =>
    val n = nest.v
    val open = openSubs.v
    val all = filtered.v
    rows.v.flatMap { r =>
      GRow(r, 0) :: (if (n && open(r.page.id)) all.filter(_.page.parentId.contains(r.page.id)).map(GRow(_, 1)) else Nil)
    }
  }
  // グループ化（表・リスト）: 選択・チェック・ユーザーの列ごとに区切る
  val tableGroup: CRV[Option[DbProp]] = Computed.make(() => view.v.flatMap(_.group).flatMap(g => props_.v.find(p => p.id == g && Groupable(p.tpe))))
  val groups: CRV[List[Group]] = Computed.make(() => makeGroups(tableGroup.v, tableRows.v, db.v.fold(Map.empty[String, String])(_.users)))
  val groupProp: CRV[Option[DbProp]] = Computed.make(() => view.v.flatMap(_.groupBy).flatMap(g => props_.v.find(p => p.id == g && p.tpe == "select")))
  val columns: CRV[List[Column]] = Computed.make { () =>
    groupProp.v.fold(List.empty[Column]) { g =>
      val rs = rows.v
      (DbOption("", "未設定", "gray") :: g.opts).map(o => Column(o, rs.filter(r => selectKey(g, r) == o.id)))
    }
  }
}

def initState(props: Props) = new State(props)

def selectKey(g: DbProp, r: DbRow): String = {
  val v = Val.of(r.values.get(g.id))
  if (Db.option(g, v).isDefined) Val.jsString(v) else ""
}

def makeGroups(tg: Option[DbProp], all: List[GRow], users: Map[String, String]): List[Group] = tg match {
  case None => List(Group("", "", None, all))
  case Some(g) =>
    def keyOf(r: DbRow): List[String] = {
      val v = Val.of(r.values.get(g.id))
      g.tpe match {
        case "checkbox" => List(if (Val.truthy(v)) "true" else "false")
        case "select" => List(selectKey(g, r))
        case _ =>
          val a = Db.arr(v).filter(x => g.tpe != "multi_select" || g.opts.exists(_.id == x))
          if (a.isEmpty) List("") else a
      }
    }
    val order0: List[(String, String, Option[String])] = g.tpe match {
      case "checkbox" => List(("true", "✓ あり", None), ("false", "なし", None))
      case "person" => all.filter(_.depth == 0).flatMap(x => keyOf(x.row)).distinct.filter(_.nonEmpty).map(k => (k, s"👤 ${users.getOrElse(k, "（不明）")}", None))
      case _ => g.opts.map(o => (o.id, o.name, Some(o.color)))
    }
    val order = order0 ++ (if (g.tpe == "checkbox") Nil else List(("", "未設定", Some("gray"))))
    val buckets = mutable.LinkedHashMap.from(order.map(o => o._1 -> mutable.ListBuffer.empty[GRow]))
    // サブ項目は親と同じグループに入れる
    var cur: List[String] = Nil
    all.foreach { x =>
      if (x.depth == 0) cur = keyOf(x.row)
      cur.foreach(k => buckets.get(k).foreach(_ += x))
    }
    order.map(o => Group(o._1, o._2, o._3, buckets(o._1).toList)).filter(g => g.items.nonEmpty || g.key != "")
}

// ---------------------------------------------------------------- 読み込み・保存

def load(s: State, props: Props): Unit =
  DbApi.get(props.pageId).onComplete {
    case Success(d) => s.db := Some(d); s.error := ""
    case Failure(e) => s.error := e.getMessage
  }

def mounted(s: State, props: Props): Unit = {
  load(s, props)
  // ツリーで項目（子ページ）を追加・削除・名前の変更をしたら読み直す
  val children = computed {
    Store.pages.v.filter(_.parentId.contains(props.pageId)).map(p => s"${p.id}:${p.title}:${p.icon.getOrElse("")}").mkString(",")
  }
  watch(children, false)((_, _) => if (s.db.v.isDefined) load(s, props))
  // ほかの画面（項目のページ）で変えた値を反映する
  useEventListener[dom.Event](dom.document, "visibilitychange")(_ => if (dom.document.visibilityState == "visible") load(s, props))
  ()
}

def canEdit(s: State): Boolean = s.db.v.exists(_.canEdit)

def d(s: State): Database = s.db.v.getOrElse(Database("", Nil, Nil, Nil, Map.empty, false, Map.empty))

def vw(s: State): DbView = s.view.v.getOrElse(DbView("", "", "table"))

def ready(s: State): Boolean = s.db.v.isDefined && s.view.v.isDefined

/** 定義の保存（列・ビュー）。閲覧のみの人のビューの変更は、この画面だけ */
def saveSoon(s: State, props: Props): Unit =
  if (canEdit(s) && s.db.v.isDefined) s.saver(() => save(s, props))

def save(s: State, props: Props): Unit = s.db.v.foreach { cur =>
  DbApi.save(props.pageId, cur.props, cur.views).onComplete {
    case Success(r) => s.db.v.foreach(now => s.db := Some(now.copy(props = r.props, views = r.views, targets = r.targets, rows = r.rows)))
    case Failure(e) => Dialog.alertError("保存できませんでした", e)
  }
}

private object Stop extends Exception("stop")

/** 取り消されたら（None）そこでやめる */
private def need[A](f: Future[Option[A]]): Future[A] = f.flatMap(_.fold[Future[A]](Future.failed(Stop))(Future.successful))

/** 知らせてやめる */
private def stop(msg: String): Future[Nothing] = Dialog.alert(msg).flatMap(_ => Future.failed(Stop))

private def run(f: Future[Any]): Unit = f.failed.foreach {
  case Stop => ()
  case e => Dialog.alertError("操作に失敗しました", e)
}

private def trimmed(f: Future[Option[String]]): Future[Option[String]] = f.map(_.map(_.trim).filter(_.nonEmpty))

// ---------------------------------------------------------------- ビュー

def setViews(s: State, props: Props, vs: List[DbView]): Unit = if (s.db.v.isDefined) {
  if (props.embedded) {
    s.local := vs
    props.onViews(DbView.toJson(vs))
  } else {
    s.db.v.foreach(cur => s.db := Some(cur.copy(views = vs)))
    saveSoon(s, props)
  }
}

def patchView(s: State, props: Props, f: DbView => DbView): Unit = s.view.v.foreach { cur =>
  setViews(s, props, s.views.v.map(v => if (v.id == cur.id) f(v) else v))
}

def dateProps(s: State): List[DbProp] = s.props_.v.filter(_.tpe == "date")

def selectProps(s: State): List[DbProp] = s.props_.v.filter(_.tpe == "select")

def urlProps(s: State): List[DbProp] = s.props_.v.filter(_.tpe == "url")

def groupableProps(s: State): List[DbProp] = s.props_.v.filter(p => Groupable(p.tpe))

def addView(s: State, props: Props): Unit = if (s.db.v.isDefined) run(for {
  t <- need(Dialog.choose("ビューの種類", Db.ViewTypes.map(t => Choice(s"${Db.ViewIcon(t)} ${Db.ViewLabel(t)}", t))))
  name <- need(trimmed(Dialog.prompt("ビューの名前", Db.shortLabel(Db.ViewLabel(t)), okLabel = "追加")))
} yield {
  val dps = dateProps(s)
  val d0 = dps.headOption.map(_.id)
  val v = DbView(Db.newId(), name, t,
    groupBy = if (t == "board") selectProps(s).headOption.map(_.id) else None,
    dateProp = if (t == "calendar") d0 else None,
    startProp = if (t == "timeline") d0 else None,
    endProp = if (t == "timeline") dps.lift(1).map(_.id) else None,
    cover = if (t == "gallery") Some("content") else None)
  setViews(s, props, s.views.v :+ v)
  s.activeId := Some(v.id)
})

def viewMenu(s: State, props: Props): Unit = for (_ <- s.db.v; v <- s.view.v) {
  val opts =
    (if (canEdit(s) && !props.embedded) List(Choice("📝 フォーム（回答が項目になる）", "forms")) else Nil) ++
      List(Choice("⤓ CSVで書き出し（このビューの項目・列）", "csv-out")) ++
      (if (canEdit(s)) List(Choice("⤒ CSVから取り込み", "csv-in")) else Nil) ++
      List(Choice("✏️ 名前を変更", "rename")) ++
      (if (s.views.v.size > 1) List(Choice("🗑 このビューを削除", "delete")) else Nil)
  Dialog.choose(s"ビュー「${v.name}」", opts).foreach {
    case Some("forms") => s.formsOpen := true
    case Some("csv-out") => exportCsv(s, props)
    case Some("csv-in") => importCsv(s, props)
    case Some("rename") =>
      trimmed(Dialog.prompt("ビューの名前", v.name, okLabel = "変更")).foreach(_.foreach(n => patchView(s, props, _.copy(name = n))))
    case Some("delete") =>
      Dialog.confirm(s"ビュー「${v.name}」を削除しますか？（項目は消えません）", okLabel = "削除", danger = true).foreach { ok =>
        if (ok) {
          val rest = s.views.v.filterNot(_.id == v.id)
          setViews(s, props, rest)
          s.activeId := rest.headOption.map(_.id)
        }
      }
    case _ => ()
  }
}

def onTab(s: State, props: Props): Unit = if (canEdit(s) || props.embedded) viewMenu(s, props)

// ---------------------------------------------------------------- CSV

def exportCsv(s: State, props: Props): Unit = if (s.db.v.isDefined) {
  val name = Store.byId(props.pageId).map(_.title).filter(_.nonEmpty).getOrElse("データベース")
  val csv = Db.toCsv(s.visibleProps.v, s.rows.v, s.ctx.v)
  sbirds.web.Dom.download(new dom.Blob(js.Array(csv), new dom.BlobPropertyBag { `type` = "text/csv;charset=utf-8" }), s"$name.csv")
}

/** CSV を取り込む: 1行目は列名（取り込み方は Db.planCsvImport） */
def importCsv(s: State, props: Props): Unit = run(for {
  f <- need(Db.pickFiles(".csv,text/csv").map(_.headOption))
  cur <- need(Future.successful(s.db.v))
  rowsIn <- Db.readCsv(f)
  _ <- if (rowsIn.size < 2) stop("取り込める行がありません（1行目は列名にしてください）")
       else if (rowsIn.size - 1 > 2000) stop("一度に取り込めるのは2000行までです")
       else Future.successful(())
  plan = Db.planCsvImport(rowsIn, cur)
  ok <- Dialog.confirm(s"${plan.items.size} 件の項目を取り込みますか？${if (plan.added.nonEmpty) s"\n新しい列（テキスト）: ${plan.added.mkString("、")}" else ""}", okLabel = "取り込む")
  _ <- if (ok) Future.successful(()) else Future.failed(Stop)
  _ <- (for {
    saved <- DbApi.save(props.pageId, plan.props, cur.views)
    _ = s.db.v.foreach(now => s.db := Some(now.copy(props = saved.props, targets = saved.targets)))
    created <- DbApi.addRows(props.pageId, plan.items)
    fresh <- DbApi.get(props.pageId)
    _ = { s.db := Some(fresh); s.error := ""; Store.reloadPages() }
    _ <- Dialog.alert(s"$created 件を取り込みました")
  } yield ()).recoverWith { case e if e != Stop => Dialog.alertError("取り込めませんでした", e) }
} yield ())

// ---------------------------------------------------------------- 表示する項目

def cellVal(s: State, p: DbProp, r: DbRow): Val =
  s.cache.v.getOrElseUpdate((r.page.id, p.id), Db.cellValue(p, Db.like(r), s.ctx.v))

def hasSubs(s: State, id: String): Boolean = s.parentIds.v(id)

def toggleSubs(s: State, id: String): Unit = s.openSubs := (if (s.openSubs.v(id)) s.openSubs.v - id else s.openSubs.v + id)

def toggleGroup(s: State, k: String): Unit = s.collapsed := (if (s.collapsed.v(k)) s.collapsed.v - k else s.collapsed.v + k)

def addSub(s: State, props: Props, parent: DbRow): Unit = run(for {
  t <- need(trimmed(Dialog.prompt(s"「${parent.page.displayTitle}」のサブ項目の名前", "", okLabel = "追加")))
  r <- DbApi.addRow(props.pageId, t, Map.empty, Some(parent.page.id)).recoverWith { case e => Dialog.alertError("追加できませんでした", e).flatMap(_ => Future.failed(Stop)) }
} yield {
  s.db.v.foreach(cur => s.db := Some(cur.copy(rows = cur.rows :+ r)))
  s.openSubs := s.openSubs.v + parent.page.id
  Store.reloadPages()
})

// --- 集計行 ---
def setCalc(s: State, props: Props, pid: String, fn: String): Unit =
  patchView(s, props, v => v.copy(calcs = Some(if (fn.nonEmpty) v.calcMap + (pid -> fn) else v.calcMap - pid)))

def calcRows(items: List[GRow]): List[DbRow] = items.filter(_.depth == 0).map(_.row)

def calcText(s: State, pid: String, items: List[GRow]): String =
  vw(s).calcMap.get(pid).fold("") { fn =>
    Db.calc(fn, if (pid == "title") None else s.props_.v.find(_.id == pid), calcRows(items), s.ctx.v)
  }

def calcOf(s: State, pid: String): String = vw(s).calcMap.getOrElse(pid, "")

// --- リスト・ギャラリー ---
def listProps(s: State): List[DbProp] = s.visibleProps.v.take(4)

def shown(s: State, p: DbProp, r: DbRow): Boolean = Db.text(p, cellVal(s, p, r), d(s).users, Some(s.ctx.v)).nonEmpty

def coverOf(s: State, r: DbRow): Option[String] = vw(s).cover.getOrElse("content") match {
  case "none" => None
  case "content" => r.meta.flatMap(_.cover)
  case c => r.values.get(c).flatMap(_.asString).filter(v => v.startsWith("http://") || v.startsWith("https://"))
}

// --- タイムライン・カレンダー ---
def dateProp(s: State, id: Option[String]): Option[DbProp] = id.flatMap(i => s.props_.v.find(p => p.id == i && p.tpe == "date"))

// ---------------------------------------------------------------- 値の変更・項目の追加

def updRow(s: State, id: String)(f: DbRow => DbRow): Unit =
  s.db.v.foreach(cur => s.db := Some(cur.copy(rows = cur.rows.map(r => if (r.page.id == id) f(r) else r))))

def rowById(s: State, id: String): Option[DbRow] = s.db.v.flatMap(_.rows.find(_.page.id == id))

def setValue(s: State, rowId: String, p: DbProp, v: Json): Future[Unit] = rowById(s, rowId) match {
  case None => Future.successful(())
  case Some(r) =>
    val before = r.values
    def put(values: Map[String, Json], x: Option[Json]) = x.filterNot(_.isNull).fold(values - p.id)(j => values + (p.id -> j))
    updRow(s, rowId)(r => r.copy(values = put(r.values, Some(v)))) // すぐに反映
    DbApi.setValues(rowId, Map(p.id -> v)).map { res =>
      // 変えた値だけを反映する（続けて別の値を変えたとき、先の応答で後の変更を戻さないように）
      updRow(s, rowId)(r => r.copy(values = put(r.values, res.values.get(p.id))))
      s.db.v.foreach(cur => s.db := Some(cur.copy(users = cur.users ++ res.users)))
    }.recoverWith { case e =>
      updRow(s, rowId)(_.copy(values = before))
      Dialog.alertError("変更できませんでした", e)
    }
}

def onUser(s: State, id: String, name: String): Unit = s.db.v.foreach(cur => s.db := Some(cur.copy(users = cur.users + (id -> name))))

def addRow(s: State, props: Props, title: String, values: Map[String, Json] = Map.empty): Future[Unit] =
  if (s.db.v.isEmpty) Future.successful(())
  else DbApi.addRow(props.pageId, title.trim, values).map { r =>
    s.db.v.foreach(cur => s.db := Some(cur.copy(rows = cur.rows :+ r)))
    Store.reloadPages()
    ()
  }.recoverWith { case e => Dialog.alertError("追加できませんでした", e) }

def addFromTable(s: State, props: Props): Unit = {
  val t = s.newTitle.v.trim
  if (t.nonEmpty) {
    s.newTitle := ""
    // 絞り込みの「が」条件は、新しい項目の値にも入れる（追加した項目が見えなくならないように）
    val vals = vw(s).filterList.flatMap { f =>
      s.props_.v.find(_.id == f.prop).filter(p => f.op == "is" && f.value.exists(_.nonEmpty) && Set("select", "date", "text")(p.tpe))
        .map(p => p.id -> Json.fromString(f.value.getOrElse("")))
    }.toMap
    addRow(s, props, t, vals)
  }
}

def onNewKey(s: State, props: Props, e: dom.Event): Unit =
  if (e.asInstanceOf[dom.KeyboardEvent].key == "Enter" && !DbDom.composing(e)) addFromTable(s, props)

def addPrompt(s: State, props: Props, values: Map[String, Json], label: String): Unit =
  trimmed(Dialog.prompt(s"新しい項目の名前（$label）", "", okLabel = "追加")).foreach(_.foreach(t => addRow(s, props, t, values)))

// ---------------------------------------------------------------- 列（項目の種類）

def openCol(s: State, e: dom.Event, id: String): Unit = {
  val r = e.currentTarget.asInstanceOf[dom.Element].getBoundingClientRect()
  s.colMenuAt := (math.min(r.left, dom.window.innerWidth - 270), r.bottom + 2)
  s.colMenu := (if (s.colMenu.v.contains(id)) None else Some(id))
}

def colProp(s: State): Option[DbProp] = s.colMenu.v.flatMap(id => s.props_.v.find(_.id == id))

def colMenuStyle(s: State): String = s"left: ${s.colMenuAt.v._1}px; top: ${s.colMenuAt.v._2}px"

def setProps(s: State, props: Props, f: List[DbProp] => List[DbProp]): Unit = s.db.v.foreach { cur =>
  s.db := Some(cur.copy(props = f(cur.props)))
  saveSoon(s, props)
}

def patchProp(s: State, props: Props, id: String, f: DbProp => DbProp): Unit = setProps(s, props, _.map(p => if (p.id == id) f(p) else p))

/** 数式を入力してもらう（正しく読めるまで聞き直す） */
def askFormula(cur: String): Future[Option[String]] =
  Dialog.prompt(s"数式\n${Formula.Help}", cur, okLabel = "設定", placeholder = "prop(\"金額\") * 1.1").flatMap {
    case None => Future.successful(None)
    case Some(r) =>
      val v = r.trim
      if (v.isEmpty) Future.successful(None)
      else Formula.check(v) match {
        case None => Future.successful(Some(v))
        case Some(err) => Dialog.alert(s"数式を読めませんでした: $err").flatMap(_ => askFormula(v))
      }
  }

/** 列の種類ごとに聞くこと（数式・ID の前の文字・関連先・集計のしかた） */
def extra(s: State, props: Props, p: DbProp): Future[DbProp] = p.tpe match {
  case "formula" => need(askFormula("")).map(ex => p.copy(expr = Some(ex)))
  case "unique_id" =>
    need(Dialog.prompt("ID の前に付ける文字（任意。例: TASK → TASK-12）", "", okLabel = "追加")).map { pre =>
      if (pre.trim.nonEmpty) p.copy(prefix = Some(pre.trim.replaceAll("[^0-9A-Za-z_-]", "").take(12))) else p
    }
  case "relation" =>
    val dbs = Store.pages.v.filter(_.isDatabase)
    if (dbs.isEmpty) stop("関連先にできるデータベースがありません（このワークスペースのデータベースから選びます）")
    else need(Dialog.choose("関連先のデータベース",
      dbs.map(x => Choice(s"📊 ${x.displayTitle}${if (x.id == props.pageId) "（このデータベース）" else ""}", x.id)))).map(t => p.copy(target = Some(t)))
  case "rollup" =>
    val cur = d(s)
    val rels = cur.props.filter(_.tpe == "relation")
    if (rels.isEmpty) stop("先に「関連」の列を追加してください")
    else for {
      rel <- if (rels.size == 1) Future.successful(rels.head.id) else need(Dialog.choose("どの関連を集計しますか？", rels.map(x => Choice(s"↗ ${x.name}", x.id))))
      fn <- need(Dialog.choose("集計のしかた", Db.Fns.map((f, l) => Choice(l, f))))
      tp <- if (fn == "count") Future.successful(None)
            else {
              val target = cur.props.find(_.id == rel).flatMap(_.target).flatMap(cur.targets.get)
              val cand = target.fold(List.empty[DbProp])(_.props).filter(x => if (fn == "checked") x.tpe == "checkbox" else if (fn == "count_values") true else x.tpe == "number")
              if (cand.isEmpty) stop(if (fn == "checked") "関連先に「チェック」の列がありません" else if (fn == "count_values") "関連先に列がありません" else "関連先に「数値」の列がありません")
              else need(Dialog.choose("関連先のどの列を集計しますか？", cand.map(x => Choice(s"${Db.icon(x.tpe)} ${x.name}", x.id)))).map(Some(_))
            }
    } yield p.copy(relation = Some(rel), fn = Some(fn), prop = tp)
  case _ => Future.successful(p)
}

def addProp(s: State, props: Props): Unit = if (s.db.v.isDefined) run(for {
  t <- need(Dialog.choose("追加する列の種類", Db.Types.map(t => Choice(s"${Db.icon(t)} ${Db.TypeLabel(t)}", t))))
  name <- need(trimmed(Dialog.prompt("列の名前", Db.shortLabel(Db.TypeLabel(t)), okLabel = "追加")))
  p <- extra(s, props, DbProp(Db.newId(), name, t, options = if (t == "select" || t == "multi_select") Some(Nil) else None))
} yield setProps(s, props, _ :+ p))

def editFormula(s: State, props: Props, p: DbProp): Unit =
  askFormula(p.expr.getOrElse("")).foreach(_.foreach(ex => patchProp(s, props, p.id, _.copy(expr = Some(ex)))))

def renameProp(s: State, props: Props, p: DbProp): Unit =
  trimmed(Dialog.prompt("列の名前", p.name, okLabel = "変更")).foreach(_.foreach(n => patchProp(s, props, p.id, _.copy(name = n))))

def changeType(s: State, props: Props, p: DbProp): Unit =
  Dialog.choose(s"「${p.name}」の種類（今の値は、新しい種類に合うものだけ表示されます）",
    Db.Types.filter(x => x != p.tpe && x != "relation" && !Db.Computed(x)).map(x => Choice(s"${Db.icon(x)} ${Db.TypeLabel(x)}", x))).foreach {
    case Some(t) => patchProp(s, props, p.id, _.copy(`type` = t, options = if (t == "select" || t == "multi_select") Some(p.opts) else None))
    case None => ()
  }

def deleteProp(s: State, props: Props, p: DbProp): Unit = {
  s.colMenu := None
  Dialog.confirm(s"列「${p.name}」を削除しますか？", okLabel = "削除", danger = true).foreach { ok =>
    if (ok) setProps(s, props, _.filterNot(_.id == p.id))
  }
}

def moveProp(s: State, props: Props, p: DbProp, dir: Int): Unit = setProps(s, props, { ps =>
  val i = ps.indexWhere(_.id == p.id)
  val j = i + dir
  if (i < 0 || j < 0 || j >= ps.size) ps else ps.updated(i, ps(j)).updated(j, ps(i))
})

def hideProp(s: State, props: Props, p: DbProp): Unit = {
  s.colMenu := None
  patchView(s, props, v => v.copy(hidden = Some(v.hiddenList :+ p.id)))
}

def showProp(s: State, props: Props, p: DbProp): Unit = patchView(s, props, v => v.copy(hidden = Some(v.hiddenList.filterNot(_ == p.id))))

def addOption(s: State, props: Props, p: DbProp): Unit =
  trimmed(Dialog.prompt("選択肢の名前", "", okLabel = "追加")).foreach(_.foreach { n =>
    patchProp(s, props, p.id, x => x.copy(options = Some(x.opts :+ DbOption(Db.newId(), n, Db.Colors(x.opts.size % Db.Colors.size)))))
  })

def renameOption(s: State, props: Props, p: DbProp, o: DbOption): Unit =
  trimmed(Dialog.prompt("選択肢の名前", o.name, okLabel = "変更")).foreach(_.foreach { n =>
    patchProp(s, props, p.id, x => x.copy(options = Some(x.opts.map(y => if (y.id == o.id) y.copy(name = n) else y))))
  })

def colorOption(s: State, props: Props, p: DbProp, o: DbOption, color: String): Unit =
  patchProp(s, props, p.id, x => x.copy(options = Some(x.opts.map(y => if (y.id == o.id) y.copy(color = color) else y))))

def deleteOption(s: State, props: Props, p: DbProp, o: DbOption): Unit =
  Dialog.confirm(s"選択肢「${o.name}」を削除しますか？（この値の項目は空になります）", okLabel = "削除", danger = true).foreach { ok =>
    if (ok) patchProp(s, props, p.id, x => x.copy(options = Some(x.opts.filterNot(_.id == o.id))))
  }

// ---------------------------------------------------------------- 絞り込み・並べ替え

def filters(s: State): List[DbFilter] = vw(s).filterList

def fieldOptions(s: State): List[(String, String)] = ("title" -> "名前") :: s.props_.v.map(p => p.id -> p.name)

def addFilter(s: State, props: Props): Unit = patchView(s, props, v => v.copy(filters = Some(v.filterList :+ DbFilter("title", "contains", Some("")))))

def typeOf(s: State, id: String): String = if (id == "title") "title" else s.props_.v.find(_.id == id).fold("text")(_.tpe)

def propOf(s: State, id: String): Option[DbProp] = s.props_.v.find(_.id == id)

def setFilter(s: State, props: Props, i: Int, f: DbFilter => DbFilter): Unit =
  patchView(s, props, v => v.copy(filters = Some(v.filterList.zipWithIndex.map((x, j) => if (j == i) f(x) else x))))

def setFilterProp(s: State, props: Props, i: Int, prop: String): Unit =
  setFilter(s, props, i, _ => DbFilter(prop, Db.opsFor(typeOf(s, prop)).head._1, Some("")))

def removeFilter(s: State, props: Props, i: Int): Unit =
  patchView(s, props, v => v.copy(filters = Some(v.filterList.zipWithIndex.filterNot(_._2 == i).map(_._1))))

def sortValue(s: State): String = vw(s).sort.fold("")(x => s"${x.prop}:${x.dir}")

def sortOptions(s: State): List[(String, String)] =
  (List("title" -> "名前", "updated" -> "更新日時") ++ s.props_.v.map(p => p.id -> p.name)).flatMap { (id, name) =>
    List(s"$id:asc" -> s"$name（昇順）", s"$id:desc" -> s"$name（降順）")
  }

def setSort(s: State, props: Props, v: String): Unit =
  if (v.isEmpty) patchView(s, props, _.copy(sort = None))
  else {
    val i = v.lastIndexOf(':')
    patchView(s, props, _.copy(sort = Some(DbSort(v.substring(0, i), v.substring(i + 1)))))
  }

def filterInputType(s: State, prop: String): String = typeOf(s, prop) match {
  case "date" => "date"
  case "number" => "number"
  case _ => "text"
}

def orNone(v: String): Option[String] = Option(v).filter(_.nonEmpty)

def filterOn(s: State): String = if (s.filterOpen.v || filters(s).nonEmpty) "on" else ""

// ---------------------------------------------------------------- ボード

def over(s: State, e: dom.Event, colId: String): Unit = { e.preventDefault(); s.dropCol := Some(colId) }

def dropOn(s: State, props: Props, e: dom.Event, colId: String): Unit = {
  e.preventDefault()
  val g = s.groupProp.v
  val r = s.dragRow.v.flatMap(rowById(s, _))
  s.dragRow := None
  s.dropCol := None
  for (g <- g; r <- r) {
    val cur = r.values.get(g.id).flatMap(_.asString).getOrElse("")
    if (cur != colId) setValue(s, r.page.id, g, if (colId.isEmpty) Json.Null else Json.fromString(colId))
  }
}

def bcolClass(s: State, c: Column): String = if (s.dropCol.v.contains(c.option.id)) "bcol drop" else "bcol"

def cardProps(s: State): List[DbProp] = s.visibleProps.v.filterNot(p => s.groupProp.v.exists(_.id == p.id)).take(4)

def addToColumn(s: State, props: Props, c: Column): Unit =
  addPrompt(s, props, s.groupProp.v.filter(_ => c.option.id.nonEmpty).fold(Map.empty[String, Json])(g => Map(g.id -> Json.fromString(c.option.id))), c.option.name)

// ---------------------------------------------------------------- そのほか

def rowHref(props: Props, r: DbRow): String = Nav.pageHref(props.wid, r.page.id)


def iconOf(r: DbRow): String = r.page.iconOr

def draggable(r: DbRow): String = if (r.page.canEdit) "true" else "false"

def tagClass(color: Option[String]): String = color.fold("tag")(c => s"tag c-$c")

def embTitle(props: Props): String = Store.byId(props.pageId).map(_.title).filter(_.nonEmpty).getOrElse("データベース")

def embHref(props: Props): String = Nav.pageHref(props.wid, props.pageId)

def tabClass(s: State, v: DbView): String = if (s.view.v.exists(_.id == v.id)) "on" else ""

def cellEditable(r: DbRow, p: DbProp): Boolean = r.page.canEdit && !Db.Computed(p.tpe)

def emptyText(s: State): String = if (d(s).rows.nonEmpty) "条件に合う項目はありません" else "まだ項目がありません"

def convertBack(s: State, props: Props): Unit =
  Dialog.confirm("データベースをやめて、普通のページに戻しますか？\n子ページと入力した値はそのまま残ります（もう一度データベースにすると表示されます）。", okLabel = "戻す", danger = true)
    .foreach(ok => if (ok) DbApi.remove(props.pageId).flatMap(_ => Store.reloadPages()).failed.foreach(e => Dialog.alertError("戻せませんでした", e)))
</script>

<template>
  <section class="db" data-testid="database">
    <p v-if="s.error.v.nonEmpty" class="error">{{ s.error }}</p>
    <template v-else-if="ready(s)">
      <div v-if="props.embedded" class="emb-head">
        <a :href="embHref(props)" class="emb-title" data-testid="db-embed-title" @click="Nav.link(event, embHref(props))">📊 {{ embTitle(props) }} ↗</a>
        <span class="muted small">（ここでのビューの変更は、この場所だけに保存されます）</span>
      </div>
      <div class="views" role="tablist">
        <button v-for="v in s.views" :key="v.id" role="tab" :class="tabClass(s, v)" data-testid="db-view-tab" @click="s.activeId := Some(v.id)" @dblclick="onTab(s, props)">
          {{ Db.ViewIcon.getOrElse(v.tpe, "") }} {{ v.name }}
        </button>
        <button v-if="canEdit(s) || props.embedded" class="ghost" title="ビューを追加" data-testid="db-view-add" @click="addView(s, props)">＋</button>
        <span class="spacer"></span>
        <input v-model="s.q" type="search" class="search" placeholder="🔍 検索" data-testid="db-search">
        <button :class="filterOn(s)" data-testid="db-filter-toggle" @click="s.filterOpen := !s.filterOpen.v">⚙ 絞り込み・並べ替え<span v-if="filters(s).nonEmpty" class="n">{{ filters(s).size }}</span></button>
        <button v-if="canEdit(s) || props.embedded" class="ghost" title="ビュー・データベースの操作" data-testid="db-view-menu" @click="viewMenu(s, props)">⋯</button>
        <HelpLink to="database" />
      </div>

      <div v-if="s.filterOpen" class="filters" data-testid="db-filters">
        <div v-for="fi in filters(s).zipWithIndex" :key="fi._2" class="frow">
          <select @change="setFilterProp(s, props, fi._2, Dom.value(event))">
            <option v-for="o in fieldOptions(s)" :key="o._1" :value="o._1" :selected="o._1 == fi._1.prop">{{ o._2 }}</option>
          </select>
          <template v-if="Db.needsValue(fi._1.op)">
            <select v-if='typeOf(s, fi._1.prop) == "select" || typeOf(s, fi._1.prop) == "multi_select"' data-testid="db-filter-value" @change="setFilter(s, props, fi._2, _.copy(value = Some(Dom.value(event))))">
              <option value="" :selected="fi._1.value.forall(_.isEmpty)">（選ぶ）</option>
              <option v-for="o in propOf(s, fi._1.prop).fold(List.empty[DbOption])(_.opts)" :key="o.id" :value="o.id" :selected="fi._1.value.contains(o.id)">{{ o.name }}</option>
            </select>
            <select v-else-if='typeOf(s, fi._1.prop) == "checkbox"' @change="setFilter(s, props, fi._2, _.copy(value = Some(Dom.value(event))))">
              <option value="true" :selected='fi._1.value.contains("true")'>✓ あり</option><option value="false" :selected='fi._1.value.contains("false")'>なし</option>
            </select>
            <select v-else-if='typeOf(s, fi._1.prop) == "person"' @change="setFilter(s, props, fi._2, _.copy(value = Some(Dom.value(event))))">
              <option value="" :selected="fi._1.value.forall(_.isEmpty)">（選ぶ）</option><option value="@me" :selected='fi._1.value.contains("@me")'>自分</option>
              <option v-for="u in d(s).users.toList" :key="u._1" :value="u._1" :selected="fi._1.value.contains(u._1)">{{ u._2 }}</option>
            </select>
            <input v-else :type="filterInputType(s, fi._1.prop)" :value='fi._1.value.getOrElse("")' data-testid="db-filter-input" @change="setFilter(s, props, fi._2, _.copy(value = Some(Dom.value(event))))">
          </template>
          <select @change="setFilter(s, props, fi._2, _.copy(op = Dom.value(event)))">
            <option v-for="o in Db.opsFor(typeOf(s, fi._1.prop))" :key="o._1" :value="o._1" :selected="o._1 == fi._1.op">{{ o._2 }}</option>
          </select>
          <button class="ghost" title="条件を外す" @click="removeFilter(s, props, fi._2)">×</button>
        </div>
        <div class="frow">
          <button class="ghost" data-testid="db-filter-add" @click="addFilter(s, props)">＋ 条件を追加</button>
          <span class="spacer"></span>
          並べ替え:
          <select data-testid="db-sort" @change="setSort(s, props, Dom.value(event))">
            <option value="" :selected="sortValue(s).isEmpty">手動（ページの並び）</option>
            <option v-for="o in sortOptions(s)" :key="o._1" :value="o._1" :selected="o._1 == sortValue(s)">{{ o._2 }}</option>
          </select>
        </div>
        <div v-if='vw(s).tpe == "board"' class="frow">
          列に分ける項目:
          <select data-testid="db-group-by" @change="patchView(s, props, _.copy(groupBy = orNone(Dom.value(event))))">
            <option value="" :selected="vw(s).groupBy.isEmpty">（選ぶ）</option>
            <option v-for="p in selectProps(s)" :key="p.id" :value="p.id" :selected="vw(s).groupBy.contains(p.id)">{{ p.name }}</option>
          </select>
        </div>
        <div v-if='vw(s).tpe == "table" || vw(s).tpe == "list"' class="frow">
          グループ化:
          <select data-testid="db-group" @change="patchView(s, props, _.copy(group = orNone(Dom.value(event))))">
            <option value="" :selected="vw(s).group.isEmpty">しない</option>
            <option v-for="p in groupableProps(s)" :key="p.id" :value="p.id" :selected="vw(s).group.contains(p.id)">{{ p.name }}</option>
          </select>
          <label v-if='vw(s).tpe == "table"' class="chk"><input type="checkbox" :checked="!vw(s).subItems.contains(false)" data-testid="db-subitems" @change="patchView(s, props, _.copy(subItems = Some(Dom.checked(event))))"> サブ項目を親の下に表示</label>
        </div>
        <div v-if='vw(s).tpe == "gallery"' class="frow">
          表紙:
          <select data-testid="db-cover" @change="patchView(s, props, _.copy(cover = Some(Dom.value(event))))">
            <option value="content" :selected='vw(s).cover.forall(_ == "content")'>本文の最初の画像</option>
            <option value="none" :selected='vw(s).cover.contains("none")'>なし</option>
            <option v-for="p in urlProps(s)" :key="p.id" :value="p.id" :selected="vw(s).cover.contains(p.id)">URL の列: {{ p.name }}</option>
          </select>
        </div>
        <div v-if='vw(s).tpe == "timeline"' class="frow">
          開始:
          <select data-testid="db-tl-start" @change="patchView(s, props, _.copy(startProp = orNone(Dom.value(event))))">
            <option value="" :selected="vw(s).startProp.isEmpty">（選ぶ）</option>
            <option v-for="p in dateProps(s)" :key="p.id" :value="p.id" :selected="vw(s).startProp.contains(p.id)">{{ p.name }}</option>
          </select>
          終了:
          <select data-testid="db-tl-end" @change="patchView(s, props, _.copy(endProp = orNone(Dom.value(event))))">
            <option value="" :selected="vw(s).endProp.isEmpty">（なし: 1日だけ）</option>
            <option v-for="p in dateProps(s)" :key="p.id" :value="p.id" :selected="vw(s).endProp.contains(p.id)">{{ p.name }}</option>
          </select>
        </div>
        <div v-if='vw(s).tpe == "calendar"' class="frow">
          日付の項目:
          <select data-testid="db-date-prop" @change="patchView(s, props, _.copy(dateProp = orNone(Dom.value(event))))">
            <option value="" :selected="vw(s).dateProp.isEmpty">（選ぶ）</option>
            <option v-for="p in dateProps(s)" :key="p.id" :value="p.id" :selected="vw(s).dateProp.contains(p.id)">{{ p.name }}</option>
          </select>
        </div>
        <div v-if="s.hiddenProps.v.nonEmpty" class="frow">
          表示していない列:
          <button v-for="p in s.hiddenProps" :key="p.id" class="ghost" @click="showProp(s, props, p)">👁 {{ p.name }}</button>
        </div>
        <p v-if="!canEdit(s)" class="muted small">閲覧のみのため、ビューの変更はこの画面だけに反映されます。</p>
        <div v-if="canEdit(s) &amp;&amp; !props.embedded" class="frow"><span class="spacer"></span><button class="ghost danger" data-testid="db-convert-back" @click="convertBack(s, props)">データベースをやめる…</button></div>
      </div>

      <!-- 表 -->
      <div v-if='vw(s).tpe == "table"' class="table-wrap">
        <table class="tbl" data-testid="db-table">
          <thead>
            <tr>
              <th class="title-col">名前</th>
              <th v-for="p in s.visibleProps" :key="p.id" :class='"t-" + p.tpe'>
                <button class="th" :disabled="!canEdit(s)" data-testid="db-col" @click="openCol(s, event, p.id)">{{ Db.icon(p.tpe) }} {{ p.name }}</button>
              </th>
              <th v-if="canEdit(s)" class="add-col"><button class="ghost" title="列を追加" data-testid="db-col-add" @click="addProp(s, props)">＋</button></th>
            </tr>
          </thead>
          <tbody v-for="g in s.groups" :key="g.key" data-testid="db-group-body">
            <tr v-if="s.tableGroup.v.isDefined" class="ghead" data-testid="db-group-head">
              <td :colspan="s.visibleProps.v.size + 2">
                <button class="ghost twisty" @click="toggleGroup(s, g.key)">{{ if (s.collapsed.v(g.key)) "▸" else "▾" }}</button>
                <span :class="tagClass(g.color)">{{ g.label }}</span>
                <span class="muted small">{{ calcRows(g.items).size }}</span>
              </td>
            </tr>
            <template v-if="!s.collapsed.v(g.key)">
              <tr v-for="x in g.items" :key="x.row.page.id" :class='if (x.depth > 0) "sub" else ""' data-testid="db-row">
                <td class="title-col">
                  <span class="tcell" :style='"padding-left: " + (x.depth * 1.3) + "rem"'>
                    <button v-if="s.nest.v &amp;&amp; x.depth == 0 &amp;&amp; hasSubs(s, x.row.page.id)" class="ghost twisty" :title='if (s.openSubs.v(x.row.page.id)) "サブ項目を閉じる" else "サブ項目を開く"' data-testid="db-sub-toggle" @click="toggleSubs(s, x.row.page.id)">{{ if (s.openSubs.v(x.row.page.id)) "▾" else "▸" }}</button>
                    <span v-else-if="s.nest.v &amp;&amp; x.depth == 0" class="twisty-space"></span>
                    <a :href="rowHref(props, x.row)" class="rlink" @click="Nav.link(event, rowHref(props, x.row))">{{ iconOf(x.row) }} {{ x.row.page.displayTitle }}</a>
                    <button v-if="s.nest.v &amp;&amp; x.depth == 0 &amp;&amp; x.row.page.canEdit" class="ghost add-sub" title="サブ項目を追加" data-testid="db-sub-add" @click="addSub(s, props, x.row)">＋</button>
                  </span>
                </td>
                <td v-for="p in s.visibleProps" :key="p.id" :class='"t-" + p.tpe'>
                  <DbCell :prop="p" :value="cellVal(s, p, x.row)" :users="d(s).users" :ctx="Some(s.ctx.v)" :editable="cellEditable(x.row, p)" :wid="props.wid"
                    :onUpdate="v => setValue(s, x.row.page.id, p, v)" :onUser="(id, n) => onUser(s, id, n)" :pop="Some(s.pop)" :popKey="x.row.page.id + ':' + p.id" />
                </td>
                <td v-if="canEdit(s)"></td>
              </tr>
            </template>
            <tr class="calc" data-testid="db-calc-row">
              <td>
                <select class="calc-sel" title="集計" @change='setCalc(s, props, "title", Dom.value(event))'>
                  <option value="" :selected='calcOf(s, "title").isEmpty'>{{ if (calcOf(s, "title").nonEmpty) "" else "集計" }}</option><option value="count" :selected='calcOf(s, "title") == "count"'>件数</option>
                </select>
                <span class="calc-v">{{ calcText(s, "title", g.items) }}</span>
              </td>
              <td v-for="p in s.visibleProps" :key="p.id">
                <select class="calc-sel" title="集計" data-testid="db-calc" @change="setCalc(s, props, p.id, Dom.value(event))">
                  <option value="" :selected="calcOf(s, p.id).isEmpty">{{ if (calcOf(s, p.id).nonEmpty) "（なし）" else "集計" }}</option>
                  <option v-for="f in Db.calcsFor(p.tpe)" :key="f" :value="f" :selected="calcOf(s, p.id) == f">{{ Db.CalcLabel(f) }}</option>
                </select>
                <span class="calc-v" data-testid="db-calc-value">{{ calcText(s, p.id, g.items) }}</span>
              </td>
              <td v-if="canEdit(s)"></td>
            </tr>
          </tbody>
          <tbody>
            <tr v-if="canEdit(s)" class="new">
              <td :colspan="s.visibleProps.v.size + 2">
                <input v-model="s.newTitle" placeholder="＋ 新しい項目（名前を入力して Enter）" data-testid="db-new-row" @keydown="onNewKey(s, props, event)">
              </td>
            </tr>
          </tbody>
        </table>
        <p v-if="s.rows.v.isEmpty" class="muted small empty">{{ emptyText(s) }}</p>
      </div>

      <!-- リスト -->
      <div v-else-if='vw(s).tpe == "list"' class="list" data-testid="db-list">
        <template v-for="g in s.groups" :key="g.key">
          <div v-if="s.tableGroup.v.isDefined" class="lgh"><span :class="tagClass(g.color)">{{ g.label }}</span><span class="muted small">{{ g.items.size }}</span></div>
          <div v-for="x in g.items" :key="x.row.page.id" class="litem" data-testid="db-list-item">
            <a :href="rowHref(props, x.row)" class="rlink" @click="Nav.link(event, rowHref(props, x.row))">{{ iconOf(x.row) }} {{ x.row.page.displayTitle }}</a>
            <span class="lprops">
              <template v-for="p in listProps(s)" :key="p.id">
                <DbCell v-if="shown(s, p, x.row)" :prop="p" :value="cellVal(s, p, x.row)" :users="d(s).users" :ctx="Some(s.ctx.v)" :editable="false" :compact="true" :wid="props.wid" />
              </template>
            </span>
          </div>
        </template>
        <button v-if="canEdit(s)" class="ghost addc" @click="addPrompt(s, props, Map.empty, vw(s).name)">＋ 新しい項目</button>
      </div>

      <!-- ギャラリー -->
      <div v-else-if='vw(s).tpe == "gallery"' class="gallery" data-testid="db-gallery">
        <a v-for="r in s.rows" :key="r.page.id" :href="rowHref(props, r)" class="gcard" data-testid="db-gallery-card" @click="Nav.link(event, rowHref(props, r))">
          <div v-if='!vw(s).cover.contains("none")' class="gcover">
            <img v-if="coverOf(s, r).isDefined" :src='coverOf(s, r).getOrElse("")' alt="" loading="lazy">
            <span v-else class="gexcerpt">{{ r.meta.flatMap(_.excerpt).getOrElse("") }}</span>
          </div>
          <div class="gbody">
            <div class="gtitle">{{ iconOf(r) }} {{ r.page.displayTitle }}</div>
            <template v-for="p in listProps(s)" :key="p.id">
              <DbCell v-if="shown(s, p, r)" :prop="p" :value="cellVal(s, p, r)" :users="d(s).users" :ctx="Some(s.ctx.v)" :editable="false" :compact="true" :wid="props.wid" />
            </template>
          </div>
        </a>
        <button v-if="canEdit(s)" class="gcard gadd" @click="addPrompt(s, props, Map.empty, vw(s).name)">＋ 新しい項目</button>
      </div>

      <!-- タイムライン -->
      <DbTimeline v-else-if='vw(s).tpe == "timeline"' :rows="s.rows.v" :startProp="dateProp(s, vw(s).startProp)" :endProp="dateProp(s, vw(s).endProp)"
        :hasDateProps="dateProps(s).nonEmpty" :wid="props.wid" :zoom="s.tlZoom" :start="s.tlStart"
        :setValue="(id, p, v) => setValue(s, id, p, v)" :rowNow="id => rowById(s, id)" />

      <!-- ボード -->
      <div v-else-if='vw(s).tpe == "board"' class="board" data-testid="db-board">
        <p v-if="s.groupProp.v.isEmpty" class="muted">「⚙ 絞り込み・並べ替え」で、列に分ける項目（選択）を選んでください。<template v-if="selectProps(s).isEmpty">先に「選択」の列を追加してください。</template></p>
        <div v-for="c in s.columns" :key="c.option.id" :class="bcolClass(s, c)" data-testid="db-board-col"
          @dragover="over(s, event, c.option.id)" @dragleave="s.dropCol := None" @drop="dropOn(s, props, event, c.option.id)">
          <div class="bhead"><span :class="tagClass(Some(c.option.color))">{{ c.option.name }}</span><span class="muted small">{{ c.rows.size }}</span></div>
          <div v-for="r in c.rows" :key="r.page.id" class="card" :draggable="draggable(r)" data-testid="db-card" @dragstart="s.dragRow := Some(r.page.id)" @dragend="s.dragRow := None">
            <a :href="rowHref(props, r)" class="rlink" @click="Nav.link(event, rowHref(props, r))">{{ iconOf(r) }} {{ r.page.displayTitle }}</a>
            <template v-for="p in cardProps(s)" :key="p.id">
              <div v-if="shown(s, p, r)" class="cprop">
                <DbCell :prop="p" :value="cellVal(s, p, r)" :users="d(s).users" :ctx="Some(s.ctx.v)" :editable="false" :compact="true" :wid="props.wid" />
              </div>
            </template>
          </div>
          <button v-if="canEdit(s)" class="ghost addc" @click="addToColumn(s, props, c)">＋ 追加</button>
        </div>
      </div>

      <!-- カレンダー -->
      <DbCalendar v-else :rows="s.rows.v" :dateProp="dateProp(s, vw(s).dateProp)" :hasDateProps="dateProps(s).nonEmpty" :canEdit="canEdit(s)" :wid="props.wid"
        :month="s.month" :onSet="(r, p, v) => setValue(s, r.page.id, p, v)" :onAdd="(vals, label) => addPrompt(s, props, vals, label)" />

      <!-- 列の設定 -->
      <template v-for="cp in colProp(s).toList" :key="cp.id">
        <div v-if="canEdit(s)" class="colmenu" :style="colMenuStyle(s)" data-testid="db-col-menu" @mouseleave="s.colMenu := None">
          <div class="cm-head">{{ Db.icon(cp.tpe) }} {{ cp.name }} <span class="muted small">（{{ Db.TypeLabel.getOrElse(cp.tpe, "") }}）</span></div>
          <button @click="renameProp(s, props, cp)">✏️ 名前を変更</button>
          <button @click="changeType(s, props, cp)">🔁 種類を変更</button>
          <button @click="moveProp(s, props, cp, -1)">← 左へ</button>
          <button @click="moveProp(s, props, cp, 1)">→ 右へ</button>
          <button @click="hideProp(s, props, cp)">🙈 このビューで隠す</button>
          <button v-if='cp.tpe == "formula"' data-testid="db-formula-edit" @click="editFormula(s, props, cp)">ƒ 数式を編集</button>
          <div v-if='cp.tpe == "formula"' class="cm-sub mono">{{ cp.expr.getOrElse("") }}</div>
          <template v-if='cp.tpe == "select" || cp.tpe == "multi_select"'>
            <div class="cm-sub">選択肢</div>
            <div v-for="o in cp.opts" :key="o.id" class="opt">
              <span :class="tagClass(Some(o.color))" @click="renameOption(s, props, cp, o)">{{ o.name }}</span>
              <select title="色" @change="colorOption(s, props, cp, o, Dom.value(event))">
                <option v-for="c in Db.Colors" :key="c" :value="c" :selected="c == o.color">{{ Db.ColorLabel(c) }}</option>
              </select>
              <button class="ghost" title="削除" @click="deleteOption(s, props, cp, o)">×</button>
            </div>
            <button data-testid="db-option-add" @click="addOption(s, props, cp)">＋ 選択肢を追加</button>
          </template>
          <button class="danger" @click="deleteProp(s, props, cp)">🗑 列を削除</button>
        </div>
      </template>
    </template>
    <p v-else class="muted">読み込み中…</p>
    <FormsDialog v-if="s.formsOpen.v &amp;&amp; s.db.v.isDefined" :pageId="props.pageId" :dbProps="d(s).props" :onClose="_ => s.formsOpen := false" />
  </section>
</template>

<style scoped>
.ghost { border-color: transparent !important; background: none; color: var(--muted); }
.ghost:hover { color: var(--fg); }
.rlink { color: inherit; text-decoration: none; font-weight: 500; }
.rlink:hover { color: var(--accent); text-decoration: underline; }
.cal-head { display: flex; align-items: center; gap: .5rem; margin-bottom: .4rem; }
.db { margin: .5rem 0 1.5rem; }
.emb-head { display: flex; align-items: baseline; gap: .6rem; margin-bottom: .3rem; flex-wrap: wrap; }
.emb-title { font-weight: 700; font-size: 1.05rem; color: inherit; text-decoration: none; }
.emb-title:hover { color: var(--accent); }
.chk { display: inline-flex; align-items: center; gap: .25rem; }
.twisty { padding: 0 3px !important; font-size: .75rem; }
.twisty-space { display: inline-block; width: 1.1rem; }
.tcell { display: flex; align-items: center; gap: 2px; }
.add-sub { opacity: 0; font-size: .8rem; padding: 0 4px !important; margin-left: auto; }
tr:hover .add-sub { opacity: .8; }
tr.sub td { background: color-mix(in srgb, var(--side) 60%, transparent); }
.ghead td { background: var(--bg) !important; border-left: none; border-right: none; padding-top: .6rem; }
.calc td { border: none; border-top: 1px solid var(--line); background: none; font-size: .75rem; color: var(--muted); }
.calc-sel { font: inherit; font-size: .72rem; border: none; background: none; color: var(--muted); opacity: 0; max-width: 5rem; }
.calc:hover .calc-sel, .calc-sel:focus { opacity: 1; }
.calc-v { margin-left: .2rem; color: var(--fg); }
.mono { font-family: ui-monospace, monospace; word-break: break-all; }
.list { display: flex; flex-direction: column; }
.lgh { display: flex; gap: .5rem; align-items: center; margin: .6rem 0 .2rem; }
.litem { display: flex; align-items: center; gap: .8rem; padding: .35rem .3rem; border-bottom: 1px solid var(--line); flex-wrap: wrap; }
.lprops { display: flex; gap: .6rem; margin-left: auto; flex-wrap: wrap; }
.gallery { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: .7rem; }
.gcard { display: flex; flex-direction: column; border: 1px solid var(--line); border-radius: 8px; overflow: hidden; color: inherit; text-decoration: none; min-height: 7rem; background: var(--bg); }
.gcard:hover { box-shadow: 0 4px 14px rgba(0,0,0,.1); }
.gcover { height: 120px; background: var(--side); overflow: hidden; display: flex; }
.gcover img { width: 100%; height: 100%; object-fit: cover; }
.gexcerpt { padding: .5rem .6rem; font-size: .72rem; color: var(--muted); line-height: 1.5; overflow: hidden; }
.gbody { padding: .45rem .6rem; display: flex; flex-direction: column; gap: 2px; }
.gtitle { font-weight: 600; font-size: .88rem; }
.gadd { align-items: center; justify-content: center; color: var(--muted); border-style: dashed; font-size: .85rem; }
.views { display: flex; align-items: center; gap: 3px; flex-wrap: wrap; border-bottom: 1px solid var(--line); padding-bottom: 4px; margin-bottom: 6px; }
.views button { font-size: .8rem; padding: 2px 9px; border-color: transparent; color: var(--muted); }
.views button.on { color: var(--fg); background: var(--hover); }
.spacer { flex: 1; }
.search { font: inherit; font-size: .8rem; padding: 2px 8px; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; width: 9rem; }
.n { margin-left: 3px; font-size: .7rem; background: var(--accent); color: #fff; border-radius: 8px; padding: 0 5px; }
.filters { border: 1px solid var(--line); border-radius: 8px; padding: .5rem .6rem; margin-bottom: .6rem; font-size: .82rem; display: flex; flex-direction: column; gap: .35rem; }
.frow { display: flex; align-items: center; gap: .35rem; flex-wrap: wrap; }
.frow select, .frow input { font: inherit; font-size: .8rem; padding: 1px 5px; border: 1px solid var(--line); border-radius: 5px; background: var(--bg); color: inherit; }
.table-wrap { overflow-x: auto; }
.tbl { border-collapse: collapse; width: 100%; font-size: .85rem; }
.tbl th, .tbl td { border: 1px solid var(--line); padding: 2px 6px; vertical-align: top; text-align: left; }
.tbl th { font-weight: 500; color: var(--muted); background: var(--side); white-space: nowrap; }
.th { border: none; background: none; padding: 2px 0; color: inherit; font-size: .8rem; }
.title-col { min-width: 12rem; }
.tbl td.t-text { min-width: 10rem; }
.tbl td.t-person, .tbl td.t-multi_select { min-width: 8rem; }
.add-col { width: 2rem; }
.new input { width: 100%; border: none; background: none; font: inherit; font-size: .82rem; padding: 4px 0; color: inherit; }
.empty { padding: .4rem; }
.board { display: flex; gap: .6rem; overflow-x: auto; padding-bottom: .5rem; align-items: flex-start; }
.bcol { flex: 0 0 15rem; background: var(--side); border-radius: 8px; padding: .4rem; display: flex; flex-direction: column; gap: .35rem; min-height: 4rem; }
.bcol.drop { outline: 2px dashed var(--accent); }
.bhead { display: flex; justify-content: space-between; align-items: center; padding: 0 .2rem; }
.card { background: var(--bg); border: 1px solid var(--line); border-radius: 6px; padding: .4rem .5rem; font-size: .85rem; cursor: grab; }
.cprop { margin-top: 2px; }
.addc { align-self: flex-start; font-size: .78rem; }
.colmenu { position: fixed; z-index: 50; width: 260px; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 4px; box-shadow: 0 8px 24px rgba(0,0,0,.18); display: flex; flex-direction: column; font-size: .83rem; max-height: 70vh; overflow: auto; }
.colmenu > button { text-align: left; border: none; padding: 4px 8px; border-radius: 5px; }
.colmenu > button:hover { background: var(--hover); }
.cm-head { padding: 4px 8px; font-weight: 600; }
.cm-sub { padding: 4px 8px 0; color: var(--muted); font-size: .75rem; border-top: 1px solid var(--line); margin-top: 3px; }
.opt { display: flex; align-items: center; gap: 4px; padding: 2px 8px; }
.opt .tag { flex: 1; cursor: pointer; }
.opt select { font-size: .75rem; }
.danger:hover { color: var(--danger); }
.tag { display: inline-block; padding: 0 7px; border-radius: 4px; font-size: .78rem; line-height: 1.6; }
.small { font-size: .76rem; }
@media (max-width: 640px) { .search { width: 100%; order: 10; } }
@media print { .views button:not(.on), .views .ghost, .views .search, .new, .add-col, .addc { display: none !important; } }
</style>
