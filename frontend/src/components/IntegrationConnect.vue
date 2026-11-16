<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { skawau, skea, type Integration } from '../ui/integration'
import { alertError, confirmDialog } from '../ui/dialog'

/**
 * 外部サービス（skea・skawau）との連携。管理者が設定したものだけ出す。
 *  - part="notice": 連携の結果のお知らせ（ヘッダに常に置く。表示のときに状態を読み込む）
 *  - part="links": 「skea と連携」「skawau と連携」（名前のメニューの中）
 */
const props = defineProps<{ part: 'notice' | 'links' }>()
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
  if (props.part !== 'notice') return
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
  <template v-if="part === 'links'">
    <template v-for="{ svc, t } in services" :key="svc.name">
      <template v-if="svc.status.value?.enabled">
        <a v-if="!svc.status.value.connected" role="menuitem" :href="svc.api.connectUrl(route.fullPath)" :data-testid="`${svc.name}-connect`"
           :title="`${svc.name} で許可すると、${t.shows}`">🔗 {{ svc.name }} と連携</a>
        <a v-else href="#" role="menuitem" :data-testid="`${svc.name}-disconnect`" :title="`${svc.name} と連携済み。クリックで解除`"
           @click.prevent="disconnect(svc, t)">🔗 {{ svc.name }} 連携中（解除）</a>
      </template>
    </template>
  </template>
  <p v-else-if="notice" class="integration-notice" :class="notice.kind" role="status" data-testid="integration-notice">
    {{ notice.text }} <button type="button" aria-label="閉じる" @click="notice = null">×</button>
  </p>
</template>

<style scoped>
.integration-notice { position: absolute; top: calc(100% + 6px); right: .8rem; z-index: 66; max-width: min(420px, calc(100vw - 1.6rem)); margin: 0; padding: .3rem .5rem; border-radius: 6px; font-size: .78rem; line-height: 1.5; box-shadow: 0 6px 18px rgba(0,0,0,.15); background-clip: padding-box; }
.integration-notice.ok { background: var(--c-green); border: 1px solid var(--c-green-line); }
.integration-notice.ng { background: var(--c-red); border: 1px solid var(--c-red-line); }
.integration-notice button { border: none; padding: 0 .2rem; float: right; }
</style>
