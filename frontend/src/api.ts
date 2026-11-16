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
export const STATUS_LABEL: Record<PageStatus, string> = { draft: '下書き', review: 'レビュー中', published: '公開済み', deprecated: '廃止' }

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
  me: () => request<Me>('GET', '/api/me'),
  setAvatar: (image: Blob) => request<{ avatarUrl: string | null }>('PUT', '/api/me/avatar', image),
  deleteAvatar: () => request<{ avatarUrl: string | null }>('DELETE', '/api/me/avatar'),
  workspaces: () => request<Workspace[]>('GET', '/api/workspaces'),
  createWorkspace: (name: string) => request<Workspace>('POST', '/api/workspaces', { name }),
  setFavorite: (id: string, favorite: boolean) => request<{ favorite: boolean }>('PUT', `/api/workspaces/${id}/favorite`, { favorite }),
  pages: (wsId: string) => request<Page[]>('GET', `/api/workspaces/${wsId}/pages`),
  createPage: (wsId: string, body: { parentId?: string | null; title?: string; afterId?: string; placement?: string }) =>
    request<Page>('POST', `/api/workspaces/${wsId}/pages`, body),
  updatePage: (id: string, body: { title?: string; icon?: string | null; ownerId?: string | null; status?: PageStatus | null; fullWidth?: boolean }) =>
    request<Page>('PATCH', `/api/pages/${id}`, body),
  /** opts: 取れなくても構わない表示用の取得では NO_REDIRECT 相当を渡す */
  getPage: (id: string, opts?: RequestOpts) => request<Page>('GET', `/api/pages/${id}`, undefined, opts),
  /** 本文（ProseMirror JSON）。取れなければ呼び出し側で諦めるので、ログイン画面には飛ばさない */
  pageContent: (id: string) => request<{ content: unknown; text: string }>('GET', `/api/pages/${id}/content`, undefined, NO_REDIRECT),
  /** 空のページに本文を入れる（取り込み・複製） */
  importContent: (id: string, s: DocState) => request<void>('POST', `/api/pages/${id}/import`, s, NO_REDIRECT),
  movePage: (id: string, body: { parentId?: string | null; afterId?: string; placement?: string; workspaceId?: string }) =>
    request<Page>('POST', `/api/pages/${id}/move`, body),
  deletePage: (id: string) => request<void>('DELETE', `/api/pages/${id}`),
  logout: () => request<void>('POST', '/auth/logout'),
}

export interface Attachment { id: string; url: string; filename: string; contentType: string; size: number }
/** attachment: 添付ファイル（PDF・Office など）の中身で見つかったとき、その添付ファイル。snippet もその中身から */
export interface SearchResult { page: Page; snippet: string | null; fuzzy: boolean; attachment?: { id: string; filename: string; url: string } | null }

export function uploadAttachment(pageId: string, file: File): Promise<Attachment> {
  const fd = new FormData()
  fd.append('file', file, file.name)
  return request<Attachment>('POST', `/api/pages/${pageId}/attachments`, fd, NO_REDIRECT)
}

/** 検索の絞り込み。label: ラベル、days: この日数以内に更新、mine: 自分が作成したページ */
export interface SearchFilter { label?: string; days?: number; mine?: boolean; status?: PageStatus | 'none'; ownerMe?: boolean }
/** workspaceId を省くと全ワークスペースを検索する。fuzzy=true は表記の近さで見つかったもの。q が空でも絞り込みがあれば一覧を返す */
export const searchPages = (q: string, workspaceId?: string, f: SearchFilter = {}) => {
  const p = new URLSearchParams({ q })
  if (workspaceId) p.set('workspaceId', workspaceId)
  if (f.label) p.set('label', f.label)
  if (f.days) p.set('days', String(f.days))
  if (f.mine) p.set('mine', 'true')
  if (f.status) p.set('status', f.status)
  if (f.ownerMe) p.set('owner', 'me')
  return request<SearchResult[]>('GET', `/api/search?${p}`)
}

export interface WorkspaceHit { id: string; name: string; role: 'view' | 'edit'; fuzzy: boolean }
export const searchWorkspaces = (q: string) =>
  request<WorkspaceHit[]>('GET', `/api/search/workspaces?q=${encodeURIComponent(q)}`)

