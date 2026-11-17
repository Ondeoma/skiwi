/**
 * Scala.js（Scalactive）の画面から使うエディタの機能（window.skiwiLoad('editor') で読み込む）。
 * 旧 components/Editor.vue・BlockMenu.vue・TableControls.vue を素の DOM にしたもの（DOM・data-testid・文言は元と同じ）。
 */
import { Editor, type Editor as CoreEditor, type JSONContent } from '@tiptap/core'
import { BubbleMenuPlugin } from '@tiptap/extension-bubble-menu'
import { DragHandlePlugin, defaultComputePositionConfig, normalizeNestedOptions } from '@tiptap/extension-drag-handle'
import { NodeSelection, PluginKey, type EditorState } from '@tiptap/pm/state'
import { CellSelection } from '@tiptap/pm/tables'
import { isNodeRangeSelection } from '@tiptap/extension-node-range'
import type { Node as PMNode } from '@tiptap/pm/model'
import * as Y from 'yjs'
import * as encoding from 'lib0/encoding'
import * as syncProtocol from 'y-protocols/sync'
import 'katex/dist/katex.min.css'
import './editor.css'
import { uploadAttachment, type Attachment, type DocState, type Role } from '../api'
import { buildExtensions, contentExtensions } from './extensions'
import { restoreBoards } from './whiteboard/restore'
import { MSG, answerCompactRequests, connectPage, onRole } from './collab'
import { alertDialog, promptDialog } from '../ui/dialog'
import { host } from '../ui/host'
import { COLORS } from '../ui/palette'
import { pickFiles } from '../ui/fileDrop'
import { knownEmbedUrl } from './Embed'
import { isDrawioPng, isDrawioXml, labelsOf, xmlFromPng } from './drawioUtil'
import { bytesToBase64 } from '../util/bytes'
import { safeAvatarUrl, type Person } from '../ui/avatar'
import { h, setChildren } from '../ui/dom'
import { openBlockMenu } from './blockMenu'
import { tableControls } from './tableControls'

export { pageHref, guestHref, setGuestContext } from './pageLinks'

export interface CreateOptions {
  pageId: string
  wid?: string
  /** 編集モードかどうか（実際に編集できるのは、サーバーが通知する権限が edit のときだけ） */
  editable: boolean
  userName: string
  userAvatar?: string | null
  onRole?: (role: Role | 'none') => void
  /** 選択した文章にコメントする */
  onComment?: (d: { from: number; to: number; quote: string }) => void
  /** コメントの付いた文章をクリックした */
  onCommentClick?: (anchorId: string) => void
  /** このページを開いているほかの人（同じ人の複数タブは1人にまとめる） */
  onPresence?: (people: Person[]) => void
}

export interface EditorHandle {
  editor: Editor
  setEditable(b: boolean): void
  /** 現在の文書状態（Yjsの全状態＋表示用JSON＋平文）。 */
  getState(): DocState
  /** 版の本文で置き換える。通常の編集としてYjs経由で全員に同期される（取り消し可能な通常の変更）。 */
  applyContent(json: unknown): void
  isSynced(): boolean
  getJSON(): JSONContent
  /** from〜to の文字にコメントの印を付ける */
  setCommentMark(from: number, to: number, id: string): void
  unsetCommentMark(id: string): void
  isDestroyed(): boolean
  destroy(): void
}

const COLORS_USER = ['#e11d48', '#2563eb', '#16a34a', '#d97706', '#7c3aed', '#0891b2']

