<script setup lang="ts">
import { progress } from './progress'
</script>

<template>
  <div v-if="progress" class="toast" role="status" aria-live="polite" data-testid="progress">
    <div class="label">{{ progress.label }}… {{ progress.done }} / {{ progress.total }}</div>
    <div class="bar"><div :style="{ width: `${progress.total ? (progress.done / progress.total) * 100 : 0}%` }" /></div>
  </div>
</template>

<style scoped>
.toast { position: fixed; right: 16px; bottom: 16px; z-index: 950; width: 280px; background: var(--bg); border: 1px solid var(--line); border-radius: 10px; padding: .6rem .8rem; box-shadow: 0 10px 30px rgba(0,0,0,.25); }
.label { font-size: .85rem; margin-bottom: .4rem; }
.bar { height: 6px; background: var(--hover); border-radius: 3px; overflow: hidden; }
.bar div { height: 100%; background: var(--accent); transition: width .2s; }
</style>
