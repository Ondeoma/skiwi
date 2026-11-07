<script setup lang="ts">
import HelpLink from '../ui/HelpLink.vue'
import { computed, onMounted, ref } from 'vue'
import { api4, commentApi, copyApi, formatDate, shareApi, type AccessRequest, type AudienceEntry, type AclEntry, type DirectoryEntry, type Role, type ShareInfo } from '../api'
import PublicShareSection from './PublicShareSection.vue'
import { ymd } from '../ui/date'
import { useDebouncedSearch } from '../ui/composables'

/** ページまたはワークスペースの共有設定。ユーザー・グループ単位で閲覧/編集を付与する。 */
const props = defineProps<{ kind: 'page' | 'workspace'; id: string; title: string }>()
const emit = defineEmits<{ close: [] }>()

const loading = ref(true)
const saving = ref(false)
const error = ref('')
const inherit = ref(true)
const defaultRole = ref<Role | ''>('')
const entries = ref<AclEntry[]>([])

const query = ref('')
const found = ref<DirectoryEntry[]>([])
const search = useDebouncedSearch(250)

// --- 閲覧・編集できる人（権限の継承・グループを展開した結果。保存済みの設定で計算する） ---
const audience = ref<AudienceEntry[] | null>(null)
const audienceOpen = ref(false)
const audienceQ = ref('')
async function toggleAudience() {
  audienceOpen.value = !audienceOpen.value
  if (audienceOpen.value && audience.value === null) {
    try { audience.value = await commentApi.audience(props.id) } catch (e) { error.value = (e as Error).message }
  }
}
const audienceShown = computed(() => {
  const q = audienceQ.value.trim().toLowerCase()
  const xs = audience.value ?? []
  return q ? xs.filter((a) => a.name.toLowerCase().includes(q) || (a.email ?? '').toLowerCase().includes(q) || a.reason.toLowerCase().includes(q)) : xs
})
const editors = computed(() => (audience.value ?? []).filter((a) => a.role === 'edit').length)

// --- 閲覧権限の申請（ページのみ） ------------------------------------------------------
const requests = ref<AccessRequest[]>([])
/** 許可する権限の期限（日数。0=なし） */
const reqDays = ref(0)
const daysLater = (d: number) => { const t = new Date(); t.setDate(t.getDate() + d); t.setHours(23, 59, 0, 0); return t.toISOString() }
async function decide(r: AccessRequest, action: 'approve' | 'deny', role?: Role) {
  error.value = ''
  try {
    const until = action === 'approve' && reqDays.value ? daysLater(reqDays.value) : null
    const res = await copyApi.decide(r.id, action, role, until)
    requests.value = requests.value.filter((x) => x.id !== r.id)
    // 承認した人を一覧にも反映する（保存前の編集は残す）
    if (action === 'approve' && res.role) {
      const e = entries.value.find((x) => x.principalType === 'user' && x.principalId === r.userId)
      if (e) { e.role = res.role; if (until && e.expiresAt) e.expiresAt = until }
      else entries.value.push({ principalType: 'user', principalId: r.userId, role: res.role, name: r.name, detail: r.email, expiresAt: until })
      audience.value = null
    }
  } catch (e) { error.value = (e as Error).message }
}

// --- ゲスト向けの公開リンク（ページのみ。設定は PublicShareSection がその場で反映する） ---------
const pub = ref<ShareInfo | null>(null)

