import { Extension } from '@tiptap/core'
import { PluginKey } from '@tiptap/pm/state'
import Suggestion from '@tiptap/suggestion'
import { menuRenderer } from './suggestionMenu'
import SlashMenu from './SlashMenu.vue'
import { filterItems, showAllItem, slashItems, type SlashContext, type SlashItem } from './commands'

/** 「/」で開くブロック挿入メニュー。 */
export const SlashCommand = Extension.create<{ ctx: SlashContext }>({
  name: 'slashCommand',
  addOptions() { return { ctx: { pickFile: async () => null, upload: async () => {} } } },
  addProseMirrorPlugins() {
    const all = slashItems(this.options.ctx)
    return [
      Suggestion<SlashItem, SlashItem>({
        editor: this.editor,
        pluginKey: new PluginKey('slashCommand'),
        char: '/',
        startOfLine: false,
        allowSpaces: false,
        // 末尾に常に「すべてのブロックを表示」（入力中の語は一覧の検索欄に引き継ぐ）
        items: ({ query }) => [...filterItems(all, query), showAllItem(all, query)],
        command: ({ editor, range, props }) => props.run(editor, range),
        render: menuRenderer<SlashItem>(SlashMenu),
      }),
    ]
  },
})
