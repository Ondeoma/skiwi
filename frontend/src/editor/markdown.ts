import { marked } from 'marked'
import { pageHref } from './pageLinks'

/** Tiptap の JSON。 */
interface JNode {
  type: string
  attrs?: Record<string, unknown>
  content?: JNode[]
  text?: string
  marks?: { type: string; attrs?: Record<string, unknown> }[]
}

const toAbsolute = (url: unknown) => {
  const s = String(url ?? '')
  return s.startsWith('/') ? location.origin + s : s
}

/** 書き出しの変換方法（ZIPでは添付・ページへのリンクを相対パスにする）。 */
export interface MarkdownOptions {
  url?: (url: string) => string
  whiteboard?: (attrs: Record<string, unknown>) => string
}
const noBoard = () => '（ホワイトボード）'

// ---------------------------------------------------------------- 書き出し（JSON → Markdown）

function escapeText(s: string): string {
  return s.replace(/([\\`*_[\]#|<>])/g, '\\$1')
}

const indent = (s: string, pad: string) => s.split('\n').map((l, i) => (i === 0 || !l ? l : pad + l)).join('\n')

/** abs: URL の変換（既定は絶対URL）、boardOf: ホワイトボードの書き出し */
function writer(abs: (url: unknown) => string, boardOf: (attrs: Record<string, unknown>) => string) {
  function inline(nodes: JNode[] | undefined): string {
    if (!nodes) return ''
    return nodes.map((n) => {
      switch (n.type) {
        case 'text': {
          let t = n.marks?.some((m) => m.type === 'code') ? '`' + (n.text ?? '') + '`' : escapeText(n.text ?? '')
          for (const m of n.marks ?? []) {
            if (m.type === 'bold') t = `**${t}**`
            else if (m.type === 'italic') t = `*${t}*`
            else if (m.type === 'strike') t = `~~${t}~~`
            else if (m.type === 'underline') t = `<u>${t}</u>`
            else if (m.type === 'highlight') t = `<mark>${t}</mark>`
            else if (m.type === 'link') t = `[${t}](${abs(m.attrs?.href)})`
          }
          return t
        }
        case 'hardBreak': return '  \n'
        case 'pageLink': return `[${escapeText(String(n.attrs?.title || '無題'))}](${abs(pageHref(String(n.attrs?.workspaceId), String(n.attrs?.pageId)))})`
        case 'inlineMath': return `$${n.attrs?.latex ?? ''}$`
        case 'image': return `![${n.attrs?.alt ?? ''}](${abs(n.attrs?.src)})`
        default: return inline(n.content)
      }
    }).join('')
  }

  function block(n: JNode): string {
    const kids = (sep = '\n\n') => (n.content ?? []).map(block).filter((x) => x !== '').join(sep)
    switch (n.type) {
      case 'doc': return kids()
      case 'paragraph': return inline(n.content)
      case 'heading': return `${'#'.repeat(Number(n.attrs?.level ?? 1))} ${inline(n.content)}`
      case 'blockquote': return kids().split('\n').map((l) => `> ${l}`.trimEnd()).join('\n')
      case 'horizontalRule': return '---'
      case 'codeBlock': return '```' + (n.attrs?.language ?? '') + '\n' + (n.content ?? []).map((t) => t.text ?? '').join('') + '\n```'
      case 'mermaid': return '```mermaid\n' + String(n.attrs?.code ?? '').replace(/\n+$/, '') + '\n```'
      case 'blockMath': return `$$\n${n.attrs?.latex ?? ''}\n$$`
      case 'image': return `![${n.attrs?.alt ?? ''}](${abs(n.attrs?.src)})`
      case 'drawio': return n.attrs?.src ? `![${n.attrs?.title || 'draw.io の図'}](${abs(n.attrs.src)})` : n.attrs?.xmlSrc ? `[📎 ${n.attrs?.title || '図'}.drawio](${abs(n.attrs.xmlSrc)})` : ''
      case 'fileBlock': return `[📎 ${n.attrs?.filename ?? 'file'}](${abs(n.attrs?.url)})`
      case 'embed': return `[${n.attrs?.src}](${n.attrs?.src})`
      case 'linkCard': return `[${escapeText(String(n.attrs?.title || n.attrs?.url))}](${n.attrs?.url})`
      case 'databaseBlock': return '（📊 データベース）'
      case 'toc': return ''
      case 'whiteboard': return boardOf(n.attrs ?? {})
      case 'syncedRef': return `[🔗 同期ブロック](${abs(pageHref(String(n.attrs?.workspaceId), String(n.attrs?.pageId)))})`
      case 'bulletList':
        return (n.content ?? []).map((li) => '- ' + indent(block({ ...li, type: 'listBody' }), '  ')).join('\n')
      case 'orderedList': {
        const start = Number(n.attrs?.start ?? 1)
        return (n.content ?? []).map((li, i) => `${start + i}. ` + indent(block({ ...li, type: 'listBody' }), '   ')).join('\n')
      }
      case 'taskList':
        return (n.content ?? []).map((li) => `- [${li.attrs?.checked ? 'x' : ' '}] ` + indent(block({ ...li, type: 'listBody' }), '  ')).join('\n')
      case 'listBody': return (n.content ?? []).map(block).join('\n')
      case 'panel': return kids().split('\n').map((l, i) => (i === 0 ? `> ${n.attrs?.icon ?? 'ℹ️'} ${l}` : `> ${l}`).trimEnd()).join('\n')
      case 'details': {
        const [summary, ...rest] = n.content ?? []
        return `<details>\n<summary>${inline(summary?.content)}</summary>\n\n${rest.map(block).join('\n\n')}\n\n</details>`
      }
      case 'detailsContent': return kids()
      case 'table': {
        const rows = (n.content ?? []).map((r) => (r.content ?? []).map((c) =>
          (c.content ?? []).map(block).join('<br>').replace(/(?<!\\)\|/g, '\\|').replace(/\n/g, ' ')))
        if (!rows.length) return ''
        const w = Math.max(...rows.map((r) => r.length))
        const line = (r: string[]) => `| ${Array.from({ length: w }, (_, i) => r[i] ?? '').join(' | ')} |`
        return [line(rows[0]), `|${' --- |'.repeat(w)}`, ...rows.slice(1).map(line)].join('\n')
      }
      default: return n.content ? kids() : inline([n])
    }
  }

  return block
}

const defaultBlock = writer(toAbsolute, noBoard)

/** 差分表示用: 本文の最上位のブロックごとの Markdown。 */
export function blockUnits(doc: unknown): string[] {
  return ((doc as JNode | null)?.content ?? []).map(defaultBlock).filter((x) => x.trim() !== '').map(readable)
}
/** 差分を読みやすくする: 画像は「🖼 説明」、リンクは文字だけ（↗）、Markdownのエスケープを外す */
function readable(s: string): string {
  return s
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, (_, alt) => `🖼 ${alt || '画像'}`)
    .replace(/\[([^\]]*)\]\([^)]*\)/g, (_, t) => `${t}↗`)
    .replace(/\\([\\`*_[\]#|<>])/g, '$1')
}

/** ページ本文を Markdown にする（タイトルは先頭の見出しとして付ける）。画像・添付・ページへのリンクは絶対URL。 */
export function toMarkdown(doc: unknown, title: string, opts: MarkdownOptions = {}): string {
  const abs = opts.url ? (u: unknown) => opts.url!(toAbsolute(u).replace(location.origin, '') || String(u ?? '')) : toAbsolute
  const block = writer(abs, opts.whiteboard ?? noBoard)
  const body = doc ? block(doc as JNode).replace(/\n{3,}/g, '\n\n').trim() : ''
  return `# ${title || '無題'}\n\n${body}\n`
}

export function download(filename: string, text: string, type = 'text/markdown;charset=utf-8', blob?: Blob) {
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob ?? new Blob([text], { type }))
  a.download = filename.replace(/[\\/:*?"<>|]/g, '_')
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(a.href), 1000)
}

// ---------------------------------------------------------------- 取り込み（Markdown / HTML → エディタ用HTML）

/** 取り込んだ文書を、エディタが解釈できるHTMLに整える。先頭の h1 はページタイトルとして取り出す。 */
export function prepareImport(text: string, filename: string): { title: string; html: string } {
  const isHtml = /\.html?$/i.test(filename) || /^\s*<(!doctype|html)/i.test(text)
  const rawHtml = isHtml ? text : (marked.parse(text, { gfm: true, async: false }) as string)
  const doc = new DOMParser().parseFromString(rawHtml, 'text/html')
  const root = doc.body

  // 実行されるもの・外部を読み込むものは取り除く
  root.querySelectorAll('script, style, link, meta, iframe, object, embed, form').forEach((e) => e.remove())
  root.querySelectorAll('*').forEach((e) => {
    for (const a of [...e.attributes]) if (/^on/i.test(a.name)) e.removeAttribute(a.name)
  })

  // ```mermaid → Mermaid図、$$…$$ → 数式ブロック
  root.querySelectorAll('pre > code').forEach((code) => {
    const lang = [...code.classList].find((c) => c.startsWith('language-'))?.slice(9)
    if (lang === 'mermaid') {
      const div = doc.createElement('div')
      div.setAttribute('data-mermaid', '')
      div.textContent = (code.textContent ?? '').replace(/\n$/, '')
      code.parentElement!.replaceWith(div)
    }
  })
  root.querySelectorAll('p').forEach((p) => {
    const m = /^\s*\$\$([\s\S]+)\$\$\s*$/.exec(p.textContent ?? '')
    if (m && p.children.length === 0) {
      const div = doc.createElement('div')
      div.setAttribute('data-type', 'block-math')
      div.setAttribute('data-latex', m[1].trim())
      p.replaceWith(div)
    }
  })

  // - [ ] / - [x] → ToDoリスト
  root.querySelectorAll('ul').forEach((ul) => {
    const items = [...ul.children].filter((c) => c.tagName === 'LI')
    if (!items.length || !items.every((li) => li.querySelector(':scope > input[type=checkbox], :scope > p > input[type=checkbox]'))) return
    ul.setAttribute('data-type', 'taskList')
    for (const li of items) {
      const box = li.querySelector('input[type=checkbox]') as HTMLInputElement
      li.setAttribute('data-type', 'taskItem')
      li.setAttribute('data-checked', box.hasAttribute('checked') ? 'true' : 'false')
      box.remove()
      if (!li.querySelector(':scope > p')) {
        const p = doc.createElement('p')
        while (li.firstChild && !(li.firstChild instanceof HTMLElement && ['UL', 'OL'].includes(li.firstChild.tagName))) p.appendChild(li.firstChild)
        li.prepend(p)
      }
    }
  })

  let title = ''
  const h1 = root.querySelector('h1')
  if (h1 && root.firstElementChild === h1) { title = h1.textContent?.trim() ?? ''; h1.remove() }
  if (!title && isHtml) title = doc.title.trim()
  if (!title) title = filename.replace(/\.(md|markdown|txt|html?)$/i, '')
  return { title, html: root.innerHTML }
}

