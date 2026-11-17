import type { IntegrationStatus, Me, Page, ServiceName, Workspace } from '../api'

/**
 * Scalactive（Scala.js）の画面が用意する機能（skiwi.web.Host.exposeToJs が window.__skiwiHost に置く）.
 * エディタなどの JS からは、画面の移動・ダイアログ・ページの一覧をこれで使う.
 */
export interface DialogChoice { label: string; value: string; detail?: string }
export interface SkiwiHost {
  /** 画面を移動する（履歴に積む）. replace は履歴を置き換える */
  navigate(path: string): void
  replace(path: string): void
  confirm(message: string, opts: { okLabel?: string; danger?: boolean }): Promise<boolean>
  prompt(message: string, value: string, opts: { okLabel?: string; placeholder?: string; multiline?: boolean }): Promise<string | null>
  choose(message: string, options: DialogChoice[]): Promise<string | null>
  alert(message: string): Promise<void>
  /** ログイン中の自分（ゲスト表示などでは null） */
  me(): Me | null
  workspaces(): Workspace[]
  /** 開いているワークスペースのページの一覧 */
  pages(): Page[]
  currentWorkspaceId(): string | null
  reloadPages(): Promise<void>
  /** ページの一覧が変わったら f を呼ぶ. 戻り値で解除 */
  onPagesChange(f: () => void): () => void
  /** Scalactive の部品を el に描画し、片付ける関数を返す（例: ページの中のデータベース） */
  mountView(name: string, el: Element, params: Record<string, unknown>): () => void
  /** ページの中に置くデータベースを選ぶか、pageId の子ページとして新しく作る（取り消しは null） */
  pickDatabase(pageId: string): Promise<string | null>
  /** ページを選ぶ（取り消しは null） */
  pickPage(): Promise<{ id: string; workspaceId: string; title: string } | null>
  /** 外部サービス（skea・skawau）との連携の状態と項目（Scala の workspace.Integrations） */
  integration<T = unknown>(name: ServiceName): HostIntegration<T>
}

/** 外部サービスひとつとの連携（ログイン中の画面で共有する. ゲスト向けの公開ページでは使わない） */
export interface HostIntegration<T = unknown> {
  /** 今の状態（読めていない・連携なしは null） */
  status(): IntegrationStatus | null
  /** 状態を読む（1回だけ. force で読み直す）. 読めなければ null（連携なしとして扱う） */
  load(force?: boolean): Promise<IntegrationStatus | null>
  /** 状態や項目が変わったときに呼ぶ（エディタの表示の更新）. 戻り値で解除 */
  onChange(f: () => void): () => void
  /** 分かっている項目（undefined はまだ分からない・取れなかった、null は見つからない・見る権限がない） */
  item(key: string): T | null | undefined
  /** まだ知らない（または古い）項目だけ、まとめて問い合わせる. 結果が届いたら onChange に知らせる */
  request(keys: string[]): void
}

export const host = (): SkiwiHost => (globalThis as unknown as { __skiwiHost: SkiwiHost }).__skiwiHost

/** ページの一覧（Scalactive の Store.pages）と、ID からの引き当て */
export const pages = () => host().pages()
export const pageById = (id: string) => host().pages().find((p) => p.id === id)
