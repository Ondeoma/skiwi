<script setup lang="ts">
import { onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { Editor, EditorContent } from '@tiptap/vue-3'
import type { Editor as CoreEditor } from '@tiptap/core'
import { BubbleMenu } from '@tiptap/vue-3/menus'
import { NodeSelection, type EditorState } from '@tiptap/pm/state'
import { CellSelection } from '@tiptap/pm/tables'
import * as Y from 'yjs'
import * as encoding from 'lib0/encoding'
import * as syncProtocol from 'y-protocols/sync'
import 'katex/dist/katex.min.css'
import { uploadAttachment, type Attachment, type DocState, type Role } from '../api'
import { buildExtensions } from '../editor/extensions'
import { restoreBoards } from '../editor/whiteboard/restore'
import { MSG, answerCompactRequests, connectPage, onRole } from '../editor/collab'
import { alertDialog, alertError, chooseDialog, promptDialog } from '../ui/dialog'
import { useWorkspaceStore } from '../stores/workspace'
import { api, dbApi } from '../api'
import { defaultDatabase } from '../database/db'
import TableControls from './TableControls.vue'
import BlockMenu from './BlockMenu.vue'
import { DragHandle } from '@tiptap/extension-drag-handle-vue-3'
import type { Node as PMNode } from '@tiptap/pm/model'
import { COLORS } from '../ui/palette'
import { pickFiles } from '../ui/fileDrop'
import { knownEmbedUrl } from '../editor/Embed'
import { isDrawioPng, isDrawioXml, labelsOf, xmlFromPng } from '../editor/drawioUtil'
import { bytesToBase64 } from '../util/bytes'
import { useEventListener } from '../ui/composables'

/** editable: 編集モードかどうか（実際に編集できるのは、サーバーが通知する権限が edit のときだけ） */
const props = defineProps<{ pageId: string; editable: boolean; userName: string }>()
const emit = defineEmits<{
  role: [role: Role | 'none']
  /** 選択した文章にコメントする */
  comment: [d: { from: number; to: number; quote: string }]
  /** コメントの付いた文章をクリックした */
  'comment-click': [anchorId: string]
  /** このページを開いているほかの人（同じ人の複数タブは1人にまとめる） */
  presence: [people: { name: string; color: string; editing: boolean }[]]
}>()

const colors = ['#e11d48', '#2563eb', '#16a34a', '#d97706', '#7c3aed', '#0891b2']
const color = colors[Math.floor(Math.random() * colors.length)]

const { ydoc, provider } = connectPage(props.pageId)
const state = ref<'connecting' | 'connected' | 'disconnected'>('connecting')
const synced = ref(false)
const uploading = ref(0)
/** サーバーが通知する現在の権限。接続中に共有設定が変わると更新される。 */
const canEdit = ref(true)
const container = ref<HTMLElement | null>(null)
const revoked = ref(false)

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
  state.value = e.status
  if (e.status === 'disconnected') needsResend = true
})
provider.on('sync', (s: boolean) => {
  synced.value = s
  if (s && needsResend && canEdit.value) { needsResend = false; sendFullState() }
})

// --- サーバーからのスナップショット要求(圧縮用) ------------------------------------------
// 表示モードでも編集権限があれば応答する（全状態を持っているため）
answerCompactRequests(provider, ydoc, () => (canEdit.value && synced.value ? editor : null))

// --- 権限の通知（接続時・共有設定の変更時） ----------------------------------------------
onRole(provider, (role) => {
  const r = role as Role | 'none'
  emit('role', r)
  if (r === 'none') {
    revoked.value = true
    canEdit.value = false
    editor.setEditable(false)
    provider.shouldConnect = false // 自動再接続しない
    provider.disconnect()
    return
  }
  canEdit.value = r === 'edit'
  applyEditable()
})
function applyEditable() {
  const want = canEdit.value && props.editable
  if (editor.isEditable !== want) editor.setEditable(want)
  provider.awareness.setLocalStateField('mode', want ? 'edit' : 'view')
}
watch(() => props.editable, () => applyEditable())

