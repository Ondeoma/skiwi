<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import './bridge'

/**
 * Scalactive(Scala.js)で書いた画面を描画する（移行中の橋渡し）.
 * view: skiwi.web.Main.mountView の画面名. その他のルートのパラメーターは params として渡す.
 */
const props = defineProps<{ view: string; params?: Record<string, string> }>()
const el = ref<HTMLElement | null>(null)
let unmount: (() => void) | null = null
let alive = true

async function render() {
  const { mountView } = await import('scalajs:main.js')
  if (!alive || !el.value) return
  unmount?.()
  unmount = mountView(props.view, el.value, { ...(props.params ?? {}) })
}

onMounted(render)
watch(() => [props.view, JSON.stringify(props.params ?? {})], render)
onBeforeUnmount(() => {
  alive = false
  unmount?.()
  unmount = null
})
</script>

<template>
  <div ref="el" class="scalactive-view" />
</template>
