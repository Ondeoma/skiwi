import { Extension } from '@tiptap/core'
import { PluginKey } from '@tiptap/pm/state'
import Suggestion from '@tiptap/suggestion'
import { menuRenderer } from './suggestionMenu'
import { emojiMenu } from './EmojiMenu'
import { findEmoji, type EmojiItem } from '../lib'

/** 「:」＋名前で絵文字を入れる（例: :いいね → 👍）。1文字以上入れると候補を出す */
export const EmojiSuggest = Extension.create({
  name: 'emojiSuggest',
  addProseMirrorPlugins() {
    return [
      Suggestion<EmojiItem, EmojiItem>({
        editor: this.editor,
        pluginKey: new PluginKey('emojiSuggest'),
        char: ':',
        startOfLine: false,
        allowSpaces: false,
        // 日本語は語の間に空白を入れないので、どの文字のあとでも始める（候補がなければメニューは出ない）
        allowedPrefixes: null,
        items: ({ query }) => (query.length ? findEmoji(query) : []),
        command: ({ editor, range, props }) => { editor.chain().focus().insertContentAt(range, props.emoji).run() },
        render: menuRenderer<EmojiItem>(emojiMenu),
      }),
    ]
  },
})
