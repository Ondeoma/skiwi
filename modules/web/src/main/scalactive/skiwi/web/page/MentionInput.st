<script lang="scala">
import org.scalajs.dom
import skiwi.web.Api
import skiwi.web.model.DirectoryEntry

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

// コメント入力欄. 「@」に続けて名前を入力すると候補を出す. ⌘/Ctrl+Enter で送信.
// 画面には「@名前」と表示し、model には保存用の @[名前](ユーザーID) の形で入れる.
case class Props(model: RV[String],
                 placeholder: String = "コメント（@で名前を入力するとメンション）",
                 autofocus: Reactive[Boolean] = RV(false),
                 onSubmit: Unit => Unit = _ => (),
                 onCancel: Unit => Unit = _ => ())

case class State(display: RV[String] = RV(""),
                 cands: RV[List[DirectoryEntry]] = RV(Nil),
                 sel: RV[Int] = RV(0),
                 ta: ElementRef[dom.HTMLTextAreaElement] = ElementRef(),
                 /** 表示用の名前 → ユーザーID（選んだ人・既存の本文にいた人） */
                 known: scala.collection.mutable.Map[String, String] = scala.collection.mutable.Map.empty,
                 /** 「@」の位置・候補の問い合わせの番号・最後に model へ入れた値 */
                 atPos: RV[Int] = RV(-1),
                 seq: RV[Int] = RV(0),
                 last: RV[String] = RV(""))

def initState() = State()

val Mark = """@\[([^\]]*)\]\(([0-9a-fA-F-]{36})\)""".r


def toDisplay(s: State, v: String): String =
  Mark.replaceAllIn(v, m => { s.known(m.group(1)) = m.group(2); scala.util.matching.Regex.quoteReplacement("@" + m.group(1)) })

def toStored(s: State, v: String): String = {
  // 長い名前から置き換える（「佐藤」と「佐藤 花子」がいる場合など）
  val names = s.known.keys.toList.sortBy(-_.length)
  if (names.isEmpty) v
  else {
    val re = ("@(" + names.map(java.util.regex.Pattern.quote).mkString("|") + ")(?![^\\s@])").r
    re.replaceAllIn(v, m => scala.util.matching.Regex.quoteReplacement(s"@[${m.group(1)}](${s.known(m.group(1))})"))
  }
}

def mounted(s: State, props: Props): Unit = {
  s.last := props.model.v
  s.display := toDisplay(s, props.model.v)
  watch(props.model, false)((_, v) => if (v != s.last.v) { s.display := toDisplay(s, v); s.last := v })
  watch(props.autofocus, true)((_, b) => if (b) s.ta.foreach(_.focus()))
}

def push(s: State, props: Props, v: String): Unit = {
  s.display := v
  val st = toStored(s, v)
  s.last := st
  props.model := st
}

def onInput(s: State, props: Props): Unit = s.ta.foreach { el =>
  push(s, props, el.value)
  autosize(s)
  val before = el.value.substring(0, el.selectionStart)
  """(^|\s)@([^\s@\[\]()]{1,30})$""".r.findFirstMatchIn(before) match {
    case None =>
      s.cands := Nil
      s.atPos := -1
    case Some(m) =>
      s.atPos := before.length - m.group(2).length - 1
      val my = s.seq.v + 1
      s.seq := my
      Api.directory(m.group(2)).recover(_ => Nil).foreach { r =>
        if (my == s.seq.v) {
          s.cands := r.filter(_.principalType == "user").take(6)
          s.sel := 0
        }
      }
  }
}

def choose(s: State, props: Props, i: Int): Unit = (s.cands.v.lift(i), s.ta.get) match {
  case (Some(c), Some(el)) if s.atPos.v >= 0 =>
    val v = s.display.v
    val name = c.name.replaceAll("""[\[\]()@]""", "")
    s.known(name) = c.id
    val insert = s"@$name "
    val a = s.atPos.v
    push(s, props, v.substring(0, a) + insert + v.substring(math.min(el.selectionStart, v.length)))
    s.cands := Nil
    dom.window.setTimeout(() => {
      val pos = a + insert.length
      el.focus()
      el.setSelectionRange(pos, pos)
    }, 0)
    s.atPos := -1
  case _ => ()
}

def onKey(s: State, props: Props, ev: dom.Event): Unit = {
  val e = ev.asInstanceOf[dom.KeyboardEvent]
  if (!e.asInstanceOf[js.Dynamic].isComposing.asInstanceOf[js.UndefOr[Boolean]].getOrElse(false)) {
    val n = s.cands.v.size
    if (n > 0 && Set("ArrowDown", "ArrowUp", "Enter", "Tab", "Escape").contains(e.key)) {
      e.preventDefault()
      e.key match {
        case "ArrowDown" => s.sel := (s.sel.v + 1) % n
        case "ArrowUp" => s.sel := (s.sel.v - 1 + n) % n
        case "Escape" => s.cands := Nil
        case _ => choose(s, props, s.sel.v)
      }
    } else if (e.key == "Enter" && (e.metaKey || e.ctrlKey)) {
      e.preventDefault()
      props.onSubmit(())
    } else if (e.key == "Escape") props.onCancel(())
  }
}

def autosize(s: State): Unit = s.ta.foreach { el =>
  el.style.height = "auto"
  el.style.height = s"${math.min(el.scrollHeight, 240)}px"
}

def candCls(s: State, i: Int) = s.sel.mapC(x => if (x == i) "active" else "")
</script>

<template>
  <div class="mi">
    <textarea ref="s.ta" :value="s.display" :placeholder="props.placeholder" rows="2"
      data-testid="comment-input" @input="onInput(s, props)" @keydown="onKey(s, props, event)"></textarea>
    <div v-if="s.cands.mapC(_.nonEmpty)" class="cands" role="listbox">
      <button v-for="c in s.cands.mapC(_.zipWithIndex)" :key="c._1.id" :class="candCls(s, c._2)" @mousedown.prevent="choose(s, props, c._2)" @mouseenter="s.sel := c._2">
        👤 {{ c._1.name }} <small>{{ c._1.detail.getOrElse("") }}</small>
      </button>
    </div>
  </div>
</template>

<style scoped>
.mi { position: relative; }
textarea { width: 100%; box-sizing: border-box; resize: none; font: inherit; font-size: .88rem; padding: .4rem .5rem; border: 1px solid var(--line); border-radius: 7px; background: var(--bg); color: inherit; min-height: 2.6em; }
textarea:focus { outline: none; border-color: var(--accent); }
.cands { position: absolute; left: 0; right: 0; top: 100%; z-index: 5; margin-top: 2px; background: var(--bg); border: 1px solid var(--line); border-radius: 7px; padding: 3px; box-shadow: 0 8px 20px rgba(0,0,0,.18); }
.cands button { display: block; width: 100%; text-align: left; border: none; padding: .3rem .5rem; border-radius: 5px; font-size: .85rem; }
.cands button.active { background: var(--hover); }
.cands small { color: var(--muted); }
</style>
