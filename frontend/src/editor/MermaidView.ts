import { domNodeView } from './nodeView'
import { isDark, onThemeChange } from '../ui/theme'
import { h } from '../ui/dom'

let seq = 0

/** Mermaid図: コードから SVG を描く。クリックでコードを編集する */
export const MermaidView = domNodeView((c) => {
  let lastCode: string | null = null
  let alive = true

  const out = h('div')
  const preview = h('div', { class: 'preview', onclick: () => startEdit() }, out)
  const textarea = h('textarea', { rows: '8', spellcheck: 'false' })
  const pane = h('div', { class: 'editor-pane', onkeydown: (e: Event) => e.stopPropagation() },
    textarea,
    h('div', { class: 'buttons' },
      h('button', { onclick: () => { c.updateAttributes({ code: textarea.value }); pane.remove() } }, '反映'),
      h('button', { onclick: () => pane.remove() }, 'キャンセル')))
  const dom = h('div', { class: 'mermaid-block' }, preview)

  async function draw(code: string) {
    const mermaid = (await import('mermaid')).default
    mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: isDark() ? 'dark' : 'default' })
    try {
      const r = await mermaid.render(`mmd-${Math.random().toString(36).slice(2)}-${++seq}`, code)
      // securityLevel: 'strict' によりMermaidがサニタイズしたSVG
      if (alive) { out.className = ''; out.innerHTML = r.svg }
    } catch (e) {
      if (alive) { out.className = 'error'; out.textContent = `Mermaid の構文エラー: ${(e as Error).message ?? String(e)}` }
    }
  }
  function startEdit() {
    if (!c.editor.isEditable) return
    textarea.value = String(c.node().attrs.code ?? '')
    if (!pane.parentNode) dom.prepend(pane)
  }
  const render = () => {
    dom.classList.toggle('selected', c.selected())
    preview.title = c.editor.isEditable ? 'クリックで編集' : ''
    const code = String(c.node().attrs.code ?? '')
    if (code !== lastCode) { lastCode = code; void draw(code) }
  }
  const stopTheme = onThemeChange(() => { if (lastCode !== null) void draw(lastCode) })
  return { dom, render, destroy: () => { alive = false; stopTheme() } }
})
