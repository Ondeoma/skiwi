import { domNodeView } from './nodeView'
import { safeLinkUrl } from '../util/url'
import { h, setChildren } from '../ui/dom'

/** URL のカード。情報がなければ取りに行き（編集できる人だけが保存）、更新もできる */
export const LinkCardView = domNodeView((c) => {
  let loading = false
  let imgOk = true
  let iconOk = true
  const attrs = () => c.node().attrs

  async function refresh() {
    if (loading) return
    loading = true
    render()
    try {
      const r = await fetch(`/api/unfurl?url=${encodeURIComponent(String(attrs().url))}`, { credentials: 'same-origin' })
      if (!r.ok) return
      const d = await r.json()
      if (!c.editor.isDestroyed && c.editor.isEditable)
        c.updateAttributes({ title: d.title ?? null, description: d.description ?? null, image: d.image ?? null, siteName: d.siteName ?? null, icon: d.icon ?? null })
    } catch { /* 取れなくても URL のカードとして出す */ } finally { loading = false; render() }
  }
  function open(e: MouseEvent) {
    // 編集中はクリックで選ぶ（⌘/Ctrl+クリックで開く）
    if (c.editor.isEditable && !(e.metaKey || e.ctrlKey)) e.preventDefault()
  }

  const card = h('a', { class: 'link-card', target: '_blank', rel: 'noopener noreferrer', 'data-testid': 'link-card', onclick: open })
  const refreshBtn = h('button', { class: 'lc-refresh', title: '情報を取り直す', contenteditable: 'false', onclick: (e: Event) => { e.stopPropagation(); void refresh() } }, '🔄')
  const dom = h('div', { class: 'link-card-wrap', 'data-drag-handle': '' }, card)

  function render() {
    const a = attrs()
    // 本文の属性は同時編集で好きな値にできるので、http(s) の URL だけを出す
    const href = safeLinkUrl(a.url)
    const image = safeLinkUrl(a.image)
    const icon = safeLinkUrl(a.icon)
    let host = ''
    try { host = new URL(String(a.url)).hostname } catch { /* URL でない */ }
    if (href) card.setAttribute('href', href)
    else card.removeAttribute('href')
    setChildren(card,
      h('div', { class: 'lc-body' },
        h('div', { class: 'lc-title' }, String(a.title || a.url || '')),
        a.description && h('div', { class: 'lc-desc' }, String(a.description)),
        h('div', { class: 'lc-site' },
          icon && iconOk && h('img', { src: icon, alt: '', class: 'lc-icon', referrerpolicy: 'no-referrer', onerror: () => { iconOk = false; render() } }),
          h('span', null, String(a.siteName || host)),
          loading && h('span', { class: 'muted' }, '・読み込み中…'))),
      image && imgOk && h('img', { src: image, alt: '', class: 'lc-image', referrerpolicy: 'no-referrer', loading: 'lazy', onerror: () => { imgOk = false; render() } }))
    if (c.editor.isEditable) { if (!refreshBtn.parentNode) dom.append(refreshBtn) } else refreshBtn.remove()
  }
  if (!attrs().title && !attrs().siteName && c.editor.isEditable) queueMicrotask(() => void refresh())
  return { dom, render }
})
