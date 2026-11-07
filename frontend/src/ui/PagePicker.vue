<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { currentPagePicker, type PickedPage } from './pagePicker'
import { useWorkspaceStore } from '../stores/workspace'
import { searchPages } from '../api'
import { useDebouncedSearch } from './composables'

/** リンクするページを選ぶ。空欄のときは開いているワークスペースのページ、入力するとすべてのワークスペースから検索する。 */
const store = useWorkspaceStore()
const q = ref('')
const selected = ref(0)
const input = ref<HTMLInputElement | null>(null)
const found = ref<(PickedPage & { icon: string | null; snippet?: string | null })[]>([])
const loading = ref(false)
const search = useDebouncedSearch(200)

watch(currentPagePicker, async (r) => {
  if (!r) return
  q.value = ''
  found.value = []
  selected.value = 0
  await nextTick()
  input.value?.focus()
})

const wsName = (id: string) => store.workspaces.find((w) => w.id === id)?.name ?? ''

const items = computed(() => {
  if (q.value.trim()) return found.value
  // 最近更新した順
  return [...store.pages].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).slice(0, 50)
    .map((p) => ({ id: p.id, workspaceId: p.workspaceId, title: p.title, icon: p.icon }))
})

watch(q, (v) => {
  selected.value = 0
  const query = v.trim()
  if (!query) { search.cancel(); found.value = []; return }
  loading.value = true
  search.run(async (latest) => {
    try {
      const r = await searchPages(query)
      if (latest()) found.value = r.map((x) => ({ id: x.page.id, workspaceId: x.page.workspaceId, title: x.page.title, icon: x.page.icon }))
    } finally {
      if (latest()) loading.value = false
    }
  })
})

function pick(i: number) {
  const it = items.value[i]
  if (it) currentPagePicker.value?.resolve({ id: it.id, workspaceId: it.workspaceId, title: it.title })
}
function close() { currentPagePicker.value?.resolve(null) }
function onKey(e: KeyboardEvent) {
  if (e.isComposing) return
  const n = items.value.length
  if (e.key === 'ArrowDown' && n) { e.preventDefault(); selected.value = (selected.value + 1) % n }
  else if (e.key === 'ArrowUp' && n) { e.preventDefault(); selected.value = (selected.value - 1 + n) % n }
  else if (e.key === 'Enter') { e.preventDefault(); pick(selected.value) }
  else if (e.key === 'Escape') { e.preventDefault(); close() }
}
</script>

<template>
  <div v-if="currentPagePicker" class="backdrop" @mousedown.self="close" @keydown="onKey">
    <div class="modal" role="dialog" aria-modal="true" aria-label="ページへのリンク" data-testid="page-picker">
      <header>
        <h2>ページへのリンク</h2>
        <input ref="input" :value="q" type="search" placeholder="ページ名で検索（すべてのワークスペース）" data-testid="page-picker-search"
          @input="q = ($event.target as HTMLInputElement).value" />
      </header>
      <ul class="list">
        <li v-if="!q.trim()" class="hint">このワークスペースの最近更新したページ</li>
        <li
          v-for="(it, i) in items"
          :key="it.id"
          :class="{ active: i === selected }"
          @mouseenter="selected = i"
          @click="pick(i)"
        >
          <span class="icon">{{ it.icon ?? '📄' }}</span>
          <span class="title">{{ it.title || '無題' }}</span>
          <span v-if="it.workspaceId !== store.currentId" class="ws">🗂 {{ wsName(it.workspaceId) }}</span>
        </li>
        <li v-if="q.trim() && !loading && !items.length" class="hint">「{{ q }}」に一致するページはありません</li>
      </ul>
      <footer>↑↓ で選択 ・ Enter でリンクを挿入 ・ Esc で閉じる ・ ページのURLを貼り付けてもリンクになります</footer>
    </div>
  </div>
</template>

<style scoped>
.backdrop { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: flex-start; justify-content: center; z-index: 900; padding: 12vh 16px 16px; }
.modal { background: var(--bg); border: 1px solid var(--line); border-radius: 12px; width: min(600px, 100%); max-height: 70vh; display: flex; flex-direction: column; box-shadow: 0 16px 48px rgba(0,0,0,.3); overflow: hidden; }
header { display: flex; align-items: center; gap: .8rem; padding: .8rem 1rem; border-bottom: 1px solid var(--line); }
h2 { font-size: 1rem; margin: 0; white-space: nowrap; }
header input { flex: 1; min-width: 0; padding: .4rem .7rem; border: 1px solid var(--line); border-radius: 8px; background: var(--bg); }
.list { list-style: none; margin: 0; padding: 4px; overflow: auto; flex: 1; }
.list li { display: flex; align-items: center; gap: .5rem; padding: .4rem .6rem; border-radius: 6px; cursor: pointer; }
.list li.active { background: var(--hover); }
.list li.hint { cursor: default; color: var(--muted); font-size: .8rem; }
.icon { width: 1.4em; text-align: center; }
.title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ws { color: var(--muted); font-size: .78rem; white-space: nowrap; }
footer { padding: .5rem 1rem; border-top: 1px solid var(--line); font-size: .78rem; color: var(--muted); }
</style>
