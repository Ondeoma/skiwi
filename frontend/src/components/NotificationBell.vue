<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api5, copyApi, formatDate, type Notification } from '../api'
import { useDismiss } from '../ui/composables'

/** 画面内の通知（ウォッチ中のページの更新・共有された）。1分ごとに確認する。 */
const router = useRouter()
const unread = ref(0)
const items = ref<Notification[]>([])
const open = ref(false)
const root = ref<HTMLElement | null>(null)

async function refresh() {
  try {
    const r = await api5.notifications()
    unread.value = r.unread
    items.value = r.items
  } catch { /* 一時的な失敗は次回 */ }
}
let timer: ReturnType<typeof setInterval> | undefined
const onCommand = (e: Event) => { if ((e as CustomEvent).detail === 'notifications') toggle() }
const onVisible = () => { if (document.visibilityState === 'visible') refresh() }
useDismiss((t) => !open.value || !root.value || root.value.contains(t), () => { open.value = false })
onMounted(() => {
  refresh()
  // 裏に回っているタブでは取りに行かない（表に戻ったときに visibilitychange で取り直す）
  timer = setInterval(() => { if (document.visibilityState === 'visible') refresh() }, 60_000)
  document.addEventListener('visibilitychange', onVisible)
  window.addEventListener('skiwi:command', onCommand)
})
onBeforeUnmount(() => {
  clearInterval(timer)
  document.removeEventListener('visibilitychange', onVisible)
  window.removeEventListener('skiwi:command', onCommand)
})

function toggle() { open.value = !open.value; if (open.value) refresh() }

function text(n: Notification): string {
  const who = n.actors.length > 2 ? `${n.actors.slice(0, 2).join('、')} ほか${n.actors.length - 2}人` : n.actors.join('、')
  const role = n.role === 'edit' ? '編集' : '閲覧'
  switch (n.kind) {
    case 'page_edited': return `${who} が更新しました`
    case 'mention': return n.excerpt ? `${who} がコメントであなたをメンションしました: ${n.excerpt}` : `${who} が本文であなたをメンションしました`
    case 'comment': return `${who} がコメントしました: ${n.excerpt ?? ''}`
    case 'task_due': return `ToDo の期限（${n.due ?? ''}）が近づいています: ${n.excerpt ?? ''}`
    case 'shared': return `${who} が共有しました（${role}）`
    case 'access_request': return `${who} が${role}権限を申請しました${n.excerpt ? `: ${n.excerpt}` : ''}`
    case 'access_granted': return `${who} が${role}権限の申請を承認しました`
    case 'access_denied': return `${who} が権限の申請を承認しませんでした`
    case 'automation': return `⚙ ${n.excerpt ?? ''}（ルール: ${who}）`
    default: return `${who} がワークスペースを共有しました（${role}）`
  }
}

async function go(n: Notification) {
  open.value = false
  if (!n.read) { unread.value = (await api5.markRead([n.id]).catch(() => ({ unread: unread.value }))).unread; n.read = true }
  if (n.pageId && n.workspaceId) router.push(`/w/${n.workspaceId}/p/${n.pageId}${n.threadId ? `?comment=${n.threadId}` : n.kind === 'access_request' ? '?access=requests' : ''}`)
  else if (n.workspaceId) router.push(`/w/${n.workspaceId}`)
}
// 権限の申請への返事（通知から直接）
const decided = ref<Record<number, string>>({})
async function decide(n: Notification, action: 'approve' | 'deny') {
  try {
    const r = await copyApi.decide(n.requestId!, action)
    decided.value = { ...decided.value, [n.id]: r.status === 'approved' ? `✅ ${r.role === 'edit' ? '編集' : '閲覧'}を許可しました` : r.status === 'denied' ? '却下しました' : '処理済みです' }
    if (!n.read) { unread.value = (await api5.markRead([n.id]).catch(() => ({ unread: unread.value }))).unread; n.read = true }
  } catch (e) { decided.value = { ...decided.value, [n.id]: `失敗しました: ${(e as Error).message}` } }
}
async function readAll() {
  unread.value = (await api5.markRead()).unread
  items.value = items.value.map((n) => ({ ...n, read: true }))
}
</script>

