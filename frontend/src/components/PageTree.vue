<script lang="ts">
import { ref as sharedRef } from 'vue'
/** ツリー全体（再帰した各階層）で共有: 開いているメニュー・アイコン選択と表示位置。同時に1つだけ開く。 */
const menuFor = sharedRef<string | null>(null)
const iconFor = sharedRef<string | null>(null)
/** 画面座標（ツリーのスクロール領域で切れないよう fixed で出す） */
const popAt = sharedRef<{ x: number; y: number } | null>(null)
/** ドラッグ中のページと、落とす位置（前・中・後） */
const dragging = sharedRef<string | null>(null)
const dropAt = sharedRef<{ id: string; where: 'before' | 'inside' | 'after' } | null>(null)
/** 範囲選択の起点 */
const anchor = sharedRef<string | null>(null)
</script>

<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useWorkspaceStore, type TreeNode } from '../stores/workspace'
import { alertDialog, chooseDialog, confirmDialog, guarded } from '../ui/dialog'
import IconPicker from '../ui/IconPicker.vue'
import { api, api5, STATUS_LABEL, type Page } from '../api'
import { duplicateTarget } from '../ui/duplicate'
import { selectedPages, toggleSelected } from '../ui/selection'
import { comparePositionThenId } from '../ui/pageTree'
import { useDismiss } from '../ui/composables'
const importPage = (wid: string, parent: string | null) => import('../ui/importer').then((m) => m.importPage(wid, parent))

defineProps<{ nodes: TreeNode[]; wid: string; depth: number }>()
const store = useWorkspaceStore()
const route = useRoute()
const router = useRouter()
const collapsed = ref(new Set<string>())

const renaming = ref<string | null>(null)
const draft = ref('')
const renameInput = ref<HTMLInputElement[] | null>(null)

function toggle(id: string) {
  collapsed.value.has(id) ? collapsed.value.delete(id) : collapsed.value.add(id)
  collapsed.value = new Set(collapsed.value)
}
const guard = (f: () => Promise<unknown>) => guarded(f)
function addChild(parentId: string, wid: string) {
  menuFor.value = null
  return guard(async () => {
    const p = await store.createPage(parentId)
    collapsed.value.delete(parentId)
    router.push(`/w/${wid}/p/${p.id}?edit=1`)
  })
}
async function del(id: string, title: string, wid: string) {
  menuFor.value = null
  // このページへのリンクがあれば知らせる（リンクは「アクセスできないページ」表示になる）
  const refs = await api5.backlinks(id).catch(() => [])
  const warn = refs.length ? `\n\n⚠ このページは他の ${refs.length} ページからリンクされています（${refs.slice(0, 3).map((r) => `「${r.title || '無題'}」`).join('')}${refs.length > 3 ? ' など' : ''}）。` : ''
  const ok = await confirmDialog(`「${title || '無題'}」とその子ページをゴミ箱に移しますか？\nゴミ箱から元に戻せます。${warn}`, { okLabel: 'ゴミ箱に移す', danger: true })
  if (!ok) return
  await guard(async () => {
    const wasOpen = route.params.pid === id || isAncestorOfOpen(id)
    await store.remove(id)
    if (wasOpen) router.push(`/w/${wid}`)
  })
}
/** 開いているページが削除対象の子孫か */
function isAncestorOfOpen(id: string): boolean {
  return store.ancestors(String(route.params.pid)).some((p) => p.id === id)
}
async function startRename(id: string, title: string) {
  menuFor.value = null
  renaming.value = id
  draft.value = title
  await nextTick()
  renameInput.value?.[0]?.focus()
  renameInput.value?.[0]?.select()
}
function commitRename() {
  const id = renaming.value
  if (!id) return
  renaming.value = null
  const t = draft.value.trim()
  const cur = store.byId.get(id)
  if (cur && t !== cur.title) guard(() => store.rename(id, t))
}
function onRenameKey(e: KeyboardEvent) {
  if (e.isComposing) return
  if (e.key === 'Enter') { e.preventDefault(); commitRename() }
  if (e.key === 'Escape') { e.preventDefault(); renaming.value = null }
}
function openIcon(id: string) {
  menuFor.value = null
  iconFor.value = id
}
function rowPos(e: Event, alignRight = false) {
  const r = (e.currentTarget as HTMLElement).getBoundingClientRect()
  return { x: alignRight ? r.right - 180 : r.left, y: r.bottom + 2 }
}
function pickIcon(id: string, icon: string | null) {
  iconFor.value = null
  guard(() => store.setIcon(id, icon))
}
const MENU_W = 180, MENU_H = 200
function openMenuAt(id: string, x: number, y: number) {
  iconFor.value = null
  popAt.value = { x: Math.max(4, Math.min(x, window.innerWidth - MENU_W - 4)), y: Math.max(4, Math.min(y, window.innerHeight - MENU_H - 4)) }
  menuFor.value = id
}
function toggleMenu(e: MouseEvent, id: string) {
  if (menuFor.value === id) { menuFor.value = null; return }
  const at = rowPos(e, true)
  openMenuAt(id, at.x, at.y)
}
/** 右クリック: 編集できるページなら独自メニュー（それ以外はブラウザ標準のメニュー） */
function onContext(e: MouseEvent, id: string, role: string) {
  if (role !== 'edit' || renaming.value === id) return
  e.preventDefault()
  openMenuAt(id, e.clientX, e.clientY)
}
// --- 別のワークスペースへ移動（子ページごと。移動先のワークスペース直下の末尾に置く） ---
async function moveToWorkspace(p: Page) {
  menuFor.value = null
  const targets = store.workspaces.filter((w) => w.role === 'edit' && w.id !== p.workspaceId)
  if (!targets.length) { await alertDialog('移動できるワークスペース（編集権限のあるもの）がありません'); return }
  const to = await chooseDialog(`「${p.title || '無題'}」と子ページを、どのワークスペースへ移しますか？\n移動先の権限が適用されます（このページに個別に付けた共有設定はそのまま残ります）。`,
    targets.map((w) => ({ label: `🗂 ${w.name}`, value: w.id })))
  if (!to) return
  await guard(async () => {
    const wasOpen = route.params.pid === p.id || isAncestorOfOpen(p.id)
    await api.movePage(p.id, { workspaceId: to, placement: 'last' })
    await store.reloadPages()
    if (wasOpen) router.push(`/w/${to}/p/${route.params.pid}`)
  })
}

