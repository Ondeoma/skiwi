<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useWorkspaceStore } from '../stores/workspace'
import { api, api5, STATUS_LABEL, type PageStatus, searchPages, searchWorkspaces, type SearchResult, type WorkspaceHit } from '../api'
import PageTree from '../components/PageTree.vue'
import ShareDialog from '../components/ShareDialog.vue'
import DuplicateDialog from '../components/DuplicateDialog.vue'
import { duplicateTarget } from '../ui/duplicate'
import TrashDialog from '../components/TrashDialog.vue'
import NotificationBell from '../components/NotificationBell.vue'
import RecentPanel from '../components/RecentPanel.vue'
import LabelPanel from '../components/LabelPanel.vue'
import TasksPanel from '../components/TasksPanel.vue'
import BrokenLinksDialog from '../components/BrokenLinksDialog.vue'
import BulkBar from '../components/BulkBar.vue'
import WebhookDialog from '../components/WebhookDialog.vue'
import AutomationDialog from '../components/AutomationDialog.vue'
import InboundDialog from '../components/InboundDialog.vue'
import BackupDialog from '../components/BackupDialog.vue'
import { clearSelection, selectedPages } from '../ui/selection'
import CssDialog from '../components/CssDialog.vue'
import GuestHtmlDialog from '../components/GuestHtmlDialog.vue'
import { alertError, confirmDialog, promptDialog } from '../ui/dialog'
import { isMac } from '../util/text'
import { useDebouncedSearch, useEventListener, useSideMenu, useStoredRef } from '../ui/composables'
const importPage = (wid: string, parent: string | null) => import('../ui/importer').then((m) => m.importPage(wid, parent))

const props = defineProps<{ wid: string }>()
const store = useWorkspaceStore()
const router = useRouter()
const route = useRoute()

async function load() {
  clearSelection()
  if (!store.me) await store.init()
  await store.select(props.wid)
}
onMounted(load)
watch(() => props.wid, load)

async function newRoot() {
  const p = await store.createPage(null)
  router.push(`/w/${props.wid}/p/${p.id}?edit=1`)
}
// --- 検索（このワークスペース / すべて）。ワークスペース名も検索する ---
const SCOPE_KEY = 'skiwi.searchScope'
const scope = useStoredRef<'ws' | 'all'>(SCOPE_KEY, (raw) => (raw === 'all' ? 'all' : 'ws'))
watch(scope, () => runSearch())
const query = ref('')
const results = ref<SearchResult[] | null>(null)
const wsHits = ref<WorkspaceHit[]>([])
const searching = ref(false)
const search = useDebouncedSearch(300)
function onSearch() { search.run(doSearch) }
// --- 検索の絞り込み（ラベル・更新日・自分が作成）。絞り込みだけでも一覧できる ---
const filterOpen = ref(false)
const fLabel = ref('')
const fDays = ref(0)
const fMine = ref(false)
const fStatus = ref<PageStatus | 'none' | ''>('')
const fOwnerMe = ref(false)
const filterCount = computed(() => (fLabel.value.trim() ? 1 : 0) + (fDays.value ? 1 : 0) + (fMine.value ? 1 : 0) + (fStatus.value ? 1 : 0) + (fOwnerMe.value ? 1 : 0))
const labelOptions = computed(() => [...new Set(store.pages.flatMap((p) => p.labels ?? []))].sort((a, b) => a.localeCompare(b, 'ja')))
watch([fDays, fMine, fStatus, fOwnerMe], () => runSearch())
function onLabelInput() { search.run(doSearch) }
function clearFilters() { fLabel.value = ''; fDays.value = 0; fMine.value = false; fStatus.value = ''; fOwnerMe.value = false; runSearch() }
const searchInput = ref<HTMLInputElement | null>(null)

