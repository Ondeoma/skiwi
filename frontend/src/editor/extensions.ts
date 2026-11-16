import type { AnyExtension, Editor } from '@tiptap/core'
import type { Node as PMNode } from '@tiptap/pm/model'
import StarterKit from '@tiptap/starter-kit'
import Placeholder from '@tiptap/extension-placeholder'
import Collaboration from '@tiptap/extension-collaboration'
import CollaborationCaret from '@tiptap/extension-collaboration-caret'
import { TaskItem, TaskList } from '@tiptap/extension-list'
import { Details, DetailsContent, DetailsSummary } from '@tiptap/extension-details'
import { TableKit } from '@tiptap/extension-table'
import Image from '@tiptap/extension-image'
import Highlight from '@tiptap/extension-highlight'
import TextAlign from '@tiptap/extension-text-align'
import FileHandler from '@tiptap/extension-file-handler'
import Mathematics from '@tiptap/extension-mathematics'
import type * as Y from 'yjs'
import type { WebsocketProvider } from 'y-websocket'
import { FileBlock } from './FileBlock'
import { Embed } from './Embed'
import { Mermaid } from './Mermaid'
import { CodeBlock } from './CodeBlock'
import { Panel } from './Panel'
import { Toc } from './Toc'
import { PageLink } from './PageLink'
import { Whiteboard, type WhiteboardOptions } from './Whiteboard'
import { Mention } from './Mention'
import { DueDate } from './DueDate'
import { Column, Columns } from './Columns'
import { SyncedBlock, SyncedRef } from './Synced'
import { CommentMark } from './CommentMark'
import { parsePageUrl } from './pageLinks'
import { ColoredTableCell, ColoredTableHeader } from './TableCellColor'
import { SlashCommand } from './SlashCommand'
import type { SlashContext } from './commands'
import { promptDialog } from '../ui/dialog'
import { BlockId, TextColor } from './BlockAttrs'
import { EmojiSuggest } from './EmojiSuggest'
import { LinkCard, UrlPaste } from './LinkCard'
import { DatabaseBlock } from './DatabaseBlock'
import { Drawio } from './Drawio'
import NodeRange from '@tiptap/extension-node-range'
import { SkawauFiles } from './SkawauFiles'
import { SkeaTasks } from './SkeaTasks'

export interface EditorDeps {
  ydoc: Y.Doc
  provider: WebsocketProvider
  user: { name: string; color: string; avatar?: string | null }
  ctx: SlashContext
  uploadAt: (file: File, pos?: number) => Promise<void>
  /** 貼り付け・ドロップしたファイル（複数可）をアップロードして、元の順で入れる */
  uploadMany: (files: File[], pos?: number) => Promise<void>
  getEditor: () => Editor | undefined
  /** 添付ファイルとしてアップロードしてURLを返す（ホワイトボードの画像用） */
  uploadFile: (file: File) => Promise<string>
}

