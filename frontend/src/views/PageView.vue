<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useWorkspaceStore } from '../stores/workspace'
import { ApiError, api, api4, api5, commentApi, STATUS_LABEL, type Comment, type Page, type PageStatus, type Role, type WatchState } from '../api'
import { useRouter } from 'vue-router'
import CommentsPanel, { type CommentDraft } from '../components/CommentsPanel.vue'
import CssDialog from '../components/CssDialog.vue'
import { useCustomCss } from '../ui/customCss'
import { cssApi } from '../api'
import { download, toMarkdown } from '../editor/markdown'
const exportZip = (id: string, content?: unknown) => import('../ui/importer').then((m) => m.exportZip(id, content))
import Editor from '../components/Editor.vue'
import ShareDialog from '../components/ShareDialog.vue'
import AccessRequestPanel from '../components/AccessRequestPanel.vue'
import HistoryPanel from '../components/HistoryPanel.vue'
import IconPicker from '../ui/IconPicker.vue'
import { alertDialog, alertError, chooseDialog, pickUser, promptDialog } from '../ui/dialog'
import { duplicateTarget } from '../ui/duplicate'
import { defineAsyncComponent } from 'vue'
import { dbApi, lockApi, type PageLockState } from '../api'
import LockDialog from '../components/LockDialog.vue'
import { defaultDatabase } from '../database/db'
import { useEventListener, isTyping } from '../ui/composables'
const DatabaseView = defineAsyncComponent(() => import('../database/DatabaseView.vue'))
const RowProps = defineAsyncComponent(() => import('../database/RowProps.vue'))

const props = defineProps<{ wid: string; pid: string }>()
const store = useWorkspaceStore()
const route = useRoute()

const page = computed(() => store.byId.get(props.pid))
const title = ref('')
let timer: ReturnType<typeof setTimeout> | undefined

/** サーバーが接続中に通知する権限（共有設定の変更で変わる）。未通知ならページ一覧の権限。 */
const liveRole = ref<Role | 'none' | null>(null)
const role = computed<Role | 'none'>(() => liveRole.value ?? page.value?.role ?? 'view')
const canEdit = computed(() => role.value === 'edit')

// --- 表示モード / 編集モード ----------------------------------------------------------
// 既定は表示モード（誤って書き換えない・リンクや目次をそのまま使える）。新規作成直後（?edit=1）と無題のページは編集モードで開く。
const mode = ref<'view' | 'edit'>(route.query.edit === '1' ? 'edit' : 'view')
let touched = false
watch(page, (p) => { if (p && mode.value === 'view' && !p.title && p.role === 'edit' && !touched) mode.value = 'edit' }, { immediate: true })
const editing = computed(() => canEdit.value && mode.value === 'edit')
function setMode(m: 'view' | 'edit') { touched = true; mode.value = m }

// 「E」で編集モード、「Esc」で表示モード（入力欄・エディタ内の入力は除く）
function onKey(e: KeyboardEvent) {
  if (e.isComposing || e.metaKey || e.ctrlKey || e.altKey) return
  if (!isTyping(e.target) && e.key.toLowerCase() === 'e' && canEdit.value && mode.value === 'view') { e.preventDefault(); setMode('edit') }
}
useEventListener(document, 'keydown', onKey)

watch(page, (p) => { if (p && document.activeElement?.id !== 'page-title') title.value = p.title }, { immediate: true })

function onTitle() {
  clearTimeout(timer)
  timer = setTimeout(() => store.rename(props.pid, title.value).catch((e) => alertDialog(`保存できませんでした: ${e.message}`)), 400)
}

function onRole(r: Role | 'none') {
  const changed = liveRole.value !== null && liveRole.value !== r
  liveRole.value = r
  if (changed || r === 'none') store.reloadPages() // ツリー上の権限表示も更新
}

// --- アイコン -------------------------------------------------------------------
const picking = ref(false)
async function pickIcon(icon: string | null) {
  picking.value = false
  try { await store.setIcon(props.pid, icon) } catch (e) { await alertError('変更できませんでした', e) }
}

