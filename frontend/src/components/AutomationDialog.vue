<script setup lang="ts">
import ModalDialog from '../ui/ModalDialog.vue'
import HelpLink from '../ui/HelpLink.vue'
import { computed, onMounted, ref, watch } from 'vue'
import {
  api4, automationApi, dbApi, formatDate, STATUS_LABEL, webhookApi,
  type AutoAction, type AutoActionType, type AutoTrigger, type AutoTriggerType, type Automation, type DbProp, type PageStatus, type Webhook,
} from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import { confirmDialog } from '../ui/dialog'
import { openPagePicker } from '../ui/pagePicker'
import { useDebouncedSearch } from '../ui/composables'

/** ワークスペースの自動化ルール（きっかけ → 動作） */
const props = defineProps<{ wid: string; name: string }>()
const emit = defineEmits<{ close: [] }>()
const store = useWorkspaceStore()
const rules = ref<Automation[] | null>(null)
const hooks = ref<Webhook[]>([])
const error = ref('')
async function load() {
  try {
    rules.value = await automationApi.list(props.wid)
    hooks.value = await webhookApi.list(props.wid).catch(() => [])
  } catch (e) { error.value = (e as Error).message }
}
onMounted(load)

const TRIGGER: Record<AutoTriggerType, string> = {
  status_changed: 'ページの状態が変わったとき', label_added: 'ラベルが付いたとき', page_created: 'ページが作られたとき',
  value_changed: 'データベースの値が変わったとき', date_reached: 'データベースの日付になったとき',
}
const ACTION: Record<AutoActionType, string> = { notify: '通知する（画面内とメール）', webhook: '外部へ送る（Webhook）', set_status: '状態を変える', add_label: 'ラベルを付ける' }
const STATUSES = Object.keys(STATUS_LABEL) as PageStatus[]
const databases = computed(() => store.pages.filter((p) => p.database))
const pageTitle = (id?: string | null) => store.byId.get(id ?? '')?.title || '（見られないページ）'

// --- 入力欄 ---
const editingId = ref<string | null>(null)
const open = ref(false)
const fName = ref('')
const t = ref<AutoTrigger>({ type: 'status_changed' })
const a = ref<AutoAction>({ type: 'notify', to: 'owner' })
const userNames = ref<Record<string, string>>({})
const dbProps = ref<DbProp[]>([])
watch(() => t.value.database, async (id) => {
  dbProps.value = []
  if (id) try { dbProps.value = (await dbApi.get(id)).props } catch { /* 見られない */ }
}, { immediate: true })
const propOf = (id?: string | null) => dbProps.value.find((p) => p.id === id)
const valueProps = computed(() => dbProps.value.filter((p) => ['select', 'checkbox', 'text', 'number', 'multi_select', 'person', 'date'].includes(p.type)))
const dateProps = computed(() => dbProps.value.filter((p) => p.type === 'date'))
const condProps = computed(() => dbProps.value.filter((p) => p.type === 'select' || p.type === 'checkbox'))
const personProps = computed(() => dbProps.value.filter((p) => p.type === 'person'))

function startNew() {
  editingId.value = null
  fName.value = ''
  t.value = { type: 'status_changed', to: 'review' }
  a.value = { type: 'notify', to: 'owner' }
  open.value = true
}
function startEdit(r: Automation) {
  editingId.value = r.id
  fName.value = r.name
  t.value = { ...r.trigger }
  a.value = { ...r.action, users: [...(r.action.users ?? [])] }
  open.value = true
}
async function pickUnder() {
  const p = await openPagePicker()
  if (p) t.value = { ...t.value, under: p.id }
}
const uq = ref('')
const ufound = ref<{ id: string; name: string }[]>([])
const userSearch = useDebouncedSearch(250)
function onUserSearch() {
  userSearch.run(async (latest) => {
    const r = uq.value.trim() ? (await api4.directory(uq.value.trim()).catch(() => [])).filter((d) => d.principalType === 'user').map((d) => ({ id: d.id, name: d.name })) : []
    if (latest()) ufound.value = r
  })
}
function addUser(u: { id: string; name: string }) {
  userNames.value[u.id] = u.name
  a.value.users = [...new Set([...(a.value.users ?? []), u.id])]
  uq.value = ''
  ufound.value = []
}

