/**
 * ホワイトボード本体（Excalidraw）をDOMに組み込む。Vue側からは React を意識しなくてよいよう、ここに閉じ込める。
 *
 * 同時編集: ページと同じ Y.Doc のルート whiteboards（Y.Map）に、ボードID → ボード（Y.Map）を持つ。
 *   e … 図形ID → 図形（Excalidraw の要素。version が大きい方を採用）
 *   f … 画像ファイルID → { mimeType, url }（実体は添付ファイルとしてアップロード）
 * ルートは接続前に登録しておく（Editor.vue）。後から getMap すると、届いていた内容の型の付け替えが起き、
 * 同期の符号化に失敗することがあるため、ボードごとのルートは作らない。
 * 他の人のカーソル位置は y-websocket の awareness で共有する。
 * Y.Doc がない表示（版のプレビュー・ゲスト表示）では、ノードに保存したスナップショットを読み取り専用で出す。
 */
import * as Y from 'yjs'
import { BOARDS_ROOT_NAME as BOARDS_ROOT, type Awareness, type BoardElement, type FileMeta, type Snapshot } from './shared'

/* eslint-disable @typescript-eslint/no-explicit-any */
type Element = BoardElement
export type { Snapshot }

export interface BoardOptions {
  id: string
  snapshot: Snapshot | null
  editable: boolean
  dark: boolean
  ydoc?: Y.Doc | null
  awareness?: Awareness | null
  user?: { name: string; color: string }
  /** 画像のアップロード（URLを返す） */
  uploadFile?: (file: File) => Promise<string>
  /** 画像URLの読み替え（ゲスト表示の公開リンク経由など） */
  mapUrl?: (url: string) => string
  /**
   * 自分の変更のたびに呼ぶ（スナップショット保存用）。変更のたびに全要素を写すと重いので、
   * スナップショットは snapshot() を呼んだときに作る（間引いて保存するときに呼ぶ）
   */
  onLocalChange?: (snapshot: () => Snapshot) => void
}

export interface BoardHandle {
  setEditable(v: boolean): void
  setDark(v: boolean): void
  refresh(): void
  /** 外から渡されたスナップショット（Y.Docなしの表示で、内容が変わったとき） */
  setSnapshot(s: Snapshot | null): void
  exportPng(): Promise<Blob | null>
  destroy(): void
}

if (typeof window !== 'undefined') (window as any).EXCALIDRAW_ASSET_PATH = `${location.origin}/excalidraw/`

const LOCAL = Symbol('whiteboard-local')
/** Y.Doc のルート名（Editor.vue が接続前に登録する） */
import { blobToDataUrl } from '../../util/bytes'