// --- ウォッチ -------------------------------------------------------------------
// 受け取る内容（本文の更新・コメント）と、子ページも含めるかを選べる。祖先を「子ページも含める」でウォッチしていれば、その設定で届く
const watchState = ref<WatchState | null>(null)
const watchMenu = ref(false)
watch(() => props.pid, async (id) => {
  watchState.value = null
  watchMenu.value = false
  try { watchState.value = await api4.watch(id) } catch { /* 権限なし等 */ }
}, { immediate: true })
/** 通知が届く状態か（このページの設定、なければ祖先の設定） */
const watchOn = computed(() => !!watchState.value && (watchState.value.watching || !!watchState.value.inherited))
const watchLabel = computed(() => {
  const w = watchState.value
  if (!w) return ''
  if (!w.watching) return w.inherited ? 'ウォッチ中（親）' : 'ウォッチ'
  return w.edits && w.comments ? 'ウォッチ中' : w.comments ? 'コメントのみ' : '更新のみ'
})
/** 今届く内容（このページの設定、なければ祖先の設定） */
const effWatch = computed(() => {
  const w = watchState.value
  if (w?.watching) return { edits: w.edits, comments: w.comments }
  return { edits: !!w?.inherited?.edits, comments: !!w?.inherited?.comments }
})
async function setWatchOpts(o: { edits?: boolean; comments?: boolean; subtree?: boolean }) {
  try { watchState.value = await api4.setWatchOpts(props.pid, o) } catch (e) { await alertError('変更できませんでした', e) }
}
async function stopWatch() {
  try { watchState.value = await api4.setWatch(props.pid, false) } catch (e) { await alertError('変更できませんでした', e) }
  watchMenu.value = false
}

// --- 共有・履歴 -----------------------------------------------------------------
// 通知・メールの「権限の申請」から開いたときは共有画面を開く
const sharing = ref(route.query.access === 'requests')
const history = ref(false)
const editorRef = ref<InstanceType<typeof Editor> | null>(null)

function onShareClosed() {
  sharing.value = false
  store.reloadPages()
}

// --- カスタムCSS（ワークスペース → 子にも適用する祖先 → このページ。編集中は入力中のCSSをその場で反映） ---
const cssLayers = ref<{ css: string; own: boolean }[]>([])
const cssPreview = ref<string | null>(null)
const cssOpen = ref(false)
async function loadCss() {
  try { cssLayers.value = (await cssApi.pageStyles(props.pid)).layers.map((l) => ({ css: l.css, own: l.own })) } catch { cssLayers.value = [] }
}
watch(() => props.pid, loadCss, { immediate: true })
const appliedCss = computed(() => {
  const outer = cssLayers.value.filter((l) => !l.own).map((l) => l.css)
  const own = cssPreview.value ?? cssLayers.value.find((l) => l.own)?.css ?? ''
  return [...outer, own]
})
useCustomCss(appliedCss)
useEventListener(window, 'skiwi:css-changed', loadCss)
function onCssClosed() { cssOpen.value = false; cssPreview.value = null }

/** このページを開いているほかの人 */
const people = ref<{ name: string; color: string; editing: boolean }[]>([])

// --- 所有者・状態 --------------------------------------------------------------
async function updateMeta(body: { ownerId?: string | null; status?: PageStatus | null }) {
  try {
    const np = await api.updatePage(props.pid, body)
    store.patchLocal(props.pid, { ownerId: np.ownerId, ownerName: np.ownerName, status: np.status })
  } catch (e) { await alertError('変更できませんでした', e) }
}
async function pickStatus() {
  if (!canEdit.value) return
  const v = await chooseDialog('ページの状態', [
    ...(Object.keys(STATUS_LABEL) as PageStatus[]).map((k) => ({ label: STATUS_LABEL[k], value: k })),
    { label: '（状態なし）', value: '' },
  ])
  if (v !== null) await updateMeta({ status: (v || null) as PageStatus | null })
}
async function pickOwner() {
  if (!canEdit.value) return
  const u = await pickUser('所有者（名前・メールで検索。空欄で外す）', { initial: page.value?.ownerName ?? '', chooseMessage: '所有者を選んでください', allowEmpty: true })
  if (u !== undefined) await updateMeta({ ownerId: u?.id ?? null })
}

const denied = ref(false)
// --- 別のワークスペースへ移ったページ（古いリンク・ブックマーク）は、正しいワークスペースで開き直す ---
const router = useRouter()
watch(() => [props.pid, store.pages.length, store.currentId] as const, async ([pid]) => {
  if (!store.currentId || store.currentId !== props.wid || page.value) return
  try {
    const p = await api.getPage(pid)
    if (p.workspaceId !== props.wid) router.replace({ path: `/w/${p.workspaceId}/p/${p.id}`, query: route.query, hash: route.hash })
  } catch (e) {
    // 権限なし・削除済み・存在しない: 閲覧権限を申請できる画面を出す
    if (e instanceof ApiError && (e.status === 404 || e.status === 403)) denied.value = true
  }
}, { immediate: true })

