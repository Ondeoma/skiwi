<script lang="scala">
import io.circe.Json
import org.scalajs.dom

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

// データベースのタイムライン: 開始〜終了の期間を横棒で。横棒のドラッグで日付を変える。
// zoom（day・week）・start（左端の日. ミリ秒）は、ビューを切り替えても残るよう親が持つ。
// setValue: 値を変える（項目のID, 列, 値）。続けて変えるため、終わったら完了する Future を返す
case class Props(rows: List[DbRow],
                 startProp: Option[DbProp],
                 endProp: Option[DbProp],
                 hasDateProps: Boolean,
                 wid: String,
                 zoom: RV[String],
                 start: RV[Double],
                 setValue: (String, DbProp, Json) => Future[Unit] = (_, _, _) => Future.successful(()),
                 rowNow: String => Option[DbRow] = _ => None)

/** ドラッグ中の横棒. mode: move（移動）・end（右端で終了日だけ） */
final case class Drag(id: String, mode: String, x0: Double, dx: Double)

final case class Item(row: DbRow, left: Int, width: Int)

case class State(drag: RV[Option[Drag]] = RV(None))

def initState() = State()

val DayMs = 86400000.0

def today: String = Db.ymd(new js.Date())

def days(props: Props): Int = if (props.zoom.v == "day") 42 else 120

def colW(props: Props): Int = if (props.zoom.v == "day") 34 else 12

private val DateRe = """^\d{4}-\d{2}-\d{2}$""".r

def parseD(v: Option[Json]): Option[js.Date] = v.flatMap(_.asString).filter(DateRe.matches).map(s => new js.Date(s + "T00:00:00"))

def cols(props: Props): List[js.Date] = List.tabulate(days(props)) { i =>
  val d = new js.Date(props.start.v)
  d.setDate(d.getDate() + i)
  d
}

def items(props: Props): List[Item] = props.startProp match {
  case None => Nil
  case Some(s) =>
    val st = props.start.v
    props.rows.map { r =>
      val a = parseD(r.values.get(s.id))
      val b = props.endProp.fold(a)(e => parseD(r.values.get(e.id)).orElse(a))
      (a, b) match {
        case (Some(a), Some(b)) =>
          val from = js.Math.round((a.getTime() - st) / DayMs).toInt
          val to = js.Math.round((math.max(a.getTime(), b.getTime()) - st) / DayMs).toInt
          Item(r, from, to - from + 1)
        case _ => Item(r, -1, 0)
      }
    }
}

def visible(it: Item): Boolean = it.left >= -it.width && it.width > 0

def shift(props: Props, n: Int): Unit = {
  val d = new js.Date(props.start.v)
  d.setDate(d.getDate() + n)
  props.start := d.getTime()
}

def step(props: Props): Int = if (props.zoom.v == "day") 14 else 60

/** 横棒のドラッグ: 移動（両端を同じだけずらす）・右端で終了日だけ変える */
def down(s: State, props: Props, e: dom.Event, r: DbRow, mode: String): Unit = if (r.page.canEdit) {
  val me = e.asInstanceOf[dom.MouseEvent]
  me.preventDefault()
  if (mode == "end") me.stopPropagation()
  s.drag := Some(Drag(r.page.id, mode, me.clientX, 0))
  lazy val move: js.Function1[dom.MouseEvent, Unit] = (ev: dom.MouseEvent) => s.drag.v.foreach(d => s.drag := Some(d.copy(dx = ev.clientX - d.x0)))
  lazy val up: js.Function1[dom.MouseEvent, Unit] = (_: dom.MouseEvent) => {
    dom.window.removeEventListener("mousemove", move)
    dom.window.removeEventListener("mouseup", up)
    val d = s.drag.v
    s.drag := None
    for (d <- d; sp <- props.startProp) {
      val n = js.Math.round(d.dx / colW(props)).toInt
      def shifted(v: Option[Json]): Json = parseD(v).fold(Json.Null) { x => x.setDate(x.getDate() + n); Json.fromString(Db.ymd(x)) }
      def cur = props.rowNow(d.id).orElse(props.rows.find(_.page.id == d.id))
      if (n != 0) cur.foreach { row =>
        if (d.mode == "move") {
          props.setValue(row.page.id, sp, shifted(row.values.get(sp.id))).foreach { _ =>
            props.endProp.foreach { ep =>
              cur.flatMap(_.values.get(ep.id)).filterNot(_.isNull).foreach(v => props.setValue(row.page.id, ep, shifted(Some(v))))
            }
          }
        } else props.endProp.foreach { ep =>
          props.setValue(row.page.id, ep, shifted(row.values.get(ep.id).filterNot(_.isNull).orElse(row.values.get(sp.id))))
        }
      }
    }
  }
  dom.window.addEventListener("mousemove", move)
  dom.window.addEventListener("mouseup", up)
}

def barStyle(s: State, props: Props, it: Item): String = {
  val d = s.drag.v.filter(_.id == it.row.page.id)
  val w = colW(props)
  val dl = d.fold(0)(x => js.Math.round(x.dx / w).toInt)
  val left = it.left + (if (d.exists(_.mode == "move")) dl else 0)
  val width = math.max(1, it.width + (if (d.exists(_.mode == "end")) dl else 0))
  s"left: ${left * w}px; width: ${width * w - 4}px"
}

