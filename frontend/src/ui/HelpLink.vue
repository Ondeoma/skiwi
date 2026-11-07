<script setup lang="ts">
/** マニュアルの該当箇所を新しいタブで開くリンク（to: 章のID、または「章のID#節」） */
const props = defineProps<{ to: string; label?: string }>()
const href = () => {
  const [ch, sec] = props.to.split('#')
  return `/help/${ch}${sec ? `#${encodeURIComponent(sec)}` : ''}`
}
</script>

<template>
  <a class="help-link" :href="href()" target="_blank" rel="noopener" title="この機能の使い方（マニュアル）を開く" data-testid="help-link">？ {{ label ?? '使い方' }}</a>
</template>

<style scoped>
.help-link { font-size: .75rem; color: var(--muted); text-decoration: none; border: 1px solid var(--line); border-radius: 999px; padding: 0 8px; white-space: nowrap; font-weight: 400; }
.help-link:hover { color: var(--accent); border-color: var(--accent); }
</style>
