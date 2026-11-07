<script setup lang="ts">
import { computed, ref, toRaw } from 'vue'
import { NodeViewContent, NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import { useEditable } from './useEditable'
import { MAX_COLUMNS, MIN_COLUMNS, parseLayout, resizeColumns } from './Columns'
import { promptDialog } from '../ui/dialog'
import { useHandleOnlyDrag } from './dragHandle'

const props = defineProps(nodeViewProps)
const editable = useEditable(props.editor)
const bar = ref<HTMLElement | null>(null)
useHandleOnlyDrag(bar, '.columns-wrap')

const count = computed(() => props.node.childCount)
const spans = computed(() => parseLayout(props.node.attrs.layout, count.value))
const grid = computed(() => spans.value.map((s) => `minmax(0, ${s}fr)`).join(' '))
const counts = Array.from({ length: MAX_COLUMNS - MIN_COLUMNS + 1 }, (_, i) => i + MIN_COLUMNS)

/** よく使う比（12等分の目盛りで考えたもの） */
const PRESETS: Record<number, { label: string; layout: string | null }[]> = {
  2: [{ label: '1 : 1', layout: null }, { label: '1 : 2', layout: '1,2' }, { label: '2 : 1', layout: '2,1' }, { label: '1 : 3', layout: '1,3' }, { label: '3 : 1', layout: '3,1' }],
  3: [{ label: '1 : 1 : 1', layout: null }, { label: '1 : 2 : 1', layout: '1,2,1' }, { label: '2 : 1 : 1', layout: '2,1,1' }, { label: '1 : 1 : 2', layout: '1,1,2' }],
  4: [{ label: '均等', layout: null }, { label: '1 : 2 : 2 : 1', layout: '1,2,2,1' }],
}
const presets = computed(() => PRESETS[count.value] ?? [{ label: '均等', layout: null }])
const currentLayout = computed(() => (props.node.attrs.layout as string | null) ?? null)

function replace(fn: (pos: number) => void) {
  const pos = props.getPos()
  if (typeof pos === 'number') fn(pos)
}
function setCount(e: Event) {
  const n = Number((e.target as HTMLSelectElement).value)
  replace((pos) => {
    // props.node は Vue の proxy 越しなので、生の ProseMirror ノードで作り直す（型の同一性で検証されるため）
    const node = props.editor.state.doc.nodeAt(pos) ?? toRaw(props.node)
    const next = resizeColumns(node, n)
    props.editor.view.dispatch(props.editor.state.tr.replaceWith(pos, pos + node.nodeSize, next))
  })
}
function setLayout(layout: string | null) { props.updateAttributes({ layout }) }
async function customLayout() {
  const v = await promptDialog(`${count.value}列の幅の比をカンマ区切りで（例: ${Array.from({ length: count.value }, (_, i) => (i === 0 ? 2 : 1)).join(',')}）。合計12にすると12等分の目盛りで指定できます`, spans.value.join(','), { okLabel: '設定' })
  if (v === null) return
  const xs = v.split(/[,、\s:]+/).filter(Boolean).map(Number)
  if (xs.length === count.value && xs.every((x) => Number.isFinite(x) && x > 0 && x <= 12)) setLayout(xs.join(','))
}
function remove() { props.deleteNode() }
</script>

<template>
  <NodeViewWrapper class="columns-wrap" :class="{ editing: editable }" data-testid="columns">
    <div v-if="editable" ref="bar" class="columns-bar" contenteditable="false">
      <span class="cb-handle" data-drag-handle draggable="true" title="ここをドラッグするとブロックを移動できます">⠿ 段組み</span>
      <label>列数
        <select :value="count" data-testid="columns-count" @change="setCount">
          <option v-for="n in counts" :key="n" :value="n">{{ n }}</option>
        </select>
      </label>
      <span class="cb-presets">
        <button v-for="p in presets" :key="p.label" :class="{ on: currentLayout === p.layout }" @click="setLayout(p.layout)">{{ p.label }}</button>
        <button title="幅の比を数字で指定" @click="customLayout">比を指定…</button>
      </span>
      <button class="cb-del" title="段組みを削除（中身も消えます）" @click="remove">🗑</button>
    </div>
    <span v-else ref="bar" class="cb-anchor" />
    <NodeViewContent class="columns" :style="{ gridTemplateColumns: grid }" />
  </NodeViewWrapper>
</template>
