<script setup lang="ts">
import { ref, watch } from 'vue'
import type { EmojiItem } from './emojiData'

/** 「:」で開く絵文字の候補 */
const props = defineProps<{ items: EmojiItem[]; command: (item: EmojiItem) => void }>()
const selected = ref(0)
watch(() => props.items, () => (selected.value = 0))
function onKeyDown(e: KeyboardEvent): boolean {
  if (!props.items.length) return false
  if (e.key === 'ArrowDown' || e.key === 'ArrowRight') { selected.value = (selected.value + 1) % props.items.length; return true }
  if (e.key === 'ArrowUp' || e.key === 'ArrowLeft') { selected.value = (selected.value - 1 + props.items.length) % props.items.length; return true }
  if (e.key === 'Enter' || e.key === 'Tab') { props.command(props.items[selected.value]); return true }
  return false
}
defineExpose({ onKeyDown })
</script>

<template>
  <div v-show="items.length" class="emoji-menu" data-testid="emoji-menu">
    <button v-for="(it, i) in items" :key="it.emoji" :class="{ on: i === selected }" :title="it.name" data-testid="emoji-item" @mousedown.prevent="command(it)" @mouseenter="selected = i">
      <span class="e">{{ it.emoji }}</span><span class="n">{{ it.name }}</span>
    </button>
  </div>
</template>

<style scoped>
.emoji-menu { position: absolute; z-index: 60; width: 15rem; max-height: 18rem; overflow: auto; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 4px; box-shadow: 0 8px 24px rgba(0,0,0,.18); display: flex; flex-direction: column; }
button { display: flex; align-items: center; gap: .5rem; border: none; border-radius: 6px; padding: .25rem .5rem; text-align: left; background: none; }
button.on { background: var(--hover); }
.e { font-size: 1.2rem; width: 1.6rem; text-align: center; }
.n { font-size: .85rem; color: var(--muted); }
</style>
