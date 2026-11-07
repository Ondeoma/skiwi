<script setup lang="ts">
import { ref, watch } from 'vue'
import type { SlashItem } from './commands'

const props = defineProps<{ items: SlashItem[]; command: (item: SlashItem) => void }>()
const selected = ref(0)
watch(() => props.items, () => (selected.value = 0))

function onKeyDown(e: KeyboardEvent): boolean {
  if (e.isComposing) return false // IME変換中は奪わない
  if (e.key === 'ArrowDown') { selected.value = (selected.value + 1) % Math.max(props.items.length, 1); scroll(); return true }
  if (e.key === 'ArrowUp') { selected.value = (selected.value - 1 + props.items.length) % Math.max(props.items.length, 1); scroll(); return true }
  if (e.key === 'Enter') { const it = props.items[selected.value]; if (it) props.command(it); return true }
  return false
}
const list = ref<HTMLElement>()
function scroll() { list.value?.children[selected.value]?.scrollIntoView({ block: 'nearest' }) }
defineExpose({ onKeyDown })
</script>

<template>
  <div class="slash-menu" ref="list" role="listbox">
    <div v-if="items.length === 1" class="empty">該当なし</div>
    <button
      v-for="(it, i) in items" :key="it.title" class="item" :class="{ active: i === selected, all: it.special === 'all' }"
      role="option" :aria-selected="i === selected"
      @mousedown.prevent="command(it)" @mouseenter="selected = i"
    >
      <span class="icon">{{ it.icon }}</span>
      <span class="text"><b>{{ it.title }}</b><small>{{ it.description }}</small></span>
    </button>
  </div>
</template>

<style scoped>
.slash-menu { position: absolute; z-index: 50; width: 280px; max-height: 320px; overflow-y: auto; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; box-shadow: 0 8px 24px rgba(0,0,0,.18); padding: 4px; }
.item { display: flex; gap: 10px; align-items: center; width: 100%; text-align: left; border: none; border-radius: 6px; padding: 6px 8px; }
.item.active { background: var(--hover); }
.icon { width: 32px; height: 32px; flex: none; display: grid; place-items: center; border: 1px solid var(--line); border-radius: 6px; font-size: 13px; }
.text { display: flex; flex-direction: column; line-height: 1.3; }
.text small { color: var(--muted); }
.empty { padding: 8px; color: var(--muted); }
/* 「すべてのブロックを表示」は一覧の最下部に固定 */
.item.all { position: sticky; bottom: -4px; background: var(--bg); border-top: 1px solid var(--line); border-radius: 0 0 6px 6px; margin-top: 2px; }
.item.all.active { background: var(--hover); }
.item.all .icon { border-color: var(--accent); color: var(--accent); font-size: 16px; }
</style>