// --- ドラッグ＆ドロップで並べ替え・移動 -------------------------------------------------
/** candidate が ancestor 自身か、その子孫か */
function isWithin(candidate: string, ancestor: string): boolean {
  return store.ancestors(candidate, true).some((p) => p.id === ancestor)
}
function onDragStart(e: DragEvent, id: string) {
  dragging.value = id
  menuFor.value = null
  e.dataTransfer?.setData('application/x-skiwi-page', id)
  if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
}
function onDragOver(e: DragEvent, target: Page) {
  const id = dragging.value
  if (!id || isWithin(target.id, id)) { dropAt.value = null; return }
  const r = (e.currentTarget as HTMLElement).getBoundingClientRect()
  const y = (e.clientY - r.top) / r.height
  // 編集できないページの中には入れない
  const where = y < 0.28 ? 'before' : y > 0.72 ? 'after' : target.role === 'edit' ? 'inside' : y < 0.5 ? 'before' : 'after'
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'
  dropAt.value = { id: target.id, where }
}
function onDragEnd() { dragging.value = null; dropAt.value = null }
async function onDrop(e: DragEvent, target: Page) {
  e.preventDefault()
  const id = dragging.value
  const at = dropAt.value
  onDragEnd()
  if (!id || !at || at.id !== target.id || isWithin(target.id, id)) return
  let body: { parentId: string | null; afterId?: string; placement?: string }
  if (at.where === 'inside') {
    body = { parentId: target.id, placement: 'last' }
    collapsed.value.delete(target.id)
  } else if (at.where === 'after') {
    body = { parentId: target.parentId, afterId: target.id }
  } else {
    const sibs = store.pages
      .filter((p) => p.parentId === target.parentId && p.workspaceId === target.workspaceId && p.id !== id)
      .sort(comparePositionThenId)
    const prev = sibs[sibs.findIndex((p) => p.id === target.id) - 1]
    body = prev ? { parentId: target.parentId, afterId: prev.id } : { parentId: target.parentId, placement: 'first' }
  }
  await guard(async () => { await api.movePage(id, body); await store.reloadPages() })
}