// ---------------------------------------------------------------- フェーズ4
export type Role = 'view' | 'edit'
export type PrincipalType = 'user' | 'group'
/** expiresAt: 権限の期限（過ぎると自動で外れる） */
export interface AclEntry { principalType: PrincipalType; principalId: string; role: Role; name?: string; detail?: string | null; expiresAt?: string | null }
interface PageAcl { inherit: boolean; entries: AclEntry[] }
export interface WorkspaceAcl { defaultRole: Role | null; entries: AclEntry[] }
export interface DirectoryEntry { principalType: PrincipalType; id: string; name: string; detail: string | null }
export interface Version { id: string; createdAt: string; label: string | null; createdBy: string | null; authors: string[] }
export interface VersionContent { version: Version; content: unknown; text: string }
/** クライアントが持つ現在の文書状態（版の保存・復元前の退避に使う）。 */
export interface DocState { ydoc: string; content: unknown; text: string; label?: string }

/** ウォッチの状態。inherited: 「子ページも含める」でウォッチしている祖先（このページに設定がなければ、その設定で通知が届く） */
export interface WatchState {
  watching: boolean; edits: boolean; comments: boolean; subtree: boolean
  inherited: { pageId: string; title: string; edits: boolean; comments: boolean } | null
}

const strip = (es: AclEntry[]) => es.map(({ principalType, principalId, role, expiresAt }) => ({ principalType, principalId, role, expiresAt: expiresAt ?? null }))

export const api4 = {
  pageAcl: (id: string) => request<PageAcl>('GET', `/api/pages/${id}/acl`),
  setPageAcl: (id: string, acl: PageAcl) => request<void>('PUT', `/api/pages/${id}/acl`, { inherit: acl.inherit, entries: strip(acl.entries) }),
  workspaceAcl: (id: string) => request<WorkspaceAcl>('GET', `/api/workspaces/${id}/acl`),
  setWorkspaceAcl: (id: string, acl: WorkspaceAcl) =>
    request<void>('PUT', `/api/workspaces/${id}/acl`, { defaultRole: acl.defaultRole, entries: strip(acl.entries) }),
  directory: (q: string) => request<DirectoryEntry[]>('GET', `/api/directory?q=${encodeURIComponent(q)}`),
  watch: (id: string) => request<WatchState>('GET', `/api/pages/${id}/watch`),
  setWatch: (id: string, watching: boolean) => request<WatchState>('PUT', `/api/pages/${id}/watch`, { watching }),
  setWatchOpts: (id: string, o: { edits?: boolean; comments?: boolean; subtree?: boolean }) => request<WatchState>('PUT', `/api/pages/${id}/watch`, o),
  versions: (id: string) => request<Version[]>('GET', `/api/pages/${id}/versions`),
  version: (id: string, vid: string) => request<VersionContent>('GET', `/api/pages/${id}/versions/${vid}`),
  saveVersion: (id: string, s: DocState) => request<Version>('POST', `/api/pages/${id}/versions`, s),
  restoreVersion: (id: string, vid: string, s: DocState) => request<VersionContent>('POST', `/api/pages/${id}/versions/${vid}/restore`, s),
}

// ---------------------------------------------------------------- ゲスト向け公開
export interface PublicShare {
  token: string; includeChildren: boolean; createdAt: string; createdBy: string | null
  expiresAt: string | null; hasPassword: boolean; expired: boolean; hideHeader: boolean
  /** ゲスト画面のサイドメニュー（ページ一覧）: auto=2ページ以上なら表示 */
  sidebar: 'auto' | 'show' | 'hide'
  /** live: 編集中の内容をそのまま公開 / pinned: 「公開」を押した時点の内容を公開 */
  mode: 'live' | 'pinned'; publishedAt: string | null; publishedBy: string | null
  /** pinned のとき: 公開版に反映していない変更がある */
  pendingChanges?: boolean | null
  /** 公開の開始日時（予約）。notStarted: まだ公開前 */
  startsAt: string | null; notStarted: boolean
  /** わかりやすいURL名（/s/<slug>）、検索エンジンに載せる、共有したときの説明文 */
  slug: string | null; indexable: boolean; description: string | null
  /** ゲスト画面に更新履歴（公開日時と変更点のメモ）を出す */
  showReleases: boolean
}
/** 公開の履歴（「現在の内容を公開」の1回分） */
export interface Release { id: string; publishedAt: string; publishedBy: string | null; note: string | null; pageCount: number }
export interface ReleasePage { pageId: string; parentId: string | null; title: string; icon: string | null; content: unknown; text: string }
export interface ShareInfo {
  /** false: サーバー設定で公開機能が無効 */
  available: boolean
  share: PublicShare | null
  /** 祖先ページの公開リンクに含まれている場合 */
  inherited: { pageId: string; title: string; token: string; slug: string | null; mode: 'live' | 'pinned' } | null
  /** このページのURL名（公開ページのURLに使う） */
  pageSlug: string | null
}
export interface GuestPage { id: string; parentId: string | null; position: string; title: string; icon: string | null; updatedAt: string; slug?: string | null }
export type ShareOpts = {
  includeChildren?: boolean; expiresAt?: string | null; password?: string | null; hideHeader?: boolean; sidebar?: 'auto' | 'show' | 'hide'
  mode?: 'live' | 'pinned'; startsAt?: string | null; slug?: string | null; indexable?: boolean; description?: string | null
  showReleases?: boolean
}