// --- 開いている人（awareness） ------------------------------------------------------------
function emitPresence() {
  const people = new Map<string, { name: string; color: string; editing: boolean }>()
  provider.awareness.getStates().forEach((st, client) => {
    if (client === provider.awareness.clientID || !st.user?.name) return
    const cur = people.get(st.user.name)
    const editing = st.mode === 'edit' || !!cur?.editing
    people.set(st.user.name, { name: st.user.name, color: st.user.color ?? '#888', editing })
  })
  emit('presence', [...people.values()].sort((a, b) => a.name.localeCompare(b.name, 'ja')))
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

/** ページの中に置くデータベース: 既存のものを選ぶか、このページの子ページとして新しく作る */
async function pickDatabase(): Promise<string | null> {
  const store = useWorkspaceStore()
  const dbs = store.pages.filter((p) => p.database)
  const v = await chooseDialog('ページの中に表示するデータベース', [
    { label: '＋ 新しいデータベースを作る', value: '__new__', detail: 'このページの子ページとして作ります' },
    ...dbs.map((p) => ({ label: `📊 ${p.title || '無題'}`, value: p.id })),
  ])
  if (v !== '__new__') return v
  const here = store.byId.get(props.pageId)
  if (!here) return null
  const name = (await promptDialog('データベースの名前', '新しいデータベース', { okLabel: '作成' }))?.trim()
  if (!name) return null
  try {
    const p = await api.createPage(here.workspaceId, { parentId: here.id, title: name })
    const d = defaultDatabase()
    await dbApi.save(p.id, d.props, d.views)
    await store.reloadPages()
    return p.id
  } catch (e) { await alertError('作れませんでした', e); return null }
}

async function uploadAt(file: File, pos?: number) { await uploadMany([file], pos) }

/** 複数のファイルを同時にアップロードし、選んだ順に入れる（貼り付けはカーソルの位置、ドロップは落とした位置）。
 *  貼り付けた画像に名前がない（スクリーンショットなど）ときは、日時から名前を付ける。 */
async function uploadMany(files: File[], pos?: number) {
  if (!editor.isEditable || !files.length) return
  uploading.value += files.length
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
    const results = await Promise.allSettled(named.map((f) => uploadAttachment(props.pageId, f)))
    const ok = results.flatMap((r, i) => (r.status === 'fulfilled' ? [nodeOf(r.value, drawio[i])] : []))
    if (ok.length) {
      if (pos != null) editor.chain().insertContentAt(Math.min(pos, editor.state.doc.content.size), ok).run()
      else editor.chain().focus().insertContent(ok).run()
    }
    const failed = results.map((r, i) => (r.status === 'rejected' ? `・${named[i].name}: ${(r.reason as Error).message}` : '')).filter(Boolean)
    if (failed.length) await alertDialog(`アップロードに失敗したファイルがあります:\n${failed.join('\n')}`)
  } finally {
    uploading.value -= files.length
  }
}

const editor: Editor = new Editor({
  editable: props.editable,
  extensions: buildExtensions({
    ydoc, provider,
    user: { name: props.userName, color },
    ctx: { pickFile, upload: (f) => uploadAt(f), pickDatabase },
    uploadAt,
    uploadMany,
    getEditor: () => editor,
    uploadFile: async (f) => (await uploadAttachment(props.pageId, f)).url,
  }),
})

async function setLink() {
  const prev = editor.getAttributes('link').href as string | undefined
  const url = await promptDialog('リンク先URL（空で解除）', prev ?? 'https://')
  if (url === null) return
  if (url === '') editor.chain().focus().unsetLink().run()
  else editor.chain().focus().extendMarkRange('link').setLink({ href: url }).run()
}

// --- 浮動メニューの表示条件 ----------------------------------------------------------------
type ShowArgs = { editor: CoreEditor; state: EditorState }
/** 書式メニュー: 文字列を選択したとき（画像などのノード選択・セル選択では出さない）。 */
const showTextMenu = ({ state: s }: ShowArgs) => {
  const sel = s.selection
  // 表示モード・閲覧のみでもコメントはできる（書式ボタンは編集できるときだけ）
  return !sel.empty && !(sel instanceof NodeSelection) && !(sel instanceof CellSelection) && !revoked.value
}
function commentSelection() {
  const { from, to } = editor.state.selection
  const quote = editor.state.doc.textBetween(from, to, ' ', ' ').replace(/\s+/g, ' ').trim().slice(0, 200)
  if (quote) emit('comment', { from, to, quote })
}
/** 画像メニュー: 画像を選択したとき（寄せ方向）。 */
const showImageMenu = ({ editor: e, state: s }: ShowArgs) =>
  e.isEditable && s.selection instanceof NodeSelection && s.selection.node.type.name === 'image'
