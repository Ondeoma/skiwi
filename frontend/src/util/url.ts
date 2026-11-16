/**
 * 本文の属性などに入った URL の検査。本文は同時編集（Yjs）や取り込みで好きな値にできるため、
 * 画面に出す直前に必ずここを通す（javascript: などを href・src に入れない）。
 */

const parse = (u: unknown): URL | null => {
  if (typeof u !== 'string' || !u.trim()) return null
  try { return new URL(u.trim(), location.origin) } catch { return null }
}

/** リンクとして出してよい URL（http・https・mailto、または同じサイトのパス）。それ以外は null */
export function safeLinkUrl(u: unknown): string | null {
  const p = parse(u)
  return p && ['http:', 'https:', 'mailto:'].includes(p.protocol) ? String(u).trim() : null
}

/** 外部のページとして iframe に埋め込める URL（https で、skiwi 自身のオリジンではないもの）。それ以外は null */
export function safeEmbedUrl(u: unknown): string | null {
  const p = parse(u)
  return p && p.protocol === 'https:' && p.origin !== location.origin ? p.toString() : null
}

/** 添付ファイルのパス（/api/attachments/{id}、公開ページでは /api/public/{key}/attachments/{id}）か */
const ATTACHMENT = /^\/api\/(public\/[\w-]+\/)?attachments\/[0-9a-f-]{36}$/i
export const isAttachmentPath = (u: unknown): u is string => typeof u === 'string' && ATTACHMENT.test(u)

/** 本文の属性に入った添付ファイルを取りに行く（添付ファイルのパス以外へは、ログインの Cookie 付きで送らない） */
export function fetchAttachment(u: unknown): Promise<Response> {
  if (!isAttachmentPath(u)) return Promise.reject(new Error('添付ファイルのパスではありません'))
  return fetch(u, { credentials: 'same-origin' })
}