export const shareApi = {
  get: (pageId: string) => request<ShareInfo>('GET', `/api/pages/${pageId}/share`),
  /** expiresAt / startsAt / password / slug / description: undefined=変更しない、null=解除 */
  enable: (pageId: string, opts: ShareOpts = {}) => request<PublicShare>('PUT', `/api/pages/${pageId}/share`, opts),
  /** 公開版を作り直す（現在の内容を公開する） */
  publish: (pageId: string, note?: string) => request<PublicShare>('POST', `/api/pages/${pageId}/share/publish`, note ? { note } : {}),
  releases: (pageId: string) => request<Release[]>('GET', `/api/pages/${pageId}/share/releases`),
  release: (pageId: string, rid: string) => request<{ release: Release; pages: ReleasePage[] }>('GET', `/api/pages/${pageId}/share/releases/${rid}`),
  setReleaseNote: (pageId: string, rid: string, note: string | null) => request<void>('PATCH', `/api/pages/${pageId}/share/releases/${rid}`, { note }),
  setPageSlug: (pageId: string, slug: string | null) => request<{ slug: string | null }>('PUT', `/api/pages/${pageId}/guest-slug`, { slug }),
  adminList: () => request<{ pageId: string; title: string; icon: string | null; workspaceId: string; workspaceName: string; share: PublicShare }[]>('GET', '/api/admin/shares'),
  disable: (pageId: string) => request<void>('DELETE', `/api/pages/${pageId}/share`),
}

/** key: 公開リンクのトークンまたはURL名、page: ページのURL名またはID */
export const shareUrl = (key: string, page?: string) => `${location.origin}/s/${key}${page ? `/${page}` : ''}`

/** ゲスト用（認証なし）。ログイン画面には飛ばさない。 */
// パスワード付きリンクの解錠Cookieを送るため same-origin（ログインのCookieは公開APIでは使われない）
const guestGet = <T>(url: string) => request<T>('GET', url, undefined, NO_REDIRECT)
export const guestApi = {
  tree: (token: string) => guestGet<{ rootId: string; pages: GuestPage[]; hideHeader?: boolean; sidebar?: 'auto' | 'show' | 'hide'; key?: string; releases?: boolean }>(`/api/public/${encodeURIComponent(token)}`),
  /** 更新履歴（公開日時と変更点のメモ） */
  releases: (token: string) => guestGet<{ publishedAt: string; note: string }[]>(`/api/public/${encodeURIComponent(token)}/releases`),
  /** 公開範囲のページの検索。snippet は一致箇所を <mark> で囲んだHTML（ほかはエスケープ済み） */
  search: (token: string, q: string) =>
    guestGet<{ page: GuestPage; snippet: string | null; titleHit: boolean }[]>(`/api/public/${encodeURIComponent(token)}/search?q=${encodeURIComponent(q)}`),
  page: (token: string, pageId: string) =>
    guestGet<{ page: GuestPage; content: unknown; styles?: string[]; top?: string[]; bottom?: string[]; fullWidth?: boolean }>(`/api/public/${encodeURIComponent(token)}/pages/${encodeURIComponent(pageId)}`),
  /** 正しければ true。回数超過などは例外 */
  unlock: async (token: string, password: string): Promise<boolean> => {
    try {
      await request<void>('POST', `/api/public/${encodeURIComponent(token)}/unlock`, { password }, NO_REDIRECT)
      return true
    } catch (e) {
      if (!(e instanceof ApiError)) throw e
      if (e.status === 403) return false
      throw e.status === 429 ? new ApiError(429, '試行回数が多すぎます。しばらく待ってからお試しください') : e
    }
  },
}

// ---------------------------------------------------------------- ゴミ箱・ワークスペース管理
export interface TrashedPage { id: string; title: string; icon: string | null; deletedAt: string; deletedBy: string | null; count: number; purgeAt: string }
export interface TrashedWorkspace { id: string; name: string; deletedAt: string; deletedBy: string | null; purgeAt: string }
export interface RecentItem { page: Page; workspaceName: string; at: string }
export interface Notification {
  id: number; kind: 'page_edited' | 'shared' | 'shared_workspace' | 'mention' | 'comment' | 'task_due' | 'access_request' | 'access_granted' | 'access_denied' | 'automation'
  actors: string[]; role: 'view' | 'edit' | null
  excerpt?: string | null; threadId?: string | null; due?: string | null; requestId?: string | null
  pageId: string | null; pageTitle: string | null; pageIcon: string | null
  workspaceId: string | null; workspaceName: string | null; createdAt: string; read: boolean
}

