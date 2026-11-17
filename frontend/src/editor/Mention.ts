import { Node, mergeAttributes } from '@tiptap/core'
import { PluginKey } from '@tiptap/pm/state'
import Suggestion from '@tiptap/suggestion'
import { menuRenderer } from './suggestionMenu'
import { api4 } from '../api'
import { mentionMenu, type MentionUser } from './MentionMenu'

/** 本文中の @メンション。保存されるとサーバーが新しくメンションされた人に通知する（閲覧できる人だけ）。 */
export const Mention = Node.create({
  name: 'mention',
  group: 'inline',
  inline: true,
  atom: true,
  selectable: false,

  addAttributes() {
    return {
      userId: { default: null, parseHTML: (el) => el.getAttribute('data-user-id'), renderHTML: (a) => ({ 'data-user-id': a.userId }) },
      label: { default: '', parseHTML: (el) => (el.textContent ?? '').replace(/^@/, ''), renderHTML: () => ({}) },
    }
  },
  parseHTML() { return [{ tag: 'span[data-mention]' }] },
  renderHTML({ node, HTMLAttributes }) {
    return ['span', mergeAttributes(HTMLAttributes, { 'data-mention': '', class: 'mention' }), `@${node.attrs.label}`]
  },
  renderText({ node }) { return `@${node.attrs.label}` },

  addProseMirrorPlugins() {
    let seq = 0
    return [
      Suggestion<MentionUser, MentionUser>({
        editor: this.editor,
        pluginKey: new PluginKey('mention'),
        char: '@',
        allowSpaces: false,
        items: async ({ query }) => {
          const q = query.trim()
          if (!q) return []
          const my = ++seq
          const r = await api4.directory(q).catch(() => [])
          if (my !== seq) return []
          return r.filter((d) => d.principalType === 'user').slice(0, 8).map((d) => ({ id: d.id, name: d.name, detail: d.detail }))
        },
        command: ({ editor, range, props }) => {
          editor.chain().focus().insertContentAt(range, [
            { type: 'mention', attrs: { userId: props.id, label: props.name } },
            { type: 'text', text: ' ' },
          ]).run()
        },
        render: menuRenderer<MentionUser>(mentionMenu),
      }),
    ]
  },
})
