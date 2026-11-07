<script setup lang="ts">
import ModalDialog from '../ui/ModalDialog.vue'
import HelpLink from '../ui/HelpLink.vue'
import { onMounted, ref } from 'vue'
import { formatDate, webhookApi, WEBHOOK_EVENTS, type Webhook, type WebhookEvent, type WebhookFormat } from '../api'
import { confirmDialog } from '../ui/dialog'

/** ワークスペースの出来事を外部（Slack・Teams・任意のURL）へ送る設定 */
const props = defineProps<{ wid: string; name: string }>()
const emit = defineEmits<{ close: [] }>()
const hooks = ref<Webhook[] | null>(null)
const error = ref('')
const busy = ref(false)
const secretShown = ref<{ id: string; secret: string } | null>(null)
const testResult = ref<Record<string, string>>({})

async function load() {
  try { hooks.value = await webhookApi.list(props.wid) } catch (e) { error.value = (e as Error).message }
}
onMounted(load)

const FORMAT: Record<WebhookFormat, string> = { slack: 'Slack', teams: 'Microsoft Teams', generic: 'JSON（任意のURL）' }
const HINT: Record<WebhookFormat, string> = {
  slack: 'Slack の「Incoming Webhook」のURL（https://hooks.slack.com/services/…）を貼り付けてください。',
  teams: 'Teams のチャネルの「ワークフロー」で「Webhook 要求を受信したらチャネルに投稿する」を作り、そのURLを貼り付けてください。',
  generic: 'JSON を POST します。X-Skiwi-Signature ヘッダー（sha256=…）で、作成時に表示される秘密の値による署名を確認できます。',
}

// --- 追加 ---
const adding = ref(false)
const form = ref({ name: '', url: '', format: 'slack' as WebhookFormat, events: ['page.created', 'page.updated', 'comment.created', 'page.published'] as WebhookEvent[] })
const message = (e: unknown) => {
  const m = (e as Error).message
  return m.startsWith('invalid url') ? 'URLが正しくありません（https:// で始まるURL）' : m === 'invalid events' ? '送る出来事を1つ以上選んでください' : m
}
async function create() {
  busy.value = true
  error.value = ''
  try {
    const h = await webhookApi.create(props.wid, { ...form.value, name: form.value.name.trim() || FORMAT[form.value.format], url: form.value.url.trim() })
    if (h.secret && h.format === 'generic') secretShown.value = { id: h.id, secret: h.secret }
    adding.value = false
    form.value = { name: '', url: '', format: 'slack', events: ['page.created', 'page.updated', 'comment.created', 'page.published'] }
    await load()
  } catch (e) { error.value = message(e) } finally { busy.value = false }
}
async function update(h: Webhook, b: Parameters<typeof webhookApi.update>[1]) {
  error.value = ''
  try { await webhookApi.update(h.id, b); await load() } catch (e) { error.value = message(e) }
}
function toggleEvent(h: Webhook, ev: WebhookEvent, on: boolean) {
  const events = on ? [...h.events, ev] : h.events.filter((x) => x !== ev)
  if (!events.length) { error.value = '送る出来事を1つ以上選んでください'; return }
  void update(h, { events })
}
async function remove(h: Webhook) {
  if (!(await confirmDialog(`「${h.name}」を削除しますか？`, { okLabel: '削除', danger: true }))) return
  await webhookApi.remove(h.id)
  await load()
}
async function test(h: Webhook) {
  testResult.value = { ...testResult.value, [h.id]: '送信中…' }
  try {
    const r = await webhookApi.test(h.id)
    testResult.value = { ...testResult.value, [h.id]: r.ok ? `✅ 送信しました（HTTP ${r.status}）` : `⚠ 送れませんでした（${r.error ?? r.status}）` }
    await load()
  } catch (e) { testResult.value = { ...testResult.value, [h.id]: `⚠ ${(e as Error).message}` } }
}
</script>