def barClass(it: Item): String = if (it.row.page.canEdit) "tl-bar" else "tl-bar ro"

def dayClass(d: js.Date): String =
  "tl-day" + (if (Db.ymd(d) == today) " today" else "") + (if (d.getDay() == 0 || d.getDay() == 6) " wk" else "")

def dayLabel(props: Props, d: js.Date, i: Int): String = {
  val dayZoom = props.zoom.v == "day"
  if (dayZoom || d.getDate() == 1 || d.getDay() == 1)
    (if (d.getDate() == 1 || i == 0) s"${d.getMonth().toInt + 1}/" else "") + (if (dayZoom || d.getDay() == 1) d.getDate().toInt.toString else "")
  else ""
}

def gridStyle(props: Props): String = s"width: ${days(props) * colW(props)}px"

def colStyle(props: Props): String = s"width: ${colW(props)}px"

def href(props: Props, r: DbRow): String = DbDom.pageHref(props.wid, r.page.id)

def titleOf(r: DbRow): String = if (r.page.title.isEmpty) "無題" else r.page.title
</script>

<template>
  <div class="timeline" data-testid="db-timeline">
    <p v-if="props.startProp.isEmpty" class="muted">「⚙ 絞り込み・並べ替え」で、開始の日付の列を選んでください。<template v-if="!props.hasDateProps">先に「日付」の列を追加してください。</template></p>
    <template v-else>
      <div class="cal-head">
        <button class="ghost" @click="shift(props, -step(props))">‹</button>
        <button class="ghost" @click="props.start := Db.weekAgo()">今日</button>
        <button class="ghost" @click="shift(props, step(props))">›</button>
        <select v-model="props.zoom" class="tl-zoom"><option value="day">日</option><option value="week">週</option></select>
      </div>
      <div class="tl-wrap">
        <div class="tl-names">
          <div class="tl-hrow"></div>
          <div v-for="it in items(props)" :key="it.row.page.id" class="tl-name"><a :href="href(props, it.row)" class="rlink" @click="DbDom.link(event, href(props, it.row))">{{ titleOf(it.row) }}</a></div>
        </div>
        <div class="tl-grid" :style="gridStyle(props)">
          <div class="tl-hrow">
            <div v-for="di in cols(props).zipWithIndex" :key="di._2" :class="dayClass(di._1)" :style="colStyle(props)">{{ dayLabel(props, di._1, di._2) }}</div>
          </div>
          <div v-for="it in items(props)" :key="it.row.page.id" class="tl-row">
            <div v-if="visible(it)" :class="barClass(it)" :style="barStyle(s, props, it)" :title="titleOf(it.row)" data-testid="db-tl-bar" @mousedown='down(s, props, event, it.row, "move")'>
              <span class="tl-label">{{ titleOf(it.row) }}</span>
              <span v-if="props.endProp.isDefined" class="tl-grip" @mousedown='down(s, props, event, it.row, "end")'></span>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.ghost { border-color: transparent !important; background: none; color: var(--muted); }
.ghost:hover { color: var(--fg); }
.rlink { color: inherit; text-decoration: none; font-weight: 500; }
.rlink:hover { color: var(--accent); text-decoration: underline; }
.cal-head { display: flex; align-items: center; gap: .5rem; margin-bottom: .4rem; }
.timeline .tl-wrap { display: flex; border: 1px solid var(--line); border-radius: 6px; overflow: hidden; }
.tl-names { flex: none; width: 11rem; border-right: 1px solid var(--line); background: var(--bg); }
.tl-name { height: 32px; display: flex; align-items: center; padding: 0 .5rem; font-size: .82rem; border-bottom: 1px solid var(--line); overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.tl-grid { overflow-x: auto; position: relative; flex: 1; }
.tl-wrap .tl-grid { max-width: calc(100% - 11rem); }
.tl-hrow { height: 26px; display: flex; border-bottom: 1px solid var(--line); background: var(--side); }
.tl-day { flex: none; font-size: .66rem; color: var(--muted); text-align: center; line-height: 26px; border-right: 1px solid color-mix(in srgb, var(--line) 50%, transparent); }
.tl-day.wk { background: color-mix(in srgb, var(--hover) 60%, transparent); }
.tl-day.today { color: var(--accent); font-weight: 700; }
.tl-row { height: 32px; position: relative; border-bottom: 1px solid var(--line); }
.tl-bar { position: absolute; top: 5px; height: 22px; border-radius: 5px; background: color-mix(in srgb, var(--accent) 22%, var(--bg)); border: 1px solid color-mix(in srgb, var(--accent) 55%, transparent); cursor: grab; display: flex; align-items: center; overflow: hidden; margin-left: 2px; }
.tl-bar.ro { cursor: default; }
.tl-label { font-size: .74rem; padding: 0 6px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; flex: 1; }
.tl-grip { width: 7px; align-self: stretch; cursor: ew-resize; background: color-mix(in srgb, var(--accent) 40%, transparent); }
.tl-zoom { font: inherit; font-size: .78rem; }
</style>