// --- コメント ----------------------------------------------------------------------
const comments = ref<Comment[]>([])
const commentsOpen = ref(false)
const commentDraft = ref<CommentDraft | null>(null)
const commentFocus = ref<string | null>(typeof route.query.comment === 'string' ? route.query.comment : null)
if (commentFocus.value) commentsOpen.value = true
const openThreads = computed(() => comments.value.filter((c) => !c.threadId && !c.resolvedAt && !c.deleted).length)
async function loadComments() {
  try { comments.value = await commentApi.list(props.pid) } catch { /* 権限なし等 */ }
}
watch(() => props.pid, loadComments, { immediate: true })
function onSelectionComment(d: { from: number; to: number; quote: string }) {
  commentDraft.value = { kind: 'text', ...d }
  commentFocus.value = null
  commentsOpen.value = true
}
function onCommentClick(anchorId: string) {
  commentFocus.value = `text:${anchorId}`
  commentsOpen.value = true
}
const onWbComment = (e: Event) => {
  commentDraft.value = { kind: 'wb', boardId: (e as CustomEvent<{ id: string }>).detail.id }
  commentsOpen.value = true
}
const onCommand = (e: Event) => {
  const cmd = (e as CustomEvent<string>).detail
  if (cmd === 'comments') commentsOpen.value = true
  else if (cmd === 'edit-page' && canEdit.value) setMode('edit')
}
useEventListener(window, 'skiwi:wb-comment', onWbComment)
useEventListener(window, 'skiwi:command', onCommand)

// 解決済み・削除済みのスレッドの印は目立たせない（本文の印はそのまま残す）
const quietStyle = document.createElement('style')
document.head.appendChild(quietStyle)
onBeforeUnmount(() => quietStyle.remove())
watch(comments, (cs) => {
  const quiet = cs.filter((c) => !c.threadId && c.anchor?.startsWith('text:') && (c.resolvedAt || c.deleted)).map((c) => c.anchor!.slice(5))
  quietStyle.textContent = quiet.map((id) => `.ProseMirror .comment-mark[data-comment="${CSS.escape(id)}"]{background:none;border-bottom:none}`).join('\n')
})

// 同期ブロックへのリンク（#sync-ID）で開いたら、そのブロックへスクロール
onMounted(() => {
  const m = /^#(sync|block)-([0-9a-z]+)$/i.exec(location.hash)
  if (!m) return
  const sel = m[1] === 'sync' ? `.ProseMirror [data-synced-id="${CSS.escape(m[2])}"]` : `.ProseMirror [data-block-id="${CSS.escape(m[2])}"]`
  let tries = 0
  const t = setInterval(() => {
    const el = document.querySelector<HTMLElement>(sel)
    if (el || ++tries > 20) {
      clearInterval(t)
      const cls = m[1] === 'sync' ? 'flash' : 'block-flash'
      if (el) { el.scrollIntoView({ block: 'center' }); el.classList.add(cls); setTimeout(() => el.classList.remove(cls), 1600) }
    }
  }, 250)
})

// --- 最近見たページ（開いたことを記録） ------------------------------------------------
watch(() => props.pid, (id) => { api5.visit(id).catch(() => { /* 記録できなくても表示は続ける */ }) }, { immediate: true })

// --- ラベル ---------------------------------------------------------------------
const labels = computed(() => page.value?.labels ?? [])
async function saveLabels(ls: string[]) {
  try {
    const r = await api5.setLabels(props.pid, ls)
    store.patchLocal(props.pid, { labels: r.labels })
  } catch (e) { await alertError('ラベルを保存できませんでした', e) }
}
async function addLabel() {
  const v = await promptDialog('追加するラベル（「,」区切りで複数可）', '', { okLabel: '追加', placeholder: '例: 手順書, API' })
  if (!v?.trim()) return
  await saveLabels([...labels.value, ...v.split(/[,、，]/)])
}
const removeLabel = (l: string) => saveLabels(labels.value.filter((x) => x !== l))

// --- 被リンク -------------------------------------------------------------------
const backlinks = ref<Page[]>([])
watch(() => props.pid, async (id) => {
  backlinks.value = []
  try { backlinks.value = await api5.backlinks(id) } catch { /* 権限なし等 */ }
}, { immediate: true })

// --- データベース（子ページを表・ボード・カレンダーで見る） -----------------------------------
const isDatabase = computed(() => !!page.value?.database)
/** 親がデータベースなら、このページはその項目（値を上部に出す） */
const inDatabase = computed(() => !!page.value?.parentId && !!store.byId.get(page.value!.parentId)?.database)
async function makeDatabase() {
  const d = defaultDatabase()
  try {
    await dbApi.save(props.pid, d.props, d.views)
    await store.reloadPages()
  } catch (e) { await alertError('データベースにできませんでした', e) }
}

