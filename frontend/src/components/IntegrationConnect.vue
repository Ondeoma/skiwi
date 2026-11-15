<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { skawau, skea, type Integration } from '../ui/integration'
import { alertError, confirmDialog } from '../ui/dialog'

/** サイドバー下部の「skea と連携」「skawau と連携」（管理者が設定したものだけ出す）と、連携の結果のお知らせ。 */
interface Texts { shows: string; lost: string }
const services: { svc: Integration<unknown>; t: Texts }[] = [
  { svc: skea, t: { shows: '本文のタスクIDにタイトルと状態が出ます', lost: '本文のタスクIDには、タイトルと状態が出なくなります（リンクは残ります）' } },
  { svc: skawau, t: { shows: '本文のファイルのリンクにファイル名が出ます', lost: '本文のファイルのリンクは、URL のままの表示に戻ります' } },
]

const route = useRoute()
const notice = ref<{ kind: 'ok' | 'ng'; text: string } | null>(null)
let timer: ReturnType<typeof setTimeout> | undefined

function message(name: string, t: Texts, r: 'connected' | 'denied' | 'error') {
  return r === 'connected' ? { kind: 'ok' as const, text: `${name} と連携しました。${t.shows}。` }
    : r === 'denied' ? { kind: 'ng' as const, text: `${name} との連携を取りやめました。` }
    : { kind: 'ng' as const, text: `${name} と連携できませんでした。時間をおいてやり直すか、管理者に確認してください。` }
}

onMounted(() => {
  for (const { svc, t } of services) {
    const r = svc.takeResult()
    if (r) {
      notice.value = message(svc.name, t, r)
      clearTimeout(timer)
      timer = setTimeout(() => (notice.value = null), 8000)
      void svc.reload()
    } else void svc.load()
  }
})
onBeforeUnmount(() => clearTimeout(timer))

async function disconnect(svc: Integration<unknown>, t: Texts) {
  if (!(await confirmDialog(`${svc.name} との連携を解除しますか？\n${t.lost}。`, { okLabel: '解除' }))) return
  try {
    await svc.api.disconnect()
    await svc.reload()
  } catch (e) { await alertError('解除できませんでした', e) }
}
</script>

<template>
  <template v-for="{ svc, t } in services" :key="svc.name">
    <template v-if="svc.status.value?.enabled">
      <a v-if="!svc.status.value.connected" :href="svc.api.connectUrl(route.fullPath)" :data-testid="`${svc.name}-connect`"
         :title="`${svc.name} で許可すると、${t.shows}`">🔗 {{ svc.name }} と連携</a>
      <a v-else href="#" :data-testid="`${svc.name}-disconnect`" :title="`${svc.name} と連携済み。クリックで解除`"
         @click.prevent="disconnect(svc, t)">🔗 {{ svc.name }} 連携中（解除）</a>
    </template>
  </template>
  <p v-if="notice" class="integration-notice" :class="notice.kind" role="status" data-testid="integration-notice">
    {{ notice.text }} <button type="button" aria-label="閉じる" @click="notice = null">×</button>
  </p>
</template>

<style scoped>
a { color: var(--muted); }
.integration-notice { flex-basis: 100%; margin: .2rem 0 0; padding: .3rem .5rem; border-radius: 6px; font-size: .78rem; line-height: 1.5; }
.integration-notice.ok { background: var(--c-green); border: 1px solid var(--c-green-line); }
.integration-notice.ng { background: var(--c-red); border: 1px solid var(--c-red-line); }
.integration-notice button { border: none; padding: 0 .2rem; float: right; }
</style>
