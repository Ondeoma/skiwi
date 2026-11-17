/**
 * エディタ（JS のまま残す部分）が使うバックエンドの API.
 * 画面（Scalactive）の API は modules/web の skiwi.web.Api と各画面の XxxApi にある.
 */
/** avatarUrl: アイコンを設定していれば、その URL */
export interface Me { id: string; name: string; email?: string; groups: string[]; isAdmin: boolean; avatarUrl?: string | null }
export interface Workspace { id: string; name: string; role: 'view' | 'edit'; favorite: boolean }
export interface Page {
  id: string; workspaceId: string; parentId: string | null; position: string
  title: string; icon: string | null; role: 'view' | 'edit'; updatedAt: string
  /** ゲスト向けに公開中 */
  published?: boolean
  /** ラベル（ページ一覧でのみ入る） */
  labels?: string[]
  ownerId?: string | null
  ownerName?: string | null
  status?: PageStatus | null
  /** データベース（子ページを表・ボード・カレンダーで見る）のページ */
  database?: boolean
  /** 保護中（編集できる人が限られている） */
  locked?: boolean
  /** 全幅で表示する */
  fullWidth?: boolean
}
export type PageStatus = 'draft' | 'review' | 'published' | 'deprecated'

export class ApiError extends Error {
  constructor(public status: number, message: string, public body?: unknown) { super(message) }
}

/** エラー応答を ApiError にする（本文の error を優先し、なければ statusText） */
async function errorOf(res: Response): Promise<ApiError> {
  const j = await res.json().catch(() => null)
  return new ApiError(res.status, (j?.error as string | undefined) ?? res.statusText, j)
}

/** redirectOn401=false: 未ログインでもログイン画面へ飛ばさず ApiError(401) にする（ゲスト表示・取れなければ諦める取得など） */
interface RequestOpts { redirectOn401?: boolean }
const NO_REDIRECT: RequestOpts = { redirectOn401: false }

/** body が FormData ならそのまま（multipart）、Blob ならその種類で中身だけ、それ以外は JSON で送る */
async function request<T>(method: string, url: string, body?: unknown, opts: RequestOpts = {}): Promise<T> {
  const raw = body instanceof FormData || body instanceof Blob
  const res = await fetch(url, {
    method,
    credentials: 'same-origin',
    headers: body === undefined || body instanceof FormData ? undefined : { 'Content-Type': body instanceof Blob ? body.type : 'application/json' },
    body: body === undefined ? undefined : raw ? body : JSON.stringify(body),
  })
  if (res.status === 401 && opts.redirectOn401 !== false) {
    // 未ログイン: IdPへ
    location.href = '/auth/login'
    throw new ApiError(401, 'unauthorized')
  }
  if (!res.ok) throw await errorOf(res)
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T)
}

/** 応答がエラー（権限なし・削除済みなど）なら null。通信エラーなどはそのまま投げる */
export const okOrNull = <T>(p: Promise<T>): Promise<T | null> =>
  p.catch((e) => { if (e instanceof ApiError) return null; throw e })

export const api = {
  /** opts: 取れなくても構わない表示用の取得では NO_REDIRECT 相当を渡す */
  getPage: (id: string, opts?: RequestOpts) => request<Page>('GET', `/api/pages/${id}`, undefined, opts),
  /** 本文（ProseMirror JSON）。取れなければ呼び出し側で諦めるので、ログイン画面には飛ばさない */
  pageContent: (id: string) => request<{ content: unknown; text: string }>('GET', `/api/pages/${id}/content`, undefined, NO_REDIRECT),
}

/** 添付ファイル */
export interface Attachment { id: string; url: string; filename: string; contentType: string; size: number }

export function uploadAttachment(pageId: string, file: File): Promise<Attachment> {
  const fd = new FormData()
  fd.append('file', file, file.name)
  return request<Attachment>('POST', `/api/pages/${pageId}/attachments`, fd, NO_REDIRECT)
}

export type Role = 'view' | 'edit'
export type PrincipalType = 'user' | 'group'
export interface DirectoryEntry { principalType: PrincipalType; id: string; name: string; detail: string | null }
/** クライアントが持つ現在の文書状態（版の保存・復元前の退避に使う）。 */
export interface DocState { ydoc: string; content: unknown; text: string; label?: string }

export const api4 = {
  directory: (q: string) => request<DirectoryEntry[]>('GET', `/api/directory?q=${encodeURIComponent(q)}`),
}

/** ゲスト向けに公開中のページ */
export interface GuestPage { id: string; parentId: string | null; position: string; title: string; icon: string | null; updatedAt: string; slug?: string | null }
const guestGet = <T>(url: string) => request<T>('GET', url, undefined, NO_REDIRECT)
export const guestApi = {
  page: (token: string, pageId: string) =>
    guestGet<{ page: GuestPage; content: unknown; styles?: string[]; top?: string[]; bottom?: string[]; fullWidth?: boolean }>(`/api/public/${encodeURIComponent(token)}/pages/${encodeURIComponent(pageId)}`),
}

export type ServiceName = 'skea' | 'skawau'
export interface IntegrationStatus { enabled: boolean; url: string | null; projectKeys: string[]; connected: boolean }
/** skea のタスク */
export interface SkeaTask { key: string; title: string; status: { name: string; category: string; color: string }; url: string }
/** skawau のファイル・フォルダ（key はファイルの ID） */
export interface SkawauFile { key: string; name: string; isDir: boolean; mimeType: string | null }
