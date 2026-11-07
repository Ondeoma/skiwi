<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Editor } from '@tiptap/core'
import type { Node as PMNode } from '@tiptap/pm/model'
import { COLORS } from '../ui/palette'
import { alertDialog, alertError, confirmDialog } from '../ui/dialog'
import { openPagePicker } from '../ui/pagePicker'
import { appendToPage } from '../ui/moveBlock'
import { BLOCK_TYPES } from '../editor/BlockAttrs'
import { useDismiss } from '../ui/composables'

/** ブロックのつまみ（⋮⋮）のメニュー: 変換・色・複製・削除・ブロックへのリンク・別のページへ移動 */
const props = defineProps<{ editor: Editor; node: PMNode; pos: number; at: { x: number; y: number }; pageId: string }>()
const emit = defineEmits<{ close: [] }>()
const el = ref<HTMLElement | null>(null)
const tab = ref<'main' | 'turn' | 'color'>('main')
const busy = ref(false)

const isText = computed(() => ['paragraph', 'heading', 'bulletList', 'orderedList', 'taskList', 'blockquote', 'details'].includes(props.node.type.name))
/** 中身の範囲（変換・色はこの範囲の文字に対して行う） */
const inner = () => ({ from: props.pos + 1, to: props.pos + props.node.nodeSize - 1 })
function done() { emit('close') }

const TURN: { label: string; icon: string; run: (c: ReturnType<Editor['chain']>) => ReturnType<Editor['chain']> }[] = [
  { label: 'テキスト', icon: '¶', run: (c) => c.clearNodes().setParagraph() },
  { label: '見出し1', icon: 'H1', run: (c) => c.clearNodes().setHeading({ level: 1 }) },
  { label: '見出し2', icon: 'H2', run: (c) => c.clearNodes().setHeading({ level: 2 }) },
  { label: '見出し3', icon: 'H3', run: (c) => c.clearNodes().setHeading({ level: 3 }) },
  { label: '箇条書きリスト', icon: '•', run: (c) => c.clearNodes().toggleBulletList() },
  { label: '番号付きリスト', icon: '1.', run: (c) => c.clearNodes().toggleOrderedList() },
  { label: 'ToDoリスト', icon: '☑', run: (c) => c.clearNodes().toggleTaskList() },
  { label: '引用', icon: '❝', run: (c) => c.clearNodes().toggleBlockquote() },
  { label: 'コード', icon: '</>', run: (c) => c.clearNodes().setCodeBlock() },
  { label: '開閉ブロック', icon: '▸', run: (c) => c.clearNodes().setDetails() },
]
function turn(t: (typeof TURN)[number]) {
  t.run(props.editor.chain().focus().setTextSelection(inner())).run()
  done()
}
function color(c: { color?: string | null; bg?: string | null }) {
  const ch = props.editor.chain().focus().setTextSelection(inner())
  if (c.color === null && c.bg === null) ch.unsetTextColor().run()
  else ch.setTextColor(c).run()
  done()
}
/** ブロックの JSON（ブロックへのリンクの ID は外す） */
const copyJson = () => {
  const j = props.node.toJSON()
  if (j.attrs?.blockId) j.attrs = { ...j.attrs, blockId: null }
  return j
}
function duplicate() {
  props.editor.chain().focus().insertContentAt(props.pos + props.node.nodeSize, copyJson()).run()
  done()
}
function remove() {
  props.editor.chain().focus().deleteRange({ from: props.pos, to: props.pos + props.node.nodeSize }).run()
  done()
}
async function copyLink() {
  const supports = BLOCK_TYPES.includes(props.node.type.name)
  if (!supports) { await alertDialog('このブロックにはリンクを付けられません'); done(); return }
  let id = props.node.attrs.blockId as string | null
  if (!id) {
    id = Math.random().toString(36).slice(2, 10)
    props.editor.view.dispatch(props.editor.state.tr.setNodeAttribute(props.pos, 'blockId', id))
  }
  const url = `${location.origin}${location.pathname}#block-${id}`
  await navigator.clipboard.writeText(url).catch(() => {})
  done()
  await alertDialog(`ブロックへのリンクをコピーしました。\nほかのページに貼り付けると、このブロックへのリンクになります。\n\n${url}`)
}
async function moveTo() {
  if (props.node.type.name === 'whiteboard') { await alertDialog('ホワイトボードは別のページへ移動できません（中身はこのページに保存されているため）'); done(); return }
  const target = await openPagePicker()
  if (!target) return
  if (target.id === props.pageId) { await alertDialog('同じページです'); return }
  if (!(await confirmDialog(`このブロックを「${target.title || '無題'}」の末尾へ移動しますか？`, { okLabel: '移動' }))) return
  busy.value = true
  const pos = props.pos
  const node = props.editor.state.doc.nodeAt(pos) ?? props.node
  const json = node.toJSON()
  if (json.attrs?.blockId === undefined) delete json.attrs?.blockId
  try {
    await appendToPage(target.id, [json])
    // 移動先に書き込めたら、こちらから消す（そのあいだに位置が変わっていないか確かめる）
    const cur = props.editor.state.doc.nodeAt(pos)
    if (cur && cur.eq(node)) props.editor.chain().deleteRange({ from: pos, to: pos + node.nodeSize }).run()
    done()
    await alertDialog(`「${target.title || '無題'}」の末尾へ移動しました`)
  } catch (e) {
    await alertError('移動できませんでした', e)
  } finally { busy.value = false }
}