onMounted(async () => {
  try {
    if (props.kind === 'page') {
      shareApi.get(props.id).then((s) => (pub.value = s)).catch(() => {})
      copyApi.requests(props.id).then((r) => (requests.value = r)).catch(() => {})
      const a = await api4.pageAcl(props.id)
      inherit.value = a.inherit
      entries.value = a.entries
    } else {
      const a = await api4.workspaceAcl(props.id)
      defaultRole.value = a.defaultRole ?? ''
      entries.value = a.entries
    }
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
})

function onSearch() {
  const q = query.value.trim()
  if (!q) { search.cancel(); found.value = []; return }
  search.run(async (latest) => {
    const r = await api4.directory(q)
    if (latest()) found.value = r.filter((d) => !entries.value.some((e) => e.principalId === d.id))
  })
}

function add(d: DirectoryEntry) {
  entries.value.push({ principalType: d.principalType, principalId: d.id, role: 'view', name: d.name, detail: d.detail })
  query.value = ''
  found.value = []
}

function remove(i: number) { entries.value.splice(i, 1) }
/** 権限の期限: 日付で選び、その日の終わり（この端末の時刻）まで */
const dateOf = (iso: string | null | undefined) => (iso ? ymd(new Date(iso)) : '')
const endOf = (v: string) => (v ? new Date(`${v}T23:59:00`).toISOString() : null)

async function save() {
  saving.value = true
  error.value = ''
  try {
    if (props.kind === 'page') await api4.setPageAcl(props.id, { inherit: inherit.value, entries: entries.value })
    else await api4.setWorkspaceAcl(props.id, { defaultRole: defaultRole.value || null, entries: entries.value })
    emit('close')
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="backdrop" @click.self="emit('close')">
    <div class="dialog" role="dialog" aria-label="共有">
      <h2>共有 ― {{ title || '無題' }} <HelpLink to="sharing" /></h2>
      <p v-if="loading" class="muted">読み込み中…</p>
      <template v-else>
        <section v-if="requests.length" class="requests" data-testid="access-requests">
          <h3>✋ 権限の申請（{{ requests.length }}件）</h3>
          <div v-for="r in requests" :key="r.id" class="req" data-testid="access-request-item">
            <div class="req-head">
              <span>👤 {{ r.name }} <span class="muted">{{ r.email ?? '' }}</span></span>
              <span class="muted">{{ r.role === 'edit' ? '編集' : '閲覧' }}を申請・{{ formatDate(r.createdAt) }}</span>
            </div>
            <p v-if="r.message" class="req-msg">{{ r.message }}</p>
            <div class="req-actions">
              <button data-testid="req-approve-view" @click="decide(r, 'approve', 'view')">閲覧を許可</button>
              <button data-testid="req-approve-edit" @click="decide(r, 'approve', 'edit')">編集を許可</button>
              <button class="link danger" data-testid="req-deny" @click="decide(r, 'deny')">却下</button>
            </div>
          </div>
          <label class="row">
            許可する権限の期限:
            <select v-model.number="reqDays" data-testid="req-expiry">
              <option :value="0">なし（無期限）</option>
              <option :value="7">1週間</option>
              <option :value="30">1か月</option>
              <option :value="90">3か月</option>
            </select>
          </label>
          <p class="note">許可すると、このページの個別の共有設定に追加します（すぐに反映されます）。期限を過ぎると自動で外れます。</p>
        </section>
        <label v-if="kind === 'page'" class="row">
          <input v-model="inherit" type="checkbox" />
          親ページ・ワークスペースの権限を引き継ぐ
        </label>
        <p v-if="kind === 'page' && !inherit" class="note">引き継がない場合、下の一覧の人・グループだけがアクセスできます。</p>
        <label v-if="kind === 'workspace'" class="row">
          全員（ログインできる人）の既定:
          <select v-model="defaultRole">
            <option value="">アクセス不可</option>
            <option value="view">閲覧</option>
            <option value="edit">編集</option>
          </select>
        </label>

        <div class="search">
          <input v-model="query" placeholder="ユーザー名・メール・グループ名で追加…" @input="onSearch" />
          <ul v-if="found.length" class="found">
            <li v-for="d in found" :key="d.id" @click="add(d)">
              {{ d.principalType === 'group' ? '👥' : '👤' }} {{ d.name }} <span class="muted">{{ d.detail ?? '' }}</span>
            </li>
          </ul>
        </div>

        <table class="entries">
          <tr v-for="(e, i) in entries" :key="e.principalId">
            <td>{{ e.principalType === 'group' ? '👥' : '👤' }} {{ e.name ?? e.principalId }} <span class="muted">{{ e.detail ?? '' }}</span></td>
            <td>
              <select v-model="e.role">
                <option value="view">閲覧</option>
                <option value="edit">編集</option>
              </select>
            </td>
            <td class="exp">
              <input
                type="date"
                :value="dateOf(e.expiresAt)"
                :min="dateOf(new Date().toISOString())"
                title="この日の終わりに権限が外れます（空欄で無期限）"
                data-testid="acl-expiry"
                @change="e.expiresAt = endOf(($event.target as HTMLInputElement).value)"
              />
              <span v-if="!e.expiresAt" class="muted small">無期限</span>
            </td>
            <td><button title="外す" @click="remove(i)">✕</button></td>
          </tr>
          <tr v-if="entries.length === 0"><td class="muted" colspan="4">個別の共有設定はありません</td></tr>
        </table>
        <p class="note">新しく共有した相手には、メールで通知します。</p>

        <section v-if="kind === 'page'" class="audience" data-testid="audience">
          <button class="aud-head" @click="toggleAudience">{{ audienceOpen ? '▾' : '▸' }} 👀 このページを閲覧・編集できる人<template v-if="audience">（{{ audience.length }}人・うち編集 {{ editors }}人）</template></button>
          <template v-if="audienceOpen">
            <p class="note">保存済みの設定での結果です（親ページ・ワークスペースからの継承とグループを展開しています）。</p>
            <p v-if="audience === null" class="muted">読み込み中…</p>
            <template v-else>
              <input v-if="audience.length > 8" v-model="audienceQ" class="aud-q" placeholder="名前・メール・理由で絞り込み" />
              <ul class="aud-list">
                <li v-for="a in audienceShown" :key="a.userId" data-testid="audience-item">
                  <span class="aud-name">👤 {{ a.name }} <span class="muted">{{ a.email ?? '' }}</span></span>
                  <span class="aud-role" :class="a.role">{{ a.role === 'edit' ? '編集' : '閲覧' }}</span>
                  <span class="aud-why">{{ a.reason }}</span>
                </li>
              </ul>
              <p v-if="pub?.share" class="note">🌐 このほか、ゲスト向けの公開リンクを知っている人は、ログインせずに閲覧できます。</p>
              <p v-else-if="pub?.inherited" class="note">🌐 このほか、親ページ「{{ pub.inherited.title || '無題' }}」の公開リンクを知っている人は、ログインせずに閲覧できます。</p>
            </template>
          </template>
        </section>

        <PublicShareSection v-if="kind === 'page' && pub?.available" v-model:pub="pub" :page-id="id" :title="title" @error="error = $event" />
      </template>
      <p v-if="error" class="error">{{ error }}</p>
      <div class="actions">
        <button @click="emit('close')">キャンセル</button>
        <button class="primary" :disabled="loading || saving" @click="save">保存</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.backdrop { position: fixed; inset: 0; background: rgba(0,0,0,.35); display: flex; align-items: flex-start; justify-content: center; padding-top: 6vh; z-index: 50; }
.dialog { background: var(--bg); border: 1px solid var(--line); border-radius: 10px; width: min(560px, 92vw); max-height: 85vh; overflow: auto; padding: 1rem 1.2rem; box-shadow: 0 20px 50px rgba(0,0,0,.25); }
h2 { font-size: 1.1rem; margin: 0 0 .8rem; }
.row { display: flex; gap: .5rem; align-items: center; margin: .4rem 0; }
.note { font-size: .8rem; margin: .2rem 0 .6rem; }
.search { position: relative; margin: .6rem 0; }
.search input { width: 100%; padding: .35rem .6rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); }
.found { position: absolute; left: 0; right: 0; top: 100%; margin: 2px 0 0; padding: 4px; list-style: none; background: var(--bg); border: 1px solid var(--line); border-radius: 6px; max-height: 220px; overflow: auto; z-index: 2; box-shadow: 0 8px 20px rgba(0,0,0,.15); }
.found li { padding: .3rem .5rem; border-radius: 4px; cursor: pointer; }
.found li:hover { background: var(--hover); }
.entries { width: 100%; border-collapse: collapse; }
.entries td { padding: .3rem .2rem; border-bottom: 1px solid var(--line); }
.entries td:nth-child(2), .entries td:nth-child(3), .entries td:nth-child(4) { width: 1%; white-space: nowrap; }
.entries .exp input { font: inherit; font-size: .78rem; padding: 0 2px; border: 1px solid var(--line); border-radius: 4px; background: var(--bg); color: inherit; }
select { font: inherit; padding: .1rem .3rem; background: var(--bg); color: inherit; border: 1px solid var(--line); border-radius: 4px; }
.audience { margin-top: .6rem; }
.aud-head { border: none; padding: 0; font-size: .88rem; font-weight: 600; }
.aud-q { width: 100%; padding: .25rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); margin-bottom: .3rem; }
.aud-list { list-style: none; margin: 0; padding: 0; max-height: 220px; overflow: auto; border: 1px solid var(--line); border-radius: 8px; }
.aud-list li { display: grid; grid-template-columns: 1fr auto; gap: 0 .5rem; padding: .3rem .5rem; border-bottom: 1px solid var(--line); font-size: .84rem; }
.aud-list li:last-child { border-bottom: none; }
.aud-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.aud-role { font-size: .72rem; border-radius: 4px; padding: 0 6px; align-self: center; }
.aud-role.edit { background: rgba(37,99,235,.15); color: var(--accent); }
.aud-role.view { background: var(--hover); color: var(--muted); }
.aud-why { grid-column: 1 / -1; font-size: .74rem; color: var(--muted); }
button.link { border: none; background: none; color: var(--accent); padding: 0 .2rem; font-size: .85rem; }
.actions { display: flex; justify-content: flex-end; gap: .5rem; margin-top: 1rem; }
.small { font-size: .78rem; }
.requests { margin-bottom: .8rem; padding: .6rem .8rem; border: 1px solid rgba(217,119,6,.5); border-radius: 8px; background: rgba(217,119,6,.06); }
.requests h3 { font-size: .92rem; margin: 0 0 .4rem; }
.req { padding: .4rem 0; border-top: 1px solid var(--line); }
.req:first-of-type { border-top: none; }
.req-head { display: flex; flex-wrap: wrap; justify-content: space-between; gap: .3rem; font-size: .86rem; }
.req-msg { margin: .25rem 0; font-size: .84rem; white-space: pre-wrap; }
.req-actions { display: flex; gap: .4rem; align-items: center; margin-top: .25rem; }
.req-actions button { font-size: .8rem; padding: 2px 8px; }
button.link.danger { color: var(--danger); }
.primary:disabled { opacity: .6; }
.muted { font-size: .85em; }
</style>