<template>
  <div ref="root" class="bell-wrap">
    <button class="bell" :title="unread ? `未読の通知 ${unread} 件` : '通知'" data-testid="bell" @click="toggle">
      🔔<span class="lbl">通知</span><span v-if="unread" class="count" data-testid="bell-count">{{ unread > 99 ? '99+' : unread }}</span>
    </button>
    <div v-if="open" class="panel" role="dialog" aria-label="通知" data-testid="notifications">
      <header>
        <b>通知</b>
        <button v-if="unread" class="link" @click="readAll">すべて既読にする</button>
      </header>
      <p v-if="!items.length" class="empty">通知はありません。ページの「🔕 ウォッチ」を押すと、更新がここに届きます。</p>
      <ul v-else>
        <li v-for="n in items" :key="n.id" :class="{ unread: !n.read }" data-testid="notification" @click="go(n)">
          <div class="t">
            <template v-if="n.pageId">{{ n.pageIcon ?? '📄' }} {{ n.pageTitle || '無題' }}</template>
            <template v-else-if="n.kind === 'access_denied'">🔒 権限の申請</template>
            <template v-else>🗂 {{ n.workspaceName }}</template>
          </div>
          <div class="d">{{ text(n) }}</div>
          <div v-if="n.kind === 'access_request' && n.requestId" class="req" @click.stop>
            <template v-if="decided[n.id]"><span class="m">{{ decided[n.id] }}</span></template>
            <template v-else>
              <button data-testid="notif-approve" @click="decide(n, 'approve')">{{ n.role === 'edit' ? '編集' : '閲覧' }}を許可</button>
              <button class="deny" data-testid="notif-deny" @click="decide(n, 'deny')">却下</button>
            </template>
          </div>
          <div class="m">{{ formatDate(n.createdAt) }}<template v-if="n.pageId && n.workspaceName"> ・ {{ n.workspaceName }}</template></div>
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.bell-wrap { position: relative; }
.bell { border: none; padding: 0 5px; position: relative; font-size: .95rem; }
.bell .lbl { font-size: .72rem; margin-left: 3px; }
.count { position: absolute; top: -4px; right: -6px; min-width: 16px; height: 16px; padding: 0 3px; border-radius: 8px; background: var(--danger); color: #fff; font-size: 10px; line-height: 16px; text-align: center; }
.panel { position: absolute; left: 0; top: calc(100% + 6px); z-index: 70; width: min(340px, calc(100vw - 24px)); max-height: 70vh; overflow: auto; background: var(--bg); border: 1px solid var(--line); border-radius: 10px; box-shadow: 0 12px 32px rgba(0,0,0,.25); }
header { display: flex; justify-content: space-between; align-items: center; padding: .5rem .7rem; border-bottom: 1px solid var(--line); position: sticky; top: 0; background: var(--bg); }
.link { border: none; color: var(--accent); font-size: .78rem; }
ul { list-style: none; margin: 0; padding: 4px; }
li { padding: .45rem .55rem; border-radius: 6px; cursor: pointer; border-left: 3px solid transparent; }
li:hover { background: var(--hover); }
li.unread { border-left-color: var(--accent); }
li.unread .t { font-weight: 700; }
.t { font-size: .88rem; }
.d { font-size: .8rem; }
.m { font-size: .72rem; color: var(--muted); }
.req { display: flex; gap: .4rem; align-items: center; margin: .3rem 0 .1rem; }
.req button { font-size: .75rem; padding: 1px 8px; }
.req .deny { color: var(--danger); }
.empty { padding: 1rem; font-size: .82rem; color: var(--muted); }
</style>