// --- パンくず（親ページの階層。見られない親は省く） ------------------------------------------
const crumbs = computed(() => {
  return store.ancestors(props.pid).slice(0, 20).reverse()
})

// --- 全幅表示 ---------------------------------------------------------------------
async function toggleFullWidth() {
  if (!page.value) return
  const v = !page.value.fullWidth
  store.patchLocal(page.value.id, { fullWidth: v })
  try { await api.updatePage(page.value.id, { fullWidth: v }) } catch (e) { store.patchLocal(page.value.id, { fullWidth: !v }); await alertError('変更できませんでした', e) }
}

// --- 保護 -----------------------------------------------------------------------
const lockOpen = ref(false)
const lock = ref<PageLockState | null>(null)
watch(() => [props.pid, page.value?.locked] as const, async ([id, locked]) => {
  lock.value = null
  if (locked) try { lock.value = await lockApi.get(id) } catch { /* 権限なし等 */ }
}, { immediate: true })
async function onLockChanged() {
  await store.reloadPages()
  liveRole.value = null // 保護による権限の変化はページ一覧の権限で反映する
}

// --- 書き出し -------------------------------------------------------------------
const exportMenu = ref(false)
function exportMarkdown() {
  exportMenu.value = false
  const ed = editorRef.value?.editor
  if (!ed || !page.value) return
  download(`${page.value.title || '無題'}.md`, toMarkdown(ed.getJSON(), page.value.title))
}
function exportTree() {
  exportMenu.value = false
  void exportZip(props.pid, editorRef.value?.editor.getJSON())
}
function printPage() {
  exportMenu.value = false
  if (mode.value === 'edit') setMode('view')
  setTimeout(() => window.print(), 100)
}

// --- 右上のボタン: 文字が入りきらないときは、アイコンだけにする（折り返して不恰好にならないように） ---
const toolbarEl = ref<HTMLElement | null>(null)
let toolbarRO: ResizeObserver | null = null
let toolbarMO: MutationObserver | null = null
function fitToolbar() {
  const el = toolbarEl.value
  if (!el) return
  // 右寄せのままだとはみ出しが左側に出て測れないので、測る間だけ左寄せにする
  // 1. そのまま → 2. 余白を詰める（tight） → 3. アイコンだけ（compact）の順に、入るものを選ぶ
  el.classList.remove('compact', 'tight')
  el.classList.add('measuring')
  const over = () => el.scrollWidth > el.clientWidth + 1
  let level: '' | 'tight' | 'compact' = ''
  if (over()) {
    el.classList.add('tight')
    level = over() ? 'compact' : 'tight'
    el.classList.remove('tight')
  }
  el.classList.remove('measuring')
  if (level) el.classList.add(level)
}
watch(toolbarEl, (el, _old, onCleanup) => {
  if (!el) return
  toolbarRO = new ResizeObserver(() => fitToolbar())
  toolbarRO.observe(el)
  // ボタンの増減（編集モード・ウォッチ・保護の表示など）でも測り直す
  toolbarMO = new MutationObserver(() => fitToolbar())
  toolbarMO.observe(el, { childList: true, subtree: true, characterData: true })
  fitToolbar()
  onCleanup(() => { toolbarRO?.disconnect(); toolbarMO?.disconnect() })
}, { flush: 'post' })
</script>

