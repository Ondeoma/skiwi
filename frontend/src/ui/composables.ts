import { onBeforeUnmount, onMounted, onScopeDispose, ref, watch, type Ref } from 'vue'

/**
 * 入力が落ち着いてから検索する。古い応答で新しい結果を上書きしないよう、
 * 実行する関数には「まだ最新の呼び出しか」を返す latest() を渡す。
 * run: delay 後に実行 / now: すぐ実行 / cancel: 待っているものを取り消し、実行中の応答も無視する
 */
export function useDebouncedSearch(delay: number) {
  let timer: ReturnType<typeof setTimeout> | undefined
  let seq = 0
  type Fn = (latest: () => boolean) => unknown
  const start = (fn: Fn) => { clearTimeout(timer); const my = ++seq; return () => fn(() => my === seq) }
  const run = (fn: Fn) => { timer = setTimeout(start(fn), delay) }
  const now = (fn: Fn) => start(fn)()
  const cancel = () => { clearTimeout(timer); seq++ }
  onScopeDispose(cancel)
  return { run, now, cancel }
}

/** 表示している間だけ listener を付ける（マウントで付け、アンマウントで外す） */
export function useEventListener<E extends Event = Event>(target: EventTarget, type: string, handler: (e: E) => void, options?: boolean | AddEventListenerOptions) {
  const h = handler as EventListener
  onMounted(() => target.addEventListener(type, h, options))
  onBeforeUnmount(() => target.removeEventListener(type, h, options))
}

/** キー操作の対象が文字の入力欄・エディタか（ショートカットを効かせない） */
export const isTyping = (t: EventTarget | null) => t instanceof HTMLElement && (t.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName))

/**
 * メニューなどの外をクリック（mousedown）したら閉じる。escape を渡すと Esc キーでも呼ぶ。
 * inside(target): 閉じないクリックか / defer: 開いたクリック自体で閉じないよう、次のタスクで登録する
 */
export function useDismiss(inside: (target: Node) => boolean, close: () => void, opts: { escape?: () => void; defer?: boolean } = {}) {
  const onDoc = (e: MouseEvent) => { if (!inside(e.target as Node)) close() }
  const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') opts.escape?.() }
  let alive = true
  onMounted(() => {
    if (opts.defer) setTimeout(() => { if (alive) document.addEventListener('mousedown', onDoc) })
    else document.addEventListener('mousedown', onDoc)
    if (opts.escape) document.addEventListener('keydown', onKey)
  })
  onBeforeUnmount(() => {
    alive = false
    document.removeEventListener('mousedown', onDoc)
    document.removeEventListener('keydown', onKey)
  })
}

/** localStorage（使えない環境では読めない・保存しないだけ） */
function loadStored(key: string): string | null {
  try { return localStorage.getItem(key) } catch { return null }
}
function saveStored(key: string, value: string) {
  try { localStorage.setItem(key, value) } catch { /* 保存できない環境 */ }
}

/**
 * このブラウザに保存する ref。parse: 保存値（未保存・読めないときは null）から値を作る。
 * serialize: 保存する文字列（null なら保存しない）
 */
export function useStoredRef<T>(key: string | (() => string), parse: (raw: string | null) => T, serialize: (v: T) => string | null = String): Ref<T> {
  const k = () => (typeof key === 'string' ? key : key())
  const r = ref(parse(loadStored(k()))) as Ref<T>
  watch(r, (v) => { const s = serialize(v); if (s !== null) saveStored(k(), s) })
  return r
}

/**
 * 開閉できるサイドメニュー。広い画面では横に置き、開閉をこのブラウザに保存する（key）。
 * 狭い画面（narrowQuery に一致）では閉じた状態から始め、狭くなったときも閉じる（保存はしない）
 */
export function useSideMenu(key: string, narrowQuery: string) {
  const mq = window.matchMedia(narrowQuery)
  const narrow = ref(mq.matches)
  const open = useStoredRef(key, (raw) => (narrow.value ? false : raw !== '0'), (v) => (narrow.value ? null : v ? '1' : '0'))
  useEventListener(mq, 'change', () => { narrow.value = mq.matches; if (narrow.value) open.value = false })
  return { narrow, open }
}

/**
 * クリップボードへのコピーと「コピーしました」の表示（1.5秒）。copied はコピーした key（既定 true）。
 * flashOnError: コピーできなかったときも表示する
 */
export function useCopy<K = true>(opts: { flashOnError?: boolean } = {}) {
  const copied = ref(null) as Ref<K | null>
  async function copy(text: string, key: K = true as K) {
    const ok = await navigator.clipboard.writeText(text).then(() => true, () => false)
    if (!ok && !opts.flashOnError) return
    copied.value = key
    setTimeout(() => (copied.value = null), 1500)
  }
  return { copied, copy }
}
