<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { currentBlockPicker } from './blockPicker'
import { CATEGORIES, filterItems, type SlashItem } from '../editor/commands'

/** ブロックの一覧（分類ごとの表）。検索・矢印キー・Enter で選んで挿入する。 */
const q = ref('')
const selected = ref(0)
const input = ref<HTMLInputElement | null>(null)
const body = ref<HTMLElement | null>(null)

watch(currentBlockPicker, async (r) => {
  if (!r) return
  q.value = r.query
  selected.value = 0
  await nextTick()
  input.value?.focus()
  input.value?.select()
})

const filtered = computed(() => filterItems(currentBlockPicker.value?.items ?? [], q.value))
/** 分類ごとの表示と、矢印キーで移動する順序（表示順）。 */
const groups = computed(() =>
  CATEGORIES.map((c) => ({ category: c, items: filtered.value.filter((i) => i.category === c) })).filter((g) => g.items.length))
const flat = computed(() => groups.value.flatMap((g) => g.items))
watch(q, () => (selected.value = 0))

function pick(it: SlashItem | undefined) { if (it) currentBlockPicker.value?.resolve(it) }
function close() { currentBlockPicker.value?.resolve(null) }
async function move(d: number) {
  const n = flat.value.length
  if (!n) return
  selected.value = (selected.value + d + n) % n
  await nextTick()
  body.value?.querySelector('tr.active')?.scrollIntoView({ block: 'nearest' })
}
function onKey(e: KeyboardEvent) {
  if (e.isComposing) return
  if (e.key === 'ArrowDown') { e.preventDefault(); move(1) }
  else if (e.key === 'ArrowUp') { e.preventDefault(); move(-1) }
  else if (e.key === 'Enter') { e.preventDefault(); pick(flat.value[selected.value]) }
  else if (e.key === 'Escape') { e.preventDefault(); close() }
}
const indexOf = (it: SlashItem) => flat.value.indexOf(it)
/** 「/」のあとに入力して呼び出せる語（日本語・英語を数個） */
const hints = (it: SlashItem) => it.keywords.slice(0, 4).join('、')
</script>

<template>
  <div v-if="currentBlockPicker" class="backdrop" @mousedown.self="close" @keydown="onKey">
    <div class="modal" role="dialog" aria-modal="true" aria-label="ブロックを挿入" data-testid="block-picker">
      <header>
        <h2>ブロックを挿入</h2>
        <!-- v-model は日本語の変換中に更新されないため、変換中も即座に絞り込めるよう input イベントで受ける -->
        <input ref="input" :value="q" type="search" @input="q = ($event.target as HTMLInputElement).value" placeholder="名前・説明・キーワードで絞り込み" data-testid="block-picker-search" />
        <button class="close" title="閉じる（Esc）" @click="close">✕</button>
      </header>
      <div ref="body" class="body">
        <table v-if="flat.length">
          <thead>
            <tr><th class="c-icon"></th><th>ブロック</th><th>説明</th><th class="c-kw">「/」のあとに入力して呼び出せる語</th></tr>
          </thead>
          <tbody v-for="g in groups" :key="g.category">
            <tr class="cat"><th colspan="4">{{ g.category }}</th></tr>
            <tr
              v-for="it in g.items"
              :key="it.title"
              :class="{ active: indexOf(it) === selected }"
              @mouseenter="selected = indexOf(it)"
              @click="pick(it)"
            >
              <td class="c-icon"><span class="icon">{{ it.icon }}</span></td>
              <td class="name">{{ it.title }}</td>
              <td class="desc">{{ it.description }}</td>
              <td class="c-kw">{{ hints(it) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="empty">「{{ q }}」に一致するブロックはありません。</p>
      </div>
      <footer>↑↓ で選択 ・ Enter で挿入 ・ Esc で閉じる ・ クリックでも挿入できます</footer>
    </div>
  </div>
</template>

<style scoped>
.backdrop { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 900; padding: 16px; }
.modal { background: var(--bg); border: 1px solid var(--line); border-radius: 12px; width: min(920px, 100%); height: min(680px, 100%); display: flex; flex-direction: column; box-shadow: 0 16px 48px rgba(0,0,0,.3); overflow: hidden; }
header { display: flex; align-items: center; gap: .8rem; padding: .8rem 1rem; border-bottom: 1px solid var(--line); }
h2 { font-size: 1.05rem; margin: 0; white-space: nowrap; }
header input { flex: 1; min-width: 0; padding: .4rem .7rem; border: 1px solid var(--line); border-radius: 8px; background: var(--bg); }
.close { border: none; font-size: 1rem; color: var(--muted); }
.body { flex: 1; overflow: auto; }
table { width: 100%; border-collapse: collapse; font-size: .9rem; }
thead th { position: sticky; top: 0; z-index: 1; background: var(--side); text-align: left; font-weight: 600; font-size: .78rem; color: var(--muted); padding: .45rem .6rem; border-bottom: 1px solid var(--line); }
tr.cat th { text-align: left; font-size: .8rem; color: var(--accent); padding: .7rem .6rem .25rem; border-bottom: 1px solid var(--line); }
tbody tr:not(.cat) { cursor: pointer; }
tbody tr.active { background: var(--hover); }
td { padding: .35rem .6rem; border-bottom: 1px solid var(--line); vertical-align: middle; }
.c-icon { width: 48px; }
.icon { width: 30px; height: 30px; display: grid; place-items: center; border: 1px solid var(--line); border-radius: 6px; font-size: 13px; background: var(--bg); }
.name { font-weight: 600; white-space: nowrap; }
.desc { color: var(--muted); }
.c-kw { color: var(--muted); font-size: .8rem; width: 30%; }
.empty { padding: 2rem; text-align: center; color: var(--muted); }
footer { padding: .5rem 1rem; border-top: 1px solid var(--line); font-size: .78rem; color: var(--muted); }
@media (max-width: 640px) { .c-kw { display: none; } }
</style>