function runSearch() { search.now(doSearch) }
// latest(): 古い応答で新しい結果を上書きしない
async function doSearch(latest: () => boolean) {
  const q = query.value.trim()
  if (!q && !filterCount.value) { results.value = null; wsHits.value = []; return }
  searching.value = true
  try {
    const f = { label: fLabel.value.trim() || undefined, days: fDays.value || undefined, mine: fMine.value || undefined, status: fStatus.value || undefined, ownerMe: fOwnerMe.value || undefined }
    const [pages, wss] = await Promise.all([searchPages(q, scope.value === 'ws' ? props.wid : undefined, f), q ? searchWorkspaces(q) : Promise.resolve([])])
    if (!latest()) return
    results.value = pages
    wsHits.value = wss
  } catch (e) {
    if (latest()) await alertError('検索できませんでした', e)
  } finally {
    if (latest()) searching.value = false
  }
}
function clearSearch() { query.value = ''; search.cancel(); if (filterCount.value) runSearch(); else { results.value = null; wsHits.value = [] } }
const wsName = (id: string) => store.workspaces.find((w) => w.id === id)?.name ?? ''
function openUrl(u: string) { window.open(u, '_blank', 'noopener') }
const exactCount = computed(() => results.value?.filter((r) => !r.fuzzy).length ?? 0)

// --- ワークスペースの作成 ---
const NEW_WS = '__new__'
async function onSelectWs(e: Event) {
  const el = e.target as HTMLSelectElement
  if (el.value !== NEW_WS) { router.push(`/w/${el.value}`); return }
  el.value = props.wid // 選択欄の表示を元に戻す
  await createWorkspaceFlow()
}
async function createWorkspaceFlow() {
  const name = (await promptDialog('新しいワークスペースの名前', '', { okLabel: '作成', placeholder: '例: 開発チーム' }))?.trim()
  if (!name) return
  try {
    const w = await store.createWorkspace(name)
    router.push(`/w/${w.id}`)
  } catch (err) { await alertError('作成できませんでした', err) }
}

const sharingWs = ref(false)

// --- ワークスペースのメニュー（名前の変更・取り込み・ゴミ箱・削除） ---
const wsMenu = ref(false)
const wsMenuEl = ref<HTMLElement | null>(null)
const wsMenuShift = ref(0)
/** メニューを開き、画面の端からはみ出す分だけ内側へずらす */
async function toggleWsMenu() {
  wsMenu.value = !wsMenu.value
  if (!wsMenu.value) return
  wsMenuShift.value = 0
  await nextTick()
  const r = wsMenuEl.value?.getBoundingClientRect()
  if (r) wsMenuShift.value = Math.max(8 - r.left, Math.min(0, window.innerWidth - 8 - r.right))
}
const trashOpen = ref(false)
const brokenOpen = ref(false)
const wsCssOpen = ref(false)
const webhooksOpen = ref(false)
const automationsOpen = ref(false)
const inboundOpen = ref(false)
const backupOpen = ref(false)
const wsGuestHtmlOpen = ref(false)
const canEditWs = computed(() => store.current?.role === 'edit')
async function renameWs() {
  wsMenu.value = false
  const cur = store.current
  if (!cur) return
  const name = (await promptDialog('ワークスペースの名前', cur.name, { okLabel: '変更' }))?.trim()
  if (!name || name === cur.name) return
  try { await store.renameWorkspace(cur.id, name) } catch (e) { await alertError('変更できませんでした', e) }
}
async function deleteWs() {
  wsMenu.value = false
  const cur = store.current
  if (!cur) return
  const ok = await confirmDialog(`ワークスペース「${cur.name}」をゴミ箱に移しますか？\n中のページも見られなくなります。ゴミ箱から元に戻せます。`, { okLabel: 'ゴミ箱に移す', danger: true })
  if (!ok) return
  try {
    await api5.deleteWorkspace(cur.id)
    await store.reloadWorkspaces()
    router.push('/')
  } catch (e) { await alertError('削除できませんでした', e) }
}
function openTrash() { wsMenu.value = false; trashOpen.value = true }
function importRoot() { wsMenu.value = false; importPage(props.wid, null) }

