/**
 * エディタ（JS のまま残す部分）が扱うデータの型. 取得・送信は Scala.js（lib.ts の window.__skiwiLib・ui/host.ts の window.__skiwiHost）が行う.
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

/** 添付ファイル */
export interface Attachment { id: string; url: string; filename: string; contentType: string; size: number }

export type Role = 'view' | 'edit'
/** クライアントが持つ現在の文書状態（版の保存・復元前の退避に使う）。 */
export interface DocState { ydoc: string; content: unknown; text: string; label?: string }

/** ゲスト向けに公開中のページ */
export interface GuestPage { id: string; parentId: string | null; position: string; title: string; icon: string | null; updatedAt: string; slug?: string | null }

export type ServiceName = 'skea' | 'skawau' | 'smohua'
export interface IntegrationStatus { enabled: boolean; url: string | null; projectKeys: string[]; connected: boolean }
/** skea のタスク */
export interface SkeaTask { key: string; title: string; status: { name: string; category: string; color: string }; url: string }
/** skawau のファイル・フォルダ（key はファイルの ID） */
export interface SkawauFile { key: string; name: string; isDir: boolean; mimeType: string | null }
