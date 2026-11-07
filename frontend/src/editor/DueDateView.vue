<script setup lang="ts">
import { computed, ref } from 'vue'
import { NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import { useEditable } from './useEditable'
import { today } from './DueDate'

const props = defineProps(nodeViewProps)
const editable = useEditable(props.editor)
const input = ref<HTMLInputElement | null>(null)

const date = computed(() => String(props.node.attrs.date ?? ''))
/** 項目が完了済みか（親の ToDo 項目の checked） */
const done = computed(() => {
  const pos = props.getPos()
  if (typeof pos !== 'number') return false
  const $pos = props.editor.state.doc.resolve(pos)
  for (let d = $pos.depth; d > 0; d--) { const n = $pos.node(d); if (n.type.name === 'taskItem') return !!n.attrs.checked }
  return false
})
const tone = computed(() => {
  if (!date.value || done.value) return 'normal'
  if (date.value < today()) return 'overdue'
  if (date.value <= today(1)) return 'soon'
  return 'normal'
})
const label = computed(() => {
  if (!date.value) return '期限なし'
  const [y, m, d] = date.value.split('-').map(Number)
  const wd = '日月火水木金土'[new Date(y, m - 1, d).getDay()]
  return `${y === new Date().getFullYear() ? '' : `${y}/`}${m}/${d}（${wd}）`
})
function pick() {
  if (!editable.value) return
  const el = input.value
  if (!el) return
  if (typeof el.showPicker === 'function') el.showPicker()
  else el.click()
}
function onChange(e: Event) { props.updateAttributes({ date: (e.target as HTMLInputElement).value || null }) }
</script>

<template>
  <NodeViewWrapper as="span" class="due-date" :class="[tone, { done }]" contenteditable="false" :title="editable ? 'クリックで期限を変更' : `期限 ${date}`" data-testid="due-date" @click="pick">
    📅 {{ label }}
    <input ref="input" type="date" :value="date" class="due-input" tabindex="-1" @change="onChange" />
  </NodeViewWrapper>
</template>
