<script setup lang="ts">
import type { Editor } from '@tiptap/vue-3'
import { CellSelection } from '@tiptap/pm/tables'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { confirmDialog } from '../ui/dialog'
import { COLORS, type ColorId } from '../ui/palette'
import { useDismiss } from '../ui/composables'

/**
 * 表の操作。カーソルが表の中にあるとき、表の左側の余白（本文と重ならない位置）に小さなボタンを出し、
 * クリックで操作メニューを開く。container は位置計算の基準（position: relative の要素）。
 */
const props = defineProps<{ editor: Editor; container: HTMLElement | null }>()

const btn = ref<{ left: number; top: number } | null>(null)
const open = ref(false)
const menuEl = ref<HTMLElement | null>(null)
const btnEl = ref<HTMLElement | null>(null)
const cellColor = ref<string | null>(null)
const headerRow = ref(false)

/** 選択位置のセル（td/th）と表のDOM。表の外なら null。 */
function current(): { cell: HTMLElement; table: HTMLElement; attrs: Record<string, unknown> } | null {
  const { state, view } = props.editor
  const $from = state.selection.$from
  for (let d = $from.depth; d > 0; d--) {
    const n = $from.node(d)
    if (n.type.name === 'tableCell' || n.type.name === 'tableHeader') {
      const cell = view.nodeDOM($from.before(d)) as HTMLElement | null
      const table = (cell?.closest('.tableWrapper') ?? cell?.closest('table')) as HTMLElement | null
      return cell && table ? { cell, table, attrs: n.attrs } : null
    }
  }
  return null
}

function update() {
  const c = props.editor.isEditable && props.container ? current() : null
  if (!c || !props.container) { btn.value = null; open.value = false; return }
  const base = props.container.getBoundingClientRect()
  const t = c.table.getBoundingClientRect()
  const r = c.cell.getBoundingClientRect()
  // 表の左外側・現在の行の高さに置く
  btn.value = { left: t.left - base.left - 28, top: r.top - base.top + Math.min(6, Math.max(0, r.height / 2 - 11)) }
  cellColor.value = (c.attrs.backgroundColor as string | null) ?? null
  headerRow.value = props.editor.isActive('tableHeader')
}

function run(f: (c: ReturnType<Editor['chain']>) => ReturnType<Editor['chain']>, close = false) {
  f(props.editor.chain().focus()).run()
  if (close) open.value = false
}
function setColor(c: ColorId | null) {
  run((ch) => ch.setCellAttribute('backgroundColor', c))
  cellColor.value = c
}
async function deleteTable() {
  open.value = false
  if (await confirmDialog('表を削除しますか？', { okLabel: '削除', danger: true })) props.editor.chain().focus().deleteTable().run()
}
useDismiss((t) => !open.value || !!menuEl.value?.contains(t) || !!btnEl.value?.contains(t), () => { open.value = false }, {
  escape: () => { if (open.value) { open.value = false; props.editor.commands.focus() } },
})

onMounted(() => {
  props.editor.on('selectionUpdate', update)
  props.editor.on('update', update)
  props.editor.on('transaction', update)
  window.addEventListener('resize', update)
  update()
})
onBeforeUnmount(() => {
  props.editor.off('selectionUpdate', update)
  props.editor.off('update', update)
  props.editor.off('transaction', update)
  window.removeEventListener('resize', update)
})
const multi = () => props.editor.state.selection instanceof CellSelection
</script>

<template>
  <template v-if="btn">
    <button
      ref="btnEl"
      class="table-handle"
      :class="{ on: open }"
      :style="{ left: btn.left + 'px', top: btn.top + 'px' }"
      title="表の操作"
      data-testid="table-handle"
      @mousedown.prevent
      @click="open = !open"
    >⋮</button>
    <div
      v-if="open"
      ref="menuEl"
      class="table-menu"
      :style="{ left: btn.left + 'px', top: btn.top + 26 + 'px' }"
      data-testid="table-menu"
      @mousedown.prevent
    >
      <div class="grp">
        <span class="lbl">行</span>
        <button title="上に行を追加" @click="run((c) => c.addRowBefore())">↑ 上に追加</button>
        <button title="下に行を追加" @click="run((c) => c.addRowAfter())">↓ 下に追加</button>
        <button class="danger" title="行を削除" @click="run((c) => c.deleteRow())">削除</button>
      </div>
      <div class="grp">
        <span class="lbl">列</span>
        <button title="左に列を追加" @click="run((c) => c.addColumnBefore())">← 左に追加</button>
        <button title="右に列を追加" @click="run((c) => c.addColumnAfter())">→ 右に追加</button>
        <button class="danger" title="列を削除" @click="run((c) => c.deleteColumn())">削除</button>
      </div>
      <div class="grp colors">
        <span class="lbl">背景</span>
        <button class="swatch none" :class="{ on: !cellColor }" title="なし" data-testid="cell-color-none" @click="setColor(null)">∅</button>
        <button
          v-for="c in COLORS"
          :key="c.id"
          class="swatch"
          :class="{ on: cellColor === c.id }"
          :data-color="c.id"
          :title="c.label"
          :data-testid="`cell-color-${c.id}`"
          @click="setColor(c.id)"
        />
      </div>
      <div class="grp">
        <span class="lbl">表</span>
        <button :class="{ on: headerRow }" title="先頭行を見出しにする／戻す" @click="run((c) => c.toggleHeaderRow())">見出し行</button>
        <button title="先頭列を見出しにする／戻す" @click="run((c) => c.toggleHeaderColumn())">見出し列</button>
        <button :title="multi() ? '選択したセルを結合' : '結合を解除（複数セルをドラッグで選ぶと結合）'" @click="run((c) => c.mergeOrSplit())">結合/分割</button>
      </div>
      <div class="grp">
        <button class="danger wide" title="表を削除" @click="deleteTable">🗑 表を削除</button>
      </div>
    </div>
  </template>
</template>

<style scoped>
.table-handle { position: absolute; z-index: 20; width: 22px; height: 22px; padding: 0; line-height: 20px; font-weight: 700; color: var(--muted); background: var(--bg); border: 1px solid var(--line); border-radius: 5px; }
.table-handle:hover, .table-handle.on { color: var(--accent); border-color: var(--accent); }
.table-menu { position: absolute; z-index: 30; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 6px; box-shadow: 0 8px 24px rgba(0,0,0,.18); display: flex; flex-direction: column; gap: 4px; font-size: .82rem; }
.grp { display: flex; align-items: center; gap: 3px; }
.lbl { width: 2.6em; color: var(--muted); font-size: .78rem; flex: none; }
.grp button { border: none; padding: 2px 7px; white-space: nowrap; }
.grp button.on { color: var(--accent); background: var(--hover); }
.danger:hover { color: var(--danger); }
.wide { width: 100%; text-align: left; }
.colors { gap: 4px; }
.colors .swatch { width: 20px; height: 20px; padding: 0; border: 1px solid var(--line); border-radius: 4px; }
.colors .swatch.on { outline: 2px solid var(--accent); outline-offset: 1px; }
.colors .none { font-size: .75rem; color: var(--muted); line-height: 18px; }
</style>
