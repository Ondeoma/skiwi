<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { Editor, EditorContent, NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import type { JSONContent } from '@tiptap/core'
import { fetchSyncedSource, onSyncedRefresh } from './syncedSource'
import { guestContext, guestHref, pageHref } from './pageLinks'
import { findSyncedBlocks } from './Synced'
import { router } from '../router'
import { useHandleOnlyDrag } from './dragHandle'

/** 同期ブロックの埋め込み。元のページの最新の本文（保存済みのもの）を読み取り専用で表示する。 */
const props = defineProps(nodeViewProps)
const depth = computed(() => Number((props.extension.options as { depth: number }).depth) || 0)
const MAX_DEPTH = 2

const state = ref<'loading' | 'ok' | 'denied' | 'missing'>('loading')
const sourceTitle = ref('')
const view = shallowRef<Editor | null>(null)
let lastJson = ''
const bodyEl = ref<HTMLElement | null>(null)
useHandleOnlyDrag(bodyEl, '.synced-ref')

const href = computed(() => {
  const g = guestContext.value
  const pid = String(props.node.attrs.pageId)
  const gp = g?.pages.get(pid)
  return g ? (gp ? guestHref(g.token, gp) : null) : pageHref(String(props.node.attrs.workspaceId), pid)
})

function fetchSource() {
  const pid = String(props.node.attrs.pageId)
  const g = guestContext.value
  if (g && !g.pages.has(pid)) return Promise.resolve(null)
  return fetchSyncedSource(pid, g?.token ?? null)
}

async function refresh() {
  if (depth.value >= MAX_DEPTH) return
  try {
    const src = await fetchSource()
    if (!src) { state.value = 'denied'; return }
    sourceTitle.value = src.title
    const blockId = props.node.attrs.blockId as string | null
    const body = blockId ? findSyncedBlocks(src.content).find((b) => b.id === blockId)?.node : src.content
    if (!body) { state.value = 'missing'; return }
    const doc: JSONContent = { type: 'doc', content: (body as JSONContent).content ?? [] }
    const json = JSON.stringify(doc)
    if (json === lastJson) return
    lastJson = json
    if (!view.value) {
      // 埋め込みの中の埋め込みは1段まで（循環を防ぐ）
      const { contentExtensions } = await import('./extensions')
      const exts = contentExtensions().map((e) => (e.name === 'syncedRef' ? (e as typeof props.extension).configure({ depth: depth.value + 1 }) : e))
      view.value = new Editor({ editable: false, extensions: exts, content: doc })
    } else view.value.commands.setContent(doc)
    state.value = 'ok'
  } catch { if (state.value === 'loading') state.value = 'denied' }
}

let stopRefresh: (() => void) | undefined
onMounted(() => {
  void refresh()
  stopRefresh = onSyncedRefresh(() => void refresh())
})
onBeforeUnmount(() => {
  stopRefresh?.()
  view.value?.destroy()
})

function open(e: MouseEvent) {
  if (!href.value) return
  e.preventDefault()
  const id = props.node.attrs.blockId
  router.push(href.value + (id && !guestContext.value ? `#sync-${id}` : ''))
}
</script>

<template>
  <NodeViewWrapper class="synced-ref" contenteditable="false" data-testid="synced-ref">
    <div class="sr-bar" data-drag-handle draggable="true" title="ここをドラッグするとブロックを移動できます">
      <span>🔗 {{ node.attrs.blockId ? '同期ブロック' : 'ページの埋め込み' }}</span>
      <a v-if="href && state === 'ok'" :href="href" class="sr-src" @click="open">「{{ sourceTitle || '無題' }}」から ↗</a>
      <button title="最新の内容を読み込む" @click="lastJson = ''; refresh()">↻</button>
    </div>
    <div ref="bodyEl" class="sr-body">
      <p v-if="depth >= MAX_DEPTH" class="muted"><a v-if="href" :href="href" @click="open">埋め込みの中の埋め込みは表示しません（元のページを開く）</a></p>
      <p v-else-if="state === 'loading'" class="muted">読み込み中…</p>
      <p v-else-if="state === 'denied'" class="muted">🔒 元のページを閲覧する権限がないか、削除されています</p>
      <p v-else-if="state === 'missing'" class="muted">元のページから同期ブロックが削除されています</p>
      <EditorContent v-else-if="view" :editor="view" />
    </div>
  </NodeViewWrapper>
</template>