type Align = 'left' | 'center' | 'right'
const imageAlign = (): Align => (editor.getAttributes('image').align as Align | null) ?? 'left'
function alignImage(align: Align) {
  // 左寄せは既定値（属性なし）として保存する
  editor.chain().focus().updateAttributes('image', { align: align === 'left' ? null : align }).run()
}

// --- 版履歴用 ------------------------------------------------------------------------------
/** 現在の文書状態（Yjsの全状態＋表示用JSON＋平文）。 */
function getState(): DocState {
  return {
    ydoc: bytesToBase64(Y.encodeStateAsUpdate(ydoc)),
    content: editor.getJSON(),
    text: editor.getText({ blockSeparator: '\n' }),
  }
}
/** 版の本文で置き換える。通常の編集としてYjs経由で全員に同期される（取り消し可能な通常の変更）。 */
function applyContent(json: unknown) {
  // ホワイトボードの中身（本文とは別に同期している）もその版に戻す
  restoreBoards(ydoc, json)
  editor.commands.setContent(json as Record<string, unknown>, { emitUpdate: true })
}

/** 表示モードでは開閉ブロックのタイトルをクリックしても開閉する（編集モードではタイトルの編集になる） */
function onAreaClick(e: MouseEvent) {
  const marked = (e.target as HTMLElement).closest<HTMLElement>('[data-comment]')
  if (marked && editor.state.selection.empty) emit('comment-click', marked.dataset.comment!)
  if (editor.isEditable) return
  const summary = (e.target as HTMLElement).closest('summary')
  const toggle = summary?.closest('.details')?.querySelector<HTMLButtonElement>(':scope > button')
  if (toggle) { e.preventDefault(); toggle.click() }
}

onBeforeUnmount(() => {
  provider.awareness.off('change', emitPresence)
  editor.destroy()
  provider.destroy()
  ydoc.destroy()
})

// --- ブロックのつまみ（⋮⋮）とメニュー --------------------------------------------------
const hovered = shallowRef<{ node: PMNode; pos: number } | null>(null)
const blockMenu = shallowRef<{ node: PMNode; pos: number; at: { x: number; y: number } } | null>(null)
function onNodeChange(d: { node: PMNode | null; pos: number }) {
  if (!blockMenu.value) hovered.value = d.node ? { node: d.node, pos: d.pos } : null
}
function openBlockMenu(e: MouseEvent) {
  const h = hovered.value
  if (!h) return
  // つまみの位置のブロックを、今の文書から取り直す（同じブロックの上ではつまみの通知が来ないため、属性が古いことがある）
  const node = editor.state.doc.nodeAt(h.pos) ?? h.node
  const r = (e.currentTarget as HTMLElement).getBoundingClientRect()
  blockMenu.value = { node, pos: h.pos, at: { x: r.right + 4, y: r.top } }
}

// --- URL を貼り付けたとき: リンクのまま／カード／埋め込み を選べる --------------------------------
const urlMenu = ref<{ url: string; from: number; to: number; x: number; y: number; embed: string | null } | null>(null)
function onUrlPasted(e: Event) {
  const d = (e as CustomEvent<{ url: string; from: number; to: number }>).detail
  if (!editor.isEditable) return
  const c = editor.view.coordsAtPos(d.to)
  urlMenu.value = { ...d, x: c.left, y: c.bottom + 4, embed: knownEmbedUrl(d.url) }
}
useEventListener(window, 'skiwi:url-pasted', onUrlPasted)
/** 貼り付けたリンクを、カードか埋め込みに置き換える（段落がそのリンクだけなら段落ごと） */
function convertUrl(kind: 'card' | 'embed') {
  const m = urlMenu.value
  urlMenu.value = null
  if (!m) return
  const { state } = editor
  if (state.doc.textBetween(m.from, m.to) !== m.url) return
  const $from = state.doc.resolve(m.from)
  const para = $from.parent
  const node = kind === 'card' ? { type: 'linkCard', attrs: { url: m.url } } : { type: 'embed', attrs: { src: m.embed } }
  if (para.type.name === 'paragraph' && para.textContent.trim() === m.url) {
    editor.chain().focus().insertContentAt({ from: $from.before(), to: $from.after() }, node).run()
  } else {
    editor.chain().focus().deleteRange({ from: m.from, to: m.to }).insertContentAt(editor.state.doc.resolve(Math.min(m.from, editor.state.doc.content.size)).after(), node).run()
  }
}
// 入力を続けたり、別の場所をクリックしたら閉じる
editor.on('selectionUpdate', () => { if (urlMenu.value && editor.state.selection.from !== urlMenu.value.to) urlMenu.value = null })