<template>
  <ModalDialog label="外部への通知" testid="webhooks" :max-height="82" @close="emit('close')">
    <template #title>📣 外部への通知（Webhook）— {{ name }} <HelpLink to="automation#外部への通知slackteamswebhook" /></template>
    <p class="note">このワークスペースのページの作成・更新・コメント・公開などを、Slack・Teams・任意のURLへ送ります。個別に権限を絞ったページ（共有設定で「親の権限を引き継がない」にしたページとその下）の出来事は送りません。</p>
    <p v-if="error" class="error" data-testid="webhook-error">{{ error }}</p>

    <div v-if="secretShown" class="secret" data-testid="webhook-secret">
      署名の確認に使う秘密の値（この画面でしか表示しません）:
      <code>{{ secretShown.secret }}</code>
      <button class="link" @click="secretShown = null">閉じる</button>
    </div>

    <p v-if="hooks === null" class="muted">読み込み中…</p>
    <ul v-else class="list">
      <li v-if="!hooks.length && !adding" class="muted">まだ設定がありません。</li>
      <li v-for="h in hooks" :key="h.id" class="hook" :class="{ off: !h.enabled }" data-testid="webhook-item">
        <div class="h-head">
          <b>{{ h.name }}</b><span class="fmt">{{ FORMAT[h.format] }}</span>
          <label class="en"><input type="checkbox" :checked="h.enabled" data-testid="webhook-enabled" @change="update(h, { enabled: ($event.target as HTMLInputElement).checked })" /> 有効</label>
        </div>
        <div class="url">{{ h.url }}</div>
        <div class="events">
          <label v-for="ev in WEBHOOK_EVENTS" :key="ev.value"><input type="checkbox" :checked="h.events.includes(ev.value)" @change="toggleEvent(h, ev.value, ($event.target as HTMLInputElement).checked)" /> {{ ev.label }}</label>
        </div>
        <div class="h-foot">
          <span class="muted small">
            <template v-if="h.lastAt">最後の送信: {{ formatDate(h.lastAt) }} {{ h.lastError ? `⚠ ${h.lastError}` : `✅ HTTP ${h.lastStatus}` }}</template>
            <template v-else>まだ送信していません</template>
          </span>
          <span class="acts">
            <button data-testid="webhook-test" @click="test(h)">テスト送信</button>
            <button class="danger" data-testid="webhook-delete" @click="remove(h)">削除</button>
          </span>
        </div>
        <div v-if="testResult[h.id]" class="small" data-testid="webhook-test-result">{{ testResult[h.id] }}</div>
      </li>
    </ul>

    <form v-if="adding" class="form" data-testid="webhook-form" @submit.prevent="create">
      <div class="formats">
        <label v-for="(l, f) in FORMAT" :key="f"><input v-model="form.format" type="radio" :value="f" :data-testid="`webhook-format-${f}`" /> {{ l }}</label>
      </div>
      <p class="hint">{{ HINT[form.format] }}</p>
      <input v-model="form.url" type="url" placeholder="https://…" required data-testid="webhook-url" />
      <input v-model="form.name" placeholder="名前（例: #skiwi-更新 チャンネル）" maxlength="100" data-testid="webhook-name" />
      <div class="events">
        <label v-for="ev in WEBHOOK_EVENTS" :key="ev.value"><input v-model="form.events" type="checkbox" :value="ev.value" /> {{ ev.label }}</label>
      </div>
      <div class="f-acts">
        <button type="button" @click="adding = false">やめる</button>
        <button type="submit" class="primary" :disabled="busy || !form.url.trim()" data-testid="webhook-save">追加</button>
      </div>
    </form>
    <button v-else class="add" data-testid="webhook-add" @click="adding = true">＋ 送り先を追加</button>
  </ModalDialog>
</template>

<style scoped>
.note, .hint { font-size: .78rem; color: var(--muted); line-height: 1.55; }
.list { list-style: none; padding: 0; margin: .4rem 0; }
.hook { border: 1px solid var(--line); border-radius: 8px; padding: .5rem .7rem; margin-bottom: .5rem; font-size: .85rem; }
.hook.off { opacity: .6; }
.h-head { display: flex; align-items: center; gap: .5rem; }
.fmt { font-size: .7rem; color: var(--muted); border: 1px solid var(--line); border-radius: 4px; padding: 0 5px; }
.en { margin-left: auto; font-size: .8rem; display: flex; align-items: center; gap: .25rem; }
.url { font-size: .75rem; color: var(--muted); word-break: break-all; margin: .15rem 0 .3rem; }
.events { display: flex; flex-wrap: wrap; gap: .2rem .8rem; font-size: .8rem; }
.events label, .formats label { display: flex; align-items: center; gap: .25rem; }
.h-foot { display: flex; justify-content: space-between; align-items: center; gap: .5rem; margin-top: .4rem; flex-wrap: wrap; }
.acts { display: flex; gap: .3rem; }
.acts button { font-size: .78rem; padding: 1px 8px; }
.danger:hover { color: var(--danger); border-color: var(--danger); }
.form { display: flex; flex-direction: column; gap: .45rem; border: 1px solid var(--line); border-radius: 8px; padding: .6rem .7rem; }
.formats { display: flex; gap: 1rem; font-size: .85rem; flex-wrap: wrap; }
.form input:not([type=checkbox]):not([type=radio]) { font: inherit; padding: .35rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; }
.f-acts { display: flex; justify-content: flex-end; gap: .4rem; }
.add { margin-top: .3rem; }
.secret { font-size: .8rem; background: var(--side); border: 1px solid var(--line); border-radius: 8px; padding: .5rem .7rem; margin: .4rem 0; }
.secret code { display: block; word-break: break-all; margin: .3rem 0; }
.link { border: none; background: none; color: var(--accent); padding: 0; font-size: .78rem; }
.error { font-size: .85rem; }
</style>
