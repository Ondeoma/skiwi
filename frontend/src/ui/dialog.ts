import { api4 } from '../api'
import { host, type DialogChoice } from './host'

/**
 * 画面内の確認・入力ダイアログ（Scalactive の skiwi.web.Dialog を使う）。
 * window.confirm / prompt / alert は埋め込みブラウザ等で無効化されることがある（即座に false / null が返る）ため使わない。
 */
export const confirmDialog = (message: string, opts: { okLabel?: string; danger?: boolean } = {}): Promise<boolean> =>
  host().confirm(message, opts)

export const promptDialog = (message: string, value = '', opts: { okLabel?: string; placeholder?: string; multiline?: boolean } = {}): Promise<string | null> =>
  host().prompt(message, value, opts)

/** 選択肢から1つ選ぶ。取り消しは null */
export const chooseDialog = (message: string, options: DialogChoice[]): Promise<string | null> => host().choose(message, options)

export const alertDialog = (message: string): Promise<void> => host().alert(message)

/** 失敗を「（what）: 理由」で知らせる。例: alertError('変更できませんでした', e) */
export const alertError = (what: string, e: unknown) => alertDialog(`${what}: ${(e as Error).message}`)

/** f を実行し、失敗したら「操作に失敗しました」と知らせる。busy: 実行中は true にする（呼び出し側の状態を変える関数） */
export async function guarded(f: () => Promise<unknown>, busy?: (b: boolean) => void) {
  busy?.(true)
  try { await f() } catch (e) { await alertError('操作に失敗しました', e) } finally { busy?.(false) }
}

/**
 * ユーザーを名前・メールで検索して1人選ぶ（1人だけ一致すればそのまま、複数なら選択肢を出す）。
 * 戻り値: undefined=取り消し・該当なし、null=空欄で決定（allowEmpty のときだけ）、それ以外=選んだユーザー
 */
export async function pickUser(
  message: string,
  opts: { initial?: string; chooseMessage: string; allowEmpty?: boolean },
): Promise<{ id: string; name: string } | null | undefined> {
  const q = (await promptDialog(message, opts.initial ?? '', { okLabel: '検索' }))?.trim()
  if (q === undefined) return undefined
  if (q === '') return opts.allowEmpty ? null : undefined
  const users = (await api4.directory(q).catch(() => [])).filter((d) => d.principalType === 'user')
  if (!users.length) { await alertDialog(`「${q}」に一致するユーザーがいません`); return undefined }
  const id = users.length === 1 ? users[0].id : await chooseDialog(opts.chooseMessage, users.slice(0, 10).map((u) => ({ label: `👤 ${u.name}`, value: u.id, detail: u.detail ?? undefined })))
  const u = id ? users.find((x) => x.id === id) : undefined
  return u ? { id: u.id, name: u.name } : undefined
}