export const api5 = {
  renameWorkspace: (id: string, name: string) => request<{ id: string; name: string }>('PATCH', `/api/workspaces/${id}`, { name }),
  deleteWorkspace: (id: string) => request<void>('DELETE', `/api/workspaces/${id}`),
  trash: (wsId: string) => request<TrashedPage[]>('GET', `/api/workspaces/${wsId}/trash`),
  restorePage: (id: string) => request<{ id: string; workspaceId: string; parentId: string | null }>('POST', `/api/trash/pages/${id}/restore`),
  purgePage: (id: string) => request<void>('DELETE', `/api/trash/pages/${id}`),
  trashedWorkspaces: () => request<TrashedWorkspace[]>('GET', '/api/trash/workspaces'),
  restoreWorkspace: (id: string) => request<{ id: string; name: string }>('POST', `/api/trash/workspaces/${id}/restore`),
  purgeWorkspace: (id: string) => request<void>('DELETE', `/api/trash/workspaces/${id}`),
  backlinks: (id: string) => request<Page[]>('GET', `/api/pages/${id}/backlinks`),
  setLabels: (id: string, labels: string[]) => request<{ labels: string[] }>('PUT', `/api/pages/${id}/labels`, { labels }),
  visit: (id: string) => request<void>('POST', `/api/pages/${id}/visit`),
  recent: (kind: 'visited' | 'edited') => request<RecentItem[]>('GET', `/api/recent?kind=${kind}&limit=30`),
  notifications: () => request<{ unread: number; items: Notification[] }>('GET', '/api/notifications'),
  favoritePages: () => request<{ page: Page; workspaceName: string }[]>('GET', '/api/favorites/pages'),
  setPageFavorite: (id: string, favorite: boolean) => request<{ favorite: boolean }>('PUT', `/api/pages/${id}/favorite`, { favorite }),
  markRead: (ids?: number[]) => request<{ unread: number }>('POST', '/api/notifications/read', ids ? { ids } : {}),
}

