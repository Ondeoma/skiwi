/**
 * 取り込み・書き出し・複製のうち、JS のライブラリが要るところだけ（段取りは Scala の skiwi.web.content）.
 * Scala からは JsModule.load("io") で使う.
 *   marked（Markdown → HTML）・mammoth（Word → HTML）・fflate（ZIP）・Tiptap のスキーマ / Yjs（本文 → サーバーに保存する形）
 */
import { elementFromString, getSchema } from '@tiptap/core'
import { DOMParser as PMDOMParser, Node as PMNode, type Schema } from '@tiptap/pm/model'
import { prosemirrorJSONToYDoc } from '@tiptap/y-tiptap'
import * as Y from 'yjs'
import { marked } from 'marked'
import { unzipSync, zipSync } from 'fflate'
import { contentExtensions } from '../editor/extensions'
import { toBase64 } from '../lib'

/** 本文のスキーマ（ページごとに作り直すと重いので、最初に使うときに1回だけ作る） */
let schemaCache: Schema | null = null
const contentSchema = () => (schemaCache ??= getSchema(contentExtensions()))

/** 本文（ProseMirror JSON）から、サーバーに保存する Yjs 状態・表示用JSON・平文を作る（POST /api/pages/:id/import の本文） */
export function encodeJson(json: Record<string, unknown>) {
  const schema = contentSchema()
  const ydoc = prosemirrorJSONToYDoc(schema, json, 'default')
  const node = PMNode.fromJSON(schema, json)
  return { ydoc: toBase64(Y.encodeStateAsUpdate(ydoc)), content: json, text: node.textBetween(0, node.content.size, '\n', '\n') }
}

/** HTML → encodeJson と同じ形（generateJSON と同じ変換を、使い回すスキーマで行う） */
export function encodeHtml(html: string) {
  return encodeJson(PMDOMParser.fromSchema(contentSchema()).parse(elementFromString(html)).toJSON())
}

/** Markdown → HTML（GFM） */
export const markdownToHtml = (text: string): string => marked.parse(text, { gfm: true, async: false }) as string

/** Word（Google ドキュメントの .docx 書き出しなど）を HTML に。画像は data: URL で埋め込む */
export async function docxToHtml(data: Uint8Array): Promise<string> {
  const mammoth = await import('mammoth')
  const r = await mammoth.convertToHtml({ arrayBuffer: data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) as ArrayBuffer })
  return `<!doctype html><html><body>${r.value}</body></html>`
}

/** ZIP を開く（パス → 中身。ZIP の中の順） */
export const unzip = (data: Uint8Array): Record<string, Uint8Array> => unzipSync(data)

/** ZIP を作る */
export const zip = (files: Record<string, Uint8Array>): Uint8Array => zipSync(files, { level: 6 })
