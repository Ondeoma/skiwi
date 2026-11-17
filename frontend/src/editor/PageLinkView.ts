import { domNodeView } from './nodeView'
import { guestContext, guestHref, onLinksChange, pageHref, pageInfo } from '../lib'
import { host, pageById } from '../ui/host'
import { h } from '../ui/dom'

type View = { kind: 'ok' | 'denied' | 'loading'; title: string; icon: string | null; href: string | null }

/** このシステムのページへのリンク。最新のページタイトル・アイコンを出す */
export const PageLinkView = domNodeView((c) => {
  const pageId = () => String(c.node().attrs.pageId ?? '')

  /** 表示内容。ゲスト表示では公開範囲のページだけリンクにする。 */
  function view(): View {
    const g = guestContext()
    const attrs = c.node().attrs
    if (g) {
      const p = g.pages.get(pageId())
      return p
        ? { kind: 'ok', title: p.title, icon: p.icon, href: guestHref(g.token, p) }
        : { kind: 'denied', title: '非公開のページ', icon: null, href: null }
    }
    // 開いているワークスペースのページは一覧の情報（名前の変更が即座に反映される）
    const local = pageById(pageId())
    if (local) return { kind: 'ok', title: local.title, icon: local.icon, href: pageHref(local.workspaceId, local.id) }
    const info = pageInfo(pageId())
    if (info.state === 'ok') return { kind: 'ok', title: info.title, icon: info.icon, href: pageHref(info.workspaceId, pageId()) }
    // 開くと閲覧権限を申請できる画面になる
    if (info.state === 'denied') return { kind: 'denied', title: 'アクセスできないページ', icon: null, href: pageHref(String(attrs.workspaceId), pageId()) }
    return { kind: 'loading', title: String(attrs.title || '…'), icon: null, href: pageHref(String(attrs.workspaceId), pageId()) }
  }

  let href: string | null = null
  const icon = h('span', { class: 'pl-icon' })
  const text = document.createTextNode('')
  const a = h('a', { class: 'page-link', 'data-testid': 'page-link', contenteditable: 'false', draggable: 'false', onclick: onClick }, icon, text)
  const dom = h('span', { class: 'page-link-wrap' }, a)

  function onClick(e: MouseEvent) {
    const editable = c.editor.isEditable
    // 編集モードでは選択する（Ctrl/⌘+クリックで開く）
    if (!href || (editable && !(e.metaKey || e.ctrlKey))) { e.preventDefault(); return }
    if (e.metaKey || e.ctrlKey || e.shiftKey) return // 新しいタブ
    e.preventDefault()
    host().navigate(href)
  }

  const render = () => {
    const v = view()
    const editable = c.editor.isEditable
    const guest = !!guestContext()
    const anchor = c.node().attrs.anchor
    // ブロックへのリンクなら #block-ID を付ける
    href = v.href && anchor && !guest ? `${v.href}#block-${anchor}` : v.href
    a.className = `page-link ${v.kind}`
    if (href) a.setAttribute('href', href)
    else a.removeAttribute('href')
    const title = v.kind === 'denied' ? 'リンク先のページが削除されたか、閲覧する権限がありません（開くと閲覧権限を申請できます）' : editable ? 'Ctrl/⌘+クリックで開く' : null
    if (title) a.title = title
    else a.removeAttribute('title')
    icon.textContent = v.kind === 'denied' ? '🔒' : v.icon ?? '📄'
    text.data = v.title || '無題'
    // 編集中は、保存しているタイトル（検索・テキスト出力用）を最新に合わせる
    if (editable && !guest && v.kind === 'ok' && v.title !== c.node().attrs.title) {
      queueMicrotask(() => { if (!c.editor.isDestroyed && c.editor.isEditable) c.updateAttributes({ title: v.title }) })
    }
  }
  const stopLinks = onLinksChange(render)
  const stopPages = host()?.onPagesChange?.(render)
  return { dom, render, destroy: () => { stopLinks(); stopPages?.() } }
})
