<script lang="scala">
import org.scalajs.dom
import skiwi.web.{Opt, Ui}

// オートコンプリート（セレクトボックスの代わり. 兄弟のプロジェクトの Combo と同じ）. 入力した文字で選択肢を絞り込み、上下キー・Enter で選ぶ.
// bind を渡すとその値を読み書きし、渡さなければ value を初期値にする. 選ぶと onChange に値を渡す.
// 選択肢が後から読み込まれるときは live（mapC など）で渡す.
case class Props(options: List[Opt] = Nil,
                 live: Option[CRV[List[Opt]]] = None,
                 value: String = "",
                 bind: Option[RV[String]] = None,
                 onChange: String => Unit = _ => (),
                 placeholder: String = "選択…",
                 disabled: Boolean = false,
                 small: Boolean = false,
                 testid: String = "",
                 keep: Boolean = true)

case class State(sel: RV[String], open: RV[Boolean] = RV(false), q: RV[String] = RV(""), active: RV[Int] = RV(0),
                 input: ElementRef[dom.HTMLInputElement] = ElementRef())

def initState(props: Props) = State(RV(props.bind.map(_.v).getOrElse(props.value)))

def current(s: State, props: Props): RV[String] = props.bind.getOrElse(s.sel)

def all(props: Props): List[Opt] = props.live.map(_.v).getOrElse(props.options)

def matches(o: Opt, q: String): Boolean = {
  val t = Ui.normalizeForSearch(q.trim)
  t.isEmpty || Ui.normalizeForSearch(o.label).contains(t) || Ui.normalizeForSearch(o.hint).contains(t)
}

def filtered(s: State, props: Props) = computed { val q = s.q.v; all(props).filter(matches(_, q)) }

def selected(s: State, props: Props) = computed { val v = current(s, props).v; all(props).find(_.value == v) }

/** 入力欄の表示: 開いている間は入力中の文字、閉じているときは選んでいるものの名前 */
def shown(s: State, props: Props) = computed {
  if (s.open.v) s.q.v else selected(s, props).v.map(_.label).getOrElse("")
}

def openList(s: State, props: Props): Unit = if (!props.disabled && !s.open.v) {
  s.q := ""
  s.active := math.max(0, all(props).indexWhere(_.value == current(s, props).v))
  s.open := true
  s.input.foreach(_.select())
}

def choose(s: State, props: Props, o: Opt): Unit = {
  s.open := false
  if (props.keep) current(s, props) := o.value
  props.onChange(o.value)
  s.input.foreach(_.blur())
}

def key(s: State, props: Props, e: dom.Event): Unit = {
  val k = e.asInstanceOf[dom.KeyboardEvent]
  // IME 変換中の Enter は確定に使われるので無視する
  if (!k.asInstanceOf[scala.scalajs.js.Dynamic].isComposing.asInstanceOf[Boolean]) {
    val list = filtered(s, props).v
    k.key match {
      case "ArrowDown" => k.preventDefault(); if (!s.open.v) openList(s, props) else s.active := math.min(list.size - 1, s.active.v + 1)
      case "ArrowUp"   => k.preventDefault(); s.active := math.max(0, s.active.v - 1)
      case "Enter"     => k.preventDefault(); if (s.open.v) list.lift(s.active.v).foreach(choose(s, props, _)) else openList(s, props)
      case "Escape"    => if (s.open.v) { k.preventDefault(); k.stopPropagation(); s.open := false; s.input.foreach(_.blur()) }
      case "Tab"       => s.open := false
      case _           => ()
    }
  }
}

def typed(s: State, e: dom.Event): Unit = {
  s.q := e.target.asInstanceOf[dom.HTMLInputElement].value
  s.active := 0
  if (!s.open.v) s.open := true
}

/** 外をクリックしたとき（選択肢のクリックが先に届くよう少し待つ） */
def blur(s: State): Unit = dom.window.setTimeout(() => s.open := false, 150)

def itemClass(s: State, i: Int) = s.active.mapC(a => if (a == i) "opt active" else "opt")
def cls(props: Props): String = "combo" + (if (props.small) " small" else "") + (if (props.disabled) " disabled" else "")
def indexed(s: State, props: Props) = filtered(s, props).mapC(_.zipWithIndex)
def selIcon(s: State, props: Props) = selected(s, props).mapC(_.map(_.icon).getOrElse(""))
</script>

<template>
  <div :class="cls(props)">
    <span class="lead">{{ selIcon(s, props) }}</span>
    <input ref="s.input" type="text" :value="shown(s, props)" :placeholder="props.placeholder" :disabled="props.disabled" autocomplete="off"
      :data-testid="props.testid" role="combobox" :aria-expanded="s.open.mapC(_.toString)"
      @focus="openList(s, props)" @click="openList(s, props)" @input="typed(s, event)" @keydown="key(s, props, event)" @blur="blur(s)">
    <span class="caret">▾</span>
    <div v-if="s.open" class="list" role="listbox">
      <div v-for="oi in indexed(s, props)" :class="itemClass(s, oi._2)" role="option" @mousedown.prevent="choose(s, props, oi._1)">
        <span v-if="oi._1.icon.nonEmpty" class="ic">{{ oi._1.icon }}</span>
        <span class="grow">{{ oi._1.label }}</span>
        <span v-if="oi._1.hint.nonEmpty" class="hint">{{ oi._1.hint }}</span>
      </div>
      <div v-if="filtered(s, props).mapC(_.isEmpty)" class="none">該当するものがありません</div>
    </div>
  </div>
</template>

<style scoped>
.combo { position: relative; display: flex; align-items: center; border: 1px solid var(--line); border-radius: 7px; background: var(--bg);
  min-width: 6rem; max-width: 100%; }
.combo:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 18%, transparent); }
.combo.disabled { opacity: .6; }
.combo input { border: none; background: transparent; box-shadow: none !important; outline: none; flex: 1; min-width: 0; padding: .35rem .2rem .35rem .3rem;
  cursor: pointer; font: inherit; color: inherit; }
.combo input:focus { cursor: text; }
.combo.small input { padding: .2rem .2rem .2rem .25rem; font-size: .82rem; }
.lead { display: inline-flex; align-items: center; padding-left: .45rem; flex: none; }
.lead:empty { padding-left: .2rem; }
.caret { color: var(--muted); font-size: .7rem; padding: 0 .45rem; pointer-events: none; }
.list { position: absolute; top: calc(100% + 4px); left: 0; min-width: 100%; max-width: 360px; max-height: 320px; overflow-y: auto; z-index: 1500;
  background: var(--bg); border: 1px solid var(--line); border-radius: 9px; padding: 4px; box-shadow: 0 10px 28px rgba(0, 0, 0, .2); }
.opt { display: flex; align-items: center; gap: .45rem; padding: .38rem .55rem; border-radius: 6px; cursor: pointer; font-size: .88rem; white-space: nowrap; }
.opt.active { background: color-mix(in srgb, var(--accent) 14%, transparent); color: var(--accent); }
.grow { flex: 1; overflow: hidden; text-overflow: ellipsis; }
.hint { color: var(--muted); font-size: .75rem; }
.ic { width: 1.2em; text-align: center; }
.none { padding: .45rem .6rem; color: var(--muted); font-size: .8rem; }
</style>
