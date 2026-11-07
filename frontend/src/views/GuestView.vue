<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { Editor, EditorContent } from '@tiptap/vue-3'
import 'katex/dist/katex.min.css'
import { ApiError, guestApi, formatDate, type GuestPage } from '../api'
import { contentExtensions } from '../editor/extensions'
import { guestContext, guestHref } from '../editor/pageLinks'
import { useCustomCss } from '../ui/customCss'
import SandboxedHtml from '../ui/SandboxedHtml.vue'
import { groupByParent } from '../ui/pageTree'
import { normalizeForSearch } from '../util/text'
import { useDebouncedSearch, useSideMenu, useStoredRef } from '../ui/composables'

/** ゲスト向けの公開ページ（ログイン不要・閲覧のみ）。公開範囲のページを左に一覧する。 */
const props = defineProps<{ token: string; pid?: string }>()

const pages = ref<GuestPage[]>([])
const rootId = ref<string | null>(null)
const current = ref<GuestPage | null>(null)
const error = ref('')
const loading = ref(true)
const editor = shallowRef<Editor | null>(null)
// カスタムCSS（添付ファイルの url() は公開リンク経由に読み替える）
const styles = ref<string[]>([])
// 公開リンクの設定: ヘッダーを出さない / 本文の上下のHTML
const hideHeader = ref(false)
const topHtml = ref<string[]>([])
const bottomHtml = ref<string[]>([])
useCustomCss(styles, () => (u: string) => u.replace(/^\/api\/attachments\//, `/api/public/${props.token}/attachments/`))

editor.value = new Editor({ editable: false, extensions: contentExtensions(), content: '' })
onBeforeUnmount(() => { editor.value?.destroy(); guestContext.value = null })

/** 開いているページ（URLの末尾はページのIDかURL名） */
const pageId = computed(() => {
  if (!props.pid) return rootId.value
  const k = props.pid.toLowerCase()
  return pages.value.find((p) => p.id === k || (p.slug && p.slug === k))?.id ?? (pages.value.length ? props.pid : null)
})
const pageHref = (p: GuestPage) => guestHref(props.token, p)

// --- サイドメニュー（公開範囲のページ一覧） ----------------------------------------------
/** 公開リンクの設定: auto（2ページ以上なら表示）| show | hide */
const sidebarMode = ref<'auto' | 'show' | 'hide'>('auto')
const hasSidebar = computed(() => sidebarMode.value === 'show' || (sidebarMode.value === 'auto' && pages.value.length > 1))

/** 親 → 子の一覧（公開範囲の外にある親はないものとして扱う） */
const children = computed(() => groupByParent(pages.value, { orphansToRoot: true }))
const byId = computed(() => new Map(pages.value.map((p) => [p.id, p])))

// 畳んだ階層（このブラウザに公開リンクごとに保存）。開いているページの祖先は自動で開く
const collapsedKey = () => `skiwi.guestCollapsed.${props.token}`
const collapsed = useStoredRef(collapsedKey, (raw) => {
  try { return new Set<string>(JSON.parse(raw ?? '[]') as string[]) } catch { return new Set<string>() }
}, (v) => JSON.stringify([...v]))
function toggle(id: string) {
  const next = new Set(collapsed.value)
  next.has(id) ? next.delete(id) : next.add(id)
  collapsed.value = next
}
const ancestorsOfCurrent = computed(() => {
  const out = new Set<string>()
  let cur = pageId.value ? byId.value.get(pageId.value) : undefined
  while (cur?.parentId && byId.value.has(cur.parentId)) { out.add(cur.parentId); cur = byId.value.get(cur.parentId) }
  return out
})

// 絞り込み（ひらがな/カタカナ・全角/半角・大文字/小文字を区別しない）。一致したページと、その祖先を出す
const filter = ref('')
const matched = computed(() => {
  const q = normalizeForSearch(filter.value.trim())
  if (!q) return null
  const keep = new Set<string>()
  for (const p of pages.value) {
    if (!normalizeForSearch(p.title || '無題').includes(q)) continue
    let cur: GuestPage | undefined = p
    while (cur) { keep.add(cur.id); cur = cur.parentId ? byId.value.get(cur.parentId) : undefined }
  }
  return keep
})

// 本文の検索（公開範囲のページだけ。サーバーで照合し、一致箇所を含む抜粋を返す）
const hits = ref<{ page: GuestPage; snippet: string | null; titleHit: boolean }[] | null>(null)
const search = useDebouncedSearch(250)
watch(filter, (q) => {
  if (!q.trim()) { search.cancel(); hits.value = null; return }
  search.run(async (latest) => {
    try {
      const r = await guestApi.search(props.token, q.trim())
      if (latest()) hits.value = r.filter((h) => !h.titleHit || h.snippet)
    } catch { if (latest()) hits.value = [] }
  })
})

/** 表示する行（深さ・子の有無・開閉付き） */
const rows = computed(() => {
  const out: { page: GuestPage; depth: number; hasKids: boolean; open: boolean; hit: boolean }[] = []
  const m = matched.value
  const walk = (parent: string | null, depth: number) => {
    for (const p of children.value.get(parent) ?? []) {
      if (m && !m.has(p.id)) continue
      const hasKids = (children.value.get(p.id) ?? []).length > 0
      const open = !!m || !collapsed.value.has(p.id) || ancestorsOfCurrent.value.has(p.id)
      out.push({ page: p, depth, hasKids, open, hit: !!m && normalizeForSearch(p.title || '無題').includes(normalizeForSearch(filter.value.trim())) })
      if (hasKids && open) walk(p.id, depth + 1)
    }
  }
  walk(null, 0)
  return out
})

// メニューの開閉。広い画面では横に置き（開閉をこのブラウザに保存）、狭い画面では本文に重ねて開き、ページを選ぶと閉じる
const { narrow, open: sideOpen } = useSideMenu('skiwi.guestSide', '(max-width: 720px)')
const sideEl = ref<HTMLElement | null>(null)
watch(pageId, async () => {
  if (narrow.value) sideOpen.value = false
  await nextTick()
  sideEl.value?.querySelector('.item.active')?.scrollIntoView({ block: 'nearest' })
})

// 更新履歴（公開日時と変更点のメモ。公開した人が表示する設定にしたとき）
const hasReleases = ref(false)
const releases = ref<{ publishedAt: string; note: string }[] | null>(null)
const releasesOpen = ref(false)
async function openReleases() {
  releasesOpen.value = !releasesOpen.value
  if (releasesOpen.value && !releases.value) {
    try { releases.value = await guestApi.releases(props.token) } catch { releases.value = [] }
  }
}

// パスワード付きのリンク
const needPassword = ref(false)
const password = ref('')
const pwError = ref('')
async function unlock() {
  pwError.value = ''
  try {
    if (await guestApi.unlock(props.token, password.value)) { password.value = ''; await loadTree(props.token) }
    else pwError.value = 'パスワードが違います'
  } catch (e) { pwError.value = (e as Error).message }
}

async function loadTree(token: string) {
  loading.value = true
  error.value = ''
  needPassword.value = false
  try {
    const t = await guestApi.tree(token)
    pages.value = t.pages
    rootId.value = t.rootId
    hideHeader.value = !!t.hideHeader
    sidebarMode.value = t.sidebar ?? 'auto'
    hasReleases.value = !!t.releases
    guestContext.value = { token, pages: new Map(t.pages.map((p) => [p.id, p])) }
  } catch (e) {
    const st = e instanceof ApiError ? e.status : 0
    if (st === 401) needPassword.value = true
    else if (st === 403 && e instanceof ApiError && e.message === 'not_started') {
      const at = (e.body as { startsAt?: string } | undefined)?.startsAt
      error.value = at ? `このページは ${formatDate(at)} から公開されます。` : 'このページはまだ公開されていません。'
    }
    else error.value = st === 410 ? 'このリンクは公開期間が終わりました。' : 'このリンクは無効です。公開が停止されたか、URLが間違っています。'
    loading.value = false
  }
}
watch(() => props.token, loadTree, { immediate: true })

watch([pageId, rootId], async ([id]) => {
  if (!id || error.value || needPassword.value) return
  loading.value = true
  try {
    const r = await guestApi.page(props.token, id)
    current.value = r.page
    styles.value = r.styles ?? []
    topHtml.value = r.top ?? []
    bottomHtml.value = r.bottom ?? []
    editor.value?.commands.setContent((r.content as Record<string, unknown> | null) ?? '')
    document.title = `${r.page.title || '無題'} - skiwi`
    window.scrollTo(0, 0)
  } catch (e) {
    current.value = null
    // 一覧の読み込みと同時に走った場合（パスワード・期限切れ）は、一覧側の案内に任せる
    const st = e instanceof ApiError ? e.status : 0
    if (st !== 401 && st !== 410 && st !== 403) error.value = 'ページが見つかりません。公開範囲外か、削除された可能性があります。'
  } finally {
    loading.value = false
  }
}, { immediate: true })

/** 開閉ブロックのタイトルをクリックで開閉する（閲覧専用なのでタイトルは編集できない） */
function onAreaClick(e: MouseEvent) {
  const summary = (e.target as HTMLElement).closest('summary')
  const toggle = summary?.closest('.details')?.querySelector<HTMLButtonElement>(':scope > button')
  if (toggle) { e.preventDefault(); toggle.click() }
}
</script>

<template>
  <div class="guest" :class="{ 'no-header': hideHeader }">
    <header v-if="!hideHeader" class="bar" data-testid="guest-header">
      <span class="badge" data-testid="guest-badge">🌐 公開ページ（閲覧のみ）</span>
      <a class="login" href="/" title="メンバーはログインすると編集できます">ログイン</a>
    </header>
    <div class="layout" :class="{ single: !hasSidebar || !sideOpen || narrow }">
      <button v-if="hasSidebar && !sideOpen" class="side-open" title="ページの一覧を開く" data-testid="guest-side-open" @click="sideOpen = true">☰<span> ページ一覧</span></button>
      <div v-if="hasSidebar && sideOpen && narrow" class="side-backdrop" @click="sideOpen = false" />
      <nav v-if="hasSidebar && sideOpen" ref="sideEl" class="side" :class="{ overlay: narrow }" aria-label="公開ページの一覧" data-testid="guest-side">
        <div class="side-head">
          <span class="side-title">ページ一覧<small>（{{ pages.length }}）</small></span>
          <button class="side-close" title="一覧を閉じる" data-testid="guest-side-close" @click="sideOpen = false">«</button>
        </div>
        <input v-model="filter" type="search" class="side-filter" placeholder="🔍 ページ名・本文を検索" data-testid="guest-side-filter" />
        <div v-for="r in rows" :key="r.page.id" class="row" :style="{ paddingLeft: `${r.depth * 0.9}rem` }">
          <button v-if="r.hasKids" class="twisty" :title="r.open ? '畳む' : '開く'" :disabled="!!matched" @click="toggle(r.page.id)">{{ r.open ? '▾' : '▸' }}</button>
          <span v-else class="twisty-space" />
          <router-link :to="pageHref(r.page)" class="item" :class="{ active: r.page.id === pageId, hit: r.hit }" data-testid="guest-side-item">
            <span class="ic">{{ r.page.icon ?? '📄' }}</span>{{ r.page.title || '無題' }}
          </router-link>
        </div>
        <p v-if="matched && !rows.length && hits && !hits.length" class="muted small">一致するページはありません</p>
        <div v-if="hits && hits.length" class="hits" data-testid="guest-search">
          <div class="hits-title">本文に一致（{{ hits.length }}件）</div>
          <router-link v-for="h in hits" :key="h.page.id" :to="pageHref(h.page)" class="hit" :class="{ active: h.page.id === pageId }" data-testid="guest-search-hit">
            <span class="hit-t"><span class="ic">{{ h.page.icon ?? '📄' }}</span>{{ h.page.title || '無題' }}</span>
            <!-- サーバーがエスケープ済み（一致箇所の <mark> だけを含む） -->
            <span v-if="h.snippet" class="hit-s" v-html="h.snippet" />
          </router-link>
        </div>
      </nav>
      <main class="page skiwi-page-css">
        <p v-if="error" class="error" data-testid="guest-error">{{ error }}</p>
        <form v-else-if="needPassword" class="pw" data-testid="guest-password" @submit.prevent="unlock">
          <h1>🔒 パスワードが必要です</h1>
          <p class="muted">このページを閲覧するには、公開した人から伝えられたパスワードを入力してください。</p>
          <div class="pw-row">
            <input v-model="password" type="password" autocomplete="off" placeholder="パスワード" autofocus />
            <button type="submit" :disabled="!password">表示する</button>
          </div>
          <p v-if="pwError" class="pw-error">{{ pwError }}</p>
        </form>
        <template v-else>
          <SandboxedHtml v-for="(h, i) in topHtml" :key="`t${i}:${h.length}`" :html="h" label="ページ上部" class="guest-html top" />
          <div v-if="current" class="head">
            <div v-if="current.icon" class="page-icon">{{ current.icon }}</div>
            <h1 class="title" data-testid="guest-title">{{ current.title || '無題' }}</h1>
            <p class="meta">
              最終更新 {{ formatDate(current.updatedAt) }}
              <button v-if="hasReleases" class="rel-btn" data-testid="guest-releases-toggle" @click="openReleases">🕘 更新履歴</button>
              <a class="rel-btn" :href="`/print/s/${token}`" target="_blank" rel="noopener" data-testid="guest-pdf">🖨 PDF</a>
            </p>
            <section v-if="releasesOpen" class="rel" data-testid="guest-releases">
              <p v-if="releases === null" class="muted">読み込み中…</p>
              <p v-else-if="!releases.length" class="muted">更新履歴はまだありません。</p>
              <ul v-else>
                <li v-for="(r, i) in releases" :key="i"><time>{{ formatDate(r.publishedAt) }}</time><div class="rel-note">{{ r.note }}</div></li>
              </ul>
            </section>
          </div>
          <div class="editor-area readonly" :class="{ dim: loading }" @click="onAreaClick">
            <EditorContent v-if="editor" :editor="editor" data-testid="guest-content" />
          </div>
          <SandboxedHtml v-for="(h, i) in bottomHtml" :key="`b${i}:${h.length}`" :html="h" label="ページ下部" class="guest-html bottom" />
        </template>
      </main>
    </div>
  </div>
</template>

<style scoped>
.guest { min-height: 100vh; background: var(--bg); }
.bar { position: sticky; top: 0; z-index: 5; display: flex; align-items: center; justify-content: space-between; padding: .5rem 1rem; border-bottom: 1px solid var(--line); background: var(--side); font-size: .85rem; }
.badge { color: var(--muted); }
.login { color: var(--accent); text-decoration: none; }
.layout { display: grid; grid-template-columns: 270px 1fr; position: relative; }
.layout.single { grid-template-columns: 1fr; }
.guest.no-header .side { top: 0; max-height: 100vh; min-height: 100vh; }
.side-head { display: flex; align-items: center; justify-content: space-between; padding: 0 .3rem .4rem .5rem; }
.side-title { font-size: .78rem; font-weight: 600; color: var(--muted); }
.side-title small { font-weight: 400; margin-left: 2px; }
.side-close { border: none; color: var(--muted); padding: 0 6px; }
.side-open { position: fixed; z-index: 6; left: .7rem; top: 3.2rem; border-radius: 6px; padding: 2px 9px; color: var(--muted); background: var(--bg); font-size: .85rem; }
.guest.no-header .side-open { top: .7rem; }
.side-open:hover { color: var(--fg); }
.side-filter { width: calc(100% - .6rem); margin: 0 .3rem .4rem; box-sizing: border-box; padding: .25rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; font-size: .82rem; }
.row { display: flex; align-items: center; }
.twisty { border: none; padding: 0; width: 18px; flex: none; color: var(--muted); font-size: .75rem; }
.twisty:disabled { opacity: .4; cursor: default; }
.twisty-space { width: 18px; flex: none; }
.ic { margin-right: .3em; }
.item.hit { font-weight: 600; }
.hits { margin-top: .6rem; padding-top: .5rem; border-top: 1px solid var(--line); }
.hits-title { font-size: .74rem; color: var(--muted); font-weight: 600; padding: 0 .5rem .2rem; }
.hit { display: block; padding: .35rem .5rem; border-radius: 6px; color: inherit; text-decoration: none; }
.hit:hover, .hit.active { background: var(--hover); }
.hit-t { display: block; font-size: .86rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.hit-s { display: block; font-size: .74rem; color: var(--muted); line-height: 1.45; overflow-wrap: anywhere; }
.hit-s :deep(mark) { background: rgba(250,204,21,.45); color: inherit; border-radius: 2px; }
.side.overlay { position: fixed; top: 0; bottom: 0; left: 0; z-index: 40; width: min(300px, 86vw); max-height: none; min-height: 0; box-shadow: 8px 0 24px rgba(0,0,0,.25); }
.side-backdrop { position: fixed; inset: 0; z-index: 35; background: rgba(0,0,0,.35); }
.small { font-size: .8rem; padding: 0 .6rem; }
.guest-html.top { margin-bottom: 1rem; }
.guest-html.bottom { margin-top: 2rem; }
.side { position: sticky; top: 41px; align-self: start; max-height: calc(100vh - 41px); overflow: auto; padding: .8rem .4rem; border-right: 1px solid var(--line); background: var(--side); min-height: calc(100vh - 41px); }
.item { flex: 1; min-width: 0; display: block; padding: .3rem .5rem; border-radius: 6px; color: inherit; text-decoration: none; font-size: .9rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.item:hover { background: var(--hover); }
.item.active { background: var(--hover); font-weight: 600; }
.page { max-width: 860px; width: 100%; margin: 0 auto; padding: 2rem 2.4rem 4rem; box-sizing: border-box; }
.page-icon { font-size: 2.8rem; line-height: 1.2; }
.title { font-size: 2.2rem; margin: .2rem 0 .3rem; }
.meta { color: var(--muted); font-size: .8rem; margin: 0 0 1.5rem; }
.editor-area.readonly :deep(.details summary) { cursor: pointer; }
.dim { opacity: .5; }
.rel-btn { text-decoration: none; margin-left: .6rem; border: none; background: none; color: var(--accent); padding: 0; font-size: .8rem; cursor: pointer; }
.rel { margin: -.8rem 0 1.5rem; padding: .6rem .8rem; border: 1px solid var(--line); border-radius: 8px; background: var(--side); font-size: .85rem; max-height: 18rem; overflow: auto; }
.rel ul { list-style: none; margin: 0; padding: 0; }
.rel li { padding: .35rem 0; border-bottom: 1px solid var(--line); }
.rel li:last-child { border-bottom: none; }
.rel time { font-size: .75rem; color: var(--muted); }
.rel-note { white-space: pre-wrap; }
.error { color: var(--muted); padding: 3rem 0; }
.pw { max-width: 26rem; margin: 4rem auto; }
.pw h1 { font-size: 1.3rem; }
.pw-row { display: flex; gap: .5rem; }
.pw-row input { flex: 1; padding: .4rem .6rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; }
.pw-error { color: var(--danger); font-size: .9rem; }
@media (max-width: 720px) {
  .layout { grid-template-columns: 1fr; }
  .page { padding: 1.2rem 1rem 3rem; }
}
</style>
