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
