import { expect, test, type Page } from '@playwright/test'
import { api, cleanup, editorRun, open, makeUser } from './helpers'

/** 選択のキー操作（⌘A・Shift+↑/↓） */
test.afterAll(() => cleanup())

const selection = (page: Page) => editorRun(page, `const s = editor.state.selection; return { type: s.toJSON().type, text: editor.state.doc.textBetween(s.from, s.to, '|') }`)

test('⌘A はコードブロックの中では中身だけ、もう一度で本文全体。Shift+↑ は行ごとに広げ、段落の端からブロック単位にする。表示中は本文だけを選ぶ', async ({ browser }) => {
  const u = makeUser('選択 確認')
  const ws = await api(u, 'POST', '/api/workspaces', { name: '選択の確認' })
  const p = await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: 'キー操作' })
  const page = await open(browser, u, `/w/${ws.id}/p/${p.id}?edit=1`)
  await editorRun(page, `editor.commands.setContent('<p>一行目</p><p>二行目</p><pre><code>const a = 1\\nconst b = 2</code></pre><p>最後</p>')`)

  // コードブロックの2行目の位置
  const codeLine2 = await editorRun(page, `let pos = 0; editor.state.doc.descendants((n, p) => { if (n.type.name === 'codeBlock') pos = p + 1 + n.textContent.indexOf('const b') + 4 }); return pos`)

  // コードブロックの中で ⌘A → コードブロックの中身だけ
  await editorRun(page, `editor.commands.focus(); editor.commands.setTextSelection(arg)`, codeLine2)
  await page.keyboard.press('ControlOrMeta+a')
  await expect.poll(() => selection(page)).toEqual({ type: 'text', text: 'const a = 1\nconst b = 2' })
  // もう一度 → 本文全体（ブロック単位）。選んだブロックは見えるように強調し、書式のメニューは出さない
  await page.keyboard.press('ControlOrMeta+a')
  await expect.poll(async () => (await selection(page)).type).toBe('nodeRange')
  await expect(page.locator('.ProseMirror .ProseMirror-selectednoderange')).toHaveCount(4)
  await expect(page.getByTestId('comment-selection')).toBeHidden()

  // Shift+↑: コードブロックの2行目から → 1行目まで（文字の選択）→ コードブロック → 前の段落まで
  await editorRun(page, `editor.commands.focus(); editor.commands.setTextSelection(arg)`, codeLine2)
  await page.keyboard.press('Shift+ArrowUp')
  await expect.poll(async () => (await selection(page)).type).toBe('text')
  await page.keyboard.press('Shift+ArrowUp')
  await expect.poll(() => selection(page)).toEqual({ type: 'nodeRange', text: 'const a = 1\nconst b = 2' })
  await page.keyboard.press('Shift+ArrowUp')
  await expect.poll(() => selection(page)).toEqual({ type: 'nodeRange', text: '二行目|const a = 1\nconst b = 2' })
  await page.keyboard.press('Shift+ArrowDown')
  await expect.poll(() => selection(page)).toEqual({ type: 'nodeRange', text: 'const a = 1\nconst b = 2' })

  // 表示中（エディタの外）の ⌘A → ページ全体ではなく本文だけ
  await page.getByTestId('done').click()
  await page.locator('body').click({ position: { x: 600, y: 700 } })
  await page.keyboard.press('ControlOrMeta+a')
  await expect.poll(() => page.evaluate(() => {
    const s = window.getSelection()!
    return document.querySelector('.ProseMirror')!.contains(s.anchorNode) && s.toString().includes('最後') && !s.toString().includes('選択の確認')
  })).toBe(true)
})