const close = () => emit('close')
useDismiss((t) => !el.value || el.value.contains(t), close, { escape: close, defer: true })
const style = computed(() => ({ left: `${Math.min(props.at.x, window.innerWidth - 250)}px`, top: `${Math.min(props.at.y, window.innerHeight - 380)}px` }))
</script>

<template>
  <div ref="el" class="block-menu" :style="style" role="menu" data-testid="block-menu">
    <template v-if="tab === 'main'">
      <button v-if="isText" role="menuitem" data-testid="block-turn" @click="tab = 'turn'">🔁 変換<span class="arrow">›</span></button>
      <button v-if="isText" role="menuitem" data-testid="block-color" @click="tab = 'color'">🎨 色<span class="arrow">›</span></button>
      <button role="menuitem" data-testid="block-duplicate" @click="duplicate">⧉ 複製</button>
      <button role="menuitem" data-testid="block-link" @click="copyLink">🔗 ブロックへのリンクをコピー</button>
      <button role="menuitem" :disabled="busy" data-testid="block-move" @click="moveTo">↪ 別のページへ移動…</button>
      <hr />
      <button role="menuitem" class="danger" data-testid="block-delete" @click="remove">🗑 削除</button>
    </template>
    <template v-else-if="tab === 'turn'">
      <button class="back" @click="tab = 'main'">‹ 戻る</button>
      <button v-for="t in TURN" :key="t.label" role="menuitem" data-testid="block-turn-item" @click="turn(t)"><span class="ic">{{ t.icon }}</span>{{ t.label }}</button>
    </template>
    <template v-else>
      <button class="back" @click="tab = 'main'">‹ 戻る</button>
      <p class="sub">文字の色</p>
      <div class="swatches">
        <button class="sw" title="標準" @click="color({ color: null })">A</button>
        <button v-for="c in COLORS" :key="c.id" class="sw" :data-text-color="c.id" :title="c.label" :data-testid="`block-color-${c.id}`" @click="color({ color: c.id })">A</button>
      </div>
      <p class="sub">背景の色</p>
      <div class="swatches">
        <button class="sw" title="なし" @click="color({ bg: null })">∅</button>
        <button v-for="c in COLORS" :key="c.id" class="sw" :data-text-bg="c.id" :title="c.label" :data-testid="`block-bg-${c.id}`" @click="color({ bg: c.id })">&nbsp;</button>
      </div>
      <button class="clear" @click="color({ color: null, bg: null })">色をすべて外す</button>
    </template>
  </div>
</template>

<style scoped>
.block-menu { position: fixed; z-index: 70; width: 240px; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 4px; box-shadow: 0 8px 24px rgba(0,0,0,.18); display: flex; flex-direction: column; font-size: .88rem; }
.block-menu > button { display: flex; align-items: center; gap: .4rem; text-align: left; border: none; padding: .35rem .6rem; border-radius: 6px; background: none; }
.block-menu > button:hover { background: var(--hover); }
.arrow { margin-left: auto; color: var(--muted); }
.ic { width: 1.6rem; color: var(--muted); font-size: .8rem; text-align: center; }
.back { color: var(--muted); font-size: .8rem; }
hr { border: none; border-top: 1px solid var(--line); margin: 3px 0; width: 100%; }
.danger:hover { color: var(--danger); }
.sub { margin: .4rem .6rem .2rem; font-size: .72rem; color: var(--muted); }
.swatches { display: grid; grid-template-columns: repeat(5, 1fr); gap: 4px; padding: 0 .5rem; }
.sw { height: 28px; border: 1px solid var(--line); border-radius: 6px; font-weight: 700; padding: 0; background: none; }
.clear { margin-top: .5rem; color: var(--muted); font-size: .8rem; }
</style>
