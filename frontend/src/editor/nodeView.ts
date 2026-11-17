import { NodeView, type Editor, type NodeViewRenderer, type NodeViewRendererOptions, type NodeViewRendererProps } from '@tiptap/core'
import type { Node as PMNode } from '@tiptap/pm/model'
import type { Decoration, DecorationSource } from '@tiptap/pm/view'
import { onEditableChange } from './useEditable'

/**
 * 素の DOM で書くノードビュー（旧 @tiptap/vue-3 の VueNodeViewRenderer の代わり）。
 * ドラッグのつまみ（data-drag-handle）・イベントの扱いは Tiptap の NodeView のものをそのまま使う。
 * 外枠には data-node-view-wrapper、中身（contentDOM）には data-node-view-content を付ける（Vue 版と同じ）。
 */
export interface ViewCtx<O = unknown> {
  editor: Editor
  extension: NodeViewRendererProps['extension'] & { options: O }
  /** 今のノード（更新のたびに変わる） */
  node: () => PMNode
  /** ノードが選択されているか */
  selected: () => boolean
  getPos: () => number | undefined
  updateAttributes: (attrs: Record<string, unknown>) => void
  deleteNode: () => void
}

export interface ViewParts {
  dom: HTMLElement
  contentDOM?: HTMLElement | null
  /** ノード・選択・編集できるかが変わったときに呼ぶ（表示を合わせる） */
  render?: () => void
  destroy?: () => void
}

type Setup<O> = (c: ViewCtx<O>) => ViewParts

class DomNodeView extends NodeView<Setup<any>> {
  declare parts: ViewParts
  declare isSelected: boolean
  declare decoClasses: string[]
  declare stopEditable: () => void
  declare alive: boolean

  mount() {
    this.isSelected = false
    this.decoClasses = []
    this.alive = true
    const ctx: ViewCtx<any> = {
      editor: this.editor,
      extension: this.extension,
      node: () => this.node,
      selected: () => this.isSelected,
      getPos: () => this.getPos(),
      updateAttributes: (a) => this.updateAttributes(a),
      deleteNode: () => this.deleteNode(),
    }
    this.parts = this.component(ctx)
    const { dom, contentDOM } = this.parts
    dom.setAttribute('data-node-view-wrapper', '')
    dom.style.whiteSpace = 'normal'
    dom.addEventListener('dragstart', (e) => this.onDragStart(e))
    if (contentDOM) {
      contentDOM.setAttribute('data-node-view-content', '')
      contentDOM.style.whiteSpace = 'pre-wrap'
    }
    this.applyDecorations()
    this.stopEditable = onEditableChange(this.editor, () => this.render())
    this.render()
    // 作っている途中は位置（getPos）が分からないので、もう一度合わせる
    queueMicrotask(() => this.render())
  }

  render() { if (this.alive) this.parts.render?.() }

  get dom() { return this.parts.dom }
  get contentDOM() { return this.node.isLeaf ? null : this.parts.contentDOM ?? null }

  applyDecorations() {
    const next = (this.decorations as readonly Decoration[]).flatMap((d) => String((d as unknown as { type: { attrs: { class?: string } } }).type.attrs?.class ?? '').split(/\s+/).filter(Boolean))
    const cl = this.parts.dom.classList
    this.decoClasses.forEach((c) => { if (!next.includes(c)) cl.remove(c) })
    next.forEach((c) => cl.add(c))
    this.decoClasses = next
  }

  update(node: PMNode, decorations: readonly Decoration[], innerDecorations: DecorationSource) {
    if (node.type !== this.node.type) return false
    const changed = node !== this.node
    this.node = node
    this.decorations = decorations
    this.innerDecorations = innerDecorations
    this.applyDecorations()
    if (changed) this.render()
    return true
  }

  selectNode() {
    this.isSelected = true
    this.parts.dom.classList.add('ProseMirror-selectednode')
    this.render()
  }

  deselectNode() {
    this.isSelected = false
    this.parts.dom.classList.remove('ProseMirror-selectednode')
    this.render()
  }

  destroy() {
    this.alive = false
    this.stopEditable()
    this.parts.destroy?.()
  }
}

/** setup でノードビューの DOM を作る。options は Tiptap の NodeView のもの（stopEvent など） */
export function domNodeView<O = unknown>(setup: Setup<O>, options?: Partial<NodeViewRendererOptions>): NodeViewRenderer {
  return (props) => new DomNodeView(setup, props, options)
}