/** ページの本文のエディタ（同時編集）を el の中に作る */
export function create(el: HTMLElement, opts: CreateOptions): EditorHandle {
  const color = COLORS_USER[Math.floor(Math.random() * COLORS_USER.length)]
  let editable = opts.editable

  const { ydoc, provider } = connectPage(opts.pageId)
  let state: 'connecting' | 'connected' | 'disconnected' = 'connecting'
  let synced = false
  let uploading = 0
  /** サーバーが通知する現在の権限。接続中に共有設定が変わると更新される。 */
  let canEdit = true
  let revoked = false

  // --- DOM ---------------------------------------------------------------------------------
  const statusEl = h('div', { class: 'status' })
  const content = h('div', { 'data-testid': 'editor' })
  const area = h('div', { class: 'editor-area', onclick: onAreaClick }, content)
  const root = h('div', { class: 'skiwi-editor' }, statusEl, area)
  el.append(root)

  function drawStatus() {
    statusEl.className = `status ${revoked ? 'disconnected' : state}`
    setChildren(statusEl,
      h('span', null, revoked ? '● このページへのアクセス権がなくなりました'
        : state === 'connected' && synced ? '● 同期済み'
          : state === 'connected' ? '● 同期中…'
            : state === 'connecting' ? '● 接続中…'
              : '● オフライン（再接続します。編集は保持されます）'),
      !canEdit && !revoked && h('span', null, ' ・ 閲覧のみ'),
      uploading > 0 && h('span', { 'data-testid': 'uploading' }, ` ・ アップロード中…（${uploading}件）`))
  }

  // --- オフライン/切断中の編集を救済する -------------------------------------------------
  // サーバーはstate vectorを持たないため、切断中・接続前のローカル編集は
  // 同期完了後に全状態をupdateとして再送する（CRDTなので冪等）。
  let needsResend = false
  ydoc.on('update', (_u: Uint8Array, origin: unknown) => {
    if (origin !== provider && !provider.synced) needsResend = true
  })
  function sendFullState() {
    const enc = encoding.createEncoder()
    encoding.writeVarUint(enc, MSG.SYNC)
    syncProtocol.writeUpdate(enc, Y.encodeStateAsUpdate(ydoc))
    provider.ws?.send(encoding.toUint8Array(enc))
  }
  provider.on('status', (e: { status: 'connecting' | 'connected' | 'disconnected' }) => {
    state = e.status
    if (e.status === 'disconnected') needsResend = true
    drawStatus()
  })
  provider.on('sync', (s: boolean) => {
    synced = s
    if (s && needsResend && canEdit) { needsResend = false; sendFullState() }
    drawStatus()
  })

  // --- 添付ファイル ------------------------------------------------------------------------
  const pickFile = async (accept: string) => (await pickFiles(accept))[0] ?? null

  /** アップロードした添付を本文に入れる形に（画像は画像として、それ以外はファイルのカード） */
  /** drawio: draw.io の図のファイル（.drawio.png・.drawio）なら、図の中の文字 */
  function nodeOf(a: Attachment, drawio?: string | null) {
    if (drawio != null) {
      const title = a.filename.replace(/\.(drawio|dio)(\.png)?$/i, '')
      return isDrawioPng(a.filename)
        ? { type: 'drawio', attrs: { src: a.url, title, text: drawio } }
        : { type: 'drawio', attrs: { xmlSrc: a.url, title, text: drawio } }
    }
    const isImage = a.contentType.startsWith('image/') && a.contentType !== 'image/svg+xml'
    return isImage
      ? { type: 'image', attrs: { src: a.url, alt: a.filename } }
      : { type: 'fileBlock', attrs: { id: a.id, url: a.url, filename: a.filename, size: a.size, contentType: a.contentType } }
  }

  /** ページの中に置くデータベース: 既存のものを選ぶか、このページの子ページとして新しく作る（Scala の Host.pickDatabase） */
  const pickDatabase = (): Promise<string | null> => host().pickDatabase(opts.pageId)

  async function uploadAt(file: File, pos?: number) { await uploadMany([file], pos) }

  /** 複数のファイルを同時にアップロードし、選んだ順に入れる（貼り付けはカーソルの位置、ドロップは落とした位置）。
   *  貼り付けた画像に名前がない（スクリーンショットなど）ときは、日時から名前を付ける。 */
  async function uploadMany(files: File[], pos?: number) {
    if (!editor.isEditable || !files.length) return
    uploading += files.length
    drawStatus()
    const stamp = new Date().toISOString().slice(0, 19).replace(/[-:]/g, '').replace('T', '-')
    const named = files.map((f, i) => (!f.name || /^image\.(png|jpe?g|gif|webp)$/i.test(f.name))
      ? new File([f], `貼り付け-${stamp}${files.length > 1 ? `-${i + 1}` : ''}.${(f.type.split('/')[1] ?? 'png').replace('jpeg', 'jpg')}`, { type: f.type })
      : f)
    try {
      // draw.io の図（図を埋め込んだ PNG・図の XML）は、画像・ファイルではなく図のブロックにする
      const drawio = await Promise.all(named.map(async (f) => {
        try {
          const xml = isDrawioPng(f.name) ? await xmlFromPng(await f.arrayBuffer()) : isDrawioXml(f.name) ? await f.text() : null
          return xml && xml.includes('<mx') ? await labelsOf(xml) : null
        } catch { return null }
      }))
      const results = await Promise.allSettled(named.map((f) => uploadAttachment(opts.pageId, f)))
      const ok = results.flatMap((r, i) => (r.status === 'fulfilled' ? [nodeOf(r.value, drawio[i])] : []))
      if (ok.length && !editor.isDestroyed) {
        if (pos != null) editor.chain().insertContentAt(Math.min(pos, editor.state.doc.content.size), ok).run()
        else editor.chain().focus().insertContent(ok).run()
      }
      const failed = results.map((r, i) => (r.status === 'rejected' ? `・${named[i].name}: ${(r.reason as Error).message}` : '')).filter(Boolean)
      if (failed.length) await alertDialog(`アップロードに失敗したファイルがあります:\n${failed.join('\n')}`)
    } finally {
      uploading -= files.length
      drawStatus()
    }
  }

  const editor: Editor = new Editor({
    element: content,
    editable,
    extensions: buildExtensions({
      ydoc, provider,
      user: { name: opts.userName, color, avatar: opts.userAvatar ?? null },
      ctx: { pickFile, upload: (f) => uploadAt(f), pickDatabase },
      uploadAt,
      uploadMany,
      getEditor: () => editor,
      uploadFile: async (f) => (await uploadAttachment(opts.pageId, f)).url,
    }),
  })

  // --- サーバーからのスナップショット要求(圧縮用) ------------------------------------------
  // 表示モードでも編集権限があれば応答する（全状態を持っているため）
  answerCompactRequests(provider, ydoc, () => (canEdit && synced ? editor : null))

  // --- 権限の通知（接続時・共有設定の変更時） ----------------------------------------------
  onRole(provider, (role) => {
    const r = role as Role | 'none'
    opts.onRole?.(r)
    if (r === 'none') {
      revoked = true
      canEdit = false
      editor.setEditable(false)
      provider.shouldConnect = false // 自動再接続しない
      provider.disconnect()
      applyEditable()
      return
    }
    canEdit = r === 'edit'
    applyEditable()
  })
  function applyEditable() {
    const want = canEdit && editable
    if (editor.isEditable !== want) editor.setEditable(want)
    provider.awareness.setLocalStateField('mode', want ? 'edit' : 'view')
    area.classList.toggle('readonly', !want)
    setDragHandle(want)
    drawStatus()
    refreshTextMenu()
  }

  // --- 開いている人（awareness） ------------------------------------------------------------
  function emitPresence() {
    const people = new Map<string, Person>()
    provider.awareness.getStates().forEach((st, client) => {
      if (client === provider.awareness.clientID || !st.user?.name) return
      const cur = people.get(st.user.name)
      const editing = st.mode === 'edit' || !!cur?.editing
      people.set(String(st.user.name), { name: String(st.user.name), color: /^#[0-9a-f]{6}$/i.test(String(st.user.color)) ? st.user.color : '#888888', editing, avatar: safeAvatarUrl(st.user.avatar) ?? cur?.avatar ?? null })
    })
    opts.onPresence?.([...people.values()].sort((a, b) => a.name.localeCompare(b.name, 'ja')))
  }
  provider.awareness.on('change', emitPresence)
  // サーバーは awareness を中継するだけで、後から来た人に今の状態を送らない。
  // 新しい人が来たら自分の状態を一度送り直す（相手は「追加」ではなく「更新」として受け取るので往復しない）
  provider.awareness.on('change', ({ added }: { added: number[] }) => {
    if (added.some((c) => c !== provider.awareness.clientID)) {
      const st = provider.awareness.getLocalState()
      if (st) provider.awareness.setLocalState({ ...st })
    }
  })

  // --- 書式のメニュー（文字列を選択したとき） ---------------------------------------------------
  async function setLink() {
    const prev = editor.getAttributes('link').href as string | undefined
    const url = await promptDialog('リンク先URL（空で解除）', prev ?? 'https://')
    if (url === null) return
    if (url === '') editor.chain().focus().unsetLink().run()
    else editor.chain().focus().extendMarkRange('link').setLink({ href: url }).run()
  }
  function commentSelection() {
    const { from, to } = editor.state.selection
    const quote = editor.state.doc.textBetween(from, to, ' ', ' ').replace(/\s+/g, ' ').trim().slice(0, 200)
    if (quote) opts.onComment?.({ from, to, quote })
  }
  let colorOpen = false
  function textColor(c: { color?: string | null; bg?: string | null }) {
    if (c.color === null && c.bg === null) editor.chain().focus().unsetTextColor().run()
    else editor.chain().focus().setTextColor(c).run()
    colorOpen = false
    refreshTextMenu()
  }
  type ShowArgs = { editor: CoreEditor; state: EditorState }
  /** 書式メニュー: 文字列を選択したとき（画像などのノード選択・セル選択では出さない）。 */
  const showTextMenu = ({ state: s }: ShowArgs) => {
    const sel = s.selection
    // 表示モード・閲覧のみでもコメントはできる（書式ボタンは編集できるときだけ）
    // ブロック単位の選択（Shift+↑/↓・⌘A）では出さない
    return !sel.empty && !(sel instanceof NodeSelection) && !(sel instanceof CellSelection) && !isNodeRangeSelection(sel) && !revoked
  }
  const textMenu = h('div', { class: 'bubble' })
  const markButtons: [HTMLButtonElement, string][] = []
  const mark = (name: string, title: string, label: string | HTMLElement, run: () => void) => {
    const b = h('button', { title, onclick: run }, label)
    markButtons.push([b, name])
    return b
  }
  const colorA = h('span', { class: 'a' }, 'A')
  const colorBtn = h('button', { title: '文字の色・背景色', 'data-testid': 'text-color', onclick: () => { colorOpen = !colorOpen; refreshTextMenu() } }, colorA, '▾')
  markButtons.push([colorBtn, 'textColor'])
  const colorPop = h('div', { class: 'color-pop', 'data-testid': 'text-color-pop' },
    h('div', { class: 'cp-row' }, h('span', { class: 'cp-l' }, '文字'),
      h('button', { class: 'sw', title: '標準', onclick: () => textColor({ color: null }) }, 'A'),
      COLORS.map((c) => h('button', { class: 'sw', 'data-text-color': c.id, title: c.label, 'data-testid': `text-color-${c.id}`, onclick: () => textColor({ color: c.id }) }, 'A'))),
    h('div', { class: 'cp-row' }, h('span', { class: 'cp-l' }, '背景'),
      h('button', { class: 'sw', title: 'なし', onclick: () => textColor({ bg: null }) }, '∅'),
      COLORS.map((c) => h('button', { class: 'sw', 'data-text-bg': c.id, title: c.label, 'data-testid': `text-bg-${c.id}`, onclick: () => textColor({ bg: c.id }) }, ' '))))
  const colorWrap = h('span', { class: 'color-wrap' }, colorBtn)
  const formatButtons: HTMLElement[] = [
    mark('bold', '太字', h('b', null, 'B'), () => editor.chain().focus().toggleBold().run()),
    mark('italic', '斜体', h('i', null, 'I'), () => editor.chain().focus().toggleItalic().run()),
    mark('underline', '下線', h('u', null, 'U'), () => editor.chain().focus().toggleUnderline().run()),
    mark('strike', '取り消し線', h('s', null, 'S'), () => editor.chain().focus().toggleStrike().run()),
    mark('code', 'コード', '</>', () => editor.chain().focus().toggleCode().run()),
    mark('highlight', 'ハイライト', '🖍', () => editor.chain().focus().toggleHighlight().run()),
    mark('link', 'リンク', '🔗', () => void setLink()),
    colorWrap,
    h('span', { class: 'sep' }),
  ]
  const commentLabel = h('span', null, ' コメント')
  const commentBtn = h('button', { title: '選択した文章にコメント', 'data-testid': 'comment-selection', onclick: commentSelection }, '💬')
  let menuEditable: boolean | null = null
  function refreshTextMenu() {
    if (editor.isDestroyed) return
    const ed = editor.isEditable
    if (ed !== menuEditable) {
      menuEditable = ed
      setChildren(textMenu, ed && formatButtons, commentBtn)
      if (ed) commentLabel.remove()
      else commentBtn.append(commentLabel)
    }
    if (!ed) return
    for (const [b, name] of markButtons) b.classList.toggle('on', editor.isActive(name))
    const tc = editor.getAttributes('textColor').color as string | undefined
    if (tc) colorA.setAttribute('data-text-color', tc)
    else colorA.removeAttribute('data-text-color')
    if (colorOpen) { if (!colorPop.parentNode) colorWrap.append(colorPop) } else colorPop.remove()
  }

  // --- 画像のメニュー（画像を選択したとき。寄せ方向） ------------------------------------------
  type Align = 'left' | 'center' | 'right'
  const showImageMenu = ({ editor: e, state: s }: ShowArgs) =>
    e.isEditable && s.selection instanceof NodeSelection && s.selection.node.type.name === 'image'
  function alignImage(align: Align) {
    // 左寄せは既定値（属性なし）として保存する
    editor.chain().focus().updateAttributes('image', { align: align === 'left' ? null : align }).run()
  }
  const alignButtons: [HTMLButtonElement, Align][] = [
    [h('button', { title: '左寄せ', 'data-testid': 'img-left', onclick: () => alignImage('left') }, '⇤ 左'), 'left'],
    [h('button', { title: '中央', 'data-testid': 'img-center', onclick: () => alignImage('center') }, '↔ 中央'), 'center'],
    [h('button', { title: '右寄せ', 'data-testid': 'img-right', onclick: () => alignImage('right') }, '右 ⇥'), 'right'],
  ]
  const imageMenu = h('div', { class: 'bubble', 'data-testid': 'image-menu' }, h('span', { class: 'label' }, '配置'), alignButtons.map(([b]) => b))
  function refreshImageMenu() {
    if (editor.isDestroyed) return
    const cur = (editor.getAttributes('image').align as Align | null) ?? 'left'
    for (const [b, a] of alignButtons) b.classList.toggle('on', a === cur)
  }

  for (const m of [textMenu, imageMenu]) { m.style.visibility = 'hidden'; m.style.position = 'absolute' }
  editor.registerPlugin(BubbleMenuPlugin({ editor, element: textMenu, pluginKey: 'textMenu', shouldShow: showTextMenu }))
  editor.registerPlugin(BubbleMenuPlugin({ editor, element: imageMenu, pluginKey: 'imageMenu', shouldShow: showImageMenu, options: { placement: 'top' } }))
  const onTransaction = () => { refreshTextMenu(); refreshImageMenu() }
  editor.on('transaction', onTransaction)
  refreshTextMenu()
  refreshImageMenu()

  // --- ブロックのつまみ（⋮⋮）とメニュー --------------------------------------------------
  let hovered: { node: PMNode; pos: number } | null = null
  let closeBlockMenu: (() => void) | null = null
  const handleBtn = h('button', { class: 'block-handle', title: 'ドラッグで移動・クリックでメニュー', 'data-testid': 'block-handle', onclick: openMenu }, '⋮⋮')
  const handleWrap = h('div', { class: 'block-handle-wrap', 'data-dragging': 'false' }, handleBtn)
  handleWrap.style.position = 'absolute'
  const dragKey = new PluginKey('blockHandle')
  let drag: ReturnType<typeof DragHandlePlugin> | null = null
  function setDragHandle(on: boolean) {
    if (on === !!drag || editor.isDestroyed) return
    if (on) {
      handleWrap.style.visibility = 'hidden'
      drag = DragHandlePlugin({
        editor, element: handleWrap, pluginKey: dragKey,
        computePositionConfig: { ...defaultComputePositionConfig },
        onNodeChange: (d) => { if (!closeBlockMenu) hovered = d.node ? { node: d.node, pos: d.pos } : null },
        nestedOptions: normalizeNestedOptions(false),
      })
      editor.registerPlugin(drag.plugin)
    } else {
      editor.unregisterPlugin(dragKey)
      drag?.unbind()
      drag = null
      handleWrap.remove()
    }
  }
  function openMenu(e: MouseEvent) {
    const hv = hovered
    if (!hv) return
    closeBlockMenu?.()
    // つまみの位置のブロックを、今の文書から取り直す（同じブロックの上ではつまみの通知が来ないため、属性が古いことがある）
    const node = editor.state.doc.nodeAt(hv.pos) ?? hv.node
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect()
    closeBlockMenu = openBlockMenu(area, { editor, node, pos: hv.pos, at: { x: r.right + 4, y: r.top }, pageId: opts.pageId, onClose: () => { closeBlockMenu = null } })
  }

  // --- URL を貼り付けたとき: リンクのまま／カード／埋め込み を選べる --------------------------------
  let urlMenu: { url: string; from: number; to: number; embed: string | null; el: HTMLElement } | null = null
  const closeUrlMenu = () => { urlMenu?.el.remove(); urlMenu = null }
  function onUrlPasted(e: Event) {
    const d = (e as CustomEvent<{ url: string; from: number; to: number }>).detail
    if (!editor.isEditable) return
    closeUrlMenu()
    const c = editor.view.coordsAtPos(d.to)
    const embed = knownEmbedUrl(d.url)
    const m = h('div', { class: 'url-menu', 'data-testid': 'url-menu', style: { left: `${c.left}px`, top: `${c.bottom + 4}px` }, onmousedown: (ev: Event) => ev.preventDefault() },
      h('button', { 'data-testid': 'url-keep', onclick: closeUrlMenu }, '🔗 リンクのまま'),
      h('button', { 'data-testid': 'url-card', onclick: () => convertUrl('card') }, '🔖 カード'),
      embed && h('button', { 'data-testid': 'url-embed', onclick: () => convertUrl('embed') }, '▶ 埋め込み'))
    urlMenu = { ...d, embed, el: m }
    area.append(m)
  }
  window.addEventListener('skiwi:url-pasted', onUrlPasted)
  const offSelectAll = selectArticleOnModA(() => editor.view.dom)
  /** 貼り付けたリンクを、カードか埋め込みに置き換える（段落がそのリンクだけなら段落ごと） */
  function convertUrl(kind: 'card' | 'embed') {
    const m = urlMenu
    closeUrlMenu()
    if (!m) return
    const { state: st } = editor
    if (st.doc.textBetween(m.from, m.to) !== m.url) return
    const $from = st.doc.resolve(m.from)
    const para = $from.parent
    const node = kind === 'card' ? { type: 'linkCard', attrs: { url: m.url } } : { type: 'embed', attrs: { src: m.embed } }
    if (para.type.name === 'paragraph' && para.textContent.trim() === m.url) {
      editor.chain().focus().insertContentAt({ from: $from.before(), to: $from.after() }, node).run()
    } else {
      editor.chain().focus().deleteRange({ from: m.from, to: m.to }).insertContentAt(editor.state.doc.resolve(Math.min(m.from, editor.state.doc.content.size)).after(), node).run()
    }
  }
  // 入力を続けたり、別の場所をクリックしたら閉じる
  editor.on('selectionUpdate', () => { if (urlMenu && editor.state.selection.from !== urlMenu.to) closeUrlMenu() })

  // --- 表の操作 ------------------------------------------------------------------------------
  const stopTable = tableControls(editor, area)

  /** 表示モードでは開閉ブロックのタイトルをクリックしても開閉する（編集モードではタイトルの編集になる） */
  function onAreaClick(e: MouseEvent) {
    const marked = (e.target as HTMLElement).closest<HTMLElement>('[data-comment]')
    if (marked && editor.state.selection.empty) opts.onCommentClick?.(marked.dataset.comment!)
    if (editor.isEditable) return
    const summary = (e.target as HTMLElement).closest('summary')
    const toggle = summary?.closest('.details')?.querySelector<HTMLButtonElement>(':scope > button')
    if (toggle) { e.preventDefault(); toggle.click() }
  }

  applyEditable()

  let destroyed = false
  return {
    editor,
    setEditable(b) { editable = b; if (!editor.isDestroyed) applyEditable() },
    getState: () => ({
      ydoc: bytesToBase64(Y.encodeStateAsUpdate(ydoc)),
      content: editor.getJSON(),
      text: editor.getText({ blockSeparator: '\n' }),
    }),
    applyContent(json) {
      // ホワイトボードの中身（本文とは別に同期している）もその版に戻す
      restoreBoards(ydoc, json)
      editor.commands.setContent(json as JSONContent, { emitUpdate: true })
    },
    isSynced: () => synced,
    getJSON: () => editor.getJSON(),
    setCommentMark(from, to, id) { editor.chain().setTextSelection({ from, to }).setCommentMark(id).setTextSelection(to).run() },
    unsetCommentMark(id) { editor.commands.unsetCommentMark(id) },
    isDestroyed: () => destroyed,
    destroy() {
      if (destroyed) return
      destroyed = true
      window.removeEventListener('skiwi:url-pasted', onUrlPasted)
      offSelectAll()
      provider.awareness.off('change', emitPresence)
      closeBlockMenu?.()
      closeUrlMenu()
      stopTable()
      editor.off('transaction', onTransaction)
      setDragHandle(false)
      editor.destroy()
      provider.destroy()
      ydoc.destroy()
      textMenu.remove()
      imageMenu.remove()
      root.remove()
    },
  }
}

/**
 * 読み取り専用の表示（ゲスト・印刷・版の履歴のプレビュー）。el の中に作る。
 * opts.testid: 表示の外枠の data-testid（例: guest-content）/ opts.class: 外枠のクラス（例: 'editor-area readonly'）
 */
/**
 * ⌘A / Ctrl+A を、入力欄やエディタの外（表示モードなど）で押したとき、ページ全体ではなく本文（dom の中）だけを選ぶ.
 * ダイアログを開いているときは何もしない. 戻り値で解除
 */
function selectArticleOnModA(dom: () => HTMLElement): () => void {
  const onKey = (e: KeyboardEvent) => {
    if (!(e.metaKey || e.ctrlKey) || e.shiftKey || e.altKey || e.key.toLowerCase() !== 'a' || e.defaultPrevented) return
    const t = e.target
    if (t instanceof HTMLElement && (t.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName))) return
    if (document.querySelector('[role=dialog], [aria-modal=true]')) return
    const el = dom()
    if (!el.isConnected) return
    e.preventDefault()
    window.getSelection()?.selectAllChildren(el)
  }
  document.addEventListener('keydown', onKey)
  return () => document.removeEventListener('keydown', onKey)
}

/** selectAll: ⌘A で本文だけを選ぶ（ゲスト向けのページなど、本文が主役の画面） */
export function renderStatic(el: HTMLElement, content: unknown, opts: { testid?: string; class?: string; selectAll?: boolean } = {}) {
  const box = h('div', { 'data-testid': opts.testid, class: opts.class })
  el.append(box)
  const asContent = (c: unknown) => (c && typeof c === 'object' ? (c as JSONContent) : '')
  const editor = new Editor({ element: box, editable: false, extensions: contentExtensions(), content: asContent(content) })
  const offSelectAll = opts.selectAll ? selectArticleOnModA(() => editor.view.dom) : () => {}
  return {
    editor,
    setContent(json: unknown) { if (!editor.isDestroyed) editor.commands.setContent(asContent(json)) },
    destroy() { offSelectAll(); editor.destroy(); box.remove() },
  }
}
