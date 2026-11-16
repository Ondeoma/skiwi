<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { avatarColor } from './avatar'

/** ユーザーのアイコン。画像がない・読めないときは、名前の1文字目を色付きの丸で出す。 */
const props = withDefaults(defineProps<{ name?: string | null; url?: string | null; size?: number; color?: string }>(), { size: 24 })
const failed = ref(false)
watch(() => props.url, () => (failed.value = false))
const initial = computed(() => (props.name ?? '?').trim().slice(0, 1) || '?')
const bg = computed(() => props.color ?? avatarColor(props.name ?? ''))
</script>

<template>
  <span class="user-avatar" :style="{ width: `${size}px`, height: `${size}px`, fontSize: `${Math.round(size * 0.46)}px`, background: url && !failed ? 'transparent' : bg }" aria-hidden="true">
    <img v-if="url && !failed" :src="url" alt="" @error="failed = true" />
    <template v-else>{{ initial }}</template>
  </span>
</template>

<style scoped>
.user-avatar { flex: none; display: inline-grid; place-items: center; border-radius: 50%; overflow: hidden; color: #fff; font-weight: 700; line-height: 1; vertical-align: middle; }
.user-avatar img { width: 100%; height: 100%; object-fit: cover; display: block; }
</style>
