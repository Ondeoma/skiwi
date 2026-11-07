<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api5, searchPages, searchWorkspaces, type Page } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import { isMac } from '../util/text'
import { useDebouncedSearch, useEventListener } from './composables'

/**
 * ⌘K / Ctrl+K のクイック切り替え。ページ・ワークスペースへの移動と、よく使う操作をキーボードだけで行う。
 * サイドバー側の操作は window の 'skiwi:command' イベントで頼む（WorkspaceView などが受け取る）。
 */
const store = useWorkspaceStore()
const router = useRouter()
const route = useRoute()

const open = ref(false)
const q = ref('')
const selected = ref(0)
const input = ref<HTMLInputElement | null>(null)
const list = ref<HTMLElement | null>(null)
const recent = ref<{ page: Page; workspaceName: string }[]>([])
const found = ref<Item[]>([])
const loading = ref(false)

interface Item { key: string; group: string; icon: string; title: string; sub?: string; run: () => void }

type CommandName = 'new-page' | 'trash' | 'toggle-side' | 'notifications' | 'search' | 'new-workspace' | 'edit-page' | 'comments'
const send = (cmd: CommandName) => window.dispatchEvent(new CustomEvent('skiwi:command', { detail: cmd }))

function go(path: string) { router.push(path) }
const pageItem = (p: Page, group: string, wsName?: string): Item => ({
  key: `${group}:${p.id}`, group, icon: p.icon ?? '📄', title: p.title || '無題',
  sub: p.workspaceId !== store.currentId ? wsName : undefined, run: () => go(`/w/${p.workspaceId}/p/${p.id}`),
})

const commands = computed<Item[]>(() => {
  const onPage = !!route.params.pid
  const canEditWs = store.current?.role === 'edit'
  const c: (Item & { kw: string })[] = []
  if (canEditWs) c.push({ key: 'c:new', group: '操作', icon: '＋', title: '新しいページを作成', kw: 'new page create あたらしい ぺーじ さくせい', run: () => send('new-page') })
  if (onPage) {
    c.push({ key: 'c:edit', group: '操作', icon: '✏️', title: 'このページを編集', kw: 'edit へんしゅう', run: () => send('edit-page') })
    c.push({ key: 'c:comments', group: '操作', icon: '💬', title: 'コメントを開く', kw: 'comment こめんと', run: () => send('comments') })
  }
  c.push(
    { key: 'c:search', group: '操作', icon: '🔍', title: '検索（絞り込み）', kw: 'search filter けんさく しぼりこみ', run: () => send('search') },
    { key: 'c:notif', group: '操作', icon: '🔔', title: '通知を開く', kw: 'notification つうち', run: () => send('notifications') },
    { key: 'c:side', group: '操作', icon: '☰', title: 'サイドメニューを開閉', kw: 'sidebar menu さいど めにゅー', run: () => send('toggle-side') },
    { key: 'c:trash', group: '操作', icon: '🗑', title: 'ゴミ箱を開く', kw: 'trash restore ごみばこ ふっかつ', run: () => send('trash') },
    { key: 'c:newws', group: '操作', icon: '🗂', title: 'ワークスペースを作成', kw: 'workspace create わーくすぺーす さくせい', run: () => send('new-workspace') },
    { key: 'c:help', group: '操作', icon: '📖', title: '使い方（ユーザーマニュアル）', kw: 'help manual guide つかいかた まにゅある へるぷ', run: () => go('/help') },
    { key: 'c:keys', group: '操作', icon: '⌨️', title: 'キーボードショートカット一覧', sub: '?', kw: 'shortcut keyboard key しょーとかっと きー', run: () => window.dispatchEvent(new Event('skiwi:shortcuts')) },
    { key: 'c:apidocs', group: '操作', icon: '📘', title: 'API の説明書', kw: 'api openapi docs せつめいしょ', run: () => go('/settings/api') },
    { key: 'c:tokens', group: '操作', icon: '🔑', title: 'APIトークン', kw: 'api token とーくん', run: () => go('/settings/tokens') },
  )
  if (store.me?.isAdmin) {
    c.push({ key: 'c:audit', group: '操作', icon: '📜', title: '監査ログ', kw: 'audit log かんさ', run: () => go('/admin/audit') })
    c.push({ key: 'c:shares', group: '操作', icon: '🌐', title: '公開中のページ', kw: 'public share こうかい', run: () => go('/admin/shares') })
  }
  const t = q.value.trim().toLowerCase()
  return t ? c.filter((x) => x.title.toLowerCase().includes(t) || x.kw.includes(t)) : c
})

const items = computed<Item[]>(() => {
  if (!q.value.trim()) {
    const favs = store.favoritePages.map((f) => pageItem(f.page, '★ お気に入り', f.workspaceName))
    const favIds = new Set(store.favoritePages.map((f) => f.page.id))
    const rec = recent.value.filter((r) => !favIds.has(r.page.id)).slice(0, 8).map((r) => pageItem(r.page, '🕘 最近見たページ', r.workspaceName))
    return [...favs, ...rec, ...commands.value]
  }
  return [...found.value, ...commands.value]
})
const groups = computed(() => {
  const out: { group: string; items: Item[] }[] = []
  for (const it of items.value) {
    const g = out[out.length - 1]
    if (g?.group === it.group) g.items.push(it)
    else out.push({ group: it.group, items: [it] })
  }
  return out
})