/** 送る内容（きっかけの種類で使わない項目は消す） */
function clean(): { trigger: AutoTrigger; action: AutoAction } {
  const x = t.value
  const trig: AutoTrigger = { type: x.type, ...(x.under ? { under: x.under } : {}) }
  if (x.type === 'status_changed' && x.to) trig.to = x.to
  if (x.type === 'label_added') trig.label = (x.label ?? '').trim()
  if (x.type === 'value_changed' || x.type === 'date_reached') { trig.database = x.database; trig.prop = x.prop }
  if (x.type === 'value_changed' && x.value) trig.value = x.value
  if (x.type === 'date_reached') {
    trig.offset = Number(x.offset ?? 0)
    if (x.unlessProp && x.unlessValue) { trig.unlessProp = x.unlessProp; trig.unlessValue = x.unlessValue }
  }
  const y = a.value
  const act: AutoAction = { type: y.type, ...(y.message?.trim() ? { message: y.message.trim() } : {}) }
  if (y.type === 'notify') { act.to = y.to; if (y.to === 'users') act.users = y.users; if (y.to === 'prop') act.prop = y.prop }
  if (y.type === 'webhook') act.webhook = y.webhook
  if (y.type === 'set_status') act.status = y.status ?? null
  if (y.type === 'add_label') act.label = (y.label ?? '').trim()
  return { trigger: trig, action: act }
}
const busy = ref(false)
async function save() {
  error.value = ''
  busy.value = true
  const body = { name: fName.value.trim() || `${TRIGGER[t.value.type]} → ${ACTION[a.value.type]}`, ...clean() }
  try {
    if (editingId.value) await automationApi.update(editingId.value, body)
    else await automationApi.create(props.wid, body)
    open.value = false
    await load()
  } catch (e) { error.value = message(e) } finally { busy.value = false }
}
const message = (e: unknown) => {
  const m = (e as Error).message
  return m === 'notify needs recipients' ? '通知する相手を選んでください'
    : m.startsWith('trigger needs a database') ? 'データベースと列を選んでください'
    : m === 'trigger needs a label' ? 'ラベルを入力してください'
    : m === 'invalid action' ? '動作の設定が足りません' : m
}
async function toggle(r: Automation) { await automationApi.update(r.id, { enabled: !r.enabled }); await load() }
async function remove(r: Automation) {
  if (await confirmDialog(`ルール「${r.name}」を削除しますか？`, { okLabel: '削除', danger: true })) { await automationApi.remove(r.id); await load() }
}

/** ルールの説明 */
function describe(r: Automation): string {
  const x = r.trigger, y = r.action
  let when = TRIGGER[x.type]
  if (x.type === 'status_changed' && x.to) when = `状態が「${STATUS_LABEL[x.to]}」になったとき`
  if (x.type === 'label_added') when = `ラベル「${x.label}」が付いたとき`
  if (x.type === 'value_changed') when = `「${pageTitle(x.database)}」の値が変わったとき`
  if (x.type === 'date_reached') when = `「${pageTitle(x.database)}」の日付の${x.offset ? (x.offset < 0 ? `${-x.offset}日前` : `${x.offset}日後`) : '当日'}`
  if (x.under) when += `（「${pageTitle(x.under)}」の下）`
  const to = y.to === 'owner' ? '所有者' : y.to === 'watchers' ? 'ウォッチしている人' : y.to === 'prop' ? '項目のユーザー' : `${y.users?.length ?? 0}人`
  const what = y.type === 'notify' ? `${to}に通知` : y.type === 'webhook' ? `「${hooks.value.find((h) => h.id === y.webhook)?.name ?? 'Webhook'}」へ送る`
    : y.type === 'set_status' ? `状態を「${y.status ? STATUS_LABEL[y.status] : 'なし'}」に` : `ラベル「${y.label}」を付ける`
  return `${when} → ${what}`
}
</script>

