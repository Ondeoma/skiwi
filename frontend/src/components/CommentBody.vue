<script setup lang="ts">
import { computed } from 'vue'

/** コメント本文の表示。@[名前](ID) をメンションの印にする（HTMLとしては解釈しない）。 */
const props = defineProps<{ body: string }>()
const parts = computed(() => {
  const out: { t: 'text' | 'mention'; v: string }[] = []
  const re = /@\[([^\]]*)\]\([0-9a-fA-F-]{36}\)/g
  let last = 0
  for (const m of props.body.matchAll(re)) {
    if (m.index! > last) out.push({ t: 'text', v: props.body.slice(last, m.index) })
    out.push({ t: 'mention', v: m[1] })
    last = m.index! + m[0].length
  }
  if (last < props.body.length) out.push({ t: 'text', v: props.body.slice(last) })
  return out
})
</script>

<template>
  <div class="cbody"><template v-for="(p, i) in parts" :key="i"><span v-if="p.t === 'mention'" class="cm">@{{ p.v }}</span><template v-else>{{ p.v }}</template></template></div>
</template>

<style scoped>
.cbody { white-space: pre-wrap; word-break: break-word; font-size: .88rem; line-height: 1.55; }
.cm { color: var(--accent); background: rgba(37,99,235,.10); border-radius: 4px; padding: 0 .15em; font-weight: 500; }
</style>
