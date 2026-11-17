<script lang="scala">
import org.scalajs.dom
import skiwi.web.Ui
import skiwi.web.workspace.Listeners

import scala.scalajs.js

// キーボードショートカットの一覧.「?」（入力中以外）または ⌘K の「ショートカット一覧」で開く
case class State(open: RV[Boolean] = RV(false), q: RV[String] = RV(""), input: ElementRef[dom.HTMLInputElement] = ElementRef(),
                 ls: Listeners = new Listeners)

def initState() = State()

final case class Group(title: String, items: List[(List[String], String)])

val mod = if (Ui.isMac) "⌘" else "Ctrl"
val alt = if (Ui.isMac) "⌥" else "Alt"

val Groups: List[Group] = List(
  Group("全体", List(
    List(mod, "K") -> "クイック切り替え（ページ・ワークスペースへの移動、操作）",
    List(mod, "\\") -> "サイドメニューを開く・閉じる",
    List("?") -> "このショートカット一覧",
    List("Esc") -> "ダイアログ・メニューを閉じる／ページの選択を解除",
  )),
  Group("ページ", List(
    List("E") -> "編集モードにする（表示モードのとき）",
    List("Esc") -> "編集モードを終える（エディタの外で）",
    List(mod, "P") -> "印刷 / PDF（ブラウザの機能）",
  )),
  Group("サイドメニュー（ページツリー）", List(
    List(mod, "クリック") -> "ページを選択に加える・外す（まとめて操作）",
    List("Shift", "クリック") -> "範囲をまとめて選択",
    List("ダブルクリック") -> "ページ名を変更",
    List("右クリック") -> "ページのメニュー",
    List("ドラッグ") -> "並べ替え・別のページの下へ移動",
  )),
  Group("エディタ（編集モード）", List(
    List("/") -> "ブロックを挿入（見出し・表・ToDo・図・ホワイトボードなど）",
    List("@") -> "ユーザーをメンション",
    List(mod, "B") -> "太字",
    List(mod, "I") -> "斜体",
    List(mod, "U") -> "下線",
    List(mod, "Shift", "S") -> "取り消し線",
    List(mod, "E") -> "インラインコード",
    List(mod, "Shift", "H") -> "マーカー（強調）",
    List(mod, alt, "1〜3") -> "見出し1〜3",
    List(mod, alt, "0") -> "本文に戻す",
    List(mod, "Shift", "7") -> "番号付きリスト",
    List(mod, "Shift", "8") -> "箇条書き",
    List(mod, "Shift", "9") -> "ToDoリスト",
    List(mod, "Shift", "B") -> "引用",
    List(mod, alt, "C") -> "コードブロック",
    List("Tab", "/", "Shift", "Tab") -> "リストの字下げ・戻す",
    List("Shift", "Enter") -> "改行（段落を分けない）",
    List(mod, "A") -> "本文全体を選択（コードブロックの中ではまずその中身）",
    List("Shift", "↑", "/", "↓") -> "選択を広げる（段落の端からはブロック単位）",
    List(mod, "V") -> "画像を貼り付け（スクリーンショットなど。ファイルはドラッグでも）",
    List("---") -> "区切り線（行頭で入力）",
    List("#", "Space") -> "見出し（## で見出し2…。行頭で入力）",
    List("-", "Space") -> "箇条書き（行頭で入力）",
    List("[ ]", "Space") -> "ToDo（行頭で入力）",
    List("```") -> "コードブロック（行頭で入力）",
    List("$$", "…", "$$") -> "インライン数式",
  )),
  Group("データベース", List(
    List("Enter") -> "表の下の欄: 項目を追加／セル: 入力を確定",
    List("Esc") -> "セルの入力をやめる",
    List("ドラッグ") -> "ボード: 列を移す（値が変わる）／カレンダー: 日付を変える",
  )),
)

def groups(s: State): List[Group] = {
  val k = Ui.normalizeForSearch(s.q.v.trim)
  if (k.isEmpty) Groups
  else Groups.map(g => g.copy(items = g.items.filter { case (keys, d) => Ui.normalizeForSearch(s"${keys.mkString(" ")} $d ${g.title}").contains(k) }))
    .filter(_.items.nonEmpty)
}

def show(s: State): Unit = {
  s.q := ""
  s.open := true
  nextTick().foreach(_ => s.input.foreach(_.focus()))(using scala.scalajs.concurrent.JSExecutionContext.Implicits.queue)
}

def mounted(s: State): Unit = {
  s.ls.on[dom.KeyboardEvent](dom.document, "keydown") { e =>
    if (!e.asInstanceOf[js.Dynamic].isComposing.asInstanceOf[Boolean]) {
      if (s.open.v && e.key == "Escape") s.open := false
      else if (!Ui.isTyping(e.target) && e.key == "?" && !e.metaKey && !e.ctrlKey) {
        e.preventDefault()
        if (s.open.v) s.open := false else show(s)
      }
    }
  }
  s.ls.on[dom.Event](dom.window, "skiwi:shortcuts")(_ => show(s))
}

def unmounted(s: State): Unit = s.ls.clear()
</script>

<template>
  <div v-if="s.open.v" class="backdrop" @mousedown.self="s.open := false">
    <div class="dialog" role="dialog" aria-label="キーボードショートカット" data-testid="shortcuts">
      <header>
        <h2>⌨️ キーボードショートカット</h2>
        <input ref="s.input" v-model="s.q" type="search" placeholder="絞り込み（例: 見出し）" data-testid="shortcuts-filter">
        <a href="/help" target="_blank" rel="noopener" class="manual" @click="s.open := false">📖 使い方（マニュアル）</a>
        <button class="x" title="閉じる（Esc）" @click="s.open := false">✕</button>
      </header>
      <div class="cols">
        <section v-for="g in groups(s)" :key="g.title">
          <h3>{{ g.title }}</h3>
          <dl>
            <template v-for="it in g.items" :key="it._2">
              <dt><kbd v-for="k in it._1">{{ k }}</kbd></dt>
              <dd>{{ it._2 }}</dd>
            </template>
          </dl>
        </section>
        <p v-if="groups(s).isEmpty" class="muted">一致するショートカットはありません。</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.backdrop { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: flex-start; justify-content: center; padding: 6vh 16px 16px; z-index: 90; }
.dialog { background: var(--bg); border: 1px solid var(--line); border-radius: 12px; width: min(860px, 100%); max-height: 84vh; overflow: auto; padding: .8rem 1.1rem 1rem; box-shadow: 0 16px 48px rgba(0,0,0,.3); }
header { display: flex; align-items: center; gap: .8rem; margin-bottom: .4rem; }
h2 { font-size: 1.05rem; margin: 0; flex: none; }
header input { flex: 1; min-width: 0; font: inherit; font-size: .85rem; padding: .25rem .6rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; }
.x { border: none; color: var(--muted); }
.manual { font-size: .8rem; color: var(--accent); text-decoration: none; white-space: nowrap; }
.cols { columns: 2 22rem; column-gap: 1.6rem; }
section { break-inside: avoid; margin-bottom: .8rem; }
h3 { font-size: .8rem; color: var(--muted); margin: .4rem 0 .3rem; }
dl { display: grid; grid-template-columns: auto 1fr; gap: .25rem .7rem; margin: 0; font-size: .84rem; align-items: center; }
dt { white-space: nowrap; }
dd { margin: 0; }
kbd { display: inline-block; min-width: 1.4em; text-align: center; font: inherit; font-size: .74rem; padding: 0 5px; margin-right: 3px; border: 1px solid var(--line); border-bottom-width: 2px; border-radius: 5px; background: var(--side); }
</style>