// --- お気に入り（ユーザーごと）: 一覧の上に「お気に入り」としてまとめて並ぶ ---
const favorites = computed(() => store.workspaces.filter((w) => w.favorite))
const others = computed(() => store.workspaces.filter((w) => !w.favorite))
async function toggleFavorite() {
  if (!store.current) return
  try { await store.toggleFavorite(store.current.id) } catch (e) { await alertError('お気に入りを変更できませんでした', e) }
}

const FAV_KEY = 'skiwi.favOpen'
const favOpen = useStoredRef(FAV_KEY, (raw) => raw !== '0', (v) => (v ? '1' : '0'))

// --- サイドメニューの開閉（状態はこのブラウザに保存）。Ctrl/⌘ + \ でも切り替え ---
// スマートフォンなど狭い画面では、本文の上に重ねて出し、ページを開いたら閉じる
const { narrow, open: sideOpen } = useSideMenu('skiwi.sideOpen', '(max-width: 768px)')
watch(() => route.fullPath, () => { if (narrow.value) sideOpen.value = false })
function onKey(e: KeyboardEvent) {
  // 選択中は Esc で解除（入力欄・ダイアログでの Esc は除く）
  if (e.key === 'Escape' && selectedPages.value.length && !(e.target as HTMLElement).closest?.('input, textarea, [role=dialog], .ProseMirror')) clearSelection()
  // JISキーボードの「¥」「ろ」位置も受け付ける
  if ((e.metaKey || e.ctrlKey) && !e.altKey && (['Backslash', 'IntlYen', 'IntlRo'].includes(e.code) || e.key === '\\')) {
    e.preventDefault()
    sideOpen.value = !sideOpen.value
  }
}
useEventListener(document, 'keydown', onKey)
const shortcut = isMac ? '⌘ + \\' : 'Ctrl + \\'

// --- ⌘K のクイック切り替えからの操作 ---
async function onCommand(e: Event) {
  switch ((e as CustomEvent<string>).detail) {
    case 'new-page': if (store.current?.role === 'edit') await newRoot(); break
    case 'trash': trashOpen.value = true; break
    case 'toggle-side': sideOpen.value = !sideOpen.value; break
    case 'new-workspace': await createWorkspaceFlow(); break
    case 'search':
      sideOpen.value = true
      store.sidePanel = 'tree'
      filterOpen.value = true
      await nextTick()
      searchInput.value?.focus()
      break
  }
}
useEventListener(window, 'skiwi:command', onCommand)
const paletteKey = isMac ? '⌘K' : 'Ctrl+K'
const openPalette = () => window.dispatchEvent(new Event('skiwi:palette'))

async function logout() {
  await api.logout()
  location.href = '/'
}
</script>

