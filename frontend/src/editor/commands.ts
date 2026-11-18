import type { Editor, Range } from '@tiptap/core'
import { PANEL_PRESETS } from './Panel'
import { alertDialog, chooseDialog, promptDialog } from '../ui/dialog'
import { host } from '../ui/host'
import { blockCategory, findSyncedBlocks, openBlockPicker, pageContent, type BlockItemView } from '../lib'
import { smohuaFormId } from './SmohuaForm'

/** 「/」の項目. 分類（一覧での見出し）・絞り込み・一覧のモーダルは Scala（editor.Blocks・parts/BlockPicker） */
export interface SlashItem extends BlockItemView {
  /** 'all' = 「すべてのブロックを表示」（一覧モーダルを開く特別な項目） */
  special?: 'all'
  run: (editor: Editor, range: Range) => void
}

/** スラッシュコマンドの外部依存（ファイル選択・入力ダイアログ）。 */
export interface SlashContext {
  pickFile: (accept: string) => Promise<File | null>
  /** ページの中に置くデータベースを選ぶ（新しく作ることもできる）。データベースのページID */
  pickDatabase?: () => Promise<string | null>
  upload: (file: File) => Promise<void>
}

const ask = (msg: string, def = '') => promptDialog(msg, def)

export function slashItems(ctx: SlashContext): SlashItem[] {
  // 注意: Mathematics の insert*Math は pos 省略時に editor.state（チェーン開始前の状態）を参照するため、
  // deleteRange 後の位置 r.from を明示的に渡す（渡さないと文末付近で RangeError）。
  const chain = (e: Editor, r: Range) => e.chain().focus().deleteRange(r)
  const items: SlashItem[] = [
    { title: 'テキスト', description: '通常の段落', icon: '¶', keywords: ['text', 'paragraph', 'p', 'てきすと'],
      run: (e, r) => chain(e, r).setParagraph().run() },
    { title: '見出し1', description: '大見出し', icon: 'H1', keywords: ['h1', 'heading', 'みだし'],
      run: (e, r) => chain(e, r).setHeading({ level: 1 }).run() },
    { title: '見出し2', description: '中見出し', icon: 'H2', keywords: ['h2', 'heading', 'みだし'],
      run: (e, r) => chain(e, r).setHeading({ level: 2 }).run() },
    { title: '見出し3', description: '小見出し', icon: 'H3', keywords: ['h3', 'heading', 'みだし'],
      run: (e, r) => chain(e, r).setHeading({ level: 3 }).run() },
    { title: '箇条書きリスト', description: '・で始まるリスト', icon: '•', keywords: ['bullet', 'list', 'ul', 'りすと'],
      run: (e, r) => chain(e, r).toggleBulletList().run() },
    { title: '番号付きリスト', description: '1. で始まるリスト', icon: '1.', keywords: ['number', 'ordered', 'ol', 'list', 'ばんごう'],
      run: (e, r) => chain(e, r).toggleOrderedList().run() },
    { title: 'ToDoリスト', description: 'チェックボックス付き', icon: '☑', keywords: ['todo', 'task', 'check', 'たすく'],
      run: (e, r) => chain(e, r).toggleTaskList().run() },
    { title: '期限', description: 'ToDo の期限（「自分のToDo」に表示・近づくと通知）', icon: '📅', keywords: ['due', 'date', 'deadline', 'きげん', 'しめきり', 'ひづけ'],
      run: (e, r) => chain(e, r).insertDueDate().run() },
    { title: '担当者', description: 'ToDo の担当者（@名前でも入力できます）', icon: '👤', keywords: ['assign', 'mention', 'たんとう', 'めんしょん'],
      run: (e, r) => chain(e, r).insertContent('@').run() },
    { title: '段組み（2列）', description: 'セクションを横に並べる（列数・幅の比はあとから変更可）', icon: '◫', keywords: ['columns', 'column', 'layout', 'grid', 'だんぐみ', 'れつ', 'ならべる'],
      run: (e, r) => chain(e, r).insertColumns(2).run() },
    { title: '段組み（3列）', description: '3つのセクションを横に並べる', icon: '▥', keywords: ['columns', 'column', 'layout', 'grid', 'だんぐみ', 'れつ'],
      run: (e, r) => chain(e, r).insertColumns(3).run() },
    { title: '段組み（列数を指定）', description: '2〜12列まで', icon: '▦', keywords: ['columns', 'column', 'layout', 'grid', '12', 'だんぐみ', 'れつ'],
      run: async (e, r) => {
        chain(e, r).run()
        const v = await ask('列の数（2〜12）', '4')
        const n = Number(v)
        if (v && Number.isInteger(n) && n >= 2 && n <= 12) e.chain().focus().insertColumns(n).run()
        else if (v) await alertDialog('2〜12 の数で指定してください')
      } },
    { title: '開閉ブロック', description: 'クリックで開閉するアコーディオン（トグルリスト）', icon: '▸', keywords: ['toggle', 'details', 'collapse', 'expand', 'accordion', 'とぐる', 'とぐるりすと', 'あこーでぃおん', 'かいへい', 'おりたたみ'],
      run: (e, r) => chain(e, r).setDetails().run() },
    ...([1, 2, 3] as const).map((level): SlashItem => ({
      title: `開閉見出し${level}`, description: `見出し${level}の大きさのタイトルで、中身を開閉できる（トグル見出し）`, icon: `▸H${level}`,
      keywords: ['toggle', 'heading', `h${level}`, 'とぐる', 'みだし', 'かいへい', 'とぐるみだし'],
      run: (e, r) => chain(e, r).setDetails().updateAttributes('details', { level }).run(),
    })),
    ...PANEL_PRESETS.map((p): SlashItem => ({
      title: p.title, description: 'アイコンと背景色の付いた囲み', icon: p.icon, keywords: [...p.keywords, 'panel', 'ぱねる'],
      run: (e, r) => chain(e, r).setPanel({ icon: p.icon, color: p.color }).run(),
    })),
    { title: '同期ブロックを作成', description: 'ほかのページに埋め込める範囲（直すと埋め込み先にも反映）', icon: '🔗', keywords: ['synced', 'sync', 'block', 'transclusion', 'どうき', 'うめこみ', 'きょうつう'],
      run: (e, r) => chain(e, r).setSyncedBlock().run() },
    { title: '同期ブロックを埋め込む', description: '別のページの同期ブロック・ページ全体を読み取り専用で表示', icon: '⧉', keywords: ['synced', 'embed', 'transclusion', 'include', 'どうき', 'うめこみ', 'さんしょう'],
      run: async (e, r) => {
        chain(e, r).run()
        const p = await host().pickPage()
        if (!p || e.isDestroyed) return
        const content = await pageContent(p.id).catch(() => null)
        const blocks = content ? findSyncedBlocks(content) : []
        let blockId: string | null = null
        if (blocks.length) {
          const v = await chooseDialog(`「${p.title || '無題'}」のどこを埋め込みますか？`, [
            ...blocks.map((b, i) => ({ label: `🔗 同期ブロック ${i + 1}`, value: b.id, detail: b.text.slice(0, 60) || '（空）' })),
            { label: '📄 ページ全体', value: '__page__', detail: 'ページの本文すべて' },
          ])
          if (v === null || e.isDestroyed) return
          blockId = v === '__page__' ? null : v
        }
        e.chain().focus().insertSyncedRef({ pageId: p.id, workspaceId: p.workspaceId, blockId }).run()
      } },
    { title: '目次', description: '見出しから自動で作る目次', icon: '☰', keywords: ['toc', 'contents', 'index', 'もくじ'],
      run: (e, r) => chain(e, r).insertToc().run() },
    { title: 'ページへのリンク', description: 'skiwi 内のページへのリンク（タイトルを自動表示）', icon: '↗', keywords: ['link', 'page', 'mention', 'りんく', 'ぺーじ', 'さんしょう'],
      run: async (e, r) => {
        chain(e, r).run()
        const p = await host().pickPage()
        if (e.isDestroyed) return
        e.commands.focus()
        if (p) e.chain().focus().insertPageLink({ pageId: p.id, workspaceId: p.workspaceId, title: p.title }).run()
      } },
    { title: '引用', description: '引用ブロック', icon: '❝', keywords: ['quote', 'blockquote', 'いんよう'],
      run: (e, r) => chain(e, r).toggleBlockquote().run() },
    { title: 'コード', description: 'シンタックスハイライト付き（言語は右上で選択）', icon: '</>', keywords: ['code', 'snippet', 'こーど'],
      run: (e, r) => chain(e, r).toggleCodeBlock().run() },
    { title: '表', description: '3×3の表', icon: '▦', keywords: ['table', 'grid', 'ひょう'],
      run: (e, r) => chain(e, r).insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run() },
    { title: 'ホワイトボード', description: '自由に描ける共同編集ボード（付箋・図形・矢印・手書き）', icon: '🧩', keywords: ['whiteboard', 'board', 'draw', 'miro', 'excalidraw', 'ほわいとぼーど', 'ずけい', 'てがき', 'ふせん'],
      run: (e, r) => chain(e, r).insertWhiteboard().run() },
    { title: '画像', description: '画像をアップロード（貼り付け・ドラッグでも追加できます）', icon: '🖼', iconClass: 'ico ico-image', keywords: ['image', 'img', 'picture', 'がぞう'],
      run: async (e, r) => { chain(e, r).run(); const f = await ctx.pickFile('image/*'); if (f) await ctx.upload(f) } },
    { title: 'ファイル', description: 'ファイルを添付（ドラッグでも追加できます）', icon: '📎', keywords: ['file', 'attach', 'upload', 'ふぁいる', 'てんぷ'],
      run: async (e, r) => { chain(e, r).run(); const f = await ctx.pickFile('*/*'); if (f) await ctx.upload(f) } },
    { title: 'データベース（ページ内）', description: 'データベースを表・ボードなどでこのページの中に表示する（新しく作るか、既存のものを選ぶ）', icon: '📊', keywords: ['database', 'table', 'board', 'inline', 'linked', 'でーたべーす', 'ひょう', 'ぼーど'],
      run: async (e, r) => { chain(e, r).run(); const id = await ctx.pickDatabase?.(); if (id) e.chain().focus().insertDatabaseBlock(id).run() } },
    { title: 'フォームの回答（smohua）', description: 'smohua のフォームで集めた回答（集計・一覧）をこのページの中に表示する', icon: '📋', keywords: ['smohua', 'form', 'survey', 'answer', 'response', 'ふぉーむ', 'あんけーと', 'かいとう', 'しゅうけい'],
      run: async (e, r) => {
        chain(e, r).run()
        const u = await ask('表示するフォームの URL（smohua のフォームの画面の URL）か ID')
        if (!u) return
        const id = smohuaFormId(u)
        if (id) e.chain().focus().insertSmohuaForm(id).run()
        else await alertDialog('smohua のフォームの URL か ID を指定してください')
      } },
    { title: 'ブックマーク', description: 'URL をタイトル・説明・画像付きのカードにする', icon: '🔖', keywords: ['bookmark', 'link', 'card', 'url', 'ぶっくまーく', 'かーど', 'りんく'],
      run: async (e, r) => { chain(e, r).run(); const u = await ask('カードにするURL'); if (u && !e.chain().focus().insertLinkCard(u).run()) await alertDialog('http(s) のURLを指定してください') } },
    { title: '埋め込み', description: 'YouTube・Vimeo・Figma・Google ドキュメント／ドライブ・Loom・Miro など、または任意のURL(https)', icon: '▶', keywords: ['embed', 'youtube', 'video', 'iframe', 'うめこみ'],
      run: async (e, r) => { chain(e, r).run(); const u = await ask('埋め込むURL（https）'); if (u && !e.chain().focus().insertEmbed(u).run()) await alertDialog('https のURLを指定してください') } },
    { title: '数式ブロック', description: 'KaTeX(LaTeX)で数式', icon: '∑', keywords: ['math', 'latex', 'katex', 'tex', 'equation', 'すうしき'],
      run: async (e, r) => { chain(e, r).run(); const l = await ask('数式（LaTeX）', 'E = mc^2'); if (l) e.chain().focus().insertBlockMath({ latex: l, pos: e.state.selection.from }).run() } },
    { title: 'インライン数式', description: '文中の数式', icon: 'x²', keywords: ['inline', 'math', 'latex', 'すうしき'],
      run: async (e, r) => { chain(e, r).run(); const l = await ask('数式（LaTeX）', 'x^2'); if (l) e.chain().focus().insertInlineMath({ latex: l, pos: e.state.selection.from }).run() } },
    { title: 'Mermaid図', description: 'フローチャート・シーケンス図など', icon: '⧉', keywords: ['mermaid', 'diagram', 'chart', 'flow', 'ず'],
      run: (e, r) => chain(e, r).insertMermaid().run() },
    { title: 'draw.io 図', description: 'draw.io（diagrams.net）で構成図・フロー図などを描く', icon: '◇', keywords: ['drawio', 'draw.io', 'diagrams', 'diagram', 'ず', 'こうせいず'],
      run: (e, r) => chain(e, r).insertDrawio().run() },
    { title: '区切り線', description: '水平線', icon: '—', keywords: ['hr', 'divider', 'line', 'くぎり'],
      run: (e, r) => chain(e, r).setHorizontalRule().run() },
  ]
  for (const it of items) it.category ??= blockCategory(it.title)
  return items
}

/** 「すべてのブロックを表示」: 「/」以降の入力を消し、一覧モーダルで選んだブロックをその位置に挿入する。 */
export function showAllItem(blocks: SlashItem[], query: string): SlashItem {
  return {
    title: 'すべてのブロックを表示…', description: '分類ごとの一覧から選ぶ', icon: '⊞', keywords: [], special: 'all',
    run: async (e, r) => {
      e.chain().focus().deleteRange(r).run()
      const picked = await openBlockPicker(blocks, query)
      if (e.isDestroyed) return
      e.commands.focus()
      if (picked) { const pos = e.state.selection.from; picked.run(e, { from: pos, to: pos }) }
    },
  }
}
