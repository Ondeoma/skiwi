<script lang="scala">
import skiwi.web.Nav
import sbirds.web.Dom
import skiwi.shared.Titles
import io.circe.Json
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.web.Dialog

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// データベースの1つの値（表のセル・項目のプロパティ）。編集できるときはその場で変更する（onUpdate に新しい値. null は消す）
// onUser: ユーザーを選んだとき（ID, 名前）。
// pop・popKey: 開いている選択の一覧（複数選択・関連）を親が持つときに渡す（値が変わってセルを描き直しても開いたままにする）
case class Props(prop: DbProp,
                 value: Val,
                 users: Map[String, String],
                 editable: Boolean,
                 compact: Boolean = false,
                 ctx: Option[Ctx] = None,
                 wid: String = "",
                 onUpdate: Json => Unit = _ => (),
                 onUser: (String, String) => Unit = (_, _) => (),
                 pop: Option[RV[Option[String]]] = None,
                 popKey: String = "")

case class State(editing: RV[Boolean] = RV(false),
                 draft: RV[String] = RV(""),
                 localPop: RV[Option[String]] = RV(None),
                 relQ: RV[String] = RV(""))

def initState() = State()

def cellClass(props: Props): String =
  s"cell t-${props.prop.`type`}" + (if (props.compact) " compact" else "") + (if (props.editable) "" else " ro")

def arr(props: Props): List[String] = Db.arr(props.value)

def raw(props: Props): String = props.value match {
  case Val.Null => ""
  case v => Val.jsString(v)
}

// --- テキスト・数値・URL ---
def startText(s: State, props: Props): Unit = if (props.editable) {
  s.draft := raw(props)
  s.editing := true
}

def commitText(s: State, props: Props): Unit = if (s.editing.v) {
  s.editing := false
  val v = s.draft.v.trim
  if (v != raw(props)) {
    if (props.prop.`type` == "number") {
      if (v.isEmpty) props.onUpdate(Json.Null)
      else {
        val n = Val.jsNumber(v)
        if (!n.isNaN && !n.isInfinite) props.onUpdate(Json.fromDoubleOrNull(n))
        else Dialog.alert("数値を入力してください")
      }
    } else props.onUpdate(if (v.isEmpty) Json.Null else Json.fromString(v))
  }
}

def onKey(s: State, props: Props, e: dom.Event): Unit = {
  val k = e.asInstanceOf[dom.KeyboardEvent]
  if (!DbDom.composing(e)) {
    if (k.key == "Enter" && !(k.shiftKey && props.prop.`type` == "text")) { k.preventDefault(); commitText(s, props) }
    if (k.key == "Escape") { k.preventDefault(); s.editing := false }
  }
}

def onTextKey(s: State, props: Props, e: dom.Event): Unit =
  if (e.asInstanceOf[dom.KeyboardEvent].key == "Enter") startText(s, props)

def isNum(props: Props): Boolean = props.value.isInstanceOf[Val.Num]

def txtClass(props: Props): String =
  "txt" + (if (props.prop.`type` == "number") " num" else "") + (if (raw(props).isEmpty) " empty" else "")

def urlOk(props: Props): Boolean = props.value match {
  case Val.Str(v) => v.toLowerCase.startsWith("http://") || v.toLowerCase.startsWith("https://") || v.toLowerCase.startsWith("mailto:")
  case _ => false
}

def inputType(props: Props): String = if (props.prop.`type` == "url") "url" else "text"

def inputMode(props: Props): Option[String] = if (props.prop.`type` == "number") Some("decimal") else None

// --- 選択 ---
def selected(props: Props): String = Db.option(props.prop, props.value).fold("")(_.id)

def setSelect(props: Props, v: String): Unit = props.onUpdate(if (v.isEmpty) Json.Null else Json.fromString(v))

def tagClass(color: String): String = s"tag c-$color"

// --- 開く一覧（複数選択・関連） ---
def popRv(s: State, props: Props): RV[Option[String]] = props.pop.getOrElse(s.localPop)

def isOpen(s: State, props: Props, kind: String): Boolean = popRv(s, props).v.contains(props.popKey + "|" + kind)

def toggle(s: State, props: Props, kind: String): Unit =
  popRv(s, props) := (if (isOpen(s, props, kind)) None else Some(props.popKey + "|" + kind))

def close(s: State, props: Props): Unit = popRv(s, props) := None

def toggleIn(props: Props, id: String, on: Boolean): Unit =
  props.onUpdate((if (on) arr(props) :+ id else arr(props).filterNot(_ == id)).asJson)

def multiTags(props: Props): List[DbOption] = arr(props).flatMap(id => props.prop.opts.find(_.id == id))