<template>
  <ModalDialog label="自動化ルール" testid="automations" :width="680" :max-height="86" :top="6" @close="emit('close')">
    <template #title>⚙ 自動化ルール — {{ name }} <HelpLink to="automation#自動化ルール" /></template>
    <p class="note">「きっかけ」が起きたら「動作」を自動で行います。例: 状態がレビュー中になったら所有者に通知／期限の前日に担当者に通知／ラベル「社外」が付いたら Slack に送る。動作で変えたページで、ほかのルールが続けて動くことはありません。</p>
    <p v-if="error" class="error" data-testid="automation-error">{{ error }}</p>

    <ul v-if="rules" class="list">
      <li v-if="!rules.length && !open" class="muted">まだルールがありません。</li>
      <li v-for="r in rules" :key="r.id" class="rule" :class="{ off: !r.enabled }" data-testid="automation-item">
        <div class="r-head">
          <label class="en"><input type="checkbox" :checked="r.enabled" data-testid="automation-enabled" @change="toggle(r)" /></label>
          <b>{{ r.name }}</b>
          <span class="spacer" />
          <button class="link" @click="startEdit(r)">編集</button>
          <button class="link danger" @click="remove(r)">削除</button>
        </div>
        <div class="desc">{{ describe(r) }}</div>
        <div class="muted small">
          <template v-if="r.lastRunAt">最後に動いた日時: {{ formatDate(r.lastRunAt) }}（{{ r.runCount }}回）</template><template v-else>まだ動いていません</template>
          <span v-if="r.lastError" class="error"> ⚠ {{ r.lastError }}</span>
        </div>
      </li>
    </ul>

    <form v-if="open" class="form" data-testid="automation-form" @submit.prevent="save">
      <label class="col"><span>名前</span><input v-model="fName" maxlength="100" placeholder="例: レビュー依頼を所有者に知らせる" data-testid="automation-name" /></label>

      <fieldset>
        <legend>きっかけ</legend>
        <select v-model="t.type" data-testid="automation-trigger">
          <option v-for="(l, k) in TRIGGER" :key="k" :value="k">{{ l }}</option>
        </select>
        <div v-if="t.type === 'status_changed'" class="row">
          状態が
          <select v-model="t.to" data-testid="automation-to-status">
            <option :value="null">（どれでも）</option>
            <option v-for="s in STATUSES" :key="s" :value="s">{{ STATUS_LABEL[s] }}</option>
          </select>
          になったとき
        </div>
        <div v-if="t.type === 'label_added'" class="row">ラベル <input v-model="t.label" placeholder="例: 社外" data-testid="automation-label" /> が付いたとき</div>
        <template v-if="t.type === 'value_changed' || t.type === 'date_reached'">
          <div class="row">
            データベース
            <select v-model="t.database" data-testid="automation-db">
              <option :value="undefined" disabled>（選ぶ）</option>
              <option v-for="d in databases" :key="d.id" :value="d.id">📊 {{ d.title || '無題' }}</option>
            </select>
          </div>
          <div v-if="t.type === 'value_changed'" class="row">
            列
            <select v-model="t.prop" data-testid="automation-prop">
              <option v-for="p in valueProps" :key="p.id" :value="p.id">{{ p.name }}</option>
            </select>
            が
            <select v-if="propOf(t.prop)?.type === 'select' || propOf(t.prop)?.type === 'multi_select'" v-model="t.value" data-testid="automation-value">
              <option :value="null">（どれでも）</option>
              <option v-for="o in propOf(t.prop)?.options ?? []" :key="o.id" :value="o.id">{{ o.name }}</option>
            </select>
            <select v-else-if="propOf(t.prop)?.type === 'checkbox'" v-model="t.value">
              <option :value="null">（どちらでも）</option><option value="true">✓ あり</option><option value="false">なし</option>
            </select>
            <input v-else v-model="t.value" placeholder="（空欄: どの値でも）" />
            になったとき
          </div>
          <template v-else>
            <div class="row">
              日付の列
              <select v-model="t.prop" data-testid="automation-date-prop">
                <option v-for="p in dateProps" :key="p.id" :value="p.id">{{ p.name }}</option>
              </select>
              の
              <select v-model.number="t.offset" data-testid="automation-offset">
                <option v-for="n in [-7, -3, -2, -1]" :key="n" :value="n">{{ -n }}日前</option>
                <option :value="0">当日</option>
                <option v-for="n in [1, 2, 3, 7]" :key="n" :value="n">{{ n }}日後</option>
              </select>
              （1時間ごとに確認）
            </div>
            <div class="row">
              ただし
              <select v-model="t.unlessProp">
                <option :value="null">（条件なし）</option>
                <option v-for="p in condProps" :key="p.id" :value="p.id">{{ p.name }}</option>
              </select>
              <template v-if="t.unlessProp">
                が
                <select v-if="propOf(t.unlessProp)?.type === 'select'" v-model="t.unlessValue">
                  <option v-for="o in propOf(t.unlessProp)?.options ?? []" :key="o.id" :value="o.id">{{ o.name }}</option>
                </select>
                <select v-else v-model="t.unlessValue"><option value="true">✓ あり</option><option value="false">なし</option></select>
                の項目は除く
              </template>
            </div>
          </template>
        </template>
        <div class="row">
          対象: {{ t.under ? `「${pageTitle(t.under)}」とその下のページ` : 'ワークスペースのすべてのページ' }}
          <button type="button" class="link" @click="pickUnder">ページを選ぶ</button>
          <button v-if="t.under" type="button" class="link" @click="t.under = null">すべてに戻す</button>
        </div>
      </fieldset>

      <fieldset>
        <legend>動作</legend>
        <select v-model="a.type" data-testid="automation-action">
          <option v-for="(l, k) in ACTION" :key="k" :value="k">{{ l }}</option>
        </select>
        <template v-if="a.type === 'notify'">
          <div class="row">
            通知する相手
            <select v-model="a.to" data-testid="automation-notify-to">
              <option value="owner">ページの所有者</option>
              <option value="watchers">ページをウォッチしている人</option>
              <option value="users">指定した人</option>
              <option value="prop" :disabled="!personProps.length">データベースのユーザーの列（例: 担当者）</option>
            </select>
            <select v-if="a.to === 'prop'" v-model="a.prop" data-testid="automation-notify-prop">
              <option v-for="p in personProps" :key="p.id" :value="p.id">{{ p.name }}</option>
            </select>
          </div>
          <div v-if="a.to === 'users'" class="col">
            <ul class="chips">
              <li v-for="u in a.users ?? []" :key="u">👤 {{ userNames[u] ?? u.slice(0, 8) }}<button type="button" class="x" @click="a.users = (a.users ?? []).filter((x) => x !== u)">×</button></li>
            </ul>
            <input v-model="uq" placeholder="名前・メールで検索して追加" data-testid="automation-user-search" @input="onUserSearch" />
            <ul v-if="ufound.length" class="found"><li v-for="u in ufound" :key="u.id"><button type="button" data-testid="automation-user-add" @click="addUser(u)">👤 {{ u.name }}</button></li></ul>
          </div>
        </template>
        <div v-if="a.type === 'webhook'" class="row">
          送り先
          <select v-model="a.webhook" data-testid="automation-webhook">
            <option v-for="h in hooks" :key="h.id" :value="h.id">{{ h.name }}</option>
          </select>
          <span v-if="!hooks.length" class="muted">（先に「外部への通知」で送り先を追加してください）</span>
        </div>
        <div v-if="a.type === 'set_status'" class="row">
          状態を
          <select v-model="a.status"><option :value="null">（なし）</option><option v-for="s in STATUSES" :key="s" :value="s">{{ STATUS_LABEL[s] }}</option></select>
          にする
        </div>
        <div v-if="a.type === 'add_label'" class="row">ラベル <input v-model="a.label" placeholder="例: 要確認" /> を付ける</div>
        <label v-if="a.type === 'notify' || a.type === 'webhook'" class="col">
          <span>文面（任意。{title}=ページ名、{actor}=操作した人、{date}=日付、{status}=状態）</span>
          <input v-model="a.message" maxlength="1000" placeholder="例: 「{title}」のレビューをお願いします" data-testid="automation-message" />
        </label>
      </fieldset>
      <div class="f-acts">
        <button type="button" @click="open = false">やめる</button>
        <button type="submit" class="primary" :disabled="busy" data-testid="automation-save">{{ editingId ? '保存' : '追加' }}</button>
      </div>
    </form>
    <button v-else class="add" data-testid="automation-add" @click="startNew">＋ ルールを追加</button>
  </ModalDialog>
