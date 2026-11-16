<script setup lang="ts">
import { ref, watch } from 'vue'
import { formatDate, shareApi, shareUrl, type ShareInfo, type ShareOpts } from '../api'
import { confirmDialog, promptDialog } from '../ui/dialog'
import { localDateTime } from '../ui/date'
import { useCopy } from '../ui/composables'
import GuestHtmlDialog from './GuestHtmlDialog.vue'
import ReleaseHistory from './ReleaseHistory.vue'

/** 共有画面の「ゲストに公開」。設定は保存ボタンを待たずにその場で反映する。失敗は error で親に知らせる（'' で消す） */
const props = defineProps<{ pageId: string; title: string }>()
const pub = defineModel<ShareInfo>('pub', { required: true })
const emit = defineEmits<{ error: [message: string] }>()

const busy = ref(false)
const guestHtmlOpen = ref(false)
const { copied, copy: copyLink } = useCopy()
const link = () => (pub.value.share ? shareUrl(pub.value.share.slug ?? pub.value.share.token) : '')
/** 親ページの公開リンクでの、このページのURL */
const inheritedLink = () => {
  const i = pub.value.inherited
  return i ? shareUrl(i.slug ?? i.token, pub.value.pageSlug || props.pageId) : ''
}

const ERRORS: Record<string, string> = {
  slug_taken: 'このURL名はほかで使われています',
  'invalid slug': 'URL名は半角の英小文字・数字・ハイフン（-）で入力してください（公開リンクは3〜64文字）',
  'startsAt must be before expiresAt': '公開の開始日時は、終了日時より前にしてください',
  'description is too long': '説明文は300文字までです',
}
const message = (e: unknown) => ERRORS[(e as Error).message] ?? (e as Error).message
/** 変更を送る（その間はボタン等を止め、失敗はメッセージにして知らせる） */
async function run(fn: () => Promise<void>) {
  busy.value = true
  emit('error', '')
  try { await fn() } catch (e) { emit('error', message(e)) } finally { busy.value = false }
}

async function setPublic(on: boolean) {
  if (!on && !(await confirmDialog('公開を停止しますか？\nこれまでのリンクは使えなくなります（再度公開すると別のリンクになります）。', { okLabel: '公開を停止', danger: true }))) return
  await run(async () => {
    if (on) pub.value = { ...pub.value, share: await shareApi.enable(props.pageId, { includeChildren: pub.value.share?.includeChildren ?? true }) }
    else { await shareApi.disable(props.pageId); pub.value = { ...pub.value, share: null } }
  })
}
async function updatePublic(opts: ShareOpts) {
  if (!pub.value.share) return
  await run(async () => { pub.value = { ...pub.value, share: await shareApi.enable(props.pageId, opts) } })
}
const setIncludeChildren = (v: boolean) => updatePublic({ includeChildren: v })

// 公開する内容: live（編集中の内容をそのまま）/ pinned（「公開」を押した時点の内容）
const releaseNote = ref('')
const releaseRef = ref<InstanceType<typeof ReleaseHistory> | null>(null)
async function publishNow() {
  if (!pub.value.share) return
  await run(async () => {
    pub.value = { ...pub.value, share: await shareApi.publish(props.pageId, releaseNote.value.trim() || undefined) }
    releaseNote.value = ''
    await releaseRef.value?.load()
  })
}

// 公開期間（この端末の時刻で入力する）
const toLocal = (iso: string | null | undefined) => (iso ? localDateTime(new Date(iso)) : '')
const fromLocal = (v: string) => (v ? new Date(v).toISOString() : null)
const setStarts = (v: string) => updatePublic({ startsAt: fromLocal(v) })
const setExpiry = (v: string) => updatePublic({ expiresAt: fromLocal(v) })

async function setPassword() {
  const pw = await promptDialog('閲覧用のパスワード（4文字以上）', '', { okLabel: '設定', placeholder: 'ゲストに別の手段で伝えてください' })
  if (pw === null) return
  if (pw.length < 4) { emit('error', 'パスワードは4文字以上にしてください'); return }
  await updatePublic({ password: pw })
}
const clearPassword = () => updatePublic({ password: null })