<template>
  <article v-if="page" class="page skiwi-page-css" :class="{ editing, 'full-width': page.fullWidth }">
    <div ref="toolbarEl" class="toolbar">
      <span class="mode-label">{{ editing ? '編集中' : '' }}</span>
      <span v-if="people.length" class="people" data-testid="presence" :title="people.map((p) => `${p.name}${p.editing ? '（編集中）' : '（閲覧中）'}`).join('\n')">
        <span v-for="p in people.slice(0, 5)" :key="p.name" class="avatar" :class="{ editing: p.editing }" :style="{ background: p.color }">
          {{ p.name.slice(0, 1) }}<span v-if="p.editing" class="pen">✎</span>
        </span>
        <span v-if="people.length > 5" class="avatar more">+{{ people.length - 5 }}</span>
      </span>
      <span v-if="watchState" class="menu-wrap">
        <button
          :class="{ on: watchOn }"
          title="ほかの人の更新・コメントを通知で受け取る（受け取る内容を選べます）"
          data-testid="watch"
          @click="watchMenu = !watchMenu"
        >{{ watchOn ? '🔔' : '🔕' }}<span class="lbl"> {{ watchLabel }}</span></button>
        <div v-if="watchMenu" class="pop watch-pop" data-testid="watch-menu" @mouseleave="watchMenu = false">
          <p class="wp-title">このページの通知</p>
          <label><input type="checkbox" :checked="effWatch.edits" data-testid="watch-edits" @change="setWatchOpts({ ...effWatch, edits: ($event.target as HTMLInputElement).checked })" /> 本文の更新</label>
          <label><input type="checkbox" :checked="effWatch.comments" data-testid="watch-comments" @change="setWatchOpts({ ...effWatch, comments: ($event.target as HTMLInputElement).checked })" /> コメント</label>
          <label><input type="checkbox" :checked="watchState.watching && watchState.subtree" :disabled="!watchState.watching" data-testid="watch-subtree" @change="setWatchOpts({ subtree: ($event.target as HTMLInputElement).checked })" /> 子ページも含める</label>
          <p v-if="watchState.inherited && !watchState.watching" class="wp-note" data-testid="watch-inherited">
            「{{ watchState.inherited.title || '無題' }}」のウォッチ（子ページも含む）で、{{ watchState.inherited.edits && watchState.inherited.comments ? '更新とコメント' : watchState.inherited.edits ? '更新' : 'コメント' }}の通知が届きます。ここで選ぶと、このページはその設定が優先されます。
          </p>
          <p v-else-if="watchState.inherited" class="wp-note">親の「{{ watchState.inherited.title || '無題' }}」のウォッチより、このページの設定が優先されます。</p>
          <button v-if="watchState.watching" class="wp-stop" data-testid="watch-stop" @click="stopWatch">ウォッチをやめる</button>
          <p class="wp-note">通知は画面内とメールで届きます。</p>
        </div>
      </span>
      <button
        :class="{ on: store.isFavoritePage(page.id) }"
        :title="store.isFavoritePage(page.id) ? 'お気に入りから外す' : 'お気に入りに追加（サイドバーの上部に並びます）'"
        data-testid="page-favorite"
        @click="store.toggleFavoritePage(page.id)"
      >{{ store.isFavoritePage(page.id) ? '★' : '☆' }}<span class="lbl"> お気に入り</span></button>
      <button :class="{ on: commentsOpen }" title="コメント" data-testid="comments-toggle" @click="commentsOpen = !commentsOpen">💬<span v-if="openThreads" class="badge">{{ openThreads }}</span><span class="lbl"> コメント</span></button>
      <button title="版履歴" data-testid="history" @click="history = true">🕘<span class="lbl"> 履歴</span></button>
      <span class="menu-wrap">
        <button title="書き出し" data-testid="export" @click="exportMenu = !exportMenu">⤓<span class="lbl"> 書き出し</span></button>
        <div v-if="exportMenu" class="pop" role="menu" @mouseleave="exportMenu = false">
          <button role="menuitem" data-testid="export-md" @click="exportMarkdown">
            <span class="pi">📝</span><span class="pt">Markdown</span><small>このページを .md で保存</small>
          </button>
          <button role="menuitem" data-testid="export-zip" @click="exportTree">
            <span class="pi">🗜</span><span class="pt">ZIP</span><small>子ページ・添付も含めて保存</small>
          </button>
          <button role="menuitem" data-testid="export-pdf" @click="printPage">
            <span class="pi">🖨</span><span class="pt">印刷 / PDF</span><small>このページをブラウザの印刷画面で</small>
          </button>
          <a role="menuitem" class="pop-a" :href="`/print/w/${wid}/p/${pid}?children=1&toc=1`" target="_blank" rel="noopener" data-testid="export-pdf-tree" @click="exportMenu = false">
            <span class="pi">📚</span><span class="pt">PDF（子ページも含めて）</span><small>目次付きで1つにまとめる</small>
          </a>
          <button role="menuitem" data-testid="duplicate" @click="exportMenu = false; duplicateTarget = page">
            <span class="pi">⧉</span><span class="pt">複製</span><small>子ページ・添付も含めてコピーを作る（別のワークスペースへも）</small>
          </button>
        </div>
      </span>
      <button v-if="canEdit" title="このページのスタイル（カスタムCSS）" data-testid="page-css" @click="cssOpen = true">🎨<span class="lbl"> スタイル</span></button>
      <button v-if="canEdit" :class="{ on: page.fullWidth }" :title="page.fullWidth ? '標準の幅に戻す' : '全幅で表示する（このページを見るすべての人に）'" data-testid="full-width" @click="toggleFullWidth">↔<span class="lbl">{{ page.fullWidth ? ' 標準の幅' : ' 全幅' }}</span></button>
      <button v-if="canEdit || page.locked" :class="{ on: page.locked }" :title="page.locked ? '保護中（編集できる人が限られています）' : 'ページを保護する（編集できる人を限る）'" data-testid="lock" @click="lockOpen = true">🔒<span class="lbl">{{ page.locked ? ' 保護中' : ' 保護' }}</span></button>
      <button v-if="canEdit" title="共有設定" data-testid="share" @click="sharing = true">👥<span class="lbl"> 共有</span></button>
      <template v-if="canEdit">
        <button v-if="!editing" class="primary" title="編集する（E）" data-testid="edit" @click="setMode('edit')">✏️ 編集</button>
        <button v-else class="primary" title="編集を終えて表示モードに戻る（変更は自動で保存されています）" data-testid="done" @click="setMode('view')">完了</button>
      </template>
    </div>
    <nav v-if="crumbs.length" class="crumbs" aria-label="ページの階層" data-testid="breadcrumbs">
      <router-link :to="`/w/${wid}`" class="crumb ws">{{ store.current?.name ?? '' }}</router-link>
      <template v-for="c in crumbs" :key="c.id">
        <span class="sep">›</span>
        <router-link :to="`/w/${wid}/p/${c.id}`" class="crumb">{{ c.icon ?? (c.database ? '📊' : '📄') }} {{ c.title || '無題' }}</router-link>
      </template>
    </nav>
    <div class="head">
      <div class="icon-wrap">
        <button
          v-if="page.icon || editing"
          class="page-icon"
          :class="{ empty: !page.icon }"
          :disabled="!editing"
          :title="editing ? 'アイコンを変更' : undefined"
          data-testid="page-icon"
          @click="picking = !picking"
        >{{ page.icon ?? '＋ アイコンを追加' }}</button>
        <IconPicker v-if="picking" @select="pickIcon" @close="picking = false" />
      </div>
      <input
        id="page-title"
        v-model="title"
        class="title"
        placeholder="無題"
        :readonly="!editing"
        @input="onTitle"
      />
      <div v-if="page.locked" class="lock-banner" data-testid="lock-banner">
        🔒 このページは保護されています<template v-if="lock?.reason">: {{ lock.reason }}</template>
        <span class="muted">（{{ lock?.canEdit ? 'あなたは編集できます' : '編集できるのは、保護した人・所有者・指定された人だけです' }}）</span>
      </div>
      <div v-if="page.status || page.ownerName || editing" class="meta-row" data-testid="page-meta">
        <button class="meta-chip" :class="['st', page.status ?? 'none']" :disabled="!canEdit" :title="canEdit ? '状態を変更' : undefined" data-testid="page-status" @click="pickStatus">
          📌 {{ page.status ? STATUS_LABEL[page.status] : '状態を設定' }}
        </button>
        <button class="meta-chip" :disabled="!canEdit" :title="canEdit ? '所有者を変更' : undefined" data-testid="page-owner" @click="pickOwner">
          👤 {{ page.ownerName ? `所有者: ${page.ownerName}` : '所有者を設定' }}
        </button>
        <button v-if="editing && !isDatabase" class="meta-chip" title="子ページを項目として、表・ボード・カレンダーで見られるようにする" data-testid="make-database" @click="makeDatabase">
          📊 データベースにする
        </button>
      </div>
      <div v-if="labels.length || editing" class="labels" data-testid="labels">
        <span v-for="l in labels" :key="l" class="label">
          <button class="label-name" title="このラベルのページを一覧" @click="store.showLabel(l)">🏷 {{ l }}</button>
          <button v-if="editing" class="label-x" title="ラベルを外す" @click="removeLabel(l)">×</button>
        </span>
        <button v-if="editing" class="label-add" data-testid="label-add" @click="addLabel">＋ ラベル</button>
      </div>
      <RowProps v-if="inDatabase" :page-id="page.id" :wid="wid" />
    </div>
    <DatabaseView v-if="isDatabase" :key="page.id" :page-id="page.id" :wid="wid" />
    <Editor
      ref="editorRef"
      :page-id="page.id"
      :editable="editing"
      :user-name="store.me?.name ?? 'anonymous'"
      @role="onRole"
      @presence="(p) => (people = p)"
      @comment="onSelectionComment"
      @comment-click="onCommentClick"
    />
    <CommentsPanel
      v-if="commentsOpen"
      :page-id="page.id"
      :comments="comments"
      :can-edit="canEdit"
      :editor="editorRef?.editor ?? null"
      :draft="commentDraft"
      :focus="commentFocus"
      @close="commentsOpen = false; commentDraft = null"
      @reload="loadComments"
      @clear-draft="commentDraft = null"
    />
    <section v-if="backlinks.length" class="backlinks" data-testid="backlinks">
      <h3>↩ このページへのリンク（{{ backlinks.length }}）</h3>
      <ul>
        <li v-for="b in backlinks" :key="b.id">
          <router-link :to="`/w/${b.workspaceId}/p/${b.id}`">{{ b.icon ?? '📄' }} {{ b.title || '無題' }}</router-link>
        </li>
      </ul>
    </section>
    <LockDialog v-if="lockOpen" :page-id="page.id" :title="page.title" :owner-name="page.ownerName" @close="lockOpen = false" @changed="onLockChanged" />
    <CssDialog v-if="cssOpen" kind="page" :id="page.id" :title="page.title" @preview="(c) => (cssPreview = c)" @close="onCssClosed" />
    <ShareDialog v-if="sharing && canEdit" kind="page" :id="page.id" :title="page.title" @close="onShareClosed" />
    <HistoryPanel
      v-if="history && editorRef"
      :page-id="page.id"
      :can-edit="canEdit"
      :get-state="editorRef.getState"
      :apply-content="editorRef.applyContent"
      @close="history = false"
    />
  </article>
  <AccessRequestPanel v-else-if="denied" :pid="pid" />
  <p v-else class="muted" style="padding: 3rem">ページを読み込み中…</p>
