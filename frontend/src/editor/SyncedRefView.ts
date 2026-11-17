import { Editor, type JSONContent } from '@tiptap/core'
import { domNodeView } from './nodeView'
import { fetchSyncedSource, findSyncedBlocks, onSyncedRefresh } from '../lib'
import { guestContext, guestHref, onLinksChange, pageHref } from '../lib'
import { handleOnlyDrag } from './dragHandle'
import { host } from '../ui/host'
import { h, setChildren } from '../ui/dom'

const MAX_DEPTH = 2

/** 同期ブロックの埋め込み。元のページの最新の本文（保存済みのもの）を読み取り専用で表示する。 */
export const SyncedRefView = domNodeView<{ depth: number }>((c) => {
  const depth = Number(c.extension.options.depth) || 0
  let state: 'loading' | 'ok' | 'denied' | 'missing' = 'loading'
  let sourceTitle = ''
  let view: Editor | null = null
  let lastJson = ''
  let alive = true

  const href = () => {
    const g = guestContext()
    const pid = String(c.node().attrs.pageId)
    const gp = g?.pages.get(pid)
    return g ? (gp ? guestHref(g.token, gp) : null) : pageHref(String(c.node().attrs.workspaceId), pid)
  }
  function fetchSource() {
    const pid = String(c.node().attrs.pageId)
    const g = guestContext()
    if (g && !g.pages.has(pid)) return Promise.resolve(null)
    return fetchSyncedSource(pid, g?.token ?? null)
  }

  const viewEl = h('div')
  async function refresh() {
    if (depth >= MAX_DEPTH) return
    try {
      const src = await fetchSource()
      if (!alive) return
      if (!src) { state = 'denied'; render(); return }
      sourceTitle = src.title
      const blockId = c.node().attrs.blockId as string | null
      const body = blockId ? findSyncedBlocks(src.content).find((b) => b.id === blockId)?.node : src.content
      if (!body) { state = 'missing'; render(); return }
      const doc: JSONContent = { type: 'doc', content: (body as JSONContent).content ?? [] }
      const json = JSON.stringify(doc)
      if (json === lastJson) return
      lastJson = json
      if (!view) {
        // 埋め込みの中の埋め込みは1段まで（循環を防ぐ）
        const { contentExtensions } = await import('./extensions')
        if (!alive) return
        const exts = contentExtensions().map((e) => (e.name === 'syncedRef' ? (e as unknown as { configure: (o: { depth: number }) => typeof e }).configure({ depth: depth + 1 }) : e))
        view = new Editor({ element: viewEl, editable: false, extensions: exts, content: doc })
      } else view.commands.setContent(doc)
      state = 'ok'
      render()
    } catch {
      if (state === 'loading') { state = 'denied'; render() }
    }
  }

  function open(e: MouseEvent) {
    const to = href()
    if (!to) return
    e.preventDefault()
    const id = c.node().attrs.blockId
    host().navigate(to + (id && !guestContext() ? `#sync-${id}` : ''))
  }

  const label = h('span')
  const src = h('a', { class: 'sr-src', onclick: open })
  const reload = h('button', { title: '最新の内容を読み込む', onclick: () => { lastJson = ''; void refresh() } }, '↻')
  const bar = h('div', { class: 'sr-bar', 'data-drag-handle': '', draggable: 'true', title: 'ここをドラッグするとブロックを移動できます' })
  const body = h('div', { class: 'sr-body' })
  const dom = h('div', { class: 'synced-ref', contenteditable: 'false', 'data-testid': 'synced-ref' }, bar, body)
  const fixDrag = handleOnlyDrag(dom)

  function render() {
    const to = href()
    label.textContent = `🔗 ${c.node().attrs.blockId ? '同期ブロック' : 'ページの埋め込み'}`
    if (to) src.setAttribute('href', to)
    src.textContent = `「${sourceTitle || '無題'}」から ↗`
    setChildren(bar, label, to && state === 'ok' && src, reload)
    const msg = (t: string) => h('p', { class: 'muted' }, t)
    if (depth >= MAX_DEPTH) setChildren(body, h('p', { class: 'muted' }, to && h('a', { href: to, onclick: open }, '埋め込みの中の埋め込みは表示しません（元のページを開く）')))
    else if (state === 'loading') setChildren(body, msg('読み込み中…'))
    else if (state === 'denied') setChildren(body, msg('🔒 元のページを閲覧する権限がないか、削除されています'))
    else if (state === 'missing') setChildren(body, msg('元のページから同期ブロックが削除されています'))
    else if (view && viewEl.parentNode !== body) setChildren(body, viewEl)
    fixDrag()
  }

  void refresh()
  const stopRefresh = onSyncedRefresh(() => void refresh())
  const stopLinks = onLinksChange(render)
  return { dom, render, destroy: () => { alive = false; stopRefresh(); stopLinks(); view?.destroy() } }
})