export async function mountBoard(container: HTMLElement, o: BoardOptions): Promise<BoardHandle> {
  const [React, ReactDOM, ex] = await Promise.all([import('react'), import('react-dom/client'), import('@excalidraw/excalidraw')])
  await import('@excalidraw/excalidraw/index.css')
  const h = React.createElement

  const root = o.ydoc?.getMap<Y.Map<any>>(BOARDS_ROOT) ?? null
  let board: Y.Map<any> | null = null
  let ymap: Y.Map<Element> | null = null
  let yfiles: Y.Map<FileMeta> | null = null
  /** 共有データのボードにつなぐ（つなぎ替えたら true） */
  function bind(): boolean {
    const b = root?.get(o.id)
    if (!(b instanceof Y.Map) || b === board) return false
    ymap?.unobserve(onRemote)
    yfiles?.unobserve(onFiles)
    board = b
    ymap = b.get('e') as Y.Map<Element>
    yfiles = b.get('f') as Y.Map<FileMeta>
    ymap.observe(onRemote)
    yfiles.observe(onFiles)
    return true
  }

  // 初期内容: 共有データがあればそれ、なければスナップショット（編集できる人が最初に開いたとき共有データを作る）
  bind()
  if (root && !board && o.editable) {
    o.ydoc!.transact(() => {
      const b = new Y.Map<any>()
      const e = new Y.Map<Element>()
      const f = new Y.Map<FileMeta>()
      for (const el of o.snapshot?.elements ?? []) e.set(el.id, el)
      for (const [k, v] of Object.entries(o.snapshot?.files ?? {})) f.set(k, v)
      b.set('e', e)
      b.set('f', f)
      root.set(o.id, b)
    }, LOCAL)
    bind()
  }
  const shared = ymap as Y.Map<Element> | null // bind() で設定される
  const elements: Element[] = shared && shared.size > 0 ? [...shared.values()] : (o.snapshot?.elements ?? [])
  const fileMetas = (): Record<string, FileMeta> => (yfiles ? Object.fromEntries(yfiles.entries()) : (o.snapshot?.files ?? {}))

  let api: any = null
  let editable = o.editable
  let dark = o.dark
  let applyingRemote = false
  const loadedFiles = new Set<string>()
  const uploading = new Set<string>()
  let rerender: () => void = () => {}
  let unmount: () => void = () => {}
  const apiReady = new Promise<void>((resolve) => {
    const root = ReactDOM.createRoot(container)
    const render = () => root.render(h(ex.Excalidraw, {
      excalidrawAPI: (a: any) => { if (!api) { api = a; resolve() } },
      initialData: {
        elements: elements as any,
        appState: { viewBackgroundColor: o.snapshot?.background ?? '#ffffff', currentItemFontFamily: 2 },
        scrollToContent: true,
      },
      viewModeEnabled: !editable,
      theme: dark ? 'dark' : 'light',
      langCode: 'ja-JP',
      isCollaborating: !!o.awareness,
      onChange: onChange as any,
      onPointerUpdate: onPointer as any,
      UIOptions: { canvasActions: { loadScene: false, saveToActiveFile: false, toggleTheme: false } },
    }))
    rerender = render
    unmount = () => root.unmount()
    render()
  })

  // --- 自分の変更 → Y.Map ---------------------------------------------------------------
  let lastSig = ''
  function onChange(els: readonly Element[], appState: any, files: Record<string, any>) {
    if (applyingRemote) return
    if (!editable) return
    const changed: Element[] = []
    if (ymap) {
      for (const e of els) {
        const cur = ymap.get(e.id)
        if (!cur || e.version > cur.version || (e.version === cur.version && e.versionNonce !== cur.versionNonce)) changed.push(e)
      }
      const m = ymap
      if (changed.length) o.ydoc!.transact(() => { for (const e of changed) m.set(e.id, JSON.parse(JSON.stringify(e))) }, LOCAL)
    }
    // 新しく貼った画像をアップロードする
    for (const [fid, f] of Object.entries(files ?? {})) {
      if (fileMetas()[fid] || uploading.has(fid) || !o.uploadFile) continue
      uploading.add(fid)
      fetch(f.dataURL).then((r) => r.blob())
        .then((b) => o.uploadFile!(new File([b], `whiteboard-${fid}`, { type: f.mimeType })))
        .then((url) => { loadedFiles.add(fid); yfiles?.set(fid, { mimeType: f.mimeType, url }); emitSnapshot(api?.getSceneElements() ?? els, appState) })
        .catch((e) => console.warn('[whiteboard] upload failed', e))
        .finally(() => uploading.delete(fid))
    }
    const sig = els.map((e) => `${e.id}:${e.version}`).join(',') + appState.viewBackgroundColor
    if (sig !== lastSig) { lastSig = sig; emitSnapshot(els, appState) }
  }
  function emitSnapshot(els: readonly Element[], appState: any) {
    const background = appState?.viewBackgroundColor
    o.onLocalChange?.(() => ({
      elements: els.filter((e) => !e.isDeleted).map((e) => JSON.parse(JSON.stringify(e))),
      background,
      files: fileMetas(),
    }))
  }

  // --- 他の人の変更 → 画面 ---------------------------------------------------------------
  function onRemote(evt: Y.YMapEvent<Element>) {
    if (evt.transaction.origin === LOCAL || !api) return
    const local = new Map<string, Element>((api.getSceneElementsIncludingDeleted() as Element[]).map((e) => [e.id, e]))
    for (const key of evt.keysChanged) {
      const r = (evt.target as Y.Map<Element>).get(key)
      if (!r) continue
      const l = local.get(key)
      if (!l || r.version > l.version || (r.version === l.version && r.versionNonce < l.versionNonce)) local.set(key, r)
    }
    applyingRemote = true
    try { api.updateScene({ elements: [...local.values()], captureUpdate: ex.CaptureUpdateAction.NEVER }) } finally { applyingRemote = false }
  }

  // ボードの共有データが後から届いた／同時に作られて別のものに決まったとき: つなぎ替えて、自分の図形を移す
  const onRoot = (evt: Y.YMapEvent<Y.Map<any>>) => {
    if (!evt.keysChanged.has(o.id) || !bind() || !api) return
    const mine = api.getSceneElementsIncludingDeleted() as Element[]
    if (editable) {
      o.ydoc!.transact(() => {
        for (const e of mine) {
          const cur = ymap!.get(e.id)
          if (!cur || e.version > cur.version) ymap!.set(e.id, JSON.parse(JSON.stringify(e)))
        }
      }, LOCAL)
    }
    applyingRemote = true
    try { api.updateScene({ elements: [...ymap!.values()] as any, captureUpdate: ex.CaptureUpdateAction.NEVER }) } finally { applyingRemote = false }
    void loadFiles()
  }
  root?.observe(onRoot)

  // --- 画像の読み込み ---------------------------------------------------------------------
  async function loadFiles() {
    if (!api) return
    const metas = fileMetas()
    for (const [fid, m] of Object.entries(metas)) {
      if (loadedFiles.has(fid)) continue
      loadedFiles.add(fid)
      try {
        const blob = await fetch(o.mapUrl ? o.mapUrl(m.url) : m.url, { credentials: 'same-origin' }).then((r) => { if (!r.ok) throw new Error(String(r.status)); return r.blob() })
        api.addFiles([{ id: fid, mimeType: m.mimeType, dataURL: await blobToDataUrl(blob), created: Date.now() }])
      } catch (e) { loadedFiles.delete(fid); console.warn('[whiteboard] image load failed', e) }
    }
  }
  function onFiles() { void loadFiles() }

  // --- 他の人のカーソル -------------------------------------------------------------------
  function onPointer(p: { pointer: { x: number; y: number; tool: string }; button: string }) {
    o.awareness?.setLocalStateField('whiteboard', { id: o.id, pointer: p.pointer, button: p.button })
  }
  const onAwareness = () => {
    if (!api || !o.awareness) return
    const collaborators = new Map<string, any>()
    o.awareness.getStates().forEach((st, client) => {
      if (client === o.awareness!.clientID) return
      const wb = st.whiteboard
      if (!wb || wb.id !== o.id) return
      const color = st.user?.color ?? '#7c3aed'
      collaborators.set(String(client), { pointer: wb.pointer, button: wb.button, username: st.user?.name ?? '', color: { background: color, stroke: color } })
    })
    api.updateScene({ collaborators, captureUpdate: ex.CaptureUpdateAction.NEVER })
  }
  o.awareness?.on('change', onAwareness)

  await apiReady
  void loadFiles()

  return {
    setEditable(v) { if (v !== editable) { editable = v; rerender() } },
    setDark(v) { if (v !== dark) { dark = v; rerender() } },
    refresh() { api?.refresh() },
    setSnapshot(s) {
      if (ymap || !api) return
      o.snapshot = s
      api.updateScene({ elements: (s?.elements ?? []) as any, appState: { viewBackgroundColor: s?.background ?? '#ffffff' }, captureUpdate: ex.CaptureUpdateAction.NEVER })
      void loadFiles()
    },
    async exportPng() {
      if (!api) return null
      return ex.exportToBlob({ elements: api.getSceneElements(), appState: { ...api.getAppState(), exportBackground: true }, files: api.getFiles(), mimeType: 'image/png' })
    },
    destroy() {
      ymap?.unobserve(onRemote)
      yfiles?.unobserve(onFiles)
      root?.unobserve(onRoot)
      o.awareness?.off('change', onAwareness)
      o.awareness?.setLocalStateField('whiteboard', null)
      unmount()
    },
  }
}