// --- ユーザー: 名前・メールで検索して追加 ---
def addPerson(props: Props): Unit =
  Dialog.pickUser("追加するユーザー（名前・メールで検索）", "ユーザーを選んでください").foreach {
    case Some(Some(u)) if !arr(props).contains(u.id) =>
      props.onUser(u.id, u.name)
      props.onUpdate((arr(props) :+ u.id).asJson)
    case _ => ()
  }

def removePerson(props: Props, id: String): Unit = props.onUpdate(arr(props).filterNot(_ == id).asJson)

def userName(props: Props, id: String): String = props.users.getOrElse(id, "（不明）")

// --- 関連: 関連先のデータベースの項目から選ぶ ---
def target(props: Props): Option[RelatedDb] = props.prop.target.flatMap(t => props.ctx.flatMap(_.targets.get(t)))

def relRows(s: State, props: Props): List[RelatedRow] = {
  val q = s.relQ.v.trim.toLowerCase
  target(props).fold(List.empty[RelatedRow])(_.rows).filter(r => q.isEmpty || (Titles.display(r.title)).toLowerCase.contains(q)).take(50)
}

def relTitle(props: Props, id: String): String =
  target(props).flatMap(_.rows.find(_.id == id)).map(_.title).filter(_.nonEmpty).getOrElse("（見られない項目）")

def relHref(props: Props, id: String): Option[String] = target(props).map(_ => Nav.pageHref(props.wid, id))

def openRel(props: Props, e: dom.Event, id: String): Unit = relHref(props, id) match {
  case Some(h) => Nav.link(e, h)
  case None => e.preventDefault()
}

def relAddTitle(props: Props): String = if (target(props).isDefined) "関連する項目を選ぶ" else "関連先のデータベースを閲覧できません"

def targetTitle(props: Props): String = Titles.display(target(props).map(_.title))


val ComputedTypes: Set[String] = Set("formula", "created_time", "edited_time", "created_by", "edited_by", "unique_id")

def dateValue(props: Props): String = props.value match {
  case Val.Str(v) => v
  case _ => ""
}
</script>

<template>
  <div :class="cellClass(props)" data-testid="db-cell">
    <!-- チェック -->
    <input v-if='props.prop.`type` == "checkbox"' type="checkbox" :checked="Val.truthy(props.value)" :disabled="!props.editable" @change="props.onUpdate(Json.fromBoolean(Dom.checked(event)))">

    <!-- 選択 -->
    <template v-else-if='props.prop.`type` == "select"'>
      <select v-if="props.editable" class="sel" data-testid="db-select" @change="setSelect(props, Dom.value(event))">
        <option value="" :selected="selected(props).isEmpty">（なし）</option>
        <option v-for="o in props.prop.opts" :key="o.id" :value="o.id" :selected="selected(props) == o.id">{{ o.name }}</option>
      </select>
      <span v-for="o in Db.option(props.prop, props.value).toList" :key="o.id" :class="tagClass(o.color)">{{ o.name }}</span>
    </template>

    <!-- 複数選択 -->
    <template v-else-if='props.prop.`type` == "multi_select"'>
      <span v-for="o in multiTags(props)" :key="o.id" :class="tagClass(o.color)">{{ o.name }}</span>
      <button v-if="props.editable" class="add" title="選ぶ" @click='toggle(s, props, "multi")'>＋</button>
      <div v-if='isOpen(s, props, "multi")' class="pop" @mouseleave="close(s, props)">
        <label v-for="o in props.prop.opts" :key="o.id"><input type="checkbox" :checked="arr(props).contains(o.id)" @change="toggleIn(props, o.id, Dom.checked(event))"> <span :class="tagClass(o.color)">{{ o.name }}</span></label>
        <p v-if="props.prop.opts.isEmpty" class="muted">選択肢がありません（列の設定で追加）</p>
      </div>
    </template>

    <!-- 関連 -->
    <template v-else-if='props.prop.`type` == "relation"'>
      <a v-for="id in arr(props)" :key="id" :href="relHref(props, id)" class="rel" data-testid="db-rel" @click="openRel(props, event, id)">↗ {{ relTitle(props, id) }}</a>
      <button v-if="props.editable" class="add" :title="relAddTitle(props)" :disabled="target(props).isEmpty" data-testid="db-rel-add" @click='toggle(s, props, "rel")'>＋</button>
      <div v-if='isOpen(s, props, "rel") &amp;&amp; target(props).isDefined' class="pop rel-pop" data-testid="db-rel-pop" @mouseleave="close(s, props)">
        <input v-model="s.relQ" placeholder="🔍 項目を検索" class="rq">
        <label v-for="r in relRows(s, props)" :key="r.id"><input type="checkbox" :checked="arr(props).contains(r.id)" @change="toggleIn(props, r.id, Dom.checked(event))"> {{ r.icon.getOrElse("📄") }} {{ Titles.display(r.title) }}</label>
        <p v-if="relRows(s, props).isEmpty" class="muted">項目がありません（「{{ targetTitle(props) }}」）</p>
      </div>
    </template>

    <!-- 数式・自動の列 -->
    <span v-else-if="ComputedTypes(props.prop.`type`)" :class='if (isNum(props)) "txt ro num" else "txt ro"' data-testid="db-computed">{{ Db.text(props.prop, props.value, props.users, props.ctx) }}</span>

    <!-- 集計（計算した値） -->
    <span v-else-if='props.prop.`type` == "rollup"' class="txt num ro" data-testid="db-rollup">{{ raw(props) }}</span>

    <!-- ユーザー -->
    <template v-else-if='props.prop.`type` == "person"'>
      <span v-for="id in arr(props)" :key="id" class="person">👤 {{ userName(props, id) }}<button v-if="props.editable" class="x" title="外す" @click="removePerson(props, id)">×</button></span>
      <button v-if="props.editable" class="add" title="ユーザーを追加" data-testid="db-person-add" @click="addPerson(props)">＋</button>
    </template>

    <!-- 日付 -->
    <input v-else-if='props.prop.`type` == "date" &amp;&amp; props.editable' type="date" class="date" :value="dateValue(props)" @change="setSelect(props, Dom.value(event))">
    <span v-else-if='props.prop.`type` == "date"'>{{ raw(props) }}</span>

    <!-- テキスト・数値・URL -->
    <template v-else>
      <input v-if="s.editing" v-model="s.draft" v-focus.select class="inp" :type="inputType(props)" :inputmode="inputMode(props)" data-testid="db-input" @blur="commitText(s, props)" @keydown="onKey(s, props, event)">
      <span v-else :class="txtClass(props)" :tabindex='if (props.editable) "0" else "-1"' data-testid="db-text" @click="startText(s, props)" @keydown="onTextKey(s, props, event)">
        <a v-if='props.prop.`type` == "url" &amp;&amp; urlOk(props)' :href="raw(props)" target="_blank" rel="noopener" @click.stop="()">{{ raw(props) }}</a>
        <template v-else>{{ raw(props) }}</template>
      </span>
    </template>
  </div>