// --- 複数選択（⌘/Ctrl＋クリックで追加・解除、Shift＋クリックで範囲） ---------------------------
function onTitleClick(e: MouseEvent, id: string) {
  if (e.metaKey || e.ctrlKey) {
    e.preventDefault()
    toggleSelected(id)
    anchor.value = id
  } else if (e.shiftKey) {
    e.preventDefault()
    // 画面に出ている行の並びで、最後に選んだ行（なければ開いているページ）からこの行まで
    const ids = [...document.querySelectorAll<HTMLElement>('[data-testid=tree-row][data-id]')].map((el) => el.dataset.id!)
    const from = ids.indexOf(anchor.value ?? selectedPages.value[selectedPages.value.length - 1] ?? String(route.params.pid ?? ''))
    const to = ids.indexOf(id)
    if (from < 0) { toggleSelected(id); anchor.value = id; return }
    const range = ids.slice(Math.min(from, to), Math.max(from, to) + 1)
    selectedPages.value = [...selectedPages.value, ...range.filter((x) => !selectedPages.value.includes(x))]
  }
  // 普通のクリックはページを開く（選択はそのまま）
}
function startSelect(id: string) {
  menuFor.value = null
  if (!selectedPages.value.includes(id)) toggleSelected(id)
  anchor.value = id
}

// メニューの外をクリック・Escで閉じる
const closeMenu = () => { menuFor.value = null }
useDismiss((t) => !menuFor.value || !!(t as HTMLElement).closest?.('.tree-menu, [data-testid=tree-menu]'), closeMenu, { escape: closeMenu })
</script>

<template>
  <ul class="list">
    <li v-for="n in nodes" :key="n.page.id">
      <div
        class="row"
        :class="{
          active: route.params.pid === n.page.id,
          pinned: menuFor === n.page.id || iconFor === n.page.id,
          dragging: dragging === n.page.id,
          selected: selectedPages.includes(n.page.id),
          [`drop-${dropAt?.where}`]: dropAt?.id === n.page.id,
        }"
        :style="{ paddingLeft: depth * 14 + 4 + 'px' }"
        :draggable="n.page.role === 'edit' && renaming !== n.page.id"
        :data-id="n.page.id"
        data-testid="tree-row"
        @contextmenu="onContext($event, n.page.id, n.page.role)"
        @dragstart.stop="onDragStart($event, n.page.id)"
        @dragover.stop="onDragOver($event, n.page)"
        @dragleave="dropAt?.id === n.page.id && ($event.currentTarget as HTMLElement).contains($event.relatedTarget as Node) === false && (dropAt = null)"
        @drop.stop="onDrop($event, n.page)"
        @dragend="onDragEnd"
      >
        <input
          v-if="selectedPages.length"
          type="checkbox"
          class="sel"
          :checked="selectedPages.includes(n.page.id)"
          :aria-label="`「${n.page.title || '無題'}」を選択`"
          data-testid="tree-select"
          @change="toggleSelected(n.page.id); anchor = n.page.id"
        />
        <button class="twisty" :style="{ visibility: n.children.length ? 'visible' : 'hidden' }" @click="toggle(n.page.id)">
          {{ collapsed.has(n.page.id) ? '▸' : '▾' }}
        </button>
        <template v-if="renaming === n.page.id">
          <span class="icon">{{ n.page.icon ?? '📄' }}</span>
          <input
            ref="renameInput"
            v-model="draft"
            class="rename"
            placeholder="無題"
            data-testid="tree-rename"
            @keydown="onRenameKey"
            @blur="commitRename"
          />
        </template>
        <router-link
          v-else
          :to="`/w/${wid}/p/${n.page.id}`"
          draggable="false"
          class="title"
          @click="onTitleClick($event, n.page.id)"
          @dblclick.prevent="n.page.role === 'edit' && startRename(n.page.id, n.page.title)"
        >
          <span class="icon">{{ n.page.icon ?? (n.page.database ? '📊' : '📄') }}</span>{{ n.page.title || '無題' }}
          <span v-if="n.page.status && n.page.status !== 'published'" class="st" :class="n.page.status" data-testid="tree-status">{{ STATUS_LABEL[n.page.status] }}</span>
          <span v-if="n.page.locked" class="pub" title="保護中" data-testid="tree-locked">🔒</span>
          <span v-if="n.page.published" class="pub" title="ゲストに公開中" data-testid="tree-published">🌐</span>
        </router-link>
        <span v-if="n.page.role === 'edit' && renaming !== n.page.id" class="actions">
          <button title="子ページを追加" data-testid="tree-add" @click="addChild(n.page.id, wid)">＋</button>
          <button title="その他の操作（右クリックでも開けます）" data-testid="tree-menu" @click="toggleMenu($event, n.page.id)">⋯</button>
        </span>
        <div v-if="menuFor === n.page.id" class="menu tree-menu" role="menu" :style="{ left: popAt!.x + 'px', top: popAt!.y + 'px' }" @contextmenu.prevent>
          <button role="menuitem" @click="startRename(n.page.id, n.page.title)">✏️ 名前を変更</button>
          <button role="menuitem" @click="openIcon(n.page.id)">😀 アイコンを変更</button>
          <button role="menuitem" @click="addChild(n.page.id, wid)">＋ 子ページを追加</button>
          <button role="menuitem" data-testid="tree-duplicate" @click="menuFor = null; duplicateTarget = n.page">⧉ 複製…</button>
          <button role="menuitem" data-testid="tree-move-ws" @click="moveToWorkspace(n.page)">🗂 別のワークスペースへ移動…</button>
          <button role="menuitem" data-testid="tree-select-start" @click="startSelect(n.page.id)">☑ 選択（まとめて操作）</button>
          <button role="menuitem" @click="menuFor = null; importPage(wid, n.page.id)">⤒ 子ページとして取り込む（Markdown・Word・Notion・Confluence など）</button>
          <hr />
          <button role="menuitem" class="danger" data-testid="tree-delete" @click="del(n.page.id, n.page.title, wid)">🗑 削除</button>
        </div>
        <IconPicker v-if="iconFor === n.page.id" :at="popAt" @select="(i) => pickIcon(n.page.id, i)" @close="iconFor = null" />
      </div>
      <PageTree v-if="n.children.length && !collapsed.has(n.page.id)" :nodes="n.children" :wid="wid" :depth="depth + 1" />
    </li>
  </ul>