</template>

<style scoped>
:deep(.x) { padding: 0 4px; }
.note { line-height: 1.55; }
.list { list-style: none; padding: 0; margin: .4rem 0; }
.rule { border: 1px solid var(--line); border-radius: 8px; padding: .45rem .7rem; margin-bottom: .45rem; font-size: .85rem; }
.rule.off { opacity: .55; }
.r-head { display: flex; align-items: center; gap: .4rem; }
.desc { margin: .15rem 0; }
.spacer { flex: 1; }
.link { border: none; background: none; color: var(--accent); padding: 0 .2rem; font-size: .8rem; }
.danger:hover { color: var(--danger); }
.form { display: flex; flex-direction: column; gap: .6rem; border: 1px solid var(--line); border-radius: 8px; padding: .6rem .7rem; font-size: .85rem; }
fieldset { border: 1px solid var(--line); border-radius: 8px; padding: .4rem .6rem .6rem; display: flex; flex-direction: column; gap: .4rem; }
legend { font-size: .78rem; color: var(--muted); padding: 0 .3rem; }
.row { display: flex; align-items: center; gap: .35rem; flex-wrap: wrap; }
.col { display: flex; flex-direction: column; gap: .25rem; }
.col span { font-size: .78rem; color: var(--muted); }
select, input { font: inherit; font-size: .84rem; padding: .2rem .4rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; }
.chips { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: .3rem; }
.chips li { background: var(--hover); border-radius: 12px; padding: 1px 4px 1px 8px; font-size: .8rem; }
.found { list-style: none; margin: 0; padding: 0; border: 1px solid var(--line); border-radius: 6px; }
.found button { width: 100%; text-align: left; border: none; padding: .25rem .5rem; }
.f-acts { display: flex; justify-content: flex-end; gap: .4rem; }
.add { margin-top: .3rem; }
.error { font-size: .82rem; }
</style>