</template>

<style scoped>
.page { max-width: 860px; margin: 0 auto; padding: 1.2rem 2.4rem 3rem; }
.toolbar { display: flex; justify-content: flex-end; align-items: center; gap: .4rem; margin-bottom: 1.2rem; }
.toolbar > * { flex: none; white-space: nowrap; }
.toolbar button { font-size: .82rem; border-color: transparent; color: var(--muted); white-space: nowrap; }
/* 幅が足りないときはアイコンだけ（それでも入らなければ折り返す） */
.toolbar.measuring { justify-content: flex-start; }
.toolbar.tight { gap: .1rem; }
.toolbar.tight button:not(.primary) { padding-left: 5px; padding-right: 5px; font-size: .78rem; }
.toolbar.compact { gap: .1rem; flex-wrap: wrap; }
.toolbar.compact .lbl { display: none; }
.toolbar.compact button:not(.primary) { padding: 2px 6px; font-size: .9rem; }
.toolbar button:hover { color: var(--fg); }
.toolbar button.on { color: var(--accent); }
.toolbar button.primary { color: #fff; background: var(--accent); border-color: var(--accent); padding: 2px 12px; }
.toolbar button.primary:hover { filter: brightness(1.1); }
.toolbar > .mode-label { flex: 1 0 auto; font-size: .78rem; color: var(--accent); }
.head { margin-bottom: 1rem; }
.crumbs { display: flex; flex-wrap: wrap; align-items: center; gap: .15rem .35rem; font-size: .78rem; color: var(--muted); margin: -.6rem 0 .6rem; }
.crumb { color: var(--muted); text-decoration: none; max-width: 16rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; padding: 1px 4px; border-radius: 4px; }
.crumb:hover { color: var(--fg); background: var(--hover); }
.crumbs .sep { opacity: .6; }
.icon-wrap { position: relative; display: inline-block; }
.page-icon { font-size: 2.8rem; line-height: 1.2; border: none; padding: 0 .1em; border-radius: 8px; }
.page-icon:disabled { cursor: default; background: transparent; }
.page-icon.empty { font-size: .85rem; color: var(--muted); padding: 2px 6px; opacity: .7; }
.page-icon.empty:hover { opacity: 1; }
.title { width: 100%; border: none; outline: none; background: transparent; font-size: 2.2rem; font-weight: 700; display: block; }
.menu-wrap { position: relative; }
.badge { display: inline-block; min-width: 15px; height: 15px; margin-left: 2px; border-radius: 8px; background: var(--accent); color: #fff; font-size: 10px; line-height: 15px; text-align: center; vertical-align: 2px; }
.pop { position: absolute; right: 0; top: calc(100% + 4px); z-index: 20; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 4px; box-shadow: 0 8px 24px rgba(0,0,0,.18); display: flex; flex-direction: column; width: max-content; max-width: calc(100vw - 24px); }
.toolbar .pop button { display: grid; grid-template-columns: 1.6em auto; grid-template-rows: auto auto; column-gap: .3rem; text-align: left; color: var(--fg); padding: .4rem .7rem .4rem .5rem; border-radius: 6px; white-space: nowrap; }
.toolbar .pop .pi { grid-row: 1 / 3; align-self: center; font-size: 1.05rem; }
.toolbar .pop .pt { font-weight: 600; font-size: .85rem; }
.toolbar .pop small { color: var(--muted); font-size: .72rem; }
.toolbar .pop button:hover, .toolbar .pop .pop-a:hover { background: var(--hover); }
.toolbar .pop .pop-a { display: grid; grid-template-columns: 1.6em auto; grid-template-rows: auto auto; column-gap: .3rem; text-align: left; color: var(--fg); padding: .4rem .7rem .4rem .5rem; border-radius: 6px; white-space: nowrap; text-decoration: none; }
/* ボタンの右側に開く（左に開くとサイドメニューに隠れる） */
.watch-pop { left: 0; right: auto; padding: .5rem .7rem; gap: .3rem; font-size: .84rem; width: 17rem; white-space: normal; }
.watch-pop label { display: flex; align-items: center; gap: .4rem; cursor: pointer; }
.wp-title { margin: 0 0 .1rem; font-weight: 600; font-size: .8rem; color: var(--muted); }
.wp-note { margin: .2rem 0 0; font-size: .72rem; color: var(--muted); line-height: 1.5; }
.toolbar .watch-pop .wp-stop { align-self: flex-start; border: 1px solid var(--line); color: var(--fg); padding: 1px 8px; margin-top: .2rem; }
.people { display: inline-flex; margin-right: .4rem; }
.avatar { position: relative; width: 24px; height: 24px; border-radius: 50%; color: #fff; font-size: .72rem; font-weight: 700; display: inline-grid; place-items: center; margin-left: -6px; border: 2px solid var(--bg); }
.avatar:first-child { margin-left: 0; }
.avatar.more { background: var(--hover); color: var(--muted); }
.avatar .pen { position: absolute; right: -4px; bottom: -4px; font-size: .6rem; background: var(--bg); color: var(--fg); border-radius: 50%; width: 12px; height: 12px; line-height: 12px; text-align: center; }
.lock-banner { margin-top: .5rem; font-size: .82rem; padding: .35rem .6rem; border-radius: 6px; background: rgba(234,179,8,.14); border: 1px solid rgba(234,179,8,.4); }
.lock-banner .muted { font-size: .75rem; }
.meta-row { display: flex; flex-wrap: wrap; gap: .35rem; margin-top: .4rem; }
.meta-chip { font-size: .78rem; border: 1px solid var(--line); border-radius: 6px; padding: 1px 8px; background: none; color: var(--muted); }
.meta-chip:disabled { cursor: default; opacity: 1; }
.meta-chip.st.draft { color: var(--muted); }
.meta-chip.st.review { color: #b45309; border-color: rgba(217,119,6,.5); }
.meta-chip.st.published { color: #16a34a; border-color: rgba(22,163,74,.5); }
.meta-chip.st.deprecated { color: var(--danger); border-color: rgba(220,38,38,.4); text-decoration: line-through; }
.labels { display: flex; flex-wrap: wrap; gap: .35rem; margin-top: .5rem; }
.label { display: inline-flex; align-items: center; border: 1px solid var(--line); border-radius: 999px; font-size: .78rem; overflow: hidden; }
.label button { border: none; background: none; padding: 1px 8px; font-size: inherit; color: var(--muted); }
.label .label-name:hover { color: var(--accent); }
.label .label-x { padding: 1px 6px 1px 0; }
.label-add { border: 1px dashed var(--line); border-radius: 999px; font-size: .78rem; color: var(--muted); padding: 1px 8px; background: none; }
.backlinks { margin-top: 3rem; padding-top: 1rem; border-top: 1px solid var(--line); }
.backlinks h3 { font-size: .9rem; color: var(--muted); margin: 0 0 .4rem; }
.backlinks ul { list-style: none; padding: 0; margin: 0; display: flex; flex-wrap: wrap; gap: .3rem .8rem; }
.backlinks a { color: var(--accent); text-decoration: none; font-size: .9rem; }
.backlinks a:hover { text-decoration: underline; }
@media (max-width: 768px) {
  .page { padding: .8rem 1rem 3rem; }
  .toolbar { gap: .1rem; margin-bottom: .6rem; padding-left: 2.4rem; }
  .toolbar .lbl { display: none; }
  .toolbar button { padding: 2px 6px; font-size: .9rem; }
  .title { font-size: 1.6rem; }
  .page-icon { font-size: 2.2rem; }
  .pop { right: auto; left: 50%; transform: translateX(-50%); }
}
@media print {
  .toolbar, .backlinks, .label-add, .label-x { display: none !important; }
  .page { max-width: none; padding: 0; }
}
</style>
