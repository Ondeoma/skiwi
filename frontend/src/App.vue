<script setup lang="ts">
import AppDialog from './ui/AppDialog.vue'
import { defineAsyncComponent } from 'vue'
import { currentBlockPicker } from './ui/blockPicker'
const BlockPicker = defineAsyncComponent(() => import('./ui/BlockPicker.vue'))
import PagePicker from './ui/PagePicker.vue'
import ProgressToast from './ui/ProgressToast.vue'
import CommandPalette from './ui/CommandPalette.vue'
import ShortcutsDialog from './ui/ShortcutsDialog.vue'
import { useRouter } from 'vue-router'

// ログイン後に戻る場所（フォームなどでログインを求めたとき）
const router = useRouter()
try {
  const back = sessionStorage.getItem('skiwi.returnTo')
  if (back && back.startsWith('/') && !back.startsWith('//')) {
    sessionStorage.removeItem('skiwi.returnTo')
    void router.isReady().then(() => { if (router.currentRoute.value.path === '/') void router.replace(back) })
  }
} catch { /* 保存できない環境 */ }
</script>

<template>
  <router-view />
  <BlockPicker v-if="currentBlockPicker" />
  <PagePicker />
  <ProgressToast />
  <CommandPalette />
  <ShortcutsDialog />
  <AppDialog />
</template>
