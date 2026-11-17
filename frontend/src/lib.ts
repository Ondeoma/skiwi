/**
 * Scala.js（skiwi.web.editor.EditorLib）が window.__skiwiLib に置く、ライブラリに依らない処理の型と入口.
 * URL の検査・ページへのリンク・draw.io・添付のアップロード・絵文字・ブロックの一覧・外部サービスの表示など.
 * エディタ（Tiptap・ProseMirror・Yjs・Excalidraw を直接使う JS）はこれを使う.
 * エディタは Scala.js の画面が起動したあと（JsModule.load）に読み込まれるので、モジュールの初期化の時点で使ってよい.
 */
import type { Attachment, GuestPage, SkawauFile, SkeaTask } from './types'

export type ColorId = 'gray' | 'brown' | 'orange' | 'yellow' | 'green' | 'blue' | 'purple' | 'pink' | 'red'
export interface Color { id: ColorId; label: string }
export interface EmojiItem { emoji: string; name: string; keys: string }
/** このシステムのページの URL. blockId: 同期ブロック（#sync-ID）、anchor: ブロック（#block-ID） */
export interface PageUrl { workspaceId: string; pageId: string; blockId?: string; anchor?: string }
/** 表示用のページ情報. denied は削除済み・権限なし */
export interface LinkInfo { state: 'loading' | 'ok' | 'denied'; title: string; icon: string | null; workspaceId: string }
export interface GuestContext { token: string; pages: Map<string, GuestPage> }
export interface SyncedSource { title: string; content: unknown }
/** 同じページを開いている人（同時編集の awareness から） */
export interface Person { name: string; color: string; editing: boolean; avatar: string | null }
export interface MentionUser { id: string; name: string; detail: string | null }
/** スラッシュコマンドの項目のうち、一覧の表示・絞り込みに使うところ */
export interface BlockItemView { title: string; description: string; icon: string; iconClass?: string; keywords: string[]; category?: string }
export interface IconPickerOptions {
  onSelect: (icon: string | null) => void
  onClose: () => void
  head?: HTMLElement
  allowNone?: boolean
  noneLabel?: string
  at?: { x: number; y: number } | null
}
type Off = () => void

interface SkiwiLib {
  colors: Color[]
  isColor(v: unknown): v is ColorId
  findEmoji(q: string): EmojiItem[]

  /** リンクとして出してよい URL（http・https・mailto、または同じサイトのパス）。それ以外は null */
  safeLinkUrl(u: unknown): string | null
  /** 外部のページとして iframe に埋め込める URL（https で、skiwi 自身のオリジンではないもの）。それ以外は null */
  safeEmbedUrl(u: unknown): string | null
  isAttachmentPath(u: unknown): u is string
  /** 本文の属性に入った添付ファイルを取りに行く（添付ファイルのパス以外へは送らない） */
  fetchAttachment(u: unknown): Promise<Response>
  /** 埋め込みに対応しているサービスの URL なら、埋め込み用の URL */
  knownEmbedUrl(raw: string): string | null
  /** 共有 URL を埋め込み用の URL にする（https 以外は null） */
  toEmbedUrl(raw: string): string | null

  parsePageUrl(raw: string): PageUrl | null
  pageHref(workspaceId: string, pageId: string): string
  /** ゲスト向け公開ページの URL（URL 名があればそれ、なければ ID） */
  guestHref(token: string, p: { id: string; slug?: string | null }): string
  pageInfo(pageId: string): LinkInfo
  /** ゲスト表示中は、公開範囲のページだけをリンクとして出す */
  guestContext(): GuestContext | null
  /** ページ情報・ゲスト表示の状態が変わったら f を呼ぶ */
  onLinksChange(f: () => void): Off
  /** 同期ブロックの元ページのタイトルと保存済みの本文（見られなければ null） */
  fetchSyncedSource(pageId: string, guestToken: string | null): Promise<SyncedSource | null>
  /** 30秒ごと・画面に戻ったときに refresh を呼ぶ */
  onSyncedRefresh(refresh: () => void): Off
  /** 本文（ProseMirror JSON）の中の同期ブロック */
  findSyncedBlocks(doc: unknown): { id: string; text: string; node: Record<string, unknown> }[]

