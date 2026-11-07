import type { Ref } from 'vue'
import { createRequest } from './request'
import { api4 } from '../api'

/**
 * 画面内の確認・入力ダイアログ。
 * window.confirm / prompt / alert は埋め込みブラウザ等で無効化されることがある（即座に false / null が返る）ため使わない。
 */
export interface DialogRequest {
  kind: 'confirm' | 'prompt' | 'alert' | 'choose'
  /** choose: 選択肢（value を返す） */
  options?: { label: string; value: string; detail?: string }[]
  message: string
  value: string
  placeholder?: string
  okLabel: string
  danger: boolean
  multiline: boolean
  resolve: (v: string | boolean | null) => void
}

// 既に開いているものは取り消し扱いで閉じる
const req = createRequest<Omit<DialogRequest, 'resolve'>, string | boolean | null>((next) => (next.kind === 'confirm' ? false : null))
export const currentDialog = req.current
const open = req.open

export async function confirmDialog(message: string, opts: { okLabel?: string; danger?: boolean } = {}): Promise<boolean> {
  return (await open({ kind: 'confirm', message, value: '', okLabel: opts.okLabel ?? 'OK', danger: opts.danger ?? false, multiline: false })) === true
}

export async function promptDialog(
  message: string,
  value = '',
  opts: { okLabel?: string; placeholder?: string; multiline?: boolean } = {},
): Promise<string | null> {
  const v = await open({ kind: 'prompt', message, value, placeholder: opts.placeholder, okLabel: opts.okLabel ?? 'OK', danger: false, multiline: opts.multiline ?? false })
  return typeof v === 'string' ? v : null
}

/** 選択肢から1つ選ぶ。取り消しは null */
export async function chooseDialog(message: string, options: { label: string; value: string; detail?: string }[]): Promise<string | null> {
  const v = await open({ kind: 'choose', message, value: '', okLabel: '', danger: false, multiline: false, options })
  return typeof v === 'string' ? v : null
}

export async function alertDialog(message: string): Promise<void> {
  await open({ kind: 'alert', message, value: '', okLabel: 'OK', danger: false, multiline: false })
}

/** 失敗を「（what）: 理由」で知らせる。例: alertError('変更できませんでした', e) */
export const alertError = (what: string, e: unknown) => alertDialog(`${what}: ${(e as Error).message}`)

/** f を実行し、失敗したら「操作に失敗しました」と知らせる。busy: 実行中は true にする */
export async function guarded(f: () => Promise<unknown>, busy?: Ref<boolean>) {
  if (busy) busy.value = true
  try { await f() } catch (e) { await alertError('操作に失敗しました', e) } finally { if (busy) busy.value = false }
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
