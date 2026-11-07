import { marked } from 'marked'
import { normalizeForSearch } from '../util/text'

/** ユーザーマニュアル: このフォルダの Markdown（ファイル名の先頭の番号が並び順、残りが章のID）。 */
const files = import.meta.glob('./chapters/*.md', { query: '?raw', import: 'default', eager: true }) as Record<string, string>

export interface Section { id: string; title: string; level: number }
export interface Chapter { id: string; order: number; title: string; icon: string; summary: string; html: string; sections: Section[]; blocks: SearchBlock[] }
/** 検索用の本文の塊。flat: 空白を詰めた本文（抜粋用）、norm / normFlat: それぞれの検索用の正規化 */
interface SearchBlock { section: Section | null; text: string; flat: string; norm: string; normFlat: string }

/** 見出しの文字からアンカーを作る（日本語もそのまま。記号・空白はハイフン） */
export const slug = (s: string) => s.trim().toLowerCase().replace(/[\s　]+/g, '-').replace(/[!-/:-@[-`{-~、。・（）「」『』【】]/g, '').slice(0, 60) || 'section'

function build(path: string, raw: string): Chapter {
  const m = /\/(\d+)-([a-z0-9-]+)\.md$/.exec(path)!
  // 先頭の front matter（--- で囲んだ title / icon / summary）
  const fm = /^---\n([\s\S]*?)\n---\n/.exec(raw)
  const meta: Record<string, string> = {}
  if (fm) for (const line of fm[1].split('\n')) { const i = line.indexOf(':'); if (i > 0) meta[line.slice(0, i).trim()] = line.slice(i + 1).trim() }
  const body = fm ? raw.slice(fm[0].length) : raw
  const sections: Section[] = []
  const used = new Set<string>()
  const renderer = new marked.Renderer()
  renderer.heading = ({ tokens, depth }) => {
    const text = tokens.map((t) => ('text' in t ? t.text : t.raw)).join('')
    let id = slug(text), n = 2
    while (used.has(id)) id = `${slug(text)}-${n++}`
    used.add(id)
    if (depth <= 3) sections.push({ id, title: text.replace(/`/g, ''), level: depth })
    return `<h${depth} id="${id}"><a class="anchor" href="#${id}" aria-hidden="true">#</a>${marked.parseInline(text)}</h${depth}>\n`
  }
  // 手順やアプリ内のリンク: /help/... はそのまま、外部リンクは新しいタブ
  renderer.link = ({ href, text }) => {
    const ext = /^https?:\/\//.test(href)
    return `<a href="${href}"${ext ? ' target="_blank" rel="noopener"' : ''}>${marked.parseInline(text)}</a>`
  }
  const html = marked.parse(body, { gfm: true, async: false, renderer }) as string
  // 検索用: 見出し（見出し3まで）ごとに本文を分ける
  const blocks: { section: Section | null; text: string }[] = [{ section: null, text: '' }]
  let k = 0
  for (const line of body.split('\n')) {
    const h = /^(#{1,6})\s+(.+)$/.exec(line)
    if (h && h[1].length <= 3) {
      const sec = sections[k++] ?? null
      blocks.push({ section: sec && sec.level > 1 ? sec : null, text: h[2] })
      continue
    }
    blocks[blocks.length - 1].text += '\n' + line.replace(/<\/?kbd>/g, '').replace(/-{3,}/g, ' ').replace(/[*`>|]|\[|\]\([^)]*\)/g, ' ')
  }
  return {
    id: m[2], order: Number(m[1]), title: meta.title ?? m[2], icon: meta.icon ?? '📄', summary: meta.summary ?? '',
    html, sections,
    blocks: blocks.filter((b) => b.text.trim()).map((b) => {
      const flat = b.text.replace(/\s+/g, ' ')
      return { ...b, flat, norm: normalizeForSearch(b.text), normFlat: normalizeForSearch(flat) }
    }),
  }
}

export const chapters: Chapter[] = Object.entries(files).map(([p, raw]) => build(p, raw)).sort((a, b) => a.order - b.order)

export interface Hit { chapter: Chapter; section: Section | null; snippet: string }
/** マニュアルの検索（すべての語を含む節。ひらがな/カタカナ・全角/半角の違いは無視） */
export function search(q: string): Hit[] {
  const words = normalizeForSearch(q).split(/\s+/).filter(Boolean)
  if (!words.length) return []
  const hits: Hit[] = []
  for (const c of chapters) {
    for (const b of c.blocks) {
      if (!words.every((w) => b.norm.includes(w))) continue
      const flat = b.flat
      const i = b.normFlat.indexOf(words[0])
      hits.push({ chapter: c, section: b.section, snippet: (i > 30 ? '…' : '') + flat.slice(Math.max(0, i - 30), i + 90).trim() + (i + 90 < flat.length ? '…' : '') })
      if (hits.length >= 50) return hits
    }
  }
  return hits
}
