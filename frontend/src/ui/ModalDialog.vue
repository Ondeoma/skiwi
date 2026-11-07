<script setup lang="ts">
/**
 * 設定画面などのモーダルの枠（背景・見出し・✕）。中身は既定のスロット、見出しは title スロット。
 * 背景をクリック（mousedown）するか ✕ で close。
 * width: 幅の上限（px）/ maxHeight・top: 高さの上限・上の余白（vh）/ dim: 背景の暗さ /
 * smallTitle: 見出しを小さく / side: 右に寄せて、後ろの画面を操作できるままにする（背景を暗くしない）
 */
withDefaults(defineProps<{
  label: string
  testid?: string
  width?: number
  maxHeight?: number
  top?: number
  z?: number
  dim?: number
  side?: boolean
  closeTitle?: string
  smallTitle?: boolean
}>(), { width: 640, maxHeight: 84, top: 8, z: 60, dim: 0.4 })
const emit = defineEmits<{ close: [] }>()
</script>

<template>
  <div
    class="backdrop"
    :class="{ docked: side }"
    :style="{ paddingTop: `${top}vh`, zIndex: z, background: side ? 'transparent' : `rgba(0,0,0,${dim})` }"
    @mousedown.self="emit('close')"
  >
    <div class="dialog" role="dialog" :aria-label="label" :data-testid="testid" :style="{ width: `min(${width}px, 100%)`, maxHeight: `${maxHeight}vh` }">
      <header>
        <h2 :class="{ compact: smallTitle }"><slot name="title" /></h2>
        <button class="x" :title="closeTitle" @click="emit('close')">✕</button>
      </header>
      <slot />
    </div>
  </div>
</template>

<style scoped>
.backdrop { position: fixed; inset: 0; display: flex; align-items: flex-start; justify-content: center; padding: 8vh 16px 16px; }
.backdrop.docked { justify-content: flex-end; pointer-events: none; }
.dialog { pointer-events: auto; background: var(--bg); border: 1px solid var(--line); border-radius: 12px; overflow: auto; padding: .8rem 1rem 1rem; box-shadow: 0 16px 48px rgba(0,0,0,.3); }
header { display: flex; align-items: center; justify-content: space-between; gap: .5rem; }
h2 { font-size: 1.05rem; margin: 0; }
h2.compact { font-size: 1rem; }
.x { border: none; color: var(--muted); }
</style>
