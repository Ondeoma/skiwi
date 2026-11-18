<script lang="scala">
import io.circe.Json

import scala.scalajs.js

// データベースのカレンダー（月曜はじまり）。項目を別の日へドラッグすると日付を変える（onSet）。
// month: 表示する月の1日（ミリ秒。ビューを切り替えても残るよう親が持つ）/ onAdd: その日に項目を追加（値, 見出し）
case class Props(rows: List[DbRow],
                 dateProp: Option[DbProp],
                 hasDateProps: Boolean,
                 canEdit: Boolean,
                 wid: String,
                 month: RV[Double],
                 onSet: (DbRow, DbProp, Json) => Unit = (_, _, _) => (),
                 onAdd: (Map[String, Json], String) => Unit = (_, _) => ())

case class State(dragRow: RV[Option[String]] = RV(None), dropDay: RV[Option[String]] = RV(None))

def initState() = State()

val Weekdays: List[String] = List("月", "火", "水", "木", "金", "土", "日")

def today: String = Db.ymd(new js.Date())

def monthDate(props: Props): js.Date = new js.Date(props.month.v)

def monthLabel(props: Props): String = {
  val d = monthDate(props)
  s"${d.getFullYear().toInt}年${d.getMonth().toInt + 1}月"
}

/** 表示する日（6週間ぶん） */
def days(props: Props): List[js.Date] = {
  val first = monthDate(props)
  val start = new js.Date(first.getTime())
  start.setDate(1 - ((first.getDay() + 6) % 7)) // 月曜はじまり
  List.tabulate(42) { i =>
    val d = new js.Date(start.getTime())
    d.setDate(start.getDate() + i)
    d
  }
}

def dateOf(props: Props, r: DbRow): Option[String] = props.dateProp.flatMap(p => r.values.get(p.id)).flatMap(_.asString)

def byDay(props: Props, day: String): List[DbRow] = props.rows.filter(r => dateOf(props, r).contains(day))

def undated(props: Props): List[DbRow] = if (props.dateProp.isEmpty) Nil else props.rows.filter(r => dateOf(props, r).isEmpty)

def shiftMonth(props: Props, n: Int): Unit = {
  val d = monthDate(props)
  d.setMonth(d.getMonth() + n)
  props.month := d.getTime()
}

def dayClass(s: State, props: Props, d: js.Date): String = {
  val k = Db.ymd(d)
  "day" + (if (d.getMonth() != monthDate(props).getMonth()) " out" else "") + (if (k == today) " today" else "") +
    (if (s.dropDay.v.contains(k)) " drop" else "")
}

def over(s: State, e: org.scalajs.dom.Event, day: String): Unit = { e.preventDefault(); s.dropDay := Some(day) }

def dropOnDay(s: State, props: Props, e: org.scalajs.dom.Event, day: String): Unit = {
  e.preventDefault()
  val r = props.rows.find(x => s.dragRow.v.contains(x.page.id))
  s.dragRow := None
  s.dropDay := None
  for (p <- props.dateProp; r <- r) if (!dateOf(props, r).contains(day)) props.onSet(r, p, Json.fromString(day))
}

def add(props: Props, day: String): Unit = props.dateProp.foreach(p => props.onAdd(Map(p.id -> Json.fromString(day)), day))

def href(props: Props, r: DbRow): String = DbDom.pageHref(props.wid, r.page.id)


def draggable(r: DbRow): String = if (r.page.canEdit) "true" else "false"
</script>

<template>
  <div class="cal" data-testid="db-calendar">
    <p v-if="props.dateProp.isEmpty" class="muted">「⚙ 絞り込み・並べ替え」で、日付の項目を選んでください。<template v-if="!props.hasDateProps">先に「日付」の列を追加してください。</template></p>
    <template v-else>
      <div class="cal-head">
        <button class="ghost" @click="shiftMonth(props, -1)">‹</button>
        <b data-testid="db-cal-month">{{ monthLabel(props) }}</b>
        <button class="ghost" @click="shiftMonth(props, 1)">›</button>
        <button class="ghost" @click="props.month := Db.thisMonth()">今月</button>
      </div>
      <div class="grid">
        <div v-for="w in Weekdays" :key="w" class="wd">{{ w }}</div>
        <div v-for="d in days(props)" :key="Db.ymd(d)" :class="dayClass(s, props, d)" :data-day="Db.ymd(d)" data-testid="db-day"
          @dragover="over(s, event, Db.ymd(d))" @dragleave="s.dropDay := None" @drop="dropOnDay(s, props, event, Db.ymd(d))">
          <div class="dnum">{{ d.getDate().toInt }}<button v-if="props.canEdit" class="ghost dadd" title="この日に追加" @click="add(props, Db.ymd(d))">＋</button></div>
          <a v-for="r in byDay(props, Db.ymd(d))" :key="r.page.id" :href="href(props, r)" class="ev" :draggable="draggable(r)" data-testid="db-event"
            @click="DbDom.link(event, href(props, r))" @dragstart="s.dragRow := Some(r.page.id)" @dragend="s.dragRow := None">{{ r.page.displayTitle }}</a>
        </div>
      </div>
      <details v-if="undated(props).nonEmpty" class="undated">
        <summary>日付のない項目（{{ undated(props).size }}）</summary>
        <a v-for="r in undated(props)" :key="r.page.id" :href="href(props, r)" class="ev" :draggable="draggable(r)"
          @click="DbDom.link(event, href(props, r))" @dragstart="s.dragRow := Some(r.page.id)" @dragend="s.dragRow := None">{{ r.page.displayTitle }}</a>
      </details>
    </template>
  </div>
</template>

<style scoped>
.ghost { border-color: transparent !important; background: none; color: var(--muted); }
.ghost:hover { color: var(--fg); }
.rlink { color: inherit; text-decoration: none; font-weight: 500; }
.rlink:hover { color: var(--accent); text-decoration: underline; }
.cal-head { display: flex; align-items: center; gap: .5rem; margin-bottom: .4rem; }
.grid { display: grid; grid-template-columns: repeat(7, 1fr); border-left: 1px solid var(--line); border-top: 1px solid var(--line); }
.wd { font-size: .72rem; color: var(--muted); text-align: center; padding: 2px; border-right: 1px solid var(--line); border-bottom: 1px solid var(--line); }
.day { min-height: 5.2rem; border-right: 1px solid var(--line); border-bottom: 1px solid var(--line); padding: 2px 3px; display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.day.out { background: var(--side); opacity: .6; }
.day.today .dnum { color: var(--accent); font-weight: 700; }
.day.drop { outline: 2px dashed var(--accent); outline-offset: -2px; }
.dnum { font-size: .72rem; display: flex; justify-content: space-between; }
.dadd { padding: 0 3px; font-size: .7rem; opacity: 0; }
.day:hover .dadd { opacity: 1; }
.ev { display: block; font-size: .74rem; background: color-mix(in srgb, var(--accent) 16%, transparent); border-radius: 4px; padding: 0 4px; color: inherit; text-decoration: none; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; cursor: grab; }
.undated { margin-top: .5rem; font-size: .82rem; }
.undated .ev { display: inline-block; margin: 2px; }
@media (max-width: 640px) { .day { min-height: 3.6rem; } }
@media print { .dadd { display: none !important; } }
</style>
