<script lang="ts">
export interface MentionUser { id: string; name: string; detail: string | null }
</script>

<script setup lang="ts">
import { ref, watch } from 'vue'

const props = defineProps<{ items: MentionUser[]; query: string; command: (u: MentionUser) => void }>()
const selected = ref(0)
watch(() => props.items, () => (selected.value = 0))

function onKeyDown(e: KeyboardEvent): boolean {
  if (e.isComposing || !props.items.length) return false
  if (e.key === 'ArrowDown') { selected.value = (selected.value + 1) % props.items.length; return true }
  if (e.key === 'ArrowUp') { selected.value = (selected.value - 1 + props.items.length) % props.items.length; return true }
  if (e.key === 'Enter' || e.key === 'Tab') { props.command(props.items[selected.value]); return true }
  return false
}
defineExpose({ onKeyDown })
</script>

<template>
  <div class="mention-menu" role="listbox" data-testid="mention-menu">
    <div v-if="!query" class="hint">名前・メールを入力してメンション</div>
    <div v-else-if="!items.length" class="hint">該当するユーザーがいません</div>
    <button v-for="(u, i) in items" :key="u.id" class="item" :class="{ active: i === selected }" role="option"
      @mousedown.prevent="command(u)" @mouseenter="selected = i">
      👤 {{ u.name }} <small>{{ u.detail ?? '' }}</small>
    </button>
  </div>
</template>

<style>
.mention-menu { position: absolute; z-index: 60; min-width: 220px; max-width: 320px; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 4px; box-shadow: 0 8px 24px rgba(0,0,0,.18); }
.mention-menu .item { display: block; width: 100%; text-align: left; border: none; padding: .35rem .55rem; border-radius: 6px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mention-menu .item small { color: var(--muted); }
.mention-menu .item.active { background: var(--hover); }
.mention-menu .hint { font-size: .8rem; color: var(--muted); padding: .35rem .55rem; }
</style>