<template>
  <div class="layout">
    <button
      v-if="!sideOpen"
      class="side-open"
      :title="`サイドメニューを開く（${shortcut}）`"
      data-testid="side-open"
      @click="sideOpen = true"
    >☰</button>
    <div v-if="narrow && sideOpen" class="side-backdrop" @click="sideOpen = false" />
    <aside class="side" :class="{ closed: !sideOpen, overlay: narrow }" :inert="!sideOpen" data-testid="side">
      <div class="side-head">
        <select :value="props.wid" data-testid="ws-select" @change="onSelectWs">
          <template v-if="favorites.length">
            <optgroup label="★ お気に入り">
              <option v-for="w in favorites" :key="w.id" :value="w.id">★ {{ w.name }}</option>
            </optgroup>
            <optgroup label="その他のワークスペース">
              <option v-for="w in others" :key="w.id" :value="w.id">{{ w.name }}</option>
            </optgroup>
          </template>
          <option v-for="w in others" v-else :key="w.id" :value="w.id">{{ w.name }}</option>
          <option disabled>──────────</option>
          <option :value="NEW_WS">＋ 新しいワークスペースを作成…</option>
        </select>
        <button class="side-close" :title="`サイドメニューを閉じる（${shortcut}）`" data-testid="side-close" @click="sideOpen = false">«</button>
      </div>
      <div class="side-tools">
        <button
          v-if="store.current"
          class="fav"
          :class="{ on: store.current.favorite }"
          :title="store.current.favorite ? 'お気に入りから外す' : 'お気に入りに登録（一覧の上に並びます）'"
          data-testid="ws-favorite"
          @click="toggleFavorite"
        >{{ store.current.favorite ? '★' : '☆' }}<span class="tl">お気に入り</span></button>
        <span class="ws-menu-wrap">
          <button class="icon-btn" title="ワークスペースの操作" data-testid="ws-menu" @click="toggleWsMenu">⋯<span class="tl">操作</span></button>
          <div v-if="wsMenu" ref="wsMenuEl" class="ws-menu" role="menu" :style="{ transform: `translateX(${wsMenuShift}px)` }" @mouseleave="wsMenu = false">
            <button v-if="canEditWs" role="menuitem" data-testid="ws-share" @click="wsMenu = false; sharingWs = true">👥 共有設定</button>
            <button v-if="canEditWs" role="menuitem" data-testid="ws-rename" @click="renameWs">✏️ 名前を変更</button>
            <button v-if="canEditWs" role="menuitem" data-testid="ws-import" @click="importRoot">⤒ 取り込む<small>（Markdown・Word・Notion・Confluence など）</small></button>
            <button v-if="canEditWs" role="menuitem" data-testid="ws-guest-html" @click="wsMenu = false; wsGuestHtmlOpen = true">🌐 ゲスト向けの表示（本文の上下のHTML）</button>
            <button v-if="canEditWs" role="menuitem" data-testid="ws-css" @click="wsMenu = false; wsCssOpen = true">🎨 スタイル（カスタムCSS）</button>
            <button v-if="canEditWs" role="menuitem" data-testid="ws-automations" @click="wsMenu = false; automationsOpen = true">⚙ 自動化ルール</button>
            <button role="menuitem" data-testid="ws-inbound" @click="wsMenu = false; inboundOpen = true">📥 受信用の Webhook</button>
            <button v-if="canEditWs" role="menuitem" data-testid="ws-webhooks" @click="wsMenu = false; webhooksOpen = true">📣 外部への通知<small>（Slack・Teams・Webhook）</small></button>
            <button role="menuitem" data-testid="ws-trash" @click="openTrash">🗑 ゴミ箱</button>
            <button v-if="store.me?.isAdmin" role="menuitem" data-testid="ws-backup" @click="wsMenu = false; backupOpen = true">💾 定期バックアップ<small>（管理者）</small></button>
            <button role="menuitem" data-testid="ws-broken" @click="wsMenu = false; brokenOpen = true">🩺 点検<small>（リンク切れ・孤立・古いページ）</small></button>
            <template v-if="canEditWs">
              <hr />
              <button role="menuitem" class="danger" data-testid="ws-delete" @click="deleteWs">ワークスペースを削除…</button>
            </template>
          </div>
        </span>
        <NotificationBell />
        <button class="icon-btn" :title="`クイック切り替え（${paletteKey}）`" data-testid="open-palette" @click="openPalette">⌘<span class="tl">{{ paletteKey }}</span></button>
      </div>
      <div class="search">
        <div class="search-row">
          <input ref="searchInput" v-model="query" type="search" placeholder="🔍 検索（ページ・ワークスペース）" data-testid="search" @input="onSearch" @keydown.esc="clearSearch" />
          <button class="filter-btn" :class="{ on: filterOpen || filterCount }" title="絞り込み（ラベル・更新日・作成者）" data-testid="filter-toggle" @click="filterOpen = !filterOpen">
            ⚙<span v-if="filterCount" class="fcount">{{ filterCount }}</span>
          </button>
        </div>
        <div v-if="filterOpen" class="filters" data-testid="filters">
          <label>🏷 <input v-model="fLabel" list="label-options" placeholder="ラベル" data-testid="filter-label" @input="onLabelInput" /></label>
          <datalist id="label-options"><option v-for="l in labelOptions" :key="l" :value="l" /></datalist>
          <label>🕘
            <select v-model.number="fDays" data-testid="filter-days">
              <option :value="0">更新日: 指定なし</option>
              <option :value="7">1週間以内に更新</option>
              <option :value="30">1か月以内に更新</option>
              <option :value="365">1年以内に更新</option>
            </select>
          </label>
          <label>📌
            <select v-model="fStatus" data-testid="filter-status">
              <option value="">状態: 指定なし</option>
              <option value="draft">下書き</option>
              <option value="review">レビュー中</option>
              <option value="published">公開済み</option>
              <option value="deprecated">廃止</option>
              <option value="none">状態なし</option>
            </select>
          </label>
          <label class="chk"><input v-model="fMine" type="checkbox" data-testid="filter-mine" /> 自分が作成したページ</label>
          <label class="chk"><input v-model="fOwnerMe" type="checkbox" data-testid="filter-owner" /> 自分が所有者のページ</label>
          <button v-if="filterCount" class="link" @click="clearFilters">絞り込みを解除</button>
        </div>
        <div class="scope" role="radiogroup" aria-label="検索範囲">
          <button role="radio" :aria-checked="scope === 'ws'" :class="{ on: scope === 'ws' }" data-testid="scope-ws" @click="scope = 'ws'">このワークスペース</button>
          <button role="radio" :aria-checked="scope === 'all'" :class="{ on: scope === 'all' }" data-testid="scope-all" @click="scope = 'all'">すべて</button>
        </div>
      </div>
      <nav v-if="results !== null" class="results" data-testid="results">
        <template v-if="wsHits.length">
          <p class="muted small head">ワークスペース</p>
          <router-link v-for="w in wsHits" :key="w.id" :to="`/w/${w.id}`" class="result ws-hit" @click="clearSearch">
            <div class="r-title">🗂 {{ w.name }} <span v-if="w.fuzzy" class="badge">あいまい</span></div>
          </router-link>
          <p class="muted small head">ページ</p>
        </template>
        <p class="muted small">{{ searching ? '検索中…' : `${results.length} 件` }}<template v-if="!searching && results.length > exactCount">（うち あいまい一致 {{ results.length - exactCount }} 件）</template></p>
        <router-link
          v-for="r in results"
          :key="r.page.id"
          :to="`/w/${r.page.workspaceId}/p/${r.page.id}`"
          class="result"
          :class="{ fuzzy: r.fuzzy }"
        >
          <div class="r-title">
            {{ r.page.icon ?? '📄' }} {{ r.page.title || '無題' }}
            <span v-if="r.page.status" class="badge">{{ STATUS_LABEL[r.page.status] }}</span>
            <span v-if="r.fuzzy" class="badge" title="入力した語そのものではなく、表記の近い語で見つかりました">あいまい</span>
          </div>
          <div v-if="r.page.workspaceId !== props.wid" class="r-ws">🗂 {{ wsName(r.page.workspaceId) }}</div>
          <div v-if="r.attachment" class="r-att" data-testid="search-attachment" :title="`添付ファイル「${r.attachment.filename}」の中身に見つかりました`">
            📎 {{ r.attachment.filename }}
            <button type="button" class="r-open" title="添付ファイルを開く" @click.prevent.stop="openUrl(r.attachment.url)">開く</button>
          </div>
          <!-- スニペットはPGroongaがHTMLエスケープ済み（キーワードのみspanで装飾） -->
          <div v-if="r.snippet" class="snippet" v-html="r.snippet" />
        </router-link>
      </nav>
      <template v-else>
        <div class="panels" role="tablist">
          <button role="tab" :class="{ on: store.sidePanel === 'tree' }" data-testid="panel-tree" @click="store.sidePanel = 'tree'">📄 ページ</button>
          <button role="tab" :class="{ on: store.sidePanel === 'recent' }" data-testid="panel-recent" @click="store.sidePanel = 'recent'">🕘 最近</button>
          <button role="tab" :class="{ on: store.sidePanel === 'labels' }" data-testid="panel-labels" @click="store.sidePanel = 'labels'">🏷 ラベル</button>
          <button role="tab" :class="{ on: store.sidePanel === 'tasks' }" data-testid="panel-tasks" @click="store.sidePanel = 'tasks'">✅ ToDo</button>
        </div>
        <nav v-if="store.sidePanel === 'tree'" class="tree">
          <section v-if="store.favoritePages.length" class="favs" data-testid="fav-pages">
            <button class="favs-head" @click="favOpen = !favOpen">{{ favOpen ? '▾' : '▸' }} ★ お気に入り</button>
            <template v-if="favOpen">
              <router-link v-for="f in store.favoritePages" :key="f.page.id" :to="`/w/${f.page.workspaceId}/p/${f.page.id}`" class="fav-item">
                <span>{{ f.page.icon ?? '📄' }} {{ f.page.title || '無題' }}</span>
                <span v-if="f.page.workspaceId !== props.wid" class="fav-ws">{{ f.workspaceName }}</span>
              </router-link>
            </template>
          </section>
          <PageTree :nodes="store.tree" :wid="props.wid" :depth="0" />
          <p v-if="store.tree.length === 0" class="muted">ページがありません</p>
        </nav>
        <nav v-else-if="store.sidePanel === 'recent'" class="tree"><RecentPanel :wid="props.wid" /></nav>
        <nav v-else-if="store.sidePanel === 'tasks'" class="tree"><TasksPanel :wid="props.wid" /></nav>
        <nav v-else class="tree"><LabelPanel :wid="props.wid" /></nav>
      </template>
      <BulkBar :wid="props.wid" />
      <div class="side-foot">
        <button v-if="store.current?.role === 'edit'" class="new-page" data-testid="new-page" @click="newRoot">＋ 新規ページ</button>
        <div class="me">
          <span class="user" :title="store.me?.email ? `${store.me?.name}（${store.me.email}）` : store.me?.name">👤 {{ store.me?.name }}</span>
          <button class="logout" @click="logout">ログアウト</button>
        </div>
        <div class="links">
          <router-link to="/settings/tokens">APIトークン</router-link>
          <router-link to="/help" data-testid="open-help">📖 使い方</router-link>
          <router-link to="/settings/api">API の説明書</router-link>
          <router-link v-if="store.me?.isAdmin" to="/admin/audit">監査ログ</router-link>
          <router-link v-if="store.me?.isAdmin" to="/admin/shares">公開中のページ</router-link>
        </div>
      </div>
      <GuestHtmlDialog v-if="wsGuestHtmlOpen && store.current" kind="workspace" :id="store.current.id" :title="store.current.name" @close="wsGuestHtmlOpen = false" />
      <CssDialog v-if="wsCssOpen && store.current" kind="workspace" :id="store.current.id" :title="store.current.name" @close="wsCssOpen = false" />
      <BackupDialog v-if="backupOpen && store.current" :wid="props.wid" :name="store.current.name" @close="backupOpen = false" />
      <InboundDialog v-if="inboundOpen && store.current" :wid="props.wid" :name="store.current.name" @close="inboundOpen = false" />
      <AutomationDialog v-if="automationsOpen && store.current" :wid="props.wid" :name="store.current.name" @close="automationsOpen = false" />
      <WebhookDialog v-if="webhooksOpen && store.current" :wid="props.wid" :name="store.current.name" @close="webhooksOpen = false" />
      <BrokenLinksDialog v-if="brokenOpen" :wid="props.wid" @close="brokenOpen = false" />
      <TrashDialog v-if="trashOpen" :wid="props.wid" @close="trashOpen = false" />
      <ShareDialog v-if="sharingWs && store.current" kind="workspace" :id="store.current.id" :title="store.current.name" @close="sharingWs = false" />
      <DuplicateDialog v-if="duplicateTarget" :page="duplicateTarget" @close="duplicateTarget = null" />
    </aside>
    <section class="main">
      <router-view :key="$route.fullPath" />
      <p v-if="!$route.params.pid" class="muted" style="padding: 3rem">左のツリーからページを選択してください。</p>
    </section>
  </div>
