import type { Mark, Node as PMNode } from '@tiptap/pm/model'
import { Decoration, DecorationSet } from '@tiptap/pm/view'
import type { SkawauFile } from '../types'
import { host } from '../ui/host'
import { skawauChip, skawauPatterns, skawauTitle } from '../lib'
import { eachTextblock, isCode, linkedExtension, linkedInline, linkedWidget, textblockText, type LinkedState } from './linkedDecorations'

/**
 * 本文中の skawau のファイル・フォルダのリンク（{skawau の URL}/view/{ID}・/files/{ID}・/office/{ID}）に、ファイル名を出す（docs/skawau-integration.md）。
 * リンクの文字が URL そのもの（貼り付けたまま）のときは、閲覧中は URL を隠して「📄 ファイル名」を出し、編集中は URL の後ろに名前を添える。
 * 文字を付けたリンク（[資料](URL)）は、文字はそのままで、マウスを乗せるとファイル名が出る。コードの中は対象外。
 */
const skawau = () => host().integration<SkawauFile>('skawau')

/** inLink: リンクの付いた文字（クリックはリンクに任せる）。raw: 見えている文字が URL そのもの */
export interface FileLinkMatch { from: number; to: number; id: string; url: string; raw: boolean; inLink: boolean }

const linkOf = (c: PMNode): Mark | undefined => c.marks.find((m) => m.type.name === 'link')

/** skawau のリンクの位置。id は小文字にそろえる */
export function findFileLinks(doc: PMNode, base: string): FileLinkMatch[] {
  const { href: hrefRe, text: textRe } = skawauPatterns(base)
  const out: FileLinkMatch[] = []
  eachTextblock(doc, (node, start) => {
    // リンクの付いた文字: 同じ href が続く範囲をまとめる
    let pos = start
    let cur: { from: number; to: number; href: string; text: string } | null = null
    const flush = () => {
      const m = cur && hrefRe.exec(cur.href)
      if (cur && m) out.push({ from: cur.from, to: cur.to, id: m[1].toLowerCase(), url: cur.href, raw: cur.text.trim() === cur.href, inLink: true })
      cur = null
    }
    node.forEach((c) => {
      const href = c.isText && !isCode(c) ? (linkOf(c)?.attrs.href as string | undefined) : undefined
      if (href && cur?.href === href) { cur.to = pos + c.nodeSize; cur.text += c.text! }
      else { flush(); if (href) cur = { from: pos, to: pos + c.nodeSize, href, text: c.text! } }
      pos += c.nodeSize
    })
    flush()
    // リンクの付いていない、URL の文字だけのもの
    for (const m of textblockText(node, (c) => isCode(c) || !!linkOf(c)).matchAll(textRe)) {
      out.push({ from: start + m.index!, to: start + m.index! + m[0].length, id: m[1].toLowerCase(), url: m[0], raw: true, inLink: false })
    }
  })
  return out.sort((a, b) => a.from - b.from)
}

function build(doc: PMNode): LinkedState {
  const s = skawau().status()
  if (!s?.enabled || !s.url) return { deco: DecorationSet.empty, keys: [] }
  const found = findFileLinks(doc, s.url)
  const decos: Decoration[] = []
  for (const f of found) {
    const file = s.connected ? skawau().item(f.id) : undefined
    const title = skawauTitle(s.connected, file)
    const named = !!file && f.raw
    const attrs = { class: `skawau-link${named ? ' named' : ''}`, title, 'data-testid': 'skawau-link' }
    decos.push(f.inLink ? Decoration.inline(f.from, f.to, attrs) : linkedInline(f.from, f.to, f.url, attrs))
    if (named) {
      decos.push(linkedWidget(f.to, `skawau:${f.url}:${file.name}\u0000${file.isDir}`, () => skawauChip(f.url, file)))
    }
  }
  return { deco: DecorationSet.create(doc, decos), keys: [...new Set(found.map((f) => f.id))] }
}

export const SkawauFiles = linkedExtension('skawauFiles', skawau, build)
