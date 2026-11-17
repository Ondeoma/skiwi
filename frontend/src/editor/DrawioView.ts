import { domNodeView } from './nodeView'
import type { DrawioOptions } from './Drawio'
import { alertDialog, alertError } from '../ui/dialog'
import { dataUrlToFile, drawioEditorSrc, drawioFileName, drawioLabels, drawioTitle, drawioUrl, fetchAttachment, urlToDataUrl } from '../lib'
import { h, setChildren } from '../ui/dom'

/** draw.io の図: ふだんは PNG を表示し、編集は diagrams.net のエディタ（埋め込みモード）を全画面で開いて行う */
export const DrawioView = domNodeView<DrawioOptions>((c) => {
  let overlay: HTMLElement | null = null
  let frame: HTMLIFrameElement | null = null
  let statusEl: HTMLElement | null = null
  let stuckEl: HTMLElement | null = null
  let status = ''
  let origin = ''
  let lastXml = ''
  let exitAfter = false
  let stuckTimer: ReturnType<typeof setTimeout> | undefined

  const post = (msg: object) => frame?.contentWindow?.postMessage(JSON.stringify(msg), origin || '*')
  function setStatus(s: string) {
    status = s
    if (statusEl) { statusEl.textContent = s; statusEl.hidden = !s }
  }

  async function startEdit() {
    if (!c.editor.isEditable || overlay) return
    const upload = c.extension.options.uploadFile
    const base = await drawioUrl()
    if (!base) { await alertDialog('draw.io のエディタが設定されていません（管理者が DRAWIO_URL を空にしています）。'); return }
    if (!upload) { await alertDialog('ここでは図を編集できません。'); return }
    if (overlay) return
    const src = drawioEditorSrc(base)
    origin = new URL(src).origin
    statusEl = h('span', { class: 'st' })
    stuckEl = h('span', { class: 'warn', hidden: true }, 'エディタを読み込めません。インターネットにつながらない環境では、管理者が社内に draw.io を置いて DRAWIO_URL を設定してください。')
    frame = h('iframe', { src, title: 'draw.io', 'data-testid': 'drawio-frame' })
    overlay = h('div', { class: 'drawio-overlay', 'data-testid': 'drawio-overlay', onkeydown: (e: Event) => e.stopPropagation() },
      h('div', { class: 'head' },
        h('span', null, 'draw.io で編集中（「保存して終了」で反映）'),
        statusEl, stuckEl,
        h('button', { type: 'button', 'data-testid': 'drawio-close', onclick: close }, '閉じる（保存しない）')),
      frame)
    document.body.append(overlay)
    setStatus('読み込み中…')
    clearTimeout(stuckTimer)
    stuckTimer = setTimeout(() => { if (status === '読み込み中…' && stuckEl) stuckEl.hidden = false }, 15000)
  }

  async function load() {
    setStatus('')
    if (stuckEl) stuckEl.hidden = true
    const a = c.node().attrs
    try {
      // 取り込んだ図は XML を正とする（表示用の PNG に図が埋め込まれていないことがある）
      if (a.xmlSrc) post({ action: 'load', xml: await (await fetchAttachment(a.xmlSrc)).text(), autosave: 0, title: a.title ?? '' })
      else if (a.src) post({ action: 'load', xmlpng: await urlToDataUrl(a.src), autosave: 0, title: a.title ?? '' })
      else post({ action: 'load', xml: '', autosave: 0 })
    } catch {
      post({ action: 'load', xml: '', autosave: 0 })
      setStatus('元の図を読み込めなかったため、空の図を開きました')
    }
  }

  async function saved(dataUrl: string) {
    const upload = c.extension.options.uploadFile!
    try {
      setStatus('保存中…')
      // 図のページ名（既定の「ページ-1」以外）を題名にする
      const title = drawioTitle(lastXml) ?? c.node().attrs.title
      const url = await upload(dataUrlToFile(dataUrl, drawioFileName(title)))
      const text = await drawioLabels(lastXml)
      c.updateAttributes({ src: url, xmlSrc: null, title, text })
      setStatus('')
      if (exitAfter) close()
      else post({ action: 'status', message: '保存しました', modified: false })
    } catch (e) {
      setStatus('')
      post({ action: 'spinner', show: false })
      await alertError('図を保存できませんでした', e)
    }
  }

  function onMessage(e: MessageEvent) {
    if (!overlay || e.source !== frame?.contentWindow || typeof e.data !== 'string') return
    let msg: { event?: string; xml?: string; exit?: boolean; data?: string; modified?: boolean }
    try { msg = JSON.parse(e.data) } catch { return }
    if (msg.event === 'init') void load()
    else if (msg.event === 'save') {
      lastXml = msg.xml ?? ''
      exitAfter = !!msg.exit
      post({ action: 'export', format: 'xmlpng', xml: lastXml, spinKey: 'saving', scale: 1, border: 10 })
    } else if (msg.event === 'export' && msg.data) void saved(msg.data)
    else if (msg.event === 'exit') close()
  }

  function close() {
    overlay?.remove()
    overlay = frame = statusEl = stuckEl = null
    status = ''
    clearTimeout(stuckTimer)
    c.editor.commands.focus()
  }

  const pic = h('div', { class: 'pic', ondblclick: () => void startEdit() })
  const bar = h('div', { class: 'bar', contenteditable: 'false' }, h('button', { type: 'button', 'data-testid': 'drawio-edit', onclick: () => void startEdit() }, '✏️ 図を編集'))
  const dom = h('div', { class: 'drawio-block', 'data-testid': 'drawio-block' }, pic)

  const render = () => {
    const a = c.node().attrs
    const editable = c.editor.isEditable
    dom.classList.toggle('selected', c.selected())
    pic.title = editable ? 'ダブルクリックで編集' : ''
    const img = pic.querySelector('img')
    if (a.src) {
      const el = img ?? h('img', { draggable: 'false' })
      el.src = a.src
      el.alt = a.title || 'draw.io の図'
      el.style.width = a.width ? `${a.width}px` : ''
      if (!img) setChildren(pic, el)
    } else {
      setChildren(pic, h('div', { class: 'empty' },
        h('span', { class: 'big' }, '⧉'),
        h('span', null, a.xmlSrc ? 'draw.io の図（開くと表示されます）' : '空の draw.io の図')))
    }
    if (editable) { if (!bar.parentNode) dom.append(bar) } else bar.remove()
  }

  window.addEventListener('message', onMessage)
  // 「/draw.io」で入れた直後は、すぐにエディタを開く
  const st = c.editor.storage as unknown as { drawio?: { openNext: boolean } }
  if (st.drawio?.openNext && !c.node().attrs.src && !c.node().attrs.xmlSrc) {
    st.drawio.openNext = false
    queueMicrotask(() => void startEdit())
  }
  return {
    dom, render,
    destroy: () => { window.removeEventListener('message', onMessage); clearTimeout(stuckTimer); overlay?.remove() },
  }
})
