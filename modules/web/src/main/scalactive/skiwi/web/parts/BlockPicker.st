<script lang="scala">
import org.scalajs.dom
import skiwi.web.editor.Blocks

import scala.scalajs.js

// ブロックの一覧（分類ごとの表）のモーダル. エディタの「/」→「すべてのブロックを表示…」から開く（editor.Blocks.openPicker）.
// 検索・矢印キー・Enter で選んで挿入する. items はエディタ（JS）のスラッシュコマンドの項目（選んだ項目をそのまま返す）
case class Props(items: js.Array[js.Dynamic], query: String, onDone: Option[js.Dynamic] => Unit)

case class State(q: RV[String], selected: RV[Int] = RV(0))

def initState(props: Props) = State(RV(props.query))

def mounted(s: State, props: Props): Unit = watch(s.q)((_, _) => s.selected := 0)

/** 分類ごとの表示. 各項目は矢印キーで移動する順序（表示順）での位置と組にする */
def groups(s: State, props: Props): List[(String, List[(js.Dynamic, Int)])] = {
  val filtered = Blocks.filter(props.items, s.q.v).toList
  val gs = Blocks.Categories.map(c => (c, filtered.filter(i => Blocks.str(i, "category") == c))).filter(_._2.nonEmpty)
  var n = -1
  gs.map((c, xs) => (c, xs.map { it => n += 1; (it, n) }))
}

def flat(s: State, props: Props): List[js.Dynamic] = groups(s, props).flatMap(_._2.map(_._1))

/** 「/」のあとに入力して呼び出せる語（日本語・英語を数個） */
def hints(it: js.Dynamic): String = Blocks.keywords(it).take(4).mkString("、")

def iconClass(it: js.Dynamic): String = Blocks.str(it, "iconClass")

def rowClass(s: State, i: Int): String = if (i == s.selected.v) "active" else ""

def move(s: State, props: Props, d: Int): Unit = {
  val n = flat(s, props).size
  if (n > 0) s.selected := (s.selected.v + d + n) % n
}

def onKey(s: State, props: Props, e: dom.Event): Unit = {
  val k = e.asInstanceOf[dom.KeyboardEvent]
  if (!e.asInstanceOf[js.Dynamic].isComposing.asInstanceOf[Boolean]) k.key match {
    case "ArrowDown" => e.preventDefault(); move(s, props, 1)
    case "ArrowUp" => e.preventDefault(); move(s, props, -1)
    case "Enter" => e.preventDefault(); flat(s, props).lift(s.selected.v).foreach(it => props.onDone(Some(it)))
    case "Escape" => e.preventDefault(); props.onDone(None)
    case _ => ()
  }
}
</script>

<template>
  <div class="backdrop" @mousedown.self="props.onDone(None)" @keydown="onKey(s, props, event)">
    <div class="modal" role="dialog" aria-modal="true" aria-label="ブロックを挿入" data-testid="block-picker">
      <header>
        <h2>ブロックを挿入</h2>
        <input v-model="s.q" type="search" v-focus.select placeholder="名前・説明・キーワードで絞り込み" data-testid="block-picker-search">
        <button class="close" title="閉じる（Esc）" @click="props.onDone(None)">✕</button>
      </header>
      <div class="body">
        <p v-if="flat(s, props).isEmpty" class="empty">「{{ s.q.v }}」に一致するブロックはありません。</p>
        <table v-else>
          <thead><tr><th class="c-icon"></th><th>ブロック</th><th>説明</th><th class="c-kw">「/」のあとに入力して呼び出せる語</th></tr></thead>
          <tbody v-for="g in groups(s, props)" :key="g._1">
            <tr class="cat"><th colspan="4">{{ g._1 }}</th></tr>
            <tr v-for="it in g._2" :key="it._2" :class="rowClass(s, it._2)" v-scroll-into-view="it._2 == s.selected.v" @mouseenter="s.selected := it._2" @click="props.onDone(Some(it._1))">
              <td class="c-icon"><span class="icon"><span v-if="iconClass(it._1).nonEmpty" :class="iconClass(it._1)" aria-hidden="true"></span><template v-else>{{ Blocks.str(it._1, "icon") }}</template></span></td>
              <td class="name">{{ Blocks.str(it._1, "title") }}</td>
              <td class="desc">{{ Blocks.str(it._1, "description") }}</td>
              <td class="c-kw">{{ hints(it._1) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer>↑↓ で選択 ・ Enter で挿入 ・ Esc で閉じる ・ クリックでも挿入できます</footer>
    </div>
  </div>
</template>

<style scoped>
.backdrop { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 900; padding: 16px; }
.modal { background: var(--bg); border: 1px solid var(--line); border-radius: 12px; width: min(920px, 100%); height: min(680px, 100%); display: flex; flex-direction: column; box-shadow: 0 16px 48px rgba(0,0,0,.3); overflow: hidden; }
header { display: flex; align-items: center; gap: .8rem; padding: .8rem 1rem; border-bottom: 1px solid var(--line); }
h2 { font-size: 1.05rem; margin: 0; white-space: nowrap; }
header input { flex: 1; min-width: 0; padding: .4rem .7rem; border: 1px solid var(--line); border-radius: 8px; background: var(--bg); }
.close { border: none; font-size: 1rem; color: var(--muted); }
.body { flex: 1; overflow: auto; }
table { width: 100%; border-collapse: collapse; font-size: .9rem; }
thead th { position: sticky; top: 0; z-index: 1; background: var(--side); text-align: left; font-weight: 600; font-size: .78rem; color: var(--muted); padding: .45rem .6rem; border-bottom: 1px solid var(--line); }
tr.cat th { text-align: left; font-size: .8rem; color: var(--accent); padding: .7rem .6rem .25rem; border-bottom: 1px solid var(--line); }
tbody tr:not(.cat) { cursor: pointer; }
tbody tr.active { background: var(--hover); }
td { padding: .35rem .6rem; border-bottom: 1px solid var(--line); vertical-align: middle; }
.c-icon { width: 48px; }
.icon { width: 30px; height: 30px; display: grid; place-items: center; border: 1px solid var(--line); border-radius: 6px; font-size: 13px; background: var(--bg); }
.name { font-weight: 600; white-space: nowrap; }
.desc { color: var(--muted); }
.c-kw { color: var(--muted); font-size: .8rem; width: 30%; }
.empty { padding: 2rem; text-align: center; color: var(--muted); }
footer { padding: .5rem 1rem; border-top: 1px solid var(--line); font-size: .78rem; color: var(--muted); }
@media (max-width: 640px) { .c-kw { display: none; } }
</style>