</template>

<style scoped>
.cell { position: relative; display: flex; flex-wrap: wrap; align-items: center; gap: 3px; min-height: 26px; font-size: .85rem; }
.txt { flex: 1; min-width: 2rem; min-height: 1.3em; cursor: text; white-space: pre-wrap; word-break: break-word; }
.ro .txt { cursor: default; }
.txt.num { text-align: right; font-variant-numeric: tabular-nums; }
.inp { flex: 1; min-width: 4rem; font: inherit; padding: 1px 4px; border: 1px solid var(--accent); border-radius: 4px; background: var(--bg); color: inherit; }
.sel { font: inherit; font-size: .8rem; border: 1px solid transparent; background: transparent; color: transparent; position: absolute; inset: 0; opacity: 0; cursor: pointer; }
.date { font: inherit; font-size: .8rem; border: 1px solid transparent; background: transparent; color: inherit; padding: 0; }
.date:hover, .date:focus { border-color: var(--line); }
.tag { display: inline-block; padding: 0 7px; border-radius: 4px; font-size: .78rem; line-height: 1.6; white-space: nowrap; }
.rel { font-size: .8rem; color: var(--accent); text-decoration: none; background: var(--hover); border-radius: 4px; padding: 0 5px; white-space: nowrap; }
.rel-pop { max-height: 16rem; overflow: auto; min-width: 14rem; }
.rq { font: inherit; font-size: .8rem; padding: 2px 6px; border: 1px solid var(--line); border-radius: 5px; background: var(--bg); color: inherit; margin-bottom: .2rem; }
.person { display: inline-flex; align-items: center; gap: 2px; font-size: .8rem; background: var(--hover); border-radius: 10px; padding: 0 6px; white-space: nowrap; }
.x { border: none; padding: 0 2px; color: var(--muted); font-size: .8rem; }
.add { border: 1px dashed var(--line); border-radius: 4px; padding: 0 5px; font-size: .75rem; color: var(--muted); opacity: .6; }
.cell:hover .add { opacity: 1; }
.pop { position: absolute; z-index: 30; top: 100%; left: 0; min-width: 10rem; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: .4rem .5rem; box-shadow: 0 8px 24px rgba(0,0,0,.18); display: flex; flex-direction: column; gap: .2rem; }
.pop label { display: flex; align-items: center; gap: .3rem; cursor: pointer; }
.muted { font-size: .75rem; margin: 0; }
a { color: var(--accent); word-break: break-all; }
</style>
