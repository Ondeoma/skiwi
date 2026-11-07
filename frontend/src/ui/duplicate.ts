import { shallowRef } from 'vue'
import { api, copyApi, okOrNull, type Page } from '../api'

/** 複製の画面を開く対象（WorkspaceView に1つだけ置いたダイアログが表示する） */
export const duplicateTarget = shallowRef<Page | null>(null)

const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'

/**
 * 複製した本文を整える。
 *  - 添付ファイル（画像・ファイル・ホワイトボードの画像）は複製したものに付け替える
 *  - 一緒に複製したページへのリンク・埋め込みは、複製したページに付け替える（それ以外は元のページのまま）
 *  - コメントの印は外す（コメントは複製しない）
 */
function rewriteContent(content: unknown, pages: Map<string, string>, attachments: Map<string, string>, workspaceId: string): Record<string, unknown> {
  const s = JSON.stringify(content)
    .replace(new RegExp(`/api/attachments/(${UUID})`, 'g'), (m, id: string) => (attachments.has(id) ? `/api/attachments/${attachments.get(id)}` : m))
    .replace(new RegExp(`/w/${UUID}/p/(${UUID})`, 'g'), (m, id: string) => (pages.has(id) ? `/w/${workspaceId}/p/${pages.get(id)}` : m))
  const walk = (n: any): any => {
    if (!n || typeof n !== 'object') return n
    const out = { ...n }
    if (Array.isArray(out.marks)) {
      out.marks = out.marks.filter((m: any) => m?.type !== 'comment')
      if (!out.marks.length) delete out.marks
    }
    if ((out.type === 'pageLink' || out.type === 'syncedRef') && out.attrs?.pageId && pages.has(out.attrs.pageId)) {
      out.attrs = { ...out.attrs, pageId: pages.get(out.attrs.pageId), workspaceId }
    }
    if (Array.isArray(out.content)) out.content = out.content.map(walk)
    return out
  }
  return walk(JSON.parse(s))
}

/**
 * ページ（と子ページ）を複製する。ページ・添付ファイルはサーバーが作り、本文はここで付け替えてから入れる。
 * 戻り値: 複製したページ（起点）とワークスペース
 */
export async function duplicatePage(
  src: Page,
  opts: { workspaceId: string; parentId?: string | null; includeChildren: boolean; title: string },
  onProgress?: (done: number, total: number) => void,
): Promise<{ rootId: string; workspaceId: string; failed: number }> {
  const { encodeJson, postEncoded } = await import('./importer')
  const r = await copyApi.duplicate(src.id, opts)
  const pages = new Map(r.pages.map((x) => [x.from, x.to]))
  const atts = new Map(r.attachments.map((x) => [x.from, x.to]))
  let done = 0, failed = 0
  onProgress?.(0, r.pages.length)
  for (const { from, to } of r.pages) {
    try {
      const content = (await okOrNull(api.pageContent(from)))?.content ?? null
      if (content && typeof content === 'object' && Array.isArray((content as any).content) && (content as any).content.length) {
        await postEncoded(to, encodeJson(rewriteContent(content, pages, atts, r.workspaceId)))
      }
    } catch (e) {
      failed++
      console.warn('[duplicate] content copy failed', from, e)
    }
    onProgress?.(++done, r.pages.length)
  }
  return { rootId: r.rootId, workspaceId: r.workspaceId, failed }
}