const search = useDebouncedSearch(150)
watch(q, (v) => {
  selected.value = 0
  const query = v.trim()
  if (!query) { search.cancel(); found.value = []; loading.value = false; return }
  loading.value = true
  search.run(async (latest) => {
    try {
      const [pages, wss] = await Promise.all([searchPages(query), searchWorkspaces(query)])
      if (!latest()) return
      const wsName = (id: string) => store.workspaces.find((w) => w.id === id)?.name
      found.value = [
        ...pages.slice(0, 12).map((r) => pageItem(r.page, 'ページ', wsName(r.page.workspaceId))),
        ...wss.slice(0, 5).map((w): Item => ({ key: `w:${w.id}`, group: 'ワークスペース', icon: '🗂', title: w.name, run: () => go(`/w/${w.id}`) })),
      ]
    } catch { if (latest()) found.value = [] }
    finally { if (latest()) loading.value = false }
  })
})

async function show() {
  open.value = true
  q.value = ''
  found.value = []
  selected.value = 0
  await nextTick()
  input.value?.focus()
  api5.recent('visited').then((r) => (recent.value = r)).catch(() => {})
}
function close() { open.value = false }
function pick(i: number) {
  const it = items.value[i]
  if (!it) return
  close()
  it.run()
}
async function move(d: number) {
  const n = items.value.length
  if (!n) return
  selected.value = (selected.value + d + n) % n
  await nextTick()
  list.value?.querySelector('.active')?.scrollIntoView({ block: 'nearest' })
}
function onInputKey(e: KeyboardEvent) {
  if (e.isComposing) return
  if (e.key === 'ArrowDown') { e.preventDefault(); move(1) }
  else if (e.key === 'ArrowUp') { e.preventDefault(); move(-1) }
  else if (e.key === 'Enter') { e.preventDefault(); pick(selected.value) }
  else if (e.key === 'Escape') { e.preventDefault(); close() }
}
function onGlobalKey(e: KeyboardEvent) {
  if ((isMac ? e.metaKey : e.ctrlKey) && !e.altKey && !e.shiftKey && e.key.toLowerCase() === 'k') {
    // ゲスト表示（ログインなし）では使わない
    if (route.path.startsWith('/s/')) return
    e.preventDefault()
    e.stopPropagation()
    open.value ? close() : show()
  }
}
const onOpenEvent = () => show()
useEventListener(document, 'keydown', onGlobalKey, true)
useEventListener(window, 'skiwi:palette', onOpenEvent)
const indexOf = (it: Item) => items.value.indexOf(it)
</script>

<template>
  <div v-if="open" class="backdrop" @mousedown.self="close">
    <div class="palette" role="dialog" aria-modal="true" aria-label="クイック切り替え" data-testid="palette">
      <div class="head">
        <span class="k">🔍</span>
        <input ref="input" :value="q" placeholder="ページ・ワークスペースへ移動、操作を実行…" data-testid="palette-input"
          @input="q = ($event.target as HTMLInputElement).value" @keydown="onInputKey" />
        <kbd>Esc</kbd>
      </div>
      <div ref="list" class="list">
        <template v-for="g in groups" :key="g.group">
          <div class="group">{{ g.group }}</div>
          <div
            v-for="it in g.items"
            :key="it.key"
            class="item"
            :class="{ active: indexOf(it) === selected }"
            data-testid="palette-item"
            @mouseenter="selected = indexOf(it)"
            @click="pick(indexOf(it))"
          >
            <span class="icon">{{ it.icon }}</span>
            <span class="title">{{ it.title }}</span>
            <span v-if="it.sub" class="sub">🗂 {{ it.sub }}</span>
          </div>
        </template>
        <p v-if="q.trim() && !loading && !items.length" class="empty">「{{ q }}」に一致するものはありません</p>
        <p v-if="loading" class="empty">検索中…</p>
      </div>
      <div class="foot">↑↓ で選択 ・ Enter で開く ・ {{ isMac ? '⌘' : 'Ctrl' }}+K でいつでも開閉</div>
    </div>
  </div>
</template>

<style scoped>
.backdrop { position: fixed; inset: 0; z-index: 980; background: rgba(0,0,0,.35); display: flex; justify-content: center; align-items: flex-start; padding: 12vh 16px 16px; }
.palette { width: min(640px, 100%); max-height: 70vh; display: flex; flex-direction: column; background: var(--bg); border: 1px solid var(--line); border-radius: 12px; box-shadow: 0 20px 60px rgba(0,0,0,.35); overflow: hidden; }
.head { display: flex; align-items: center; gap: .5rem; padding: .7rem .9rem; border-bottom: 1px solid var(--line); }
.head input { flex: 1; border: none; outline: none; background: transparent; font-size: 1rem; color: inherit; }
kbd { font-size: .7rem; color: var(--muted); border: 1px solid var(--line); border-radius: 4px; padding: 0 4px; }
.list { overflow: auto; padding: 4px; }
.group { font-size: .72rem; color: var(--muted); padding: .5rem .6rem .2rem; }
.item { display: flex; align-items: center; gap: .55rem; padding: .4rem .6rem; border-radius: 7px; cursor: pointer; }
.item.active { background: var(--hover); }
.icon { width: 1.4em; text-align: center; flex: none; }
.title { flex: 1; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.sub { font-size: .75rem; color: var(--muted); white-space: nowrap; }
.empty { color: var(--muted); font-size: .85rem; padding: .8rem; text-align: center; }
.foot { font-size: .72rem; color: var(--muted); padding: .45rem .9rem; border-top: 1px solid var(--line); }
</style>
