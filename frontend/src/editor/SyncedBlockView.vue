<script setup lang="ts">
import { pageHref } from './pageLinks'
import { NodeViewContent, NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import { useEditable } from './useEditable'
import { useCopy } from '../ui/composables'

const props = defineProps(nodeViewProps)
const editable = useEditable(props.editor)
const { copied, copy } = useCopy()

/** 埋め込み用のリンク（このページのURL＋#sync-ID）。貼り付けると埋め込みになる */
async function copyLink() {
  const m = /^\/w\/([0-9a-f-]{36})\/p\/([0-9a-f-]{36})/.exec(location.pathname)
  if (!m) return
  await copy(`${location.origin}${pageHref(m[1], m[2])}#sync-${props.node.attrs.id}`)
}
function unwrap() {
  const pos = props.getPos()
  if (typeof pos !== 'number') return
  props.editor.chain().focus().setTextSelection(pos + 2).lift('syncedBlock').run()
}
</script>

<template>
  <NodeViewWrapper class="synced-block" :data-synced-id="node.attrs.id" data-testid="synced-block">
    <div class="sb-bar" contenteditable="false">
      <span>🔗 同期ブロック（元）</span>
      <span class="sb-hint">ほかのページに埋め込めます。ここを直すと埋め込み先にも反映されます</span>
      <button data-testid="synced-copy" @click="copyLink">{{ copied ? 'コピーしました' : '埋め込み用リンクをコピー' }}</button>
      <button v-if="editable" title="同期ブロックを解除（中身は残ります）" @click="unwrap">解除</button>
    </div>
    <NodeViewContent class="sb-content" />
  </NodeViewWrapper>
</template>
