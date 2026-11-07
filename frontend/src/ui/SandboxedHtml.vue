<script setup lang="ts">
import { computed, ref } from 'vue'
import { useEventListener } from './composables'

/**
 * 任意のHTMLを、サンドボックス化した iframe の中で表示する（ゲスト向けの本文の上下）。
 *  - allow-same-origin を付けないので、中のスクリプトは別オリジン扱いになり、skiwi の Cookie・API・画面には触れられない
 *  - 高さは中身に合わせる（中から postMessage で知らせる）
 *  - リンクはページ全体で開く（<base target="_top">。利用者の操作によるときだけ許可）
 */
const props = defineProps<{ html: string; label?: string }>()
const frame = ref<HTMLIFrameElement | null>(null)
const height = ref(0)
const key = Math.random().toString(36).slice(2)

const srcdoc = computed(() => `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<base target="_top"><meta name="color-scheme" content="light dark">
<style>html,body{margin:0;padding:0;background:transparent;font-family:system-ui,-apple-system,"Hiragino Sans","Noto Sans JP",sans-serif;}img,video{max-width:100%;height:auto}</style>
</head><body>${props.html}
<script>(function(){var k=${JSON.stringify(key)};function s(){parent.postMessage({skiwiFrame:k,h:Math.ceil(document.documentElement.getBoundingClientRect().height)},'*')}
if(window.ResizeObserver){new ResizeObserver(s).observe(document.documentElement)}window.addEventListener('load',s);s();})();<\/script></body></html>`)

function onMessage(e: MessageEvent) {
  if (e.source !== frame.value?.contentWindow) return
  const d = e.data as { skiwiFrame?: string; h?: number }
  if (d?.skiwiFrame === key && typeof d.h === 'number') height.value = Math.min(Math.max(d.h, 0), 5000)
}
useEventListener(window, 'message', onMessage)
</script>

<template>
  <iframe
    ref="frame"
    class="sandboxed-html"
    :srcdoc="srcdoc"
    sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox allow-forms allow-top-navigation-by-user-activation"
    referrerpolicy="no-referrer"
    :title="label ?? '埋め込みHTML'"
    :style="{ height: `${height}px` }"
    data-testid="guest-html"
  />
</template>

<style scoped>
.sandboxed-html { display: block; width: 100%; border: none; background: transparent; }
</style>