export function formatDate(iso: string): string {
  return new Date(iso).toLocaleString('ja-JP', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

// ---------------------------------------------------------------- コメント・閲覧できる人
export interface Comment {
  id: string; threadId: string | null; anchor: string | null; quote: string | null; body: string
  authorId: string | null; authorName: string | null; authorAvatarUrl?: string | null; createdAt: string; updatedAt: string
  resolvedAt: string | null; resolvedBy: string | null; deleted: boolean; canEdit: boolean; canDelete: boolean
}
export interface AudienceEntry { userId: string; name: string; email: string | null; role: Role; reason: string }

export const commentApi = {
  list: (pageId: string) => request<Comment[]>('GET', `/api/pages/${pageId}/comments`),
  create: (pageId: string, body: { body: string; threadId?: string; anchor?: string; quote?: string }) =>
    request<Comment>('POST', `/api/pages/${pageId}/comments`, body),
  edit: (id: string, body: string) => request<Comment>('PATCH', `/api/comments/${id}`, { body }),
  remove: (id: string) => request<void>('DELETE', `/api/comments/${id}`),
  resolve: (id: string, resolved: boolean) => request<Comment>('POST', `/api/comments/${id}/resolve`, { resolved }),
  audience: (pageId: string) => request<AudienceEntry[]>('GET', `/api/pages/${pageId}/audience`),
}

// ---------------------------------------------------------------- ToDo・リンク切れ
export interface Task { pageId: string; workspaceId: string; workspaceName: string; pageTitle: string; pageIcon: string | null; seq: number; text: string; checked: boolean; due: string | null }
export interface BrokenLink { sourceId: string; sourceTitle: string; targetId: string; targetTitle: string | null; reason: 'missing' | 'deleted' | 'no_access' }
/** incoming: ほかのページの本文からのリンク数 / lastEditedAt: 最終更新 */
export interface PageHealth { page: Page; incoming: number; lastEditedAt: string; lastEditor: string | null; children: number }
export const taskApi = {
  mine: (done = false) => request<Task[]>('GET', `/api/tasks${done ? '?done=true' : ''}`),
  brokenLinks: (wsId: string) => request<BrokenLink[]>('GET', `/api/workspaces/${wsId}/broken-links`),
  health: (wsId: string) => request<PageHealth[]>('GET', `/api/workspaces/${wsId}/health`),
}

// ---------------------------------------------------------------- カスタムCSS
export interface CssLayer { level: 'workspace' | 'page'; pageId: string | null; title: string | null; css: string; inherit: boolean; own: boolean }
export const cssApi = {
  pageStyles: (id: string) => request<{ layers: CssLayer[]; css: string | null; inherit: boolean }>('GET', `/api/pages/${id}/styles`),
  setPageCss: (id: string, css: string | null, inherit: boolean) => request<void>('PUT', `/api/pages/${id}/css`, { css, inherit }),
  workspaceCss: (id: string) => request<{ css: string | null }>('GET', `/api/workspaces/${id}/css`),
  setWorkspaceCss: (id: string, css: string | null) => request<void>('PUT', `/api/workspaces/${id}/css`, { css }),
}

// ---------------------------------------------------------------- ゲスト向けの表示（本文の上下のHTML）
export interface GuestHtmlLayer { level: 'workspace' | 'page'; title: string | null; top: string | null; bottom: string | null }
export interface GuestHtml { top: string | null; bottom: string | null; inherit: boolean; layers?: GuestHtmlLayer[] }
export const guestHtmlApi = {
  page: (id: string) => request<GuestHtml>('GET', `/api/pages/${id}/guest-html`),
  setPage: (id: string, b: { top: string | null; bottom: string | null; inherit: boolean }) => request<void>('PUT', `/api/pages/${id}/guest-html`, b),
  workspace: (id: string) => request<GuestHtml>('GET', `/api/workspaces/${id}/guest-html`),
  setWorkspace: (id: string, b: { top: string | null; bottom: string | null }) => request<void>('PUT', `/api/workspaces/${id}/guest-html`, b),
}

// ---------------------------------------------------------------- 一括操作
export type BulkAction =
  | { action: 'move'; parentId: string | null; workspaceId?: string }
  | { action: 'delete' }
  | { action: 'labels'; addLabels?: string[]; removeLabels?: string[] }
  | { action: 'owner'; ownerId: string | null }
  | { action: 'status'; status: PageStatus | null }
/** done: 処理したページ / skipped: 選んだ親と一緒に移動・削除されたページ / failed: できなかったページと理由 */
interface BulkResult { done: string[]; skipped: string[]; failed: { id: string; title: string | null; error: string }[] }
export const bulkApi = {
  run: (ids: string[], a: BulkAction) => request<BulkResult>('POST', '/api/pages/bulk', { ids, ...a }),
}

// ---------------------------------------------------------------- 複製・閲覧権限の申請
export interface DuplicateResult { rootId: string; workspaceId: string; pages: { from: string; to: string }[]; attachments: { from: string; to: string }[] }
export interface AccessRequest { id: string; userId: string; name: string; email: string | null; role: Role; message: string | null; createdAt: string }
/** none: 申請していない / pending: 承認待ち / has_access: すでに閲覧（編集）できる */
export interface AccessStatus { status: 'none' | 'pending' | 'has_access' | 'approved' | 'denied'; role?: Role | null }
export const copyApi = {
  /** parentId: undefined=既定（同じワークスペースなら元と同じ階層）、null=ワークスペース直下 */
  duplicate: (id: string, body: { workspaceId?: string; parentId?: string | null; includeChildren?: boolean; title?: string }) =>
    request<DuplicateResult>('POST', `/api/pages/${id}/duplicate`, body),
  requestAccess: (id: string, role: Role, message: string) => request<AccessStatus>('POST', `/api/pages/${id}/access-requests`, { role, message }),
  myRequest: (id: string) => request<AccessStatus>('GET', `/api/pages/${id}/access-requests/mine`),
  requests: (id: string) => request<AccessRequest[]>('GET', `/api/pages/${id}/access-requests`),
  decide: (rid: string, action: 'approve' | 'deny', role?: Role, expiresAt?: string | null) =>
    request<AccessStatus>('POST', `/api/access-requests/${rid}/${action}`, { ...(role ? { role } : {}), ...(expiresAt ? { expiresAt } : {}) }),
}

// ---------------------------------------------------------------- Webhook（外部への通知）
export type WebhookFormat = 'generic' | 'slack' | 'teams'
export type WebhookEvent = 'page.created' | 'page.updated' | 'page.deleted' | 'comment.created' | 'page.published'
export const WEBHOOK_EVENTS: { value: WebhookEvent; label: string }[] = [
  { value: 'page.created', label: 'ページの作成' },
  { value: 'page.updated', label: 'ページの更新（編集が落ち着いてからまとめて）' },
  { value: 'page.deleted', label: 'ページの削除' },
  { value: 'comment.created', label: 'コメント' },
  { value: 'page.published', label: 'ゲスト向けの公開' },
]
export interface Webhook {
  id: string; name: string; url: string; format: WebhookFormat; events: WebhookEvent[]; enabled: boolean
  createdAt: string; createdBy: string | null; lastStatus: number | null; lastError: string | null; lastAt: string | null
  /** 作成したときだけ返る（署名の確認用） */
  secret?: string | null
}
export const webhookApi = {
  list: (wsId: string) => request<Webhook[]>('GET', `/api/workspaces/${wsId}/webhooks`),
  create: (wsId: string, b: { name: string; url: string; format: WebhookFormat; events: WebhookEvent[] }) => request<Webhook>('POST', `/api/workspaces/${wsId}/webhooks`, b),
  update: (id: string, b: Partial<{ name: string; url: string; format: WebhookFormat; events: WebhookEvent[]; enabled: boolean }>) => request<Webhook>('PATCH', `/api/webhooks/${id}`, b),
  remove: (id: string) => request<void>('DELETE', `/api/webhooks/${id}`),
  test: (id: string) => request<{ ok: boolean; status: number | null; error: string | null }>('POST', `/api/webhooks/${id}/test`),
}

// ---------------------------------------------------------------- データベース
export type DbPropType = 'text' | 'number' | 'select' | 'multi_select' | 'date' | 'person' | 'checkbox' | 'url' | 'relation' | 'rollup'
  | 'formula' | 'created_time' | 'edited_time' | 'created_by' | 'edited_by' | 'unique_id'
export type RollupFn = 'count' | 'count_values' | 'sum' | 'avg' | 'min' | 'max' | 'checked'
export interface DbOption { id: string; name: string; color: string }
/** relation: target（関連先のデータベース）/ rollup: relation（関連の列）・fn（集計）・prop（関連先の列） */
/** formula: expr（式）/ unique_id: prefix（例: TASK → TASK-12） */
export interface DbProp { id: string; name: string; type: DbPropType; options?: DbOption[]; target?: string; relation?: string; fn?: RollupFn; prop?: string; expr?: string; prefix?: string }
export interface RelatedDb { pageId: string; title: string; props: DbProp[]; rows: { id: string; title: string; icon: string | null; values: DbValues }[] }
export type DbFilterOp = 'contains' | 'is' | 'isNot' | 'empty' | 'notEmpty' | 'gt' | 'lt'
export interface DbFilter { prop: string; op: DbFilterOp; value?: string }
export type CalcFn = 'count' | 'count_values' | 'count_empty' | 'sum' | 'avg' | 'min' | 'max' | 'checked' | 'unchecked'
export interface DbView {
  id: string; name: string; type: 'table' | 'board' | 'calendar' | 'list' | 'gallery' | 'timeline'
  /** ボード: 列に分ける項目（単一選択）。カレンダー: 日付の項目 */
  groupBy?: string | null; dateProp?: string | null
  sort?: { prop: string; dir: 'asc' | 'desc' } | null
  filters?: DbFilter[]
  /** 表示しない列 */
  hidden?: string[]
  /** 表・リスト: グループ化する列（選択・チェック・ユーザー） */
  group?: string | null
  /** 表: サブ項目を親の下に入れて表示する（既定は true） */
  subItems?: boolean
  /** 表: 列の下の集計（列のID → 集計） */
  calcs?: Record<string, CalcFn>
  /** タイムライン: 開始・終了の日付の列 */
  startProp?: string | null; endProp?: string | null
  /** ギャラリー: 表紙（content=本文の最初の画像、none=なし、URL の列のID） */
  cover?: string | null
}
export type DbValues = Record<string, unknown>
/** 項目の情報（自動の列・ギャラリー用） */
export interface RowMeta { createdAt: string; createdBy: string | null; editedAt: string; editedBy: string | null; cover: string | null; excerpt: string | null }
export interface DbRow { page: Page; values: DbValues; meta?: RowMeta | null }
export interface Database { pageId: string; props: DbProp[]; views: DbView[]; rows: DbRow[]; users: Record<string, string>; canEdit: boolean; targets: Record<string, RelatedDb> }
export interface PageProps { database: { pageId: string; title: string; props: DbProp[] } | null; values: DbValues; users: Record<string, string>; canEdit: boolean; targets: Record<string, RelatedDb> }
export const dbApi = {
  get: (id: string) => request<Database>('GET', `/api/pages/${id}/database`),
  save: (id: string, props: DbProp[], views: DbView[]) => request<Database>('PUT', `/api/pages/${id}/database`, { props, views }),
  remove: (id: string) => request<void>('DELETE', `/api/pages/${id}/database`),
  addRow: (id: string, title: string, values: DbValues = {}, parentId?: string) => request<DbRow>('POST', `/api/pages/${id}/database/rows`, { title, values, ...(parentId ? { parentId } : {}) }),
  addRows: (id: string, rows: { title: string; values: DbValues }[]) => request<{ created: number }>('POST', `/api/pages/${id}/database/rows/bulk`, { rows }),
  props: (id: string) => request<PageProps>('GET', `/api/pages/${id}/props`),
  setValues: (id: string, values: DbValues) => request<PageProps>('PATCH', `/api/pages/${id}/props`, { values }),
}

// ---------------------------------------------------------------- ページの保護
/** canEdit: 保護中でも編集できる / canChange: 保護の設定・解除ができる */
export interface PageLockState {
  locked: boolean; lockedAt: string | null; lockedBy: string | null; reason: string | null
  editors: { id: string; name: string }[]; canEdit: boolean; canChange: boolean
}
export const lockApi = {
  get: (id: string) => request<PageLockState>('GET', `/api/pages/${id}/lock`),
  set: (id: string, b: { reason?: string | null; editors?: string[] }) => request<PageLockState>('PUT', `/api/pages/${id}/lock`, b),
  unlock: (id: string) => request<void>('DELETE', `/api/pages/${id}/lock`),
}

// ---------------------------------------------------------------- 自動化ルール
export type AutoTriggerType = 'status_changed' | 'label_added' | 'page_created' | 'value_changed' | 'date_reached'
export interface AutoTrigger {
  type: AutoTriggerType; under?: string | null
  to?: PageStatus | null; label?: string
  database?: string; prop?: string; value?: string | null
  offset?: number; unlessProp?: string | null; unlessValue?: string | null
}
export type AutoActionType = 'notify' | 'webhook' | 'set_status' | 'add_label'
export interface AutoAction {
  type: AutoActionType
  to?: 'owner' | 'watchers' | 'users' | 'prop'; users?: string[]; prop?: string
  webhook?: string; status?: PageStatus | null; label?: string; message?: string | null
}
export interface Automation {
  id: string; name: string; enabled: boolean; trigger: AutoTrigger; action: AutoAction
  createdBy: string | null; createdAt: string; lastRunAt: string | null; lastError: string | null; runCount: number
}
export const automationApi = {
  list: (wsId: string) => request<Automation[]>('GET', `/api/workspaces/${wsId}/automations`),
  create: (wsId: string, b: { name: string; trigger: AutoTrigger; action: AutoAction }) => request<Automation>('POST', `/api/workspaces/${wsId}/automations`, b),
  update: (id: string, b: Partial<{ name: string; enabled: boolean; trigger: AutoTrigger; action: AutoAction }>) => request<Automation>('PATCH', `/api/automations/${id}`, b),
  remove: (id: string) => request<void>('DELETE', `/api/automations/${id}`),
}

// ---------------------------------------------------------------- 受信用の Webhook
/** mode: row（データベースの項目を追加）| child（子ページを作る）| comment（コメントを付ける）。url は作成・作り直しのときだけ */
export interface InboundHook {
  id: string; name: string; mode: 'row' | 'child' | 'comment'; targetPageId: string; targetTitle: string | null; enabled: boolean
  createdBy: string | null; createdAt: string; lastAt: string | null; useCount: number; url?: string | null
}
export const inboundApi = {
  list: (wsId: string) => request<InboundHook[]>('GET', `/api/workspaces/${wsId}/inbound-hooks`),
  create: (wsId: string, b: { name: string; targetPageId: string; mode: InboundHook['mode'] }) => request<InboundHook>('POST', `/api/workspaces/${wsId}/inbound-hooks`, b),
  update: (id: string, b: { name?: string; enabled?: boolean }) => request<InboundHook>('PATCH', `/api/inbound-hooks/${id}`, b),
  rotate: (id: string) => request<InboundHook>('POST', `/api/inbound-hooks/${id}/rotate`),
  remove: (id: string) => request<void>('DELETE', `/api/inbound-hooks/${id}`),
}

// ---------------------------------------------------------------- 定期バックアップ（管理者のみ）
export interface BackupConfig { enabled: boolean; hour: number; keep: number }
export interface Backup {
  id: string; createdAt: string; finishedAt: string | null; status: 'running' | 'done' | 'failed'
  size: number | null; pages: number | null; attachments: number | null; error: string | null; manual: boolean
}
export const backupApi = {
  list: (wsId: string) => request<{ config: BackupConfig | null; backups: Backup[]; storage: boolean }>('GET', `/api/admin/workspaces/${wsId}/backups`),
  setConfig: (wsId: string, c: BackupConfig) => request<BackupConfig>('PUT', `/api/admin/workspaces/${wsId}/backups/config`, c),
  run: (wsId: string) => request<Backup>('POST', `/api/admin/workspaces/${wsId}/backups`),
  remove: (id: string) => request<void>('DELETE', `/api/admin/backups/${id}`),
  downloadUrl: (id: string) => `/api/admin/backups/${id}/download`,
}

// ---------------------------------------------------------------- データベースのフォーム
export interface FormField { prop: string; label?: string | null; required: boolean; help?: string | null }
export interface DbFormDef {
  id: string; title: string; description: string | null; fields: FormField[]; requireLogin: boolean; respondentProp: string | null
  enabled: boolean; url: string; createdBy: string | null; createdAt: string; lastAt: string | null; useCount: number
}
export interface PublicForm {
  title: string; description: string | null; requireLogin: boolean; loggedIn: boolean; userName: string | null
  fields: { prop: string; label: string; type: string; required: boolean; help: string | null; options: { id: string; name: string }[] }[]
}
type SaveForm = { title: string; description?: string | null; fields: FormField[]; requireLogin: boolean; respondentProp?: string | null; enabled?: boolean }
export const formApi = {
  list: (dbId: string) => request<DbFormDef[]>('GET', `/api/pages/${dbId}/forms`),
  create: (dbId: string, b: SaveForm) => request<DbFormDef>('POST', `/api/pages/${dbId}/forms`, b),
  update: (id: string, b: SaveForm) => request<DbFormDef>('PUT', `/api/forms/${id}`, b),
  remove: (id: string) => request<void>('DELETE', `/api/forms/${id}`),
}

// ---------------------------------------------------------------- 外部サービスとの連携（docs/skea-integration.md・docs/skawau-integration.md）
export type ServiceName = 'skea' | 'skawau'
/** enabled: 管理者が設定済み。connected: 自分が連携済み。projectKeys は skea だけ */
export interface IntegrationStatus { enabled: boolean; url: string | null; projectKeys: string[]; connected: boolean }
/** skea のタスク */
export interface SkeaTask { key: string; title: string; status: { name: string; category: string; color: string }; url: string }
/** skawau のファイル・フォルダ（key はファイルの ID） */
export interface SkawauFile { key: string; name: string; isDir: boolean; mimeType: string | null }
/** 管理者の設定（client_secret は返らない。設定済みかどうかだけ） */
export interface IntegrationSettings { url: string; clientId: string; clientSecretSet: boolean; projectKeys: string[]; redirectUri: string; connections: number }
export const integrationApi = (svc: ServiceName) => ({
  status: () => request<IntegrationStatus>('GET', `/api/integrations/${svc}`, undefined, NO_REDIRECT),
  /** 許可画面へ（ブラウザで開く）。returnTo: 戻り先（同じサイトの相対パス） */
  connectUrl: (returnTo: string) => `/api/integrations/${svc}/connect?returnTo=${encodeURIComponent(returnTo)}`,
  disconnect: () => request<void>('DELETE', `/api/integrations/${svc}/connection`),
  lookup: <T>(keys: string[]) => request<{ connected: boolean; items: T[] }>('POST', `/api/integrations/${svc}/lookup`, { keys }, NO_REDIRECT),
  settings: () => request<IntegrationSettings>('GET', `/api/admin/integrations/${svc}`),
  /** clientSecret: 空なら今の値のまま */
  saveSettings: (b: { url: string; clientId: string; clientSecret?: string; projectKeys?: string[] }) =>
    request<IntegrationSettings>('PUT', `/api/admin/integrations/${svc}`, b),
})

/** skea → skiwi: skiwi に登録する skea（OAuth のクライアント）。client_secret は返らない。connections: 連携しているユーザーの数 */
export interface OAuthClientSettings { clientId: string; clientSecretSet: boolean; redirectUri: string; name: string; connections: number }
export const oauthClientApi = {
  get: () => request<OAuthClientSettings>('GET', '/api/admin/integrations/skea/client'),
  /** clientSecret: 空なら今の値のまま（新しく登録するときは必須） */
  save: (b: { clientId: string; clientSecret?: string; redirectUri: string; name: string }) =>
    request<OAuthClientSettings>('PUT', '/api/admin/integrations/skea/client', b),
  remove: () => request<void>('DELETE', '/api/admin/integrations/skea/client'),
}
