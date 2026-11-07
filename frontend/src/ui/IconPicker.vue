<script setup lang="ts">
import { computed, ref } from 'vue'
import { EMOJIS, firstGrapheme } from './palette'
import { useDismiss } from './composables'

/** 絵文字アイコンの選択。親要素（position: relative）の下に開く。外側クリック・Escで閉じる。 */
const props = withDefaults(defineProps<{ allowNone?: boolean; noneLabel?: string; at?: { x: number; y: number } | null }>(), { allowNone: true, noneLabel: 'アイコンなし', at: null })
/** at を指定すると画面座標に固定表示（スクロール領域の外へはみ出せるように）。 */
const fixedStyle = computed(() => props.at
  ? { position: 'fixed' as const, left: Math.max(8, Math.min(props.at.x, window.innerWidth - 344)) + 'px', top: Math.max(8, Math.min(props.at.y, window.innerHeight - 260)) + 'px' }
  : undefined)
const emit = defineEmits<{ select: [icon: string | null]; close: [] }>()
const root = ref<HTMLElement | null>(null)
const custom = ref('')

const close = () => emit('close')
// 開いたクリック自体で閉じないよう、次のタスクで登録する
useDismiss((t) => !root.value || root.value.contains(t), close, { escape: close, defer: true })
function applyCustom() {
  const g = firstGrapheme(custom.value)
  if (g) emit('select', g)
}
</script>

<template>
  <div ref="root" class="picker" :style="fixedStyle" data-testid="icon-picker" @mousedown.stop>
    <slot name="head" />
    <div class="grid">
      <button v-for="e in EMOJIS" :key="e" class="emoji" :title="e" @click="emit('select', e)">{{ e }}</button>
    </div>
    <div class="foot">
      <input v-model="custom" placeholder="他の絵文字を入力" maxlength="16" @keydown.enter.prevent="!$event.isComposing && applyCustom()" />
      <button :disabled="!custom.trim()" @click="applyCustom">決定</button>
      <button v-if="props.allowNone" class="none" @click="emit('select', null)">{{ props.noneLabel }}</button>
    </div>
  </div>
</template>

<style scoped>
.picker { position: absolute; z-index: 50; top: calc(100% + 4px); left: 0; background: var(--bg); border: 1px solid var(--line); border-radius: 10px; padding: .5rem; box-shadow: 0 10px 30px rgba(0,0,0,.2); width: 336px; }
.grid { display: grid; grid-template-columns: repeat(12, 1fr); gap: 1px; }
.emoji { border: none; padding: 2px 0; font-size: 1.1rem; line-height: 1.5; border-radius: 4px; }
.foot { display: flex; gap: .3rem; margin-top: .5rem; align-items: center; }
.foot input { flex: 1; min-width: 0; padding: .2rem .4rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); font-size: .85rem; }
.foot button { font-size: .8rem; white-space: nowrap; }
.none { color: var(--muted); }
</style>