// --- 文字の色（選んだ文字のメニュー） ------------------------------------------------------
const colorOpen = ref(false)
function textColor(c: { color?: string | null; bg?: string | null }) {
  if (c.color === null && c.bg === null) editor.chain().focus().unsetTextColor().run()
  else editor.chain().focus().setTextColor(c).run()
  colorOpen.value = false
}

defineExpose({ editor, ydoc, getState, applyContent, synced })
</script>

<template>
  <div>
    <div class="status" :class="revoked ? 'disconnected' : state">
      <span v-if="revoked">● このページへのアクセス権がなくなりました</span>
      <span v-else-if="state === 'connected' && synced">● 同期済み</span>
      <span v-else-if="state === 'connected'">● 同期中…</span>
      <span v-else-if="state === 'connecting'">● 接続中…</span>
      <span v-else>● オフライン（再接続します。編集は保持されます）</span>
      <span v-if="!canEdit && !revoked"> ・ 閲覧のみ</span>
      <span v-if="uploading > 0" data-testid="uploading"> ・ アップロード中…（{{ uploading }}件）</span>
    </div>
    <BubbleMenu :editor="editor" plugin-key="textMenu" :should-show="showTextMenu" class="bubble">
      <template v-if="editor.isEditable">
      <button :class="{ on: editor.isActive('bold') }" title="太字" @click="editor.chain().focus().toggleBold().run()"><b>B</b></button>
      <button :class="{ on: editor.isActive('italic') }" title="斜体" @click="editor.chain().focus().toggleItalic().run()"><i>I</i></button>
      <button :class="{ on: editor.isActive('underline') }" title="下線" @click="editor.chain().focus().toggleUnderline().run()"><u>U</u></button>
      <button :class="{ on: editor.isActive('strike') }" title="取り消し線" @click="editor.chain().focus().toggleStrike().run()"><s>S</s></button>
      <button :class="{ on: editor.isActive('code') }" title="コード" @click="editor.chain().focus().toggleCode().run()">&lt;/&gt;</button>
      <button :class="{ on: editor.isActive('highlight') }" title="ハイライト" @click="editor.chain().focus().toggleHighlight().run()">🖍</button>
      <button :class="{ on: editor.isActive('link') }" title="リンク" @click="setLink">🔗</button>
      <span class="color-wrap">
        <button :class="{ on: editor.isActive('textColor') }" title="文字の色・背景色" data-testid="text-color" @click="colorOpen = !colorOpen">
          <span class="a" :data-text-color="editor.getAttributes('textColor').color ?? undefined">A</span>▾
        </button>
        <div v-if="colorOpen" class="color-pop" data-testid="text-color-pop">
          <div class="cp-row"><span class="cp-l">文字</span>
            <button class="sw" title="標準" @click="textColor({ color: null })">A</button>
            <button v-for="c in COLORS" :key="c.id" class="sw" :data-text-color="c.id" :title="c.label" :data-testid="`text-color-${c.id}`" @click="textColor({ color: c.id })">A</button>
          </div>
          <div class="cp-row"><span class="cp-l">背景</span>
            <button class="sw" title="なし" @click="textColor({ bg: null })">∅</button>
            <button v-for="c in COLORS" :key="c.id" class="sw" :data-text-bg="c.id" :title="c.label" :data-testid="`text-bg-${c.id}`" @click="textColor({ bg: c.id })">&nbsp;</button>
          </div>
        </div>
      </span>
      <span class="sep" />
      </template>
      <button title="選択した文章にコメント" data-testid="comment-selection" @click="commentSelection">💬<span v-if="!editor.isEditable"> コメント</span></button>
    </BubbleMenu>
    <BubbleMenu :editor="editor" plugin-key="imageMenu" :should-show="showImageMenu" :options="{ placement: 'top' }" class="bubble" data-testid="image-menu">
      <span class="label">配置</span>
      <button :class="{ on: imageAlign() === 'left' }" title="左寄せ" data-testid="img-left" @click="alignImage('left')">⇤ 左</button>
      <button :class="{ on: imageAlign() === 'center' }" title="中央" data-testid="img-center" @click="alignImage('center')">↔ 中央</button>
      <button :class="{ on: imageAlign() === 'right' }" title="右寄せ" data-testid="img-right" @click="alignImage('right')">右 ⇥</button>
    </BubbleMenu>
    <div ref="container" class="editor-area" :class="{ readonly: !canEdit || !props.editable }" @click="onAreaClick">
      <DragHandle v-if="canEdit && props.editable" :editor="editor" :on-node-change="onNodeChange" class="block-handle-wrap">
        <button class="block-handle" title="ドラッグで移動・クリックでメニュー" data-testid="block-handle" @click="openBlockMenu">⋮⋮</button>
      </DragHandle>
      <EditorContent :editor="editor" data-testid="editor" />
      <div v-if="urlMenu" class="url-menu" :style="{ left: `${urlMenu.x}px`, top: `${urlMenu.y}px` }" data-testid="url-menu" @mousedown.prevent>
        <button data-testid="url-keep" @click="urlMenu = null">🔗 リンクのまま</button>
        <button data-testid="url-card" @click="convertUrl('card')">🔖 カード</button>
        <button v-if="urlMenu.embed" data-testid="url-embed" @click="convertUrl('embed')">▶ 埋め込み</button>
      </div>
      <BlockMenu v-if="blockMenu" :editor="editor" :node="blockMenu.node" :pos="blockMenu.pos" :at="blockMenu.at" :page-id="props.pageId" @close="blockMenu = null" />
      <TableControls :editor="editor" :container="container" />
    </div>
  </div>