</template>

<style scoped>
.list { list-style: none; margin: 0; padding: 0; }
.row { display: flex; align-items: center; border-radius: 6px; min-height: 30px; position: relative; }
.row:hover, .row.active, .row.pinned { background: var(--hover); }
.row.dragging { opacity: .4; }
.row.selected { background: color-mix(in srgb, var(--accent) 16%, transparent); }
.sel { margin: 0 0 0 2px; flex: none; accent-color: var(--accent); }
.row.drop-before { box-shadow: inset 0 2px 0 var(--accent); }
.row.drop-after { box-shadow: inset 0 -2px 0 var(--accent); }
.row.drop-inside { background: var(--hover); outline: 2px solid var(--accent); outline-offset: -2px; }
.twisty { border: none; padding: 0 4px; width: 20px; color: var(--muted); }
.title { flex: 1; color: inherit; text-decoration: none; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.icon { margin-right: .35em; }
.st { margin-left: .35em; font-size: .65em; padding: 0 4px; border-radius: 3px; background: var(--hover); color: var(--muted); vertical-align: 1px; }
.st.review { color: #b45309; }
.st.deprecated { text-decoration: line-through; }
.pub { margin-left: .3em; font-size: .75em; opacity: .7; }
.rename { flex: 1; min-width: 0; padding: 1px 4px; border: 1px solid var(--accent); border-radius: 4px; background: var(--bg); margin-right: 4px; }
.actions { display: none; gap: 2px; padding-right: 4px; }
.row:hover .actions, .row.pinned .actions { display: flex; }
.actions button { border: none; padding: 0 4px; }
.menu { position: fixed; z-index: 40; width: 180px; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 4px; box-shadow: 0 8px 24px rgba(0,0,0,.18); display: flex; flex-direction: column; }
.menu button { text-align: left; padding: 4px 8px; white-space: nowrap; }
.menu hr { border: none; border-top: 1px solid var(--line); margin: 3px 0; width: 100%; }
.menu .danger:hover { color: var(--danger); }
</style>
