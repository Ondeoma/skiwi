import { elementFromString, getSchema } from '@tiptap/core'
import { DOMParser as PMDOMParser, Node as PMNode, type Schema } from '@tiptap/pm/model'
import { prosemirrorJSONToYDoc } from '@tiptap/y-tiptap'
import * as Y from 'yjs'
import { unzipSync, zipSync, strToU8 } from 'fflate'
import { api, okOrNull, uploadAttachment, type Page } from '../api'
import { isDrawioPng, isDrawioXml, labelsOf, xmlFromPng } from '../editor/drawioUtil'
import { contentExtensions } from '../editor/extensions'
import { prepareImport, toMarkdown } from '../editor/markdown'
import { parseSnapshot } from '../editor/Whiteboard'
import { router } from '../router'
import { useWorkspaceStore } from '../stores/workspace'
import { alertDialog, alertError } from './dialog'
import { progress } from './progress'
import { pickFiles } from './fileDrop'
import { decodeText } from '../util/text'
import { groupByParent } from './pageTree'
import { base64ToBytes, bytesToBase64 } from '../util/bytes'
import { ymd } from './date'

// ---------------------------------------------------------------- 共通

const TEXT_EXT = /\.(md|markdown|txt|html?)$/i
const IMAGE_EXT: Record<string, string> = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', avif: 'image/avif' }
const MAX_FILES = 500
const MAX_TOTAL = 100 * 1024 * 1024

const ACCEPT = '.md,.markdown,.txt,.html,.htm,.zip,.docx,.csv,text/markdown,text/html,text/plain,text/csv,application/zip,application/vnd.openxmlformats-officedocument.wordprocessingml.document'

/** 本文（HTML）から、サーバーに保存する Yjs 状態・表示用JSON・平文を作る。 */
/** 本文のスキーマ（ページごとに作り直すと重いので、最初に使うときに1回だけ作る） */
let schemaCache: Schema | null = null
const contentSchema = () => (schemaCache ??= getSchema(contentExtensions()))

/** HTML → 本文（generateJSON と同じ変換を、使い回すスキーマで行う） */
function encodeContent(html: string) {
  return encodeJson(PMDOMParser.fromSchema(contentSchema()).parse(elementFromString(html)).toJSON())
}

/** 本文（ProseMirror JSON）から、サーバーに保存する Yjs 状態・表示用JSON・平文を作る。 */
export function encodeJson(json: Record<string, unknown>) {
  const schema = contentSchema()
  const ydoc = prosemirrorJSONToYDoc(schema, json, 'default')
  const node = PMNode.fromJSON(schema, json)
  return { ydoc: bytesToBase64(Y.encodeStateAsUpdate(ydoc)), content: json, text: node.textBetween(0, node.content.size, '\n', '\n') }
}

async function postContent(pageId: string, html: string) {
  await postEncoded(pageId, encodeContent(html))
}

/** 空のページに本文を入れる */
export function postEncoded(pageId: string, body: ReturnType<typeof encodeJson>) {
  return api.importContent(pageId, body)
}