// URL名・検索エンジン・説明文
const slugDraft = ref('')
const pageSlugDraft = ref('')
const descDraft = ref('')
watch(() => pub.value.share?.slug, (v) => (slugDraft.value = v ?? ''), { immediate: true })
watch(() => pub.value.share?.description, (v) => (descDraft.value = v ?? ''), { immediate: true })
watch(() => pub.value.pageSlug, (v) => (pageSlugDraft.value = v ?? ''), { immediate: true })
const saveSlug = () => updatePublic({ slug: slugDraft.value.trim().toLowerCase() || null })
async function saveDescription() {
  if ((descDraft.value.trim() || null) !== (pub.value.share?.description ?? null)) await updatePublic({ description: descDraft.value.trim() || null })
}
const savePageSlug = () => run(async () => {
  pub.value = { ...pub.value, pageSlug: (await shareApi.setPageSlug(props.pageId, pageSlugDraft.value.trim().toLowerCase() || null)).slug }
})
</script>

<template>
  <section class="public" data-testid="public-share">
    <h3>🌐 ゲストに公開（閲覧のみ）</h3>
    <label class="row">
      <input type="checkbox" :checked="!!pub.share" :disabled="busy" data-testid="public-toggle" @change="setPublic(($event.target as HTMLInputElement).checked)" />
      リンクを知っている人は、ログインしなくても閲覧できる
    </label>
    <template v-if="pub.share">
      <div class="link-row">
        <input :value="link()" readonly data-testid="public-url" @focus="($event.target as HTMLInputElement).select()" />
        <button @click="copyLink(link())">{{ copied ? 'コピーしました' : 'コピー' }}</button>
        <a :href="link()" target="_blank" rel="noopener">開く</a>
        <a :href="`/print/s/${pub.share.slug ?? pub.share.token}`" target="_blank" rel="noopener" title="ゲストに見えている内容（公開版を固定していればその版）を、目次付きのPDFにする" data-testid="public-pdf">PDF</a>
      </div>
      <p v-if="pub.share.expired" class="error" data-testid="public-state">⛔ 公開期間が終わりました。ゲストは閲覧できません。</p>
      <p v-else-if="pub.share.notStarted" class="warn" data-testid="public-state">⏳ 公開前です。{{ formatDate(pub.share.startsAt!) }} からゲストが閲覧できます。</p>
      <label class="row">
        <input type="checkbox" :checked="pub.share.includeChildren" :disabled="busy" data-testid="public-children" @change="setIncludeChildren(($event.target as HTMLInputElement).checked)" />
        子ページも公開する
      </label>

      <h4>公開する内容</h4>
      <label class="row">
        <input type="radio" name="pub-mode" :checked="pub.share.mode === 'live'" :disabled="busy" data-testid="public-mode-live" @change="updatePublic({ mode: 'live' })" />
        編集中の内容をそのまま公開する（常に最新）
      </label>
      <label class="row">
        <input type="radio" name="pub-mode" :checked="pub.share.mode === 'pinned'" :disabled="busy" data-testid="public-mode-pinned" @change="updatePublic({ mode: 'pinned' })" />
        「公開」を押した時点の内容を公開する（編集中の変更はゲストに見えない）
      </label>
      <div v-if="pub.share.mode === 'pinned'" class="pinned" data-testid="public-pinned">
        <div>
          公開中の版: {{ pub.share.publishedAt ? formatDate(pub.share.publishedAt) : '—' }}<span v-if="pub.share.publishedBy" class="muted">（{{ pub.share.publishedBy }}）</span>
          <div v-if="pub.share.pendingChanges" class="warn small" data-testid="public-pending">⚠ 公開版に反映していない変更があります</div>
          <div v-else class="muted small">公開版は現在の内容と同じです</div>
        </div>
        <button class="primary" :disabled="busy" data-testid="public-publish" @click="publishNow">現在の内容を公開</button>
      </div>
      <template v-if="pub.share.mode === 'pinned'">
        <label class="col">
          <span>変更点のメモ（任意。「現在の内容を公開」のときに履歴へ残します）</span>
          <textarea v-model="releaseNote" rows="2" maxlength="2000" placeholder="例: インストール手順に Windows 版を追加しました" :disabled="busy" data-testid="public-release-note" />
        </label>
        <label class="row">
          <input type="checkbox" :checked="pub.share.showReleases" :disabled="busy" data-testid="public-show-releases" @change="updatePublic({ showReleases: ($event.target as HTMLInputElement).checked })" />
          ゲストの画面に「更新履歴」（公開日時と変更点のメモ）を出す
        </label>
        <ReleaseHistory ref="releaseRef" :page-id="pageId" />
      </template>

      <h4>公開期間</h4>
      <div class="row">
        <span class="lbl-w">開始:</span>
        <input type="datetime-local" :value="toLocal(pub.share.startsAt)" :disabled="busy" data-testid="public-starts" @change="setStarts(($event.target as HTMLInputElement).value)" />
        <span v-if="!pub.share.startsAt" class="muted">すぐに公開</span>
        <button v-else class="link" :disabled="busy" @click="setStarts('')">解除</button>
      </div>
      <div class="row">
        <span class="lbl-w">終了:</span>
        <input type="datetime-local" :value="toLocal(pub.share.expiresAt)" :disabled="busy" data-testid="public-expiry" @change="setExpiry(($event.target as HTMLInputElement).value)" />
        <span v-if="!pub.share.expiresAt" class="muted">なし（無期限）</span>
        <button v-else class="link" :disabled="busy" @click="setExpiry('')">期限なしにする</button>
      </div>

      <h4>URL・検索エンジン</h4>
      <div class="row slug-row">
        <span class="lbl-w">URL名:</span>
        <span class="prefix">/s/</span>
        <input v-model="slugDraft" placeholder="例: manual（英小文字・数字・-）" maxlength="64" :disabled="busy" data-testid="public-slug" @keydown.enter.prevent="saveSlug" />
        <button :disabled="busy || slugDraft.trim().toLowerCase() === (pub.share.slug ?? '')" data-testid="public-slug-save" @click="saveSlug">保存</button>
      </div>
      <p class="note">URL名を付けると、わかりやすいURLで開けます（トークンのURLもそのまま使えます）。子ページのURL名は、それぞれのページの共有画面で付けられます。</p>
      <label class="row">
        <input type="checkbox" :checked="pub.share.indexable" :disabled="busy || pub.share.hasPassword" data-testid="public-indexable" @change="updatePublic({ indexable: ($event.target as HTMLInputElement).checked })" />
        検索エンジンに載せる<span v-if="pub.share.hasPassword" class="muted">（パスワード付きのリンクは載りません）</span>
      </label>
      <label class="col">
        <span>共有したときの説明文（SNS・チャットのリンクの表示。空欄なら本文の冒頭）</span>
        <textarea v-model="descDraft" rows="2" maxlength="300" :disabled="busy" data-testid="public-description" @blur="saveDescription" />
      </label>

      <h4>表示</h4>
      <div class="row">
        サイドメニュー（ページ一覧）:
        <select :value="pub.share.sidebar" :disabled="busy" data-testid="public-sidebar" @change="updatePublic({ sidebar: ($event.target as HTMLSelectElement).value as 'auto' | 'show' | 'hide' })">
          <option value="auto">自動（公開するページが2つ以上なら表示）</option>
          <option value="show">常に表示</option>
          <option value="hide">表示しない</option>
        </select>
      </div>
      <label class="row">
        <input type="checkbox" :checked="pub.share.hideHeader" :disabled="busy" data-testid="public-hide-header" @change="updatePublic({ hideHeader: ($event.target as HTMLInputElement).checked })" />
        ゲストの画面に上部のヘッダー（「公開ページ」「ログイン」）を出さない
      </label>
      <div class="row">
        <button class="link" data-testid="guest-html-open" @click="guestHtmlOpen = true">🧩 本文の上下に入れるHTML（ゲスト向けの表示）…</button>
      </div>
      <div class="row">
        パスワード:
        <template v-if="pub.share.hasPassword">
          <span>🔑 設定済み</span>
          <button class="link" :disabled="busy" @click="setPassword">変更</button>
          <button class="link" :disabled="busy" data-testid="public-password-clear" @click="clearPassword">解除</button>
        </template>
        <template v-else>
          <span class="muted">なし</span>
          <button class="link" :disabled="busy" data-testid="public-password" @click="setPassword">設定する</button>
        </template>
      </div>
      <p class="note">
        権限を引き継がない子ページ（個別に閲覧を制限したページ）とその下は公開されません。
        公開の設定は保存ボタンを押さなくてもすぐに反映されます。
      </p>
    </template>
    <template v-else-if="pub.inherited">
      <p class="note">
        <template v-if="pub.inherited.token">このページは親ページ「{{ pub.inherited.title || '無題' }}」の公開リンクに含まれ、すでにゲストが閲覧できます。</template>
        <template v-else>このページは、あなたが閲覧できない上位のページの公開リンクに含まれ、すでにゲストが閲覧できます。</template>
        <template v-if="pub.inherited.mode === 'pinned'">公開版を固定しているため、変更は親ページの共有画面で「現在の内容を公開」を押すと反映されます。</template>
      </p>
      <div v-if="pub.inherited.token" class="link-row">
        <input :value="inheritedLink()" readonly data-testid="inherited-url" @focus="($event.target as HTMLInputElement).select()" />
        <button @click="copyLink(inheritedLink())">{{ copied ? 'コピーしました' : 'コピー' }}</button>
      </div>
      <div class="row slug-row">
        <span class="lbl-w">このページのURL名:</span>
        <input v-model="pageSlugDraft" placeholder="例: install（英小文字・数字・-）" maxlength="64" :disabled="busy" data-testid="page-slug" @keydown.enter.prevent="savePageSlug" />
        <button :disabled="busy || pageSlugDraft.trim().toLowerCase() === (pub.pageSlug ?? '')" data-testid="page-slug-save" @click="savePageSlug">保存</button>
      </div>
    </template>
  </section>
  <GuestHtmlDialog v-if="guestHtmlOpen" kind="page" :id="pageId" :title="title" @close="guestHtmlOpen = false" />