</template>

<style scoped>
.layout { display: flex; height: 100%; position: relative; overflow: hidden; }
.side { width: 290px; flex: none; transition: margin-left .18s ease; background: var(--side); border-right: 1px solid var(--line); display: flex; flex-direction: column; }
.side-head { padding: .6rem .6rem .3rem; display: flex; gap: .3rem; align-items: center; }
.side-tools { display: flex; align-items: center; gap: 2px; padding: 0 .5rem .5rem; flex-wrap: wrap; }
.side-tools > button, .side-tools .ws-menu-wrap > button, .side-tools :deep(.bell) { border: none; border-radius: 6px; padding: 2px 7px; color: var(--muted); font-size: .9rem; display: inline-flex; align-items: center; gap: 3px; }
.side-tools > button:hover, .side-tools .ws-menu-wrap > button:hover, .side-tools :deep(.bell:hover) { background: var(--hover); color: var(--fg); }
.tl { font-size: .72rem; }
.side.closed { margin-left: -290px; }
.fav { color: var(--muted); }
.fav.on { color: #eab308; }
.side-close { border: none; color: var(--muted); padding: 0 6px; font-size: 1rem; }
.side-open { position: absolute; z-index: 30; top: .9rem; left: .7rem; width: 30px; height: 28px; padding: 0; color: var(--muted); background: var(--bg); }
.side-open:hover { color: var(--fg); }
.side-head select { flex: 1; min-width: 0; padding: .35rem .4rem; font: inherit; font-weight: 600; }
.links { width: 100%; display: flex; flex-wrap: wrap; gap: .2rem .8rem; font-size: .78rem; }
.links a { color: var(--muted); }
.tree { flex: 1; overflow: auto; padding: 0 .3rem; }
.side-foot { padding: .6rem; border-top: 1px solid var(--line); display: flex; flex-direction: column; gap: .45rem; }
.new-page { width: 100%; }
.me { display: flex; align-items: center; gap: .4rem; }
.user { flex: 1; min-width: 0; font-size: .85em; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.logout { font-size: .8rem; padding: 1px 8px; }
.main { flex: 1; overflow: auto; }
.search { padding: 0 .6rem .5rem; }
.search-row input { width: 100%; padding: .3rem .6rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); }
.results { flex: 1; overflow: auto; padding: 0 .6rem; }
.result { display: block; padding: .4rem .5rem; border-radius: 6px; color: inherit; text-decoration: none; }
.result:hover { background: var(--hover); }
.r-title { font-weight: 600; font-size: .92em; }
.scope { display: flex; gap: 2px; margin-top: .35rem; font-size: .75rem; }
.scope button { flex: 1; border: 1px solid var(--line); padding: 1px 4px; color: var(--muted); }
.scope button:first-child { border-radius: 6px 0 0 6px; }
.scope button:last-child { border-radius: 0 6px 6px 0; }
.scope button.on { background: var(--hover); color: var(--fg); border-color: var(--accent); }
.head { margin-top: .5rem; font-weight: 600; }
.badge { font-size: .68rem; font-weight: 400; color: var(--muted); border: 1px solid var(--line); border-radius: 4px; padding: 0 4px; margin-left: 4px; vertical-align: 1px; }
.result.fuzzy .r-title { font-weight: 500; }
.r-ws { font-size: .75em; color: var(--muted); }
.r-att { font-size: .78em; color: var(--muted); display: flex; gap: .5rem; align-items: center; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.r-open { color: var(--accent); flex: none; border: none; background: none; padding: 0; font-size: inherit; cursor: pointer; }
.snippet { font-size: .8em; color: var(--muted); display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.small { font-size: .8em; margin: .2rem 0; }
.ws-menu-wrap { position: relative; display: flex; }
.icon-btn { border: none; color: var(--muted); padding: 0 5px; }
.ws-menu { position: absolute; top: calc(100% + 4px); left: 0; z-index: 70; width: max-content; min-width: 220px; max-width: calc(100vw - 24px); background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 4px; box-shadow: 0 10px 28px rgba(0,0,0,.2); display: flex; flex-direction: column; }
.ws-menu button { text-align: left; border: none; padding: .35rem .6rem; border-radius: 6px; font-size: .88rem; white-space: nowrap; }
.ws-menu button:hover { background: var(--hover); }
.ws-menu hr { border: none; border-top: 1px solid var(--line); margin: 4px 0; width: 100%; }
.ws-menu .danger:hover { color: var(--danger); }
.ws-menu small { color: var(--muted); font-size: .75em; }
.panels { display: flex; gap: 2px; padding: 0 .6rem .4rem; font-size: .75rem; }
.panels button { flex: 1; white-space: nowrap; border: none; border-bottom: 2px solid transparent; border-radius: 0; padding: 2px 0; color: var(--muted); }
.panels button.on { color: var(--fg); border-bottom-color: var(--accent); }
.search-row { display: flex; gap: 4px; }
.search-row input { flex: 1; min-width: 0; }
.filter-btn { border: 1px solid var(--line); border-radius: 6px; padding: 0 7px; color: var(--muted); position: relative; }
.filter-btn.on { color: var(--accent); border-color: var(--accent); }
.fcount { position: absolute; top: -5px; right: -5px; min-width: 14px; height: 14px; border-radius: 7px; background: var(--accent); color: #fff; font-size: 9px; line-height: 14px; text-align: center; }
.filters { display: flex; flex-direction: column; gap: .3rem; margin-top: .4rem; padding: .4rem .5rem; border: 1px solid var(--line); border-radius: 8px; font-size: .8rem; }
.filters label { display: flex; align-items: center; gap: .3rem; }
.filters input:not([type=checkbox]), .filters select { flex: 1; min-width: 0; font: inherit; padding: 2px 6px; border: 1px solid var(--line); border-radius: 5px; background: var(--bg); color: inherit; }
.filters .link { align-self: flex-start; border: none; color: var(--accent); padding: 0; font-size: .78rem; }
.favs { margin: 0 0 .4rem; padding-bottom: .3rem; border-bottom: 1px solid var(--line); }
.favs-head { border: none; font-size: .78rem; color: var(--muted); padding: 2px 4px; }
.fav-item { display: flex; justify-content: space-between; gap: .4rem; padding: .25rem .5rem .25rem 1.2rem; border-radius: 6px; color: inherit; text-decoration: none; font-size: .9rem; }
.fav-item span:first-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fav-item:hover, .fav-item.router-link-active { background: var(--hover); }
.fav-ws { font-size: .7rem; color: var(--muted); white-space: nowrap; }
.side.overlay { position: absolute; top: 0; bottom: 0; left: 0; z-index: 60; width: min(300px, 86vw); box-shadow: 8px 0 24px rgba(0,0,0,.25); }
.side.overlay.closed { margin-left: max(-300px, -86vw); box-shadow: none; }
.side-backdrop { position: absolute; inset: 0; z-index: 55; background: rgba(0,0,0,.35); }
@media (max-width: 768px) {
  .side-open { top: .6rem; left: .5rem; }
  .side-foot { font-size: .9rem; }
}
@media print { .side, .side-open { display: none !important; } .main { overflow: visible; } .layout { height: auto; overflow: visible; } }
</style>
