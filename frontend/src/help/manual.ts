import { marked } from 'marked'

/**
 * ユーザーマニュアルの Markdown の読み込みと HTML 化（章の組み立て・目次・検索は Scala の skiwi.web.views.HelpDocs）。
 * files: このフォルダの chapters/*.md（パス → 本文。ファイル名の先頭の番号が並び順、残りが章のID）
 */
export const files = import.meta.glob('./chapters/*.md', { query: '?raw', import: 'default', eager: true }) as Record<string, string>

/**
 * Markdown を HTML にする。見出しのアンカーは heading(見出しの文字, 深さ) が返す ID（呼ばれる順は文書の順）。
 * 手順やアプリ内のリンク: /help/... はそのまま、外部リンクは新しいタブ
 */
export function render(body: string, heading: (text: string, depth: number) => string): string {
  const renderer = new marked.Renderer()
  renderer.heading = ({ tokens, depth }) => {
    const text = tokens.map((t) => ('text' in t ? t.text : t.raw)).join('')
    const id = heading(text, depth)
    return `<h${depth} id="${id}"><a class="anchor" href="#${id}" aria-hidden="true">#</a>${marked.parseInline(text)}</h${depth}>\n`
  }
  renderer.link = ({ href, text }) => {
    const ext = /^https?:\/\//.test(href)
    return `<a href="${href}"${ext ? ' target="_blank" rel="noopener"' : ''}>${marked.parseInline(text)}</a>`
  }
  return marked.parse(body, { gfm: true, async: false, renderer }) as string
}
