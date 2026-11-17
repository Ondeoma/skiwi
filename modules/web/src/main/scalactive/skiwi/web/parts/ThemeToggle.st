<script lang="scala">
import skiwi.web.Theme

// 画面の明暗の切り替え（OS に合わせる → ライト → ダーク）
def label = Theme.labels(Theme.mode.v)
</script>

<template>
  <button class="theme-toggle" :title='s"表示: ${label}（クリックで切り替え）"' :aria-label='s"表示の明暗: ${label}"'
          data-testid="theme-toggle" :data-mode="Theme.mode.v" @click="Theme.cycle()">{{ Theme.icons(Theme.mode.v) }}</button>
</template>

<style scoped>
.theme-toggle { font-size: .8rem; padding: 1px 6px; line-height: 1.4; }
</style>