/** 本文の表示に必要なブロック定義（共同編集・入力補助を含まない）。版のプレビューでも使う。 */
export function contentExtensions(
  onMathClick?: (kind: 'block' | 'inline') => (node: PMNode, pos: number) => void,
  whiteboard?: Partial<WhiteboardOptions>,
): AnyExtension[] {
  return [
    StarterKit.configure({
      undoRedo: false, // Undo/Redo は Collaboration(yjs) 側が担う
      codeBlock: false, // CodeBlockLowlight を使う
      // このシステムのページURLは自動リンクにせず、ページリンク（タイトル表示）にする
      link: { openOnClick: false, autolink: true, defaultProtocol: 'https', shouldAutoLink: (url) => !parsePageUrl(url) },
    }),
    TaskList,
    TaskItem.configure({ nested: true }),
    // 開閉ブロック。level を付けるとタイトルが見出しの大きさになる（トグル見出し）
    Details.extend({
      addAttributes() {
        return {
          ...this.parent?.(),
          level: {
            default: null,
            parseHTML: (el) => { const n = Number(el.getAttribute('data-level')); return n >= 1 && n <= 3 ? n : null },
            renderHTML: (a) => (a.level ? { 'data-level': String(a.level) } : {}),
          },
        }
      },
    }).configure({ persist: false, HTMLAttributes: { class: 'details' } }),
    DetailsSummary,
    DetailsContent,
    // セルは背景色の属性を持つ版に差し替える
    TableKit.configure({ table: { resizable: true }, tableCell: false, tableHeader: false }),
    ColoredTableCell,
    ColoredTableHeader,
    // 画像は角・左右のハンドルでドラッグして大きさを変えられる（width/height属性として共同編集で同期）
    Image.extend({
      // 寄せ方向（左・中央・右）。data-align として描画し、配置はCSSで行う
      addAttributes() {
        return {
          ...this.parent?.(),
          align: {
            default: null,
            parseHTML: (el) => { const v = el.getAttribute('data-align'); return v === 'center' || v === 'right' || v === 'left' ? v : null },
            renderHTML: (a) => (a.align ? { 'data-align': a.align } : {}),
          },
          // キャプション（画像の下に出す説明）
          caption: {
            default: null,
            parseHTML: (el) => el.getAttribute('data-caption') || el.closest('figure')?.querySelector('figcaption')?.textContent || null,
            renderHTML: (a) => (a.caption ? { 'data-caption': a.caption } : {}),
          },
        }
      },
      // 大きさを変えられる表示（親）を figure で包み、下にキャプションの欄を付ける
      addNodeView() {
        const parent = this.parent?.()
        if (!parent) return null
        return (props) => {
          const nv = parent(props) as unknown as {
            dom: HTMLElement; update?: (...a: unknown[]) => boolean; destroy?: () => void
            ignoreMutation?: (m: unknown) => boolean; stopEvent?: (e: Event) => boolean; selectNode?: () => void; deselectNode?: () => void
          }
          const fig = document.createElement('figure')
          fig.className = 'img-figure'
          fig.appendChild(nv.dom)
          const cap = document.createElement('figcaption')
          cap.contentEditable = 'false'
          const input = document.createElement('input')
          input.className = 'img-caption-input'
          input.placeholder = 'キャプションを追加'
          input.setAttribute('data-testid', 'image-caption')
          const text = document.createElement('span')
          text.className = 'img-caption-text'
          cap.append(input, text)
          fig.appendChild(cap)
          let node = props.node
          const render = () => {
            const c = (node.attrs.caption as string | null) ?? ''
            if (document.activeElement !== input) input.value = c
            text.textContent = c
            fig.dataset.align = (node.attrs.align as string | null) ?? ''
            fig.classList.toggle('has-caption', !!c)
          }
          render()
          const commit = () => {
            const pos = typeof props.getPos === 'function' ? props.getPos() : undefined
            const v = input.value.trim() || null
            if (pos === undefined || v === (node.attrs.caption ?? null)) return
            props.editor.view.dispatch(props.editor.state.tr.setNodeAttribute(pos, 'caption', v))
          }
          input.addEventListener('change', commit)
          input.addEventListener('blur', commit)
          input.addEventListener('keydown', (e) => {
            if (e.isComposing) return
            if (e.key === 'Enter' || e.key === 'Escape') { e.preventDefault(); input.blur(); props.editor.commands.focus() }
          })
          return {
            dom: fig,
            update: (n: typeof node, ...rest: unknown[]) => {
              if (n.type !== node.type) return false
              const ok = nv.update ? nv.update.call(nv, n, ...rest) : true
              node = n
              render()
              return ok
            },
            destroy: () => nv.destroy?.call(nv),
            ignoreMutation: (m: { target: globalThis.Node }) => cap.contains(m.target) || (nv.ignoreMutation ? nv.ignoreMutation.call(nv, m) : false),
            stopEvent: (e: Event) => cap.contains(e.target as globalThis.Node) || (nv.stopEvent ? nv.stopEvent.call(nv, e) : false),
            selectNode: () => { fig.classList.add('ProseMirror-selectednode'); nv.selectNode?.call(nv) },
            deselectNode: () => { fig.classList.remove('ProseMirror-selectednode'); nv.deselectNode?.call(nv) },
          }
        }
      },
    }).configure({
      inline: false,
      allowBase64: false,
      resize: { enabled: true, directions: ['left', 'right', 'bottom-left', 'bottom-right'], minWidth: 48, minHeight: 32, alwaysPreserveAspectRatio: true },
    }),
    CodeBlock,
    Highlight,
    TextAlign.configure({ types: ['heading', 'paragraph'] }),
    Mathematics.configure(onMathClick ? { blockOptions: { onClick: onMathClick('block') }, inlineOptions: { onClick: onMathClick('inline') } } : {}),
    FileBlock,
    Embed,
    Mermaid,
    Panel,
    Toc,
    PageLink,
    Whiteboard.configure(whiteboard ?? {}),
    Mention,
    DueDate,
    Columns,
    Column,
    CommentMark,
    SyncedBlock,
    SyncedRef,
    BlockId,
    TextColor,
    LinkCard,
    DatabaseBlock,
    Drawio.configure({ uploadFile: whiteboard?.uploadFile ?? undefined }),
  ]
}

export function buildExtensions(d: EditorDeps): AnyExtension[] {
  const editMath = (kind: 'block' | 'inline') => (node: PMNode, pos: number) => {
    const ed = d.getEditor()
    if (!ed?.isEditable) return
    promptDialog('数式（LaTeX）', String(node.attrs.latex ?? '')).then((latex) => {
      if (latex == null || ed.isDestroyed) return
      const c = ed.chain().setNodeSelection(pos)
      ;(kind === 'block' ? c.updateBlockMath({ latex }) : c.updateInlineMath({ latex })).focus().run()
    })
  }

  return [
    ...contentExtensions(editMath, {
      ydoc: d.ydoc,
      awareness: d.provider.awareness as unknown as WhiteboardOptions['awareness'],
      user: d.user,
      uploadFile: d.uploadFile,
    }),
    Placeholder.configure({
      includeChildren: true, // 開閉ブロックのタイトル欄にも出す（表示はCSSで場所を限定）
      placeholder: ({ node }) =>
        node.type.name === 'detailsSummary' ? 'タイトル（クリックで開閉）'
          : node.type.name === 'heading' ? '見出し'
          : '「/」でブロックを挿入…',
    }),
    FileHandler.configure({
      onPaste: (_ed, files) => { void d.uploadMany(files) },
      onDrop: (_ed, files, pos) => { void d.uploadMany(files, pos) },
    }),
    SlashCommand.configure({ ctx: d.ctx }),
    EmojiSuggest,
    NodeRange,
    UrlPaste,
    // skea のタスクID・skawau のファイルのリンクの表示（ログイン中のエディタだけ。ゲスト向けの表示には入れない）
    SkeaTasks,
    SkawauFiles,
    Collaboration.configure({ document: d.ydoc }),
    CollaborationCaret.configure({ provider: d.provider, user: d.user }),
  ]
}
