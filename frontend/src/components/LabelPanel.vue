<script setup lang="ts">
import { computed } from 'vue'
import { useWorkspaceStore } from '../stores/workspace'

/** このワークスペースのラベル一覧と、ラベルでの絞り込み。 */
const props = defineProps<{ wid: string }>()
const store = useWorkspaceStore()

const all = computed(() => {
  const m = new Map<string, { label: string; count: number }>()
  for (const p of store.pages) for (const l of p.labels ?? []) {
    const k = l.toLowerCase()
    const e = m.get(k) ?? { label: l, count: 0 }
    e.count++
    m.set(k, e)
  }
  return [...m.values()].sort((a, b) => b.count - a.count || a.label.localeCompare(b.label, 'ja'))
})
const selected = computed(() => store.labelFilter)
const pages = computed(() => {
  const k = selected.value?.toLowerCase()
  return k ? store.pages.filter((p) => p.labels?.some((l) => l.toLowerCase() === k)).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)) : []
})
const pick = (l: string) => { store.labelFilter = selected.value?.toLowerCase() === l.toLowerCase() ? null : l }
</script>

<template>
  <div class="labels" data-testid="label-panel">
    <p v-if="!all.length" class="muted">ラベルの付いたページはありません。ページの編集中にタイトルの下の「＋ ラベル」から付けられます。</p>
    <div class="chips">
      <button
        v-for="l in all"
        :key="l.label"
        class="chip"
        :class="{ on: selected?.toLowerCase() === l.label.toLowerCase() }"
        data-testid="label-chip"
        @click="pick(l.label)"
      >🏷 {{ l.label }} <span class="n">{{ l.count }}</span></button>
    </div>
    <template v-if="selected">
      <p class="head">「{{ selected }}」のページ（{{ pages.length }}）</p>
      <router-link v-for="p in pages" :key="p.id" :to="`/w/${props.wid}/p/${p.id}`" class="item">
        {{ p.icon ?? '📄' }} {{ p.title || '無題' }}
      </router-link>
    </template>
  </div>
</template>

<style scoped>
.labels { padding: 0 .5rem; }
.chips { display: flex; flex-wrap: wrap; gap: .3rem; }
.chip { font-size: .78rem; border: 1px solid var(--line); border-radius: 999px; padding: 1px 8px; color: var(--muted); background: none; }
.chip.on { border-color: var(--accent); color: var(--fg); background: var(--hover); }
.n { font-size: .7rem; opacity: .7; }
.head { font-size: .8rem; font-weight: 600; color: var(--muted); margin: .8rem 0 .2rem; }
.item { display: block; padding: .3rem .5rem; border-radius: 6px; color: inherit; text-decoration: none; font-size: .9rem; }
.item:hover, .item.router-link-active { background: var(--hover); }
.muted { font-size: .8rem; }
</style>
