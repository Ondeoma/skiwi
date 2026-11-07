<script setup lang="ts">
import { computed, ref } from 'vue'
import { isMac, normalizeForSearch } from '../util/text'
import { useEventListener, isTyping } from './composables'

/** キーボードショートカットの一覧。「?」（入力中以外）または ⌘K の「ショートカット一覧」で開く */
const open = ref(false)
const q = ref('')
const mac = isMac
const mod = mac ? '⌘' : 'Ctrl'
const alt = mac ? '⌥' : 'Alt'

const GROUPS: { title: string; items: [string[], string][] }[] = [
  { title: '全体', items: [
    [[mod, 'K'], 'クイック切り替え（ページ・ワークスペースへの移動、操作）'],
    [[mod, '\\'], 'サイドメニューを開く・閉じる'],
    [['?'], 'このショートカット一覧'],
    [['Esc'], 'ダイアログ・メニューを閉じる／ページの選択を解除'],
  ] },
  { title: 'ページ', items: [
    [['E'], '編集モードにする（表示モードのとき）'],
    [['Esc'], '編集モードを終える（エディタの外で）'],
    [[mod, 'P'], '印刷 / PDF（ブラウザの機能）'],
  ] },
  { title: 'サイドメニュー（ページツリー）', items: [
    [[mod, 'クリック'], 'ページを選択に加える・外す（まとめて操作）'],
    [['Shift', 'クリック'], '範囲をまとめて選択'],
    [['ダブルクリック'], 'ページ名を変更'],
    [['右クリック'], 'ページのメニュー'],
    [['ドラッグ'], '並べ替え・別のページの下へ移動'],
  ] },
  { title: 'エディタ（編集モード）', items: [
    [['/'], 'ブロックを挿入（見出し・表・ToDo・図・ホワイトボードなど）'],
    [['@'], 'ユーザーをメンション'],
    [[mod, 'B'], '太字'],
    [[mod, 'I'], '斜体'],
    [[mod, 'U'], '下線'],
    [[mod, 'Shift', 'S'], '取り消し線'],
    [[mod, 'E'], 'インラインコード'],
    [[mod, 'Shift', 'H'], 'マーカー（強調）'],
    [[mod, alt, '1〜3'], '見出し1〜3'],
    [[mod, alt, '0'], '本文に戻す'],
    [[mod, 'Shift', '7'], '番号付きリスト'],
    [[mod, 'Shift', '8'], '箇条書き'],
    [[mod, 'Shift', '9'], 'ToDoリスト'],
    [[mod, 'Shift', 'B'], '引用'],
    [[mod, alt, 'C'], 'コードブロック'],
    [['Tab', '/', 'Shift', 'Tab'], 'リストの字下げ・戻す'],
    [['Shift', 'Enter'], '改行（段落を分けない）'],
    [[mod, 'V'], '画像を貼り付け（スクリーンショットなど。ファイルはドラッグでも）'],
    [['---'], '区切り線（行頭で入力）'],
    [['#', 'Space'], '見出し（## で見出し2…。行頭で入力）'],
    [['-', 'Space'], '箇条書き（行頭で入力）'],
    [['[ ]', 'Space'], 'ToDo（行頭で入力）'],
    [['```'], 'コードブロック（行頭で入力）'],
    [['$$', '…', '$$'], 'インライン数式'],
  ] },
  { title: 'データベース', items: [
    [['Enter'], '表の下の欄: 項目を追加／セル: 入力を確定'],
    [['Esc'], 'セルの入力をやめる'],
    [['ドラッグ'], 'ボード: 列を移す（値が変わる）／カレンダー: 日付を変える'],
  ] },
]
const groups = computed(() => {
  const k = normalizeForSearch(q.value.trim())
  if (!k) return GROUPS
  return GROUPS.map((g) => ({ ...g, items: g.items.filter(([keys, d]) => normalizeForSearch(`${keys.join(' ')} ${d} ${g.title}`).includes(k)) })).filter((g) => g.items.length)
})

function onKey(e: KeyboardEvent) {
  if (e.isComposing) return
  if (open.value && e.key === 'Escape') { open.value = false; return }
  if (!isTyping(e.target) && e.key === '?' && !e.metaKey && !e.ctrlKey) { e.preventDefault(); q.value = ''; open.value = !open.value }
}
const onOpen = () => { q.value = ''; open.value = true }
useEventListener(document, 'keydown', onKey)
useEventListener(window, 'skiwi:shortcuts', onOpen)
</script>

<template>
  <div v-if="open" class="backdrop" @mousedown.self="open = false">
    <div class="dialog" role="dialog" aria-label="キーボードショートカット" data-testid="shortcuts">
      <header>
        <h2>⌨️ キーボードショートカット</h2>
        <input v-model="q" type="search" placeholder="絞り込み（例: 見出し）" data-testid="shortcuts-filter" autofocus />
        <a href="/help" target="_blank" rel="noopener" class="manual" @click="open = false">📖 使い方（マニュアル）</a>
        <button class="x" title="閉じる（Esc）" @click="open = false">✕</button>
      </header>
      <div class="cols">
        <section v-for="g in groups" :key="g.title">
          <h3>{{ g.title }}</h3>
          <dl>
            <template v-for="([keys, d], i) in g.items" :key="i">
              <dt><kbd v-for="(k, j) in keys" :key="j">{{ k }}</kbd></dt>
              <dd>{{ d }}</dd>
            </template>
          </dl>
        </section>
        <p v-if="!groups.length" class="muted">一致するショートカットはありません。</p>
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