</template>

<style scoped>
.row { display: flex; gap: .5rem; align-items: center; margin: .4rem 0; }
.note { font-size: .8rem; margin: .2rem 0 .6rem; }
select { font: inherit; padding: .1rem .3rem; background: var(--bg); color: inherit; border: 1px solid var(--line); border-radius: 4px; }
.public { margin-top: 1rem; padding-top: .8rem; border-top: 1px solid var(--line); }
.public h3 { font-size: .95rem; margin: 0 0 .3rem; }
.link-row { display: flex; gap: .4rem; align-items: center; margin: .4rem 0; }
.link-row input { flex: 1; min-width: 0; padding: .3rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--side); color: inherit; font-size: .82rem; }
button.link { border: none; background: none; color: var(--accent); padding: 0 .2rem; font-size: .85rem; }
.link-row a { font-size: .85rem; color: var(--accent); }
.public h4 { font-size: .8rem; color: var(--muted); margin: .9rem 0 .2rem; font-weight: 600; }
.lbl-w { min-width: 2.6em; flex: none; }
.row input[type="datetime-local"], .slug-row input { font: inherit; font-size: .85rem; padding: .15rem .35rem; background: var(--bg); color: inherit; border: 1px solid var(--line); border-radius: 4px; }
.slug-row input { flex: 1; min-width: 0; }
.prefix { color: var(--muted); font-size: .85rem; margin-right: -.3rem; }
.col { display: flex; flex-direction: column; gap: .25rem; font-size: .8rem; color: var(--muted); margin: .4rem 0; }
.col textarea { font: inherit; font-size: .85rem; color: var(--fg); padding: .3rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); resize: vertical; }
.pinned { display: flex; align-items: center; justify-content: space-between; gap: .6rem; padding: .5rem .7rem; margin: .3rem 0; border: 1px solid var(--line); border-radius: 8px; background: var(--side); font-size: .85rem; }
.pinned .primary { flex: none; }
.warn { color: #b45309; }
.primary:disabled { opacity: .6; }
.muted { font-size: .85em; }
.small { font-size: .78rem; }
</style>
