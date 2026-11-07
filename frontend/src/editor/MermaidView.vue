<script setup lang="ts">
import { nodeViewProps, NodeViewWrapper } from '@tiptap/vue-3'
import { onMounted, ref, watch } from 'vue'

const props = defineProps(nodeViewProps)
const svg = ref('')
const error = ref('')
const editing = ref(false)
const draft = ref('')
let seq = 0

async function render(code: string) {
  const mermaid = (await import('mermaid')).default
  const dark = matchMedia('(prefers-color-scheme: dark)').matches
  mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: dark ? 'dark' : 'default' })
  try {
    const out = await mermaid.render(`mmd-${Math.random().toString(36).slice(2)}-${++seq}`, code)
    svg.value = out.svg
    error.value = ''
  } catch (e) {
    error.value = (e as Error).message ?? String(e)
  }
}

onMounted(() => render(props.node.attrs.code))
watch(() => props.node.attrs.code, (c) => render(c))

function startEdit() {
  if (!props.editor.isEditable) return
  draft.value = props.node.attrs.code
  editing.value = true
}
function save() {
  props.updateAttributes({ code: draft.value })
  editing.value = false
}
</script>

<template>
  <NodeViewWrapper class="mermaid-block" :class="{ selected: props.selected }">
    <div v-if="editing" class="editor-pane" @keydown.stop>
      <textarea v-model="draft" rows="8" spellcheck="false" />
      <div class="buttons">
        <button @click="save">反映</button>
        <button @click="editing = false">キャンセル</button>
      </div>
    </div>
    <div class="preview" :title="props.editor.isEditable ? 'クリックで編集' : ''" @click="startEdit">
      <div v-if="error" class="error">Mermaid の構文エラー: {{ error }}</div>
      <!-- securityLevel: 'strict' によりMermaidがサニタイズしたSVG -->
      <div v-else v-html="svg" />
    </div>
  </NodeViewWrapper>
</template>

<style scoped>
.mermaid-block { margin: .8em 0; border: 1px solid transparent; border-radius: 6px; padding: .5em; }
.mermaid-block.selected, .mermaid-block:hover { border-color: var(--line); }
.preview { cursor: pointer; text-align: center; overflow-x: auto; }
.error { font-size: .85em; text-align: left; white-space: pre-wrap; }
textarea { width: 100%; font-family: ui-monospace, monospace; font-size: .9em; background: var(--side); color: inherit; border: 1px solid var(--line); border-radius: 6px; padding: .5em; }
.buttons { display: flex; gap: .4em; margin: .3em 0 .6em; }
</style>