  openNewTab(url: string): void
  skawauPatterns(base: string): { href: RegExp; text: RegExp }
  skawauTitle(connected: boolean, file: SkawauFile | null | undefined): string
  skawauChip(url: string, file: SkawauFile): HTMLElement
  skeaPattern(projectKeys: string[]): RegExp | null
  skeaTitle(connected: boolean, task: SkeaTask | null | undefined): string
  skeaChip(url: string, task: SkeaTask | null | undefined): HTMLElement

  /** サーバーの設定（DRAWIO_URL）。空ならエディタを使えない */
  drawioUrl(): Promise<string>
  drawioEditorSrc(base: string): string
  /** 図の中の文字（検索用） */
  drawioLabels(xml: string): Promise<string>
  /** 図のページ名（既定の「ページ-1」以外） */
  drawioTitle(xml: string): string | null
  drawioFileName(title: string | null | undefined): string
  dataUrlToFile(dataUrl: string, name: string): File
  urlToDataUrl(url: string): Promise<string>

  /** アップロードして、本文に入れるノードにする（failed: 失敗の知らせ。すべて成功なら null） */
  uploadFiles(pageId: string, files: File[]): Promise<{ nodes: unknown[]; failed: string | null }>
  uploadAttachment(pageId: string, file: File): Promise<Attachment>
  /** ページの本文（ProseMirror JSON）。読めなければ null */
  pageContent(pageId: string): Promise<unknown | null>
  searchUsers(q: string, limit: number): Promise<MentionUser[]>
  /** URL のカードの情報（取れなければ null） */
  unfurl(url: string): Promise<{ title?: string; description?: string; image?: string; siteName?: string; icon?: string } | null>

  pickFiles(accept: string, multiple?: boolean): Promise<File[]>
  blockCategory(title: string): string
  filterBlocks<T extends BlockItemView>(items: T[], query: string): T[]
  /** ブロックの一覧のモーダル。選んだ項目（取り消しは null） */
  openBlockPicker<T extends BlockItemView>(items: T[], query: string): Promise<T | null>
  /** 絵文字アイコンの選択を parent の下に開く。戻り値で閉じる */
  openIconPicker(parent: HTMLElement, opts: IconPickerOptions): Off
  /** 外をクリック（mousedown）したら閉じる。escape: Esc キーでも呼ぶ / defer: 次のタスクで登録する */
  onDismiss(inside: (target: Node) => boolean, close: () => void, opts?: { escape?: () => void; defer?: boolean }): Off
  /** クリップボードへコピーし、ボタンの文字を1.5秒「コピーしました」にする */
  copyWithFlash(btn: HTMLElement, text: string, label: string, done?: string): Promise<void>
  /** ⌘A / Ctrl+A で本文（el の中）だけを選ぶ */
  selectArticleOnModA(el: () => HTMLElement): Off
  drawStatus(el: HTMLElement, revoked: boolean, state: string, synced: boolean, canEdit: boolean, uploading: number): void
  /** awareness の状態から、自分以外の人（同じ人の複数タブは1人にまとめる） */
  people(states: Map<number, unknown>, self: number): Person[]
  isDark(): boolean
  onThemeChange(f: (dark: boolean) => void): Off
  download(filename: string, blob: Blob): void
  /** この端末の時刻での日付 YYYY-MM-DD */
  ymd(d: Date): string
  toBase64(u: Uint8Array): string
  blobToDataUrl(blob: Blob): Promise<string>
}

const lib = (globalThis as unknown as { __skiwiLib: SkiwiLib }).__skiwiLib

export const COLORS = lib.colors
export const {
  isColor, findEmoji, safeLinkUrl, safeEmbedUrl, isAttachmentPath, fetchAttachment, knownEmbedUrl, toEmbedUrl,
  parsePageUrl, pageHref, guestHref, pageInfo, guestContext, onLinksChange, fetchSyncedSource, onSyncedRefresh, findSyncedBlocks,
  openNewTab, skawauPatterns, skawauTitle, skawauChip, skeaPattern, skeaTitle, skeaChip,
  drawioUrl, drawioEditorSrc, drawioLabels, drawioTitle, drawioFileName, dataUrlToFile, urlToDataUrl,
  uploadFiles, uploadAttachment, pageContent, searchUsers, unfurl,
  pickFiles, blockCategory, filterBlocks, openBlockPicker, openIconPicker, onDismiss, copyWithFlash, selectArticleOnModA, drawStatus, people,
  isDark, onThemeChange, download, ymd, toBase64, blobToDataUrl,
} = lib