const naturalSort = (a: string, b: string) => a.localeCompare(b, 'ja', { numeric: true })
const dirOf = (path: string) => path.split('/').slice(0, -1).join('/')
function resolvePath(base: string, rel: string): string {
  const parts = (base ? base.split('/') : [])
  for (const seg of decodeURIComponent(rel.split(/[?#]/)[0]).split('/')) {
    if (seg === '' || seg === '.') continue
    if (seg === '..') parts.pop()
    else parts.push(seg)
  }
  return parts.join('/')
}

// ---------------------------------------------------------------- 取り込み

/** path: ページの親子を決めるパス（Notion の ID を外す・Confluence の階層に並べ直したもの）、orig: ファイルの元のパス（リンク・画像の解決用） */
interface Entry { path: string; text: string; orig?: string }
interface TreeNode { name: string; path: string; entry?: Entry; children: Map<string, TreeNode>; page?: Page }

/** ファイル（複数・ZIP）を読み、本文ファイルと画像に分ける。Word（.docx）は HTML にし、CSV（Notion のデータベース）は別に分ける。 */
async function readFiles(files: File[]) {
  const texts: Entry[] = []
  const csvs: Entry[] = []
  const images = new Map<string, { data: Uint8Array; type: string }>()
  let total = 0
  const add = async (path: string, data: Uint8Array, depth = 0) => {
    if (/(^|\/)(__MACOSX|\.)/.test(path)) return
    total += data.length
    if (total > MAX_TOTAL) throw new Error('合計 100MB までです')
    const ext = path.split('.').pop()?.toLowerCase() ?? ''
    if (ext === 'zip' && depth < 2) {
      // Notion の書き出しは ZIP の中に ZIP が入っていることがある
      for (const [p, d] of Object.entries(unzipSync(data))) if (!p.endsWith('/')) await add(p, d, depth + 1)
      return
    }
    if (TEXT_EXT.test(path)) texts.push({ path, text: decodeText(data) })
    else if (ext === 'csv') csvs.push({ path, text: decodeText(data) })
    else if (ext === 'docx') texts.push({ path: path.replace(/\.docx$/i, '.html'), text: await docxToHtml(data) })
    else images.set(path, { data, type: IMAGE_EXT[ext] ?? (ext === 'pdf' ? 'application/pdf' : ext === 'excalidraw' ? 'application/json' : 'application/octet-stream') })
  }
  for (const f of files) await add(f.name, new Uint8Array(await f.arrayBuffer()))
  if (texts.length > MAX_FILES) throw new Error(`一度に取り込めるのは ${MAX_FILES} ページまでです`)
  return { texts, images, csvs }
}

/** Word（Google ドキュメントの .docx 書き出しなど）を HTML に。画像は data: URL で埋め込み、取り込み時にアップロードする */
async function docxToHtml(data: Uint8Array): Promise<string> {
  const mammoth = await import('mammoth')
  const r = await mammoth.convertToHtml({ arrayBuffer: data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) as ArrayBuffer })
  return `<!doctype html><html><body>${r.value}</body></html>`
}

// ---------------------------------------------------------------- Notion・Confluence の書き出しを整える

/** Notion の書き出しのファイル名の末尾の ID（「ページ名 0123…cdef.md」） */
const NOTION_ID = / [0-9a-f]{32}(?=$|\.[a-z]+$)/i
const isNotion = (texts: Entry[], csvs: Entry[]) => [...texts, ...csvs].filter((e) => e.path.split('/').some((s) => NOTION_ID.test(s))).length >= Math.max(1, (texts.length + csvs.length) / 2)
const stripNotion = (path: string) => path.split('/').map((s) => s.replace(NOTION_ID, '')).join('/')

/** Confluence の HTML 書き出し（index.html と「ページ名_数字.html」、attachments/） */
function isConfluence(texts: Entry[]): boolean {
  return texts.some((e) => /(^|\/)index\.html?$/i.test(e.path) && /id="(main-content|breadcrumb-section)"|class="pageSection/.test(e.text) || /Confluence/i.test(e.text.slice(0, 3000)))
    && texts.some((e) => /id="main-content"/.test(e.text))
}

/** Confluence のページ: タイトルと本文（#main-content）だけにする */
function cleanConfluence(html: string): string {
  const doc = new DOMParser().parseFromString(html, 'text/html')
  let title = doc.querySelector('#title-text')?.textContent?.trim() ?? doc.title.trim()
  title = title.replace(/^.*?\s:\s/, '') // 「スペース名 : ページ名」
  const main = doc.querySelector('#main-content') ?? doc.body
  main.querySelectorAll('.confluence-information-macro-icon, .expand-control-icon, script, style').forEach((e) => e.remove())
  return `<h1>${title.replace(/</g, '&lt;')}</h1>${main.innerHTML}`
}

/** Confluence の index.html の入れ子のリストから、ページの親子（ファイル → 親のファイル）を読む */
function confluenceParents(index: string, base: string): Map<string, string | null> {
  const doc = new DOMParser().parseFromString(index, 'text/html')
  const out = new Map<string, string | null>()
  const walkUl = (ul: Element, parent: string | null) => {
    for (const li of [...ul.children].filter((c) => c.tagName === 'LI')) {
      const a = li.querySelector(':scope > a[href]')
      const href = a?.getAttribute('href') ?? ''
      const path = href && !/^[a-z]+:/i.test(href) ? resolvePath(base, href) : null
      if (path) out.set(path, parent)
      for (const sub of [...li.children].filter((c) => c.tagName === 'UL')) walkUl(sub, path ?? parent)
    }
  }
  const top = doc.querySelector('.pageSection ul, #main-content ul, ul')
  if (top) walkUl(top, null)
  return out
}

/** 取り込むファイルの並びを整える（Notion: ID を外す、Confluence: 親子の階層に並べ直す） */
function normalize(texts: Entry[], csvs: Entry[]): { texts: Entry[]; csvs: Entry[]; kind: 'notion' | 'confluence' | null } {
  if (isNotion(texts, csvs)) {
    const fix = (e: Entry): Entry => ({ ...e, orig: e.path, path: stripNotion(e.path) })
    // 「X.csv」と「X_all.csv」がある場合は _all（非表示の列も含む）を使う
    const all = new Set(csvs.filter((c) => /_all\.csv$/i.test(c.path)).map((c) => c.path.replace(/_all\.csv$/i, '.csv')))
    return { texts: texts.map(fix), csvs: csvs.filter((c) => !all.has(c.path)).map((c) => ({ ...fix(c), path: stripNotion(c.path).replace(/_all\.csv$/i, '.csv') })), kind: 'notion' }
  }
  if (isConfluence(texts)) {
    const index = texts.find((e) => /(^|\/)index\.html?$/i.test(e.path))
    const parents = index ? confluenceParents(index.text, dirOf(index.path)) : new Map<string, string | null>()
    const pages = texts.filter((e) => e !== index && /id="main-content"/.test(e.text))
    const nameOf = new Map(pages.map((e) => [e.path, e.path.split('/').pop()!.replace(/\.html?$/i, '')]))
    const virtual = (path: string, seen = new Set<string>()): string => {
      const parent = parents.get(path)
      const name = nameOf.get(path) ?? path
      if (!parent || seen.has(parent) || !nameOf.has(parent)) return name
      seen.add(path)
      return `${virtual(parent, seen)}/${name}`
    }
    return { texts: pages.map((e) => ({ path: `${virtual(e.path)}.html`, orig: e.path, text: cleanConfluence(e.text) })), csvs: [], kind: 'confluence' }
  }
  return { texts, csvs, kind: null }
}

/** パスから親子関係を作る。「A.md」と「A/」、「A/index.md」（README.md）は同じページとして扱う。 */
function buildTree(texts: Entry[]): TreeNode {
  const root: TreeNode = { name: '', path: '', children: new Map() }
  for (const e of texts) {
    const segs = e.path.split('/')
    const file = segs.pop()!
    let cur = root
    for (const s of segs) {
      if (!cur.children.has(s)) cur.children.set(s, { name: s, path: cur.path ? `${cur.path}/${s}` : s, children: new Map() })
      cur = cur.children.get(s)!
    }
    const base = file.replace(TEXT_EXT, '').replace(/\.__db__$/, '')
    if (/^(index|readme)$/i.test(base) && cur !== root) { cur.entry = e; continue }
    const node: TreeNode = cur.children.get(base) ?? { name: base, path: cur.path ? `${cur.path}/${base}` : base, children: new Map() }
    node.entry = e
    cur.children.set(base, node)
  }
  return root
}

/**
 * Markdown / HTML（ConfluenceのHTML書き出しなど）を取り込む。複数ファイル・ZIP（フォルダ構成をページの親子にする）に対応。
 * parentId を省くとワークスペース直下。画像は添付ファイルとしてアップロードし、ファイル間のリンクはページへのリンクにする。
 */
export async function importPage(workspaceId: string, parentId: string | null): Promise<void> {
  const files = await pickFiles(ACCEPT, true)
  if (!files.length) return
  const store = useWorkspaceStore()
  let created: { node: TreeNode; html: string }[] = []
  try {
    const read = await readFiles(files)
    const normalized = normalize(read.texts, read.csvs)
    const { texts, csvs } = normalized
    const images = read.images
    if (!texts.length && !csvs.length) { await alertDialog('取り込める Markdown / HTML / Word / CSV ファイルがありませんでした'); return }
    // CSV（Notion のデータベース）は、同じ名前のフォルダのページにする（フォルダがなければページだけ作る）
    const tree = buildTree([...texts, ...csvs.filter((c) => !texts.some((t) => t.path.replace(TEXT_EXT, '') === c.path.replace(/\.csv$/i, ''))).map((c) => ({ ...c, path: c.path.replace(/\.csv$/i, '.__db__') }))])
    const total = texts.length + [...walk(tree)].filter((n) => !n.entry).length
    progress.value = { label: 'ページを作成中', done: 0, total }

    // 1) ページを作る（親から順に。同じ階層は名前順）
    const byPath = new Map<string, TreeNode>()
    async function create(node: TreeNode, parent: string | null) {
      for (const child of [...node.children.values()].sort((a, b) => naturalSort(a.name, b.name))) {
        const isDb = !!child.entry && /\.__db__$/.test(child.entry.path)
        const prepared = child.entry && !isDb ? prepareImport(child.entry.text, child.entry.path.split('/').pop()!) : null
        const title = prepared?.title || child.name
        child.page = await api.createPage(workspaceId, { parentId: parent, title })
        if (child.entry && !isDb) { byPath.set(child.entry.orig ?? child.entry.path, child); created.push({ node: child, html: prepared!.html }) }
        progress.value = { ...progress.value!, done: progress.value!.done + 1 }
        await create(child, child.page.id)
      }
    }
    await create(tree, parentId)

    // 2) 本文を入れる（画像のアップロード、ファイル間リンクの付け替え）
    progress.value = { label: '本文を取り込み中', done: 0, total: created.length }
    for (const { node, html } of created) {
      const dir = dirOf(node.entry!.orig ?? node.entry!.path)
      const doc = new DOMParser().parseFromString(html, 'text/html')
      for (const img of [...doc.querySelectorAll('img')]) {
        const src = img.getAttribute('src') ?? ''
        // 埋め込まれた画像（Word など）はアップロードする
        const data = /^data:(image\/[\w.+-]+);base64,(.+)$/i.exec(src)
        if (data) {
          const bin = base64ToBytes(data[2])
          const name = `image.${data[1].split('/')[1].replace('jpeg', 'jpg').replace(/\+.*/, '')}`
          const a = await uploadAttachment(node.page!.id, new File([bin as BlobPart], name, { type: data[1] }))
          img.setAttribute('src', a.url)
          continue
        }
        if (/^[a-z]+:|^\/|^data:/i.test(src)) continue
        const imgPath = resolvePath(dir, src)
        const found = images.get(imgPath)
        if (!found) continue
        const name = src.split('/').pop() || 'image'
        // draw.io の図: 図を埋め込んだ PNG（.drawio.png）、または Confluence の draw.io（「図名.png」と、図の XML の「図名」「図名.drawio」）
        const embedded = isDrawioPng(name) ? await xmlFromPng(found.data.slice().buffer as ArrayBuffer).catch(() => null) : null
        const xmlFile = embedded ? null : [imgPath.replace(/\.png$/i, ''), imgPath.replace(/\.png$/i, '.drawio')]
          .filter((p) => p !== imgPath).map((p) => ({ p, f: images.get(p) })).find((x) => x.f && /<mx(file|GraphModel)/.test(new TextDecoder().decode(x.f.data.subarray(0, 4000))))
        if (embedded || xmlFile) {
          const title = decodeURIComponent(name).replace(/\.(drawio|dio)?\.?png$/i, '').replace(/\.$/, '') || '図'
          const png = await uploadAttachment(node.page!.id, new File([found.data as BlobPart], embedded ? name : `${title}.png`, { type: 'image/png' }))
          const xmlText = embedded ?? new TextDecoder().decode(xmlFile!.f!.data)
          const xmlAtt = xmlFile ? await uploadAttachment(node.page!.id, new File([xmlFile.f!.data as BlobPart], `${title}.drawio`, { type: 'application/xml' })) : null
          img.replaceWith(drawioFigure(doc, { src: png.url, xmlSrc: xmlAtt?.url ?? null, title, text: await labelsOf(xmlText) }))
          continue
        }
        const a = await uploadAttachment(node.page!.id, new File([found.data as BlobPart], name, { type: found.type }))
        img.setAttribute('src', a.url)
        if (!img.getAttribute('alt')) img.setAttribute('alt', name)
      }
      for (const link of [...doc.querySelectorAll('a[href]')]) {
        const href = link.getAttribute('href') ?? ''
        if (/^[a-z]+:|^\/|^#/i.test(href)) continue
        const path = resolvePath(dir, href)
        // ほかのページへのリンク
        if (TEXT_EXT.test(path)) {
          // 見つからなければ、Notion のファイル名の ID で探す（ID は書き出し全体で一意）
          const nid = /([0-9a-f]{32})\.\w+$/i.exec(path)?.[1]
          const target = byPath.get(path) ?? (nid ? [...byPath.entries()].find(([k]) => k.includes(nid))?.[1] : undefined)
          if (target?.page) link.setAttribute('href', `/w/${workspaceId}/p/${target.page.id}`)
          continue
        }
        const found = images.get(path)
        if (!found) continue
        // ホワイトボード（Excalidraw のファイル）は、ホワイトボードのブロックに戻す
        if (/\.excalidraw$/i.test(path)) {
          try {
            const scene = JSON.parse(new TextDecoder().decode(found.data))
            const div = doc.createElement('div')
            div.setAttribute('data-whiteboard', '')
            div.setAttribute('data-snapshot', JSON.stringify({ elements: scene.elements ?? [], background: scene.appState?.viewBackgroundColor }))
            const host = link.parentElement?.tagName === 'P' && link.parentElement.textContent?.trim() === link.textContent?.trim() ? link.parentElement : link
            host.replaceWith(div)
          } catch { /* 壊れたファイルはリンクのまま */ }
          continue
        }
        // draw.io の図の XML（.drawio）は、図のブロックにする（最初に編集して保存したときに表示用の PNG ができる）
        if (isDrawioXml(path)) {
          const xmlText = new TextDecoder().decode(found.data)
          if (/<mx(file|GraphModel)/.test(xmlText)) {
            const title = decodeURIComponent(path.split('/').pop() ?? '図').replace(/\.(drawio|dio)$/i, '')
            const a = await uploadAttachment(node.page!.id, new File([found.data as BlobPart], `${title}.drawio`, { type: 'application/xml' }))
            const fig = drawioFigure(doc, { src: null, xmlSrc: a.url, title, text: await labelsOf(xmlText) })
            const host = link.parentElement?.tagName === 'P' && link.parentElement.textContent?.trim() === link.textContent?.trim() ? link.parentElement : link
            host.replaceWith(fig)
            continue
          }
        }
        // そのほかの添付ファイル
        const a = await uploadAttachment(node.page!.id, new File([found.data as BlobPart], path.split('/').pop() || 'file', { type: found.type }))
        link.setAttribute('href', a.url)
      }
      await postContent(node.page!.id, doc.body.innerHTML)
      progress.value = { ...progress.value!, done: progress.value!.done + 1 }
    }

    // 3) CSV（Notion のデータベース）: 同じ名前のページをデータベースにし、列と値を入れる
    for (const c of csvs) {
      const base = c.path.replace(/\.csv$/i, '')
      const node = findNode(tree, base)
      if (node?.page) await importCsvDatabase(node, c.text)
    }
    if (store.currentId === workspaceId) await store.reloadPages()
    const first = [...tree.children.values()].sort((a, b) => naturalSort(a.name, b.name))[0]?.page
    if (first) await router.push(`/w/${workspaceId}/p/${first.id}`)
    const from = normalized.kind === 'notion' ? '（Notion の書き出し）' : normalized.kind === 'confluence' ? '（Confluence の書き出し）' : ''
    if (texts.length > 1 || csvs.length) await alertDialog(`${texts.length} ページ${csvs.length ? `・${csvs.length} データベース` : ''}を取り込みました。${from}`)
  } catch (e) {
    if (store.currentId === workspaceId) await store.reloadPages().catch(() => {})
    await alertDialog(`取り込めませんでした: ${(e as Error).message}${created.length ? '\n（途中まで作成したページは残っています）' : ''}`)
  } finally {
    progress.value = null
    created = []
  }
}

/** draw.io の図のブロック（Drawio ノードの parseHTML の形） */
function drawioFigure(doc: Document, a: { src: string | null; xmlSrc: string | null; title: string; text: string }): HTMLElement {
  const fig = doc.createElement('figure')
  fig.setAttribute('data-drawio', '')
  fig.setAttribute('data-title', a.title)
  fig.setAttribute('data-text', a.text)
  if (a.xmlSrc) fig.setAttribute('data-xml-src', a.xmlSrc)
  const img = doc.createElement('img')
  img.setAttribute('src', a.src ?? '')
  fig.appendChild(img)
  return fig
}

function findNode(tree: TreeNode, path: string): TreeNode | undefined {
  let cur: TreeNode | undefined = tree
  for (const s of path.split('/')) cur = cur?.children.get(s)
  return cur
}

/** Notion のデータベース（CSV）: 列の種類を値から推測してデータベースにし、子ページ（項目）に値を入れる。ページのない行は項目を作る */
async function importCsvDatabase(node: TreeNode, csv: string) {
  const { parseCsv, parseDate, TRUTHY, newId, COLORS } = await import('../database/db')
  const { dbApi } = await import('../api')
  const rows = parseCsv(csv)
  if (rows.length < 1) return
  const [head, ...body] = rows
  const titleCol = 0
  // Notion の日付（「November 7, 2026」「2026/11/07 10:00」「A → B」）
  const notionDate = (s: string) => {
    const first = s.split('→')[0].trim()
    const d = parseDate(first)
    if (d) return d
    const t = Date.parse(first)
    if (!isNaN(t) && /\d{4}/.test(first)) return ymd(new Date(t))
    return null
  }
  type Kind = 'text' | 'number' | 'date' | 'checkbox' | 'select' | 'multi_select' | 'url'
  const guess = (vals: string[]): Kind => {
    const v = vals.map((x) => x.trim()).filter(Boolean)
    if (!v.length) return 'text'
    if (v.every((x) => /^(yes|no|true|false|はい|いいえ)$/i.test(x))) return 'checkbox'
    if (v.every((x) => !isNaN(Number(x.replace(/[,¥$%]/g, ''))))) return 'number'
    if (v.every((x) => notionDate(x))) return 'date'
    if (v.every((x) => /^https?:\/\//.test(x))) return 'url'
    const parts = v.flatMap((x) => x.split(/,\s*/))
    const distinct = new Set(parts)
    if (v.some((x) => x.includes(', ')) && distinct.size <= 40) return 'multi_select'
    if (new Set(v).size <= Math.min(20, Math.max(3, v.length / 2))) return 'select'
    return 'text'
  }
  const cols = head.map((name, i) => {
    if (i === titleCol) return null
    const kind = guess(body.map((r) => r[i] ?? ''))
    const opts = kind === 'select' || kind === 'multi_select'
      ? [...new Set(body.flatMap((r) => (kind === 'multi_select' ? (r[i] ?? '').split(/,\s*/) : [r[i] ?? ''])).map((x) => x.trim()).filter(Boolean))]
        .map((n, k) => ({ id: newId(), name: n, color: COLORS[k % COLORS.length] }))
      : undefined
    return { id: newId(), name: name.trim() || `列${i + 1}`, type: kind, ...(opts ? { options: opts } : {}) }
  })
  const props = cols.filter((c): c is NonNullable<typeof c> => !!c)
  await dbApi.save(node.page!.id, props, [{ id: newId(), name: '表', type: 'table' }])
  const valuesOf = (r: string[]) => {
    const out: Record<string, unknown> = {}
    cols.forEach((c, i) => {
      const x = (r[i] ?? '').trim()
      if (!c || !x) return
      if (c.type === 'number') { const n = Number(x.replace(/[,¥$%]/g, '')); if (Number.isFinite(n)) out[c.id] = n }
      else if (c.type === 'checkbox') out[c.id] = TRUTHY.has(x.toLowerCase()) || /^(yes|true)$/i.test(x)
      else if (c.type === 'date') { const d = notionDate(x); if (d) out[c.id] = d }
      else if (c.type === 'select') out[c.id] = c.options?.find((o) => o.name === x)?.id
      else if (c.type === 'multi_select') out[c.id] = x.split(/,\s*/).map((y) => c.options?.find((o) => o.name === y.trim())?.id).filter(Boolean)
      else out[c.id] = x
    })
    return out
  }
  const kids = [...node.children.values()].filter((k) => k.page)
  const missing: { title: string; values: Record<string, unknown> }[] = []
  for (const r of body) {
    const title = (r[titleCol] ?? '').trim()
    const page = kids.find((k) => k.page!.title === title || k.name === title)?.page
    if (page) await dbApi.setValues(page.id, valuesOf(r)).catch(() => {})
    else missing.push({ title, values: valuesOf(r) })
  }
  if (missing.length) await dbApi.addRows(node.page!.id, missing)
}

function* walk(n: TreeNode): Generator<TreeNode> {
  for (const c of n.children.values()) { yield c; yield* walk(c) }
}

// ---------------------------------------------------------------- ZIPで書き出し

const safeName = (s: string) => (s || '無題').replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').trim().slice(0, 80) || '無題'
const ext = (type: string) => ({ 'image/png': '.png', 'image/jpeg': '.jpg', 'image/gif': '.gif', 'image/webp': '.webp', 'application/pdf': '.pdf' } as Record<string, string>)[type] ?? ''

/**
 * ページと子ページを、フォルダ構成の Markdown として ZIP に書き出す。
 * 添付ファイル・画像は attachments/ に入れて相対リンクにし、ホワイトボードは .excalidraw ファイルとして入れる。
 * currentContent: 開いているページの最新の本文（保存の反映待ちを避けるため）
 */
export async function exportZip(rootId: string, currentContent?: unknown): Promise<void> {
  const store = useWorkspaceStore()
  const all = store.pages
  const root = all.find((p) => p.id === rootId)
  if (!root) return
  const children = groupByParent(all)
  const kids = (id: string) => children.get(id) ?? []

  // ページ → ファイルパス（同じ階層の同名は (2) を付ける）
  const pathOf = new Map<string, string>()
  const assign = (p: Page, dir: string) => {
    const used = new Set([...pathOf.values()].filter((x) => dirOf(x) === dir).map((x) => x.split('/').pop()))
    let base = safeName(p.title), n = 2
    while (used.has(`${base}.md`)) base = `${safeName(p.title)} (${n++})`
    pathOf.set(p.id, dir ? `${dir}/${base}.md` : `${base}.md`)
    for (const c of kids(p.id)) assign(c, dir ? `${dir}/${base}` : base)
  }
  assign(root, '')

  const files: Record<string, Uint8Array> = {}
  const attachments = new Map<string, string>() // 添付ID → zip内パス
  const ids = [...pathOf.keys()]
  progress.value = { label: 'ZIPを作成中', done: 0, total: ids.length }
  try {
    for (const id of ids) {
      const page = all.find((p) => p.id === id)!
      const path = pathOf.get(id)!
      const up = '../'.repeat(path.split('/').length - 1)
      let content: unknown = id === rootId && currentContent ? currentContent : null
      if (!content) {
        content = (await okOrNull(api.pageContent(id)))?.content ?? null
      }
      // 添付ファイルを集める（先に取得しておき、変換時は同期的に相対パスへ置き換える）
      const urls = new Set<string>()
      JSON.stringify(content ?? {}).replace(/\/api\/attachments\/([0-9a-f-]{36})/g, (_, a) => { urls.add(a); return '' })
      for (const aid of urls) {
        if (attachments.has(aid)) continue
        const r = await fetch(`/api/attachments/${aid}`, { credentials: 'same-origin' })
        if (!r.ok) continue
        const blob = await r.blob()
        const name = decodeURIComponent(/filename\*=UTF-8''([^;]+)/.exec(r.headers.get('Content-Disposition') ?? '')?.[1] ?? '') || aid + ext(blob.type)
        const p = `attachments/${aid.slice(0, 8)}-${safeName(name)}`
        files[p] = new Uint8Array(await blob.arrayBuffer())
        attachments.set(aid, p)
      }
      let boards = 0
      const md = toMarkdown(content, page.title, {
        url: (u) => {
          const att = /^\/api\/attachments\/([0-9a-f-]{36})/.exec(u)
          if (att && attachments.has(att[1])) return encodeURI(up + attachments.get(att[1])!)
          const pl = /^\/w\/[0-9a-f-]{36}\/p\/([0-9a-f-]{36})/.exec(u)
          if (pl && pathOf.has(pl[1])) return encodeURI(relative(path, pathOf.get(pl[1])!))
          return u.startsWith('/') ? location.origin + u : u
        },
        whiteboard: (attrs) => {
          const snap = parseSnapshot(attrs.snapshot)
          const name = `${path.replace(/\.md$/, '')}.whiteboard-${++boards}.excalidraw`
          files[name] = strToU8(JSON.stringify({ type: 'excalidraw', version: 2, source: location.origin, elements: snap?.elements ?? [], appState: { viewBackgroundColor: snap?.background ?? '#ffffff' }, files: {} }, null, 2))
          return `[🧩 ホワイトボード（Excalidraw で開けます）](${encodeURI(name.split('/').pop()!)})`
        },
      })
      files[path] = strToU8(md)
      progress.value = { ...progress.value!, done: progress.value!.done + 1 }
    }
    const zip = zipSync(files, { level: 6 })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(new Blob([zip as BlobPart], { type: 'application/zip' }))
    a.download = `${safeName(root.title)}.zip`
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(a.href), 2000)
  } catch (e) {
    await alertError('書き出せませんでした', e)
  } finally {
    progress.value = null
  }
}

/** from のファイルから見た to への相対パス */
function relative(from: string, to: string): string {
  const a = dirOf(from).split('/').filter(Boolean)
  const b = to.split('/')
  let i = 0
  while (i < a.length && i < b.length - 1 && a[i] === b[i]) i++
  return '../'.repeat(a.length - i) + b.slice(i).join('/')
}
