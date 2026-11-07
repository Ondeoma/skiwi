<script setup lang="ts">
import { NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import { computed, onBeforeUnmount, shallowRef } from 'vue'
import { collectHeadings, scrollToHeading, type Heading } from './Toc'
import { useEditable } from './useEditable'

const props = defineProps(nodeViewProps)
const editable = useEditable(props.editor)
const maxLevel = computed(() => Number(props.node.attrs.maxLevel) || 3)

const headings = shallowRef<Heading[]>([])
const refresh = () => {
  const next = collectHeadings(props.editor.state.doc, maxLevel.value)
  const cur = headings.value
  if (next.length !== cur.length || next.some((h, i) => h.text !== cur[i].text || h.level !== cur[i].level || h.pos !== cur[i].pos)) headings.value = next
}
refresh()
props.editor.on('update', refresh) // 本文の変更時のみ（他の人の編集も含む）
onBeforeUnmount(() => { props.editor.off('update', refresh) })

/** 一番浅い見出しを基準に字下げする（H2から始まる文書でも左詰め） */
const minLevel = computed(() => Math.min(...headings.value.map((h) => h.level), 6))

function go(h: Heading) { scrollToHeading(props.editor.view, h.pos) }
function setLevel(e: Event) { props.updateAttributes({ maxLevel: Number((e.target as HTMLSelectElement).value) }) }
</script>

<template>
  <NodeViewWrapper class="toc" :class="{ selected: props.selected }" data-testid="toc">
    <div class="toc-head" contenteditable="false">
      <span>目次</span>
      <select v-if="editable" :value="maxLevel" title="表示する見出しの深さ" @change="setLevel">
        <option :value="1">見出し1まで</option>
        <option :value="2">見出し2まで</option>
        <option :value="3">見出し3まで</option>
      </select>
    </div>
    <ul v-if="headings.length" contenteditable="false">
      <li v-for="h in headings" :key="h.pos" :data-level="h.level - minLevel" :style="{ paddingLeft: (h.level - minLevel) * 1.2 + 'em' }">
        <a href="#" @click.prevent="go(h)">{{ h.text }}</a>
      </li>
    </ul>
    <p v-else class="toc-empty" contenteditable="false">見出しを追加すると、ここに目次が表示されます。</p>
  </NodeViewWrapper>
</template>
