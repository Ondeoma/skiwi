<script setup lang="ts">
import { computed, defineAsyncComponent, ref } from 'vue'
import { NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import { useRoute } from 'vue-router'
import type { DbView } from '../api'
import { guestContext } from './pageLinks'

const DatabaseView = defineAsyncComponent(() => import('../database/DatabaseView.vue'))

/** ページの中のデータベース。ビューの変更は、編集できる人ならこのブロックに保存し、閲覧のみの人はこの画面だけに反映する */
const props = defineProps(nodeViewProps)
const route = useRoute()
const wid = computed(() => String(route.params.wid ?? ''))
const parsed = computed<DbView[] | null>(() => { try { return props.node.attrs.views ? JSON.parse(String(props.node.attrs.views)) : null } catch { return null } })
const local = ref<DbView[] | null>(null)
const views = computed(() => local.value ?? parsed.value)
function onViews(vs: DbView[]) {
  if (props.editor.isEditable) props.updateAttributes({ views: JSON.stringify(vs) })
  else local.value = vs
}
</script>

<template>
  <NodeViewWrapper class="db-block" data-testid="database-block">
    <div v-if="guestContext" class="db-guest">📊 データベース（公開ページでは表示されません）</div>
    <div v-else contenteditable="false" class="db-inner">
      <span v-if="editor.isEditable" class="db-grip" data-drag-handle title="ドラッグで移動">⋮⋮</span>
      <DatabaseView v-if="node.attrs.databaseId && wid" :page-id="String(node.attrs.databaseId)" :wid="wid" embedded :local-views="views" @views="onViews" />
    </div>
  </NodeViewWrapper>
</template>

<style scoped>
.db-block { margin: .8em 0; }
.db-inner { position: relative; border-top: 1px solid var(--line); padding-top: .5rem; }
.db-grip { position: absolute; left: -1.4rem; top: .6rem; color: var(--muted); cursor: grab; font-size: .8rem; letter-spacing: -2px; }
.db-guest { color: var(--muted); font-size: .85rem; border: 1px dashed var(--line); border-radius: 6px; padding: .5rem .8rem; }
</style>
