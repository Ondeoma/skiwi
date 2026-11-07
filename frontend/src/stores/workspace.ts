import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api, api5, type Me, type Page, type Workspace } from '../api'
import { groupByParent } from '../ui/pageTree'

export interface TreeNode { page: Page; children: TreeNode[] }

export const useWorkspaceStore = defineStore('workspace', () => {
  const me = ref<Me | null>(null)
  const workspaces = ref<Workspace[]>([])
  const currentId = ref<string | null>(null)
  const pages = ref<Page[]>([])

  /** サイドバーの表示: ページツリー / 最近 / ラベル。label はラベルで絞り込み中のラベル */
  const sidePanel = ref<'tree' | 'recent' | 'labels' | 'tasks'>('tree')
  const labelFilter = ref<string | null>(null)
  function showLabel(l: string) { labelFilter.value = l; sidePanel.value = 'labels' }

  /** お気に入りのページ（すべてのワークスペース。登録順） */
  const favoritePages = ref<{ page: Page; workspaceName: string }[]>([])
  const isFavoritePage = (id: string) => favoritePages.value.some((f) => f.page.id === id)
  async function loadFavoritePages() { favoritePages.value = await api5.favoritePages() }
  async function toggleFavoritePage(id: string) {
    const on = !isFavoritePage(id)
    await api5.setPageFavorite(id, on)
    await loadFavoritePages()
  }

  /** ID → ページ（一覧から引く） */
  const byId = computed(() => new Map(pages.value.map((p) => [p.id, p])))
  /** id のページの祖先（近い順。includeSelf なら先頭にそのページ自身）。見られない親（一覧にない）でたどるのをやめる */
  function ancestors(id: string, includeSelf = false): Page[] {
    const out: Page[] = []
    const self = byId.value.get(id)
    let cur = includeSelf ? self : self?.parentId ? byId.value.get(self.parentId) : undefined
    // 万一の循環でも止まるように、ページ数を上限にする
    while (cur && out.length <= pages.value.length) {
      out.push(cur)
      cur = cur.parentId ? byId.value.get(cur.parentId) : undefined
    }
    return out
  }

  const current = computed(() => workspaces.value.find((w) => w.id === currentId.value) ?? null)

  /** 親子関係から木を構築。閲覧権限のない親が欠けている場合、その子はルート扱いにする。 */
  const tree = computed<TreeNode[]>(() => {
    const kids = groupByParent(pages.value, { orphansToRoot: true })
    const build = (id: string | null): TreeNode[] => (kids.get(id) ?? []).map((p) => ({ page: p, children: build(p.id) }))
    return build(null)
  })

  async function init() {
    me.value = await api.me()
    workspaces.value = await api.workspaces()
    loadFavoritePages().catch(() => {})
  }

  async function select(id: string) {
    currentId.value = id
    pages.value = await api.pages(id)
  }

  async function reloadPages() {
    if (currentId.value) pages.value = await api.pages(currentId.value)
    if (favoritePages.value.length) loadFavoritePages().catch(() => {})
  }

  async function createWorkspace(name: string) {
    const w = await api.createWorkspace(name)
    workspaces.value.push(w)
    return w
  }

  /** お気に入りの登録・解除。サーバーの並び（お気に入りが先頭、それぞれ名前順）で一覧を取り直す。 */
  async function toggleFavorite(id: string) {
    const w = workspaces.value.find((x) => x.id === id)
    if (!w) return
    const next = !w.favorite
    w.favorite = next
    try {
      await api.setFavorite(id, next)
    } finally {
      workspaces.value = await api.workspaces()
    }
  }

  async function createPage(parentId: string | null) {
    if (!currentId.value) throw new Error('no workspace')
    const p = await api.createPage(currentId.value, { parentId, title: '' })
    pages.value.push(p)
    return p
  }

  function patchLocal(id: string, patch: Partial<Page>) {
    const i = pages.value.findIndex((p) => p.id === id)
    if (i >= 0) pages.value[i] = { ...pages.value[i], ...patch }
  }

  async function rename(id: string, title: string) {
    patchLocal(id, { title })
    await api.updatePage(id, { title })
  }

  async function setIcon(id: string, icon: string | null) {
    patchLocal(id, { icon })
    await api.updatePage(id, { icon })
  }

  async function remove(id: string) {
    await api.deletePage(id)
    await reloadPages()
  }

  async function renameWorkspace(id: string, name: string) {
    await api5.renameWorkspace(id, name)
    workspaces.value = await api.workspaces()
  }
  async function reloadWorkspaces() { workspaces.value = await api.workspaces() }

  return { byId, ancestors, patchLocal, favoritePages, isFavoritePage, loadFavoritePages, toggleFavoritePage, me, workspaces, currentId, current, pages, tree, sidePanel, labelFilter, showLabel, renameWorkspace, reloadWorkspaces, init, select, reloadPages, createWorkspace, toggleFavorite, createPage, rename, setIcon, remove }
})
