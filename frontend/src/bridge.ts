/**
 * Scala.js の画面から使う JS の機能（エディタ・取り込み・書き出しなど）.
 * 大きな部品を最初の読み込みに含めないよう、使うときに読み込む: window.skiwiLoad('editor') → モジュール.
 * 読み込めるものは bridge/*.ts の default（名前 → 読み込む関数）に書く（skiwi.web.JsModule から使う）.
 */
type Loader = () => Promise<unknown>
const all: Record<string, Loader> = {}
for (const m of Object.values(import.meta.glob<{ default: Record<string, Loader> }>('./bridge/*.ts', { eager: true }))) Object.assign(all, m.default)

;(globalThis as unknown as { skiwiLoad: (name: string) => Promise<unknown> }).skiwiLoad = (name: string) => {
  const f = all[name]
  return f ? f() : Promise.reject(new Error(`skiwiLoad: unknown module ${name}`))
}
