import { Extension } from '@tiptap/core'
import type { Node as PMNode } from '@tiptap/pm/model'
import { Plugin, PluginKey } from '@tiptap/pm/state'
import { Decoration, DecorationSet } from '@tiptap/pm/view'
import type { HostIntegration } from '../ui/host'
import { openNewTab } from '../lib'

/**
 * 外部サービス（skea・skawau）の項目を、本文を書き換えずに表示の飾り（decoration）で見せる拡張の共通部分。
 * 既存のページにもそのまま効き、同時編集にも影響しない。ログイン中のエディタだけに入れる。
 * - 飾りの付いた文字（data-linked-url を持つ要素）: 閲覧中はクリックで開く。編集中はふつうにカーソルを置き、Ctrl/⌘+クリックで開く。
 * - 横の小さな表示（chip）: クリックすると、いつでも新しいタブで開く（中身は Scala の editor.Linked が作る）。
 */
export interface LinkedState { deco: DecorationSet; keys: string[] }

/**
 * テキストブロックの文字を、文書の位置と添字が揃うように取り出す。
 * 文字以外のインラインは '￼'、mask が true を返す文字（コードなど）は '\0' に置き換える（単語にならない文字）。
 */
export function textblockText(node: PMNode, mask: (child: PMNode) => boolean = isCode): string {
  let text = ''
  node.forEach((c) => {
    text += c.isText ? (mask(c) ? '\u0000'.repeat(c.text!.length) : c.text!) : '￼'.repeat(c.nodeSize)
  })
  return text
}
export const isCode = (c: PMNode) => c.marks.some((m) => m.type.spec.code)

/** コードブロックの中を除いたテキストブロック（f の pos はブロックの中身の先頭の位置） */
export function eachTextblock(doc: PMNode, f: (node: PMNode, pos: number) => void) {
  doc.descendants((node, pos) => {
    if (node.type.spec.code) return false
    if (!node.isTextblock) return true
    f(node, pos + 1)
    return false
  })
}

/** 飾りの付いた文字（クリックで url を開く） */
export const linkedInline = (from: number, to: number, url: string, attrs: Record<string, string>) =>
  Decoration.inline(from, to, { ...attrs, 'data-linked-url': url })

/** 横の表示の widget（key が同じなら作り直さない） */
export const linkedWidget = (pos: number, key: string, make: () => HTMLElement) =>
  Decoration.widget(pos, make, { side: 1, ignoreSelection: true, stopEvent: () => true, key })

/** integration の状態と項目を見て build で飾りを作り、本文のキーをまとめて問い合わせる拡張 */
export function linkedExtension(name: string, integrationOf: () => HostIntegration, build: (doc: PMNode) => LinkedState) {
  const key = new PluginKey<LinkedState>(name)
  return Extension.create({
    name,
    addProseMirrorPlugins() {
      return [
        new Plugin<LinkedState>({
          key,
          state: {
            init: (_, state) => build(state.doc),
            apply: (tr, old, _old, state) => (tr.docChanged || tr.getMeta(key) ? build(state.doc) : old),
          },
          props: {
            decorations: (state) => key.getState(state)?.deco,
            handleClick(view, _pos, event) {
              const el = (event.target as HTMLElement | null)?.closest<HTMLElement>('[data-linked-url]')
              if (!el?.dataset.linkedUrl || !view.dom.contains(el)) return false
              // 編集中のふつうのクリックはカーソルを置くだけ
              if (view.editable && !(event.metaKey || event.ctrlKey)) return false
              openNewTab(el.dataset.linkedUrl)
              return true
            },
          },
          view(view) {
            const integration = integrationOf()
            let timer: ReturnType<typeof setTimeout> | undefined
            // 本文のキーをまとめて問い合わせる（入力中は待つ。同じものは問い合わせない）
            const schedule = () => {
              clearTimeout(timer)
              timer = setTimeout(() => { const keys = key.getState(view.state)?.keys; if (keys?.length) integration.request(keys) }, 400)
            }
            const off = integration.onChange(() => {
              if (view.isDestroyed) return
              view.dispatch(view.state.tr.setMeta(key, true))
              schedule()
            })
            void integration.load()
            schedule()
            return {
              update: (v, prev) => { if (v.state.doc !== prev.doc) schedule() },
              destroy: () => { off(); clearTimeout(timer) },
            }
          },
        }),
      ]
    },
  })
}
