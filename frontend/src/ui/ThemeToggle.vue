<script setup lang="ts">
import { cycleTheme, themeIcons, themeLabels, themeMode } from './theme'
</script>

<template>
  <button
    class="theme-toggle"
    :title="`表示: ${themeLabels[themeMode]}（クリックで切り替え）`"
    :aria-label="`表示の明暗: ${themeLabels[themeMode]}`"
    data-testid="theme-toggle"
    :data-mode="themeMode"
    @click="cycleTheme"
  >{{ themeIcons[themeMode] }}</button>
</template>

<style scoped>
.theme-toggle { font-size: .8rem; padding: 1px 6px; line-height: 1.4; }
</style>