</template>

<style scoped>
.status { font-size: .8rem; color: var(--muted); margin-bottom: .5rem; }
.status.connected { color: #16a34a; }
.status.disconnected { color: #d97706; }
.editor-area { position: relative; }
.editor-area.readonly :deep(.details summary) { cursor: pointer; }
.block-handle-wrap { z-index: 5; }
.url-menu { position: fixed; z-index: 60; display: flex; gap: 2px; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 3px; box-shadow: 0 6px 18px rgba(0,0,0,.15); }
.url-menu button { border: none; font-size: .8rem; padding: 2px 8px; border-radius: 6px; white-space: nowrap; }
.url-menu button:hover { background: var(--hover); }
.block-handle { border: none; background: none; color: var(--muted); cursor: grab; padding: 2px 3px; font-size: .9rem; line-height: 1; letter-spacing: -2px; border-radius: 4px; }
.block-handle:hover { background: var(--hover); color: var(--fg); }
.color-wrap { position: relative; display: inline-flex; }
.color-wrap .a { font-weight: 700; text-decoration: underline; }
.color-pop { position: absolute; top: calc(100% + 6px); left: 50%; transform: translateX(-50%); background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 6px; box-shadow: 0 8px 24px rgba(0,0,0,.18); display: flex; flex-direction: column; gap: 4px; z-index: 30; }
.cp-row { display: flex; align-items: center; gap: 3px; }
.cp-l { font-size: .7rem; color: var(--muted); width: 2rem; }
.color-pop .sw { width: 24px; height: 24px; min-width: 0; border: 1px solid var(--line); border-radius: 5px; font-weight: 700; padding: 0; }
.bubble { display: flex; gap: 2px; align-items: center; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 3px; box-shadow: 0 6px 18px rgba(0,0,0,.15); }
.bubble button { border: none; min-width: 28px; white-space: nowrap; font-size: .85rem; }
.bubble button.on { background: var(--hover); color: var(--accent); }
.bubble .label { font-size: .75rem; color: var(--muted); padding: 0 4px; }
.bubble .sep { width: 1px; align-self: stretch; background: var(--line); margin: 0 2px; }
.bubble .danger:hover { color: var(--danger); }
</style>
