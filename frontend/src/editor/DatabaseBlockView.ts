import { domNodeView } from './nodeView'
import { guestContext, onLinksChange } from './pageLinks'
import { host } from '../ui/host'
import { h, setChildren } from '../ui/dom'

/**
 * ページの中のデータベース。表示は Scalactive の部品（host().mountView('database')）が行う。
 * ビューの変更は、編集できる人ならこのブロックに保存し、閲覧のみの人はこの画面だけに反映する
 */
export const DatabaseBlockView = domNodeView((c) => {
  const dom = h('div', { class: 'db-block', 'data-testid': 'database-block' })
  const grip = h('span', { class: 'db-grip', 'data-drag-handle': '', title: 'ドラッグで移動' }, '⋮⋮')
  const mountEl = h('div', { class: 'db-mount' })
  const inner = h('div', { contenteditable: 'false', class: 'db-inner' }, mountEl)
  const guestBox = h('div', { class: 'db-guest' }, '📊 データベース（公開ページでは表示されません）')

  /** 閲覧のみの人が変えたビュー（この画面だけ） */
  let local: string | null = null
  /** いま表示している部品（データベースの ID と、渡したビュー） */
  let mounted: { key: string; views: string | null; unmount: () => void } | null = null
  const unmount = () => { mounted?.unmount(); mounted = null }

  const render = () => {
    if (guestContext()) {
      unmount()
      setChildren(dom, guestBox)
      return
    }
    if (dom.firstChild !== inner) setChildren(dom, inner)
    if (c.editor.isEditable) { if (grip.parentNode !== inner) inner.prepend(grip) } else grip.remove()
    const dbId = c.node().attrs.databaseId as string | null
    const wid = host().currentWorkspaceId()
    const key = dbId && wid ? `${dbId}/${wid}` : ''
    const views = local ?? ((c.node().attrs.views as string | null) || null)
    // ほかの人がビューを変えたとき（自分が渡したものと違う）は作り直す
    if (mounted && mounted.key === key && mounted.views === views) return
    unmount()
    if (!key) return
    const m = { key, views, unmount: () => {} }
    mounted = m
    m.unmount = host().mountView('database', mountEl, {
      pageId: dbId, wid, views,
      onViews: (json: string) => {
        m.views = json
        if (c.editor.isEditable) c.updateAttributes({ views: json })
        else local = json
      },
    })
  }
  const stop = onLinksChange(render)
  return { dom, render, destroy: () => { stop(); unmount() } }
})
