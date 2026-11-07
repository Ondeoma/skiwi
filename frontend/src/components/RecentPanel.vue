<script setup lang="ts">
import { ref, watch } from 'vue'
import { api5, formatDate, type RecentItem } from '../api'
import { useStoredRef } from '../ui/composables'

/** 最近見たページ・自分が編集したページ（すべてのワークスペース）。 */
const props = defineProps<{ wid: string }>()
const KEY = 'skiwi.recentKind'
const kind = useStoredRef<'visited' | 'edited'>(KEY, (raw) => (raw === 'edited' ? 'edited' : 'visited'))
const items = ref<RecentItem[] | null>(null)

watch([kind, () => props.wid], async ([k]) => {
  items.value = null
  try { items.value = await api5.recent(k) } catch { items.value = [] }
}, { immediate: true })
</script>

<template>
  <div class="recent" data-testid="recent">
    <div class="seg">
      <button :class="{ on: kind === 'visited' }" data-testid="recent-visited" @click="kind = 'visited'">最近見た</button>
      <button :class="{ on: kind === 'edited' }" data-testid="recent-edited" @click="kind = 'edited'">自分が編集した</button>
    </div>
    <p v-if="items === null" class="muted">読み込み中…</p>
    <p v-else-if="!items.length" class="muted">まだありません。</p>
    <router-link v-for="r in items ?? []" :key="r.page.id" :to="`/w/${r.page.workspaceId}/p/${r.page.id}`" class="item">
      <div class="t">{{ r.page.icon ?? '📄' }} {{ r.page.title || '無題' }}</div>
      <div class="m">{{ formatDate(r.at) }}<template v-if="r.page.workspaceId !== props.wid"> ・ 🗂 {{ r.workspaceName }}</template></div>
    </router-link>
  </div>
</template>

<style scoped>
.recent { padding: 0 .3rem; }
.seg { display: flex; gap: 2px; margin: 0 .3rem .4rem; font-size: .75rem; }
.seg button { flex: 1; border: 1px solid var(--line); padding: 1px 4px; color: var(--muted); }
.seg button.on { background: var(--hover); color: var(--fg); border-color: var(--accent); }
.item { display: block; padding: .35rem .5rem; border-radius: 6px; color: inherit; text-decoration: none; }
.item:hover, .item.router-link-active { background: var(--hover); }
.t { font-size: .9rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.m { font-size: .72rem; color: var(--muted); }
.muted { font-size: .85rem; padding: 0 .5rem; }
</style>
