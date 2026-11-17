<script lang="scala">
import org.scalajs.dom
import skiwi.web.Palette
import skiwi.web.workspace.Listeners

import scala.scalajs.js

// 絵文字アイコンの選択. 親要素（position: relative）の下に開く. 外側クリック・Esc で閉じる.
// at を指定すると画面座標に固定表示（スクロール領域の外へはみ出せるように）. head: 上に出す内容（名前付きスロット）. headEl: 上に出す素の DOM（エディタから）
case class Props(onSelect: Option[String] => Unit, onClose: Unit => Unit, allowNone: Boolean = true, noneLabel: String = "アイコンなし", at: Option[(Double, Double)] = None,
                 head: Slot = Slot.empty, headEl: Option[dom.Element] = None)

case class State(custom: RV[String] = RV(""), root: ElementRef[dom.HTMLElement] = ElementRef(), headBox: ElementRef[dom.HTMLElement] = ElementRef(),
                 ls: Listeners = new Listeners)

def initState() = State()

def Emojis: List[String] = Palette.Emojis

def mounted(s: State, props: Props): Unit = {
  props.headEl.foreach(h => s.headBox.foreach(_.appendChild(h)))
  // 開いたクリック自体で閉じないよう、次のタスクで登録する
  dom.window.setTimeout(() => s.ls.on[dom.MouseEvent](dom.document, "mousedown") { e =>
    if (!s.root.get.exists(_.contains(e.target.asInstanceOf[dom.Node]))) props.onClose(())
  }, 0)
  s.ls.on[dom.KeyboardEvent](dom.document, "keydown")(e => if (e.key == "Escape") props.onClose(()))
}

def unmounted(s: State): Unit = s.ls.clear()

def fixedStyle(p: Props): String = p.at.fold("") { case (x, y) =>
  val l = math.max(8, math.min(x, dom.window.innerWidth - 344))
  val t = math.max(8, math.min(y, dom.window.innerHeight - 260))
  s"position: fixed; left: ${l}px; top: ${t}px"
}

def applyCustom(s: State, p: Props): Unit = {
  val g = Palette.firstGrapheme(s.custom.v)
  if (g.nonEmpty) p.onSelect(Some(g))
}

def onCustomKey(s: State, p: Props, e: dom.Event): Unit = {
  val k = e.asInstanceOf[dom.KeyboardEvent]
  if (k.key == "Enter") {
    e.preventDefault()
    if (!e.asInstanceOf[js.Dynamic].isComposing.asInstanceOf[Boolean]) applyCustom(s, p)
  }
}
</script>

<template>
  <div ref="s.root" class="picker" :style="fixedStyle(props)" data-testid="icon-picker" @mousedown="event.stopPropagation()">
    <slot name="head"></slot>
    <div v-if="props.headEl.isDefined" ref="s.headBox"></div>
    <div class="grid">
      <button v-for="e in Emojis" class="emoji" :title="e" @click="props.onSelect(Some(e))">{{ e }}</button>
    </div>
    <div class="foot">
      <input v-model="s.custom" placeholder="他の絵文字を入力" maxlength="16" @keydown="onCustomKey(s, props, event)">
      <button :disabled="s.custom.v.trim.isEmpty" @click="applyCustom(s, props)">決定</button>
      <button v-if="props.allowNone" class="none" @click="props.onSelect(None)">{{ props.noneLabel }}</button>
    </div>
  </div>
</template>

<style scoped>
.picker { position: absolute; z-index: 50; top: calc(100% + 4px); left: 0; background: var(--bg); border: 1px solid var(--line); border-radius: 10px; padding: .5rem; box-shadow: 0 10px 30px rgba(0,0,0,.2); width: 336px; }
.grid { display: grid; grid-template-columns: repeat(12, 1fr); gap: 1px; }
.emoji { border: none; padding: 2px 0; font-size: 1.1rem; line-height: 1.5; border-radius: 4px; }
.foot { display: flex; gap: .3rem; margin-top: .5rem; align-items: center; }
.foot input { flex: 1; min-width: 0; padding: .2rem .4rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); font-size: .85rem; }
.foot button { font-size: .8rem; white-space: nowrap; }
.none { color: var(--muted); }
</style>
