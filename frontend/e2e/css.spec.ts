import { expect, test, type Browser, type Page } from '@playwright/test'
import { api, cleanup, editorRun, makeUser, open as openAs, waitContent, type TestUser } from './helpers'

/** カスタムCSS（ワークスペース・ページ・子孫への継承・範囲の限定） */
test.describe.configure({ mode: 'serial' })

let u: TestUser
let ws: { id: string }
let parent: { id: string }, child: { id: string }, grandchild: { id: string }

const BLUE = 'rgb(0, 0, 255)', GREEN = 'rgb(0, 128, 0)'

const open = (browser: Browser, path: string) => openAs(browser, u, path)
const paraColor = (page: Page) => page.locator('.ProseMirror p').first().evaluate((el) => getComputedStyle(el).color)
const paraStyle = (page: Page) => page.locator('.ProseMirror p').first().evaluate((el) => getComputedStyle(el).fontStyle)

test.beforeAll(async () => {
  u = makeUser('スタイル 担当')
  ws = await api(u, 'POST', '/api/workspaces', { name: 'スタイルの確認' })
  parent = await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '親' })
  child = await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '子', parentId: parent.id })
  grandchild = await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '孫', parentId: child.id })
})
test.afterAll(() => cleanup())

test('ページのCSS: 危険な記述は除かれ、表示エリアの中だけに効き、子ページに継承される', async ({ browser }) => {
  // 各ページに段落を入れておく
  for (const p of [parent, child, grandchild]) {
    const pg = await open(browser, `/w/${ws.id}/p/${p.id}?edit=1`)
    await editorRun(pg, `editor.chain().focus().insertContent('<p>本文の段落</p>').run()`)
    await waitContent(u, p.id, (t) => t.includes('本文の段落'))
    await pg.context().close()
  }
  const page = await open(browser, `/w/${ws.id}/p/${parent.id}`)
  const before = await paraColor(page)
  await page.getByTestId('page-css').click()
  await page.getByTestId('css-input').fill([
    'p { color: rgb(0, 0, 255); }',
    'body { background: rgb(1, 2, 3); }',
    '.side { display: none; }',
    '.x { background: url(https://evil.example/a.png); position: fixed; }',
    '@import url(https://evil.example/x.css);',
  ].join('\n'))
  // 入力中にその場で反映される
  await expect.poll(() => paraColor(page)).toBe(BLUE)
  const warnings = page.getByTestId('css-warnings')
  await expect(warnings).toContainText('外部の url()')
  await expect(warnings).toContainText('position: fixed')
  await expect(warnings).toContainText('@import')
  await page.getByTestId('css-save').click()
  await expect(page.getByTestId('css-dialog')).toHaveCount(0)
  expect(before).not.toBe(BLUE)
  await expect.poll(() => paraColor(page)).toBe(BLUE)
  // サイドバー（表示エリアの外）には効かない。body は表示エリアに置き換わる
  await expect(page.getByTestId('side')).toBeVisible()
  expect(await page.evaluate(() => getComputedStyle(document.body).backgroundColor)).not.toBe('rgb(1, 2, 3)')
  expect(await page.locator('.skiwi-page-css').evaluate((el) => getComputedStyle(el).backgroundColor)).toBe('rgb(1, 2, 3)')
  // 子ページ・孫ページにも継承される
  await page.goto(`/w/${ws.id}/p/${grandchild.id}`)
  await expect.poll(() => paraColor(page)).toBe(BLUE)
  await page.context().close()
})

test('子ページの「このページだけ」のCSSは、孫には継承されない。ワークスペースのCSSは全ページに効く', async ({ browser }) => {
  const page = await open(browser, `/w/${ws.id}/p/${child.id}`)
  await page.getByTestId('page-css').click()
  await expect(page.getByTestId('css-dialog')).toContainText('上位から適用されているスタイル（1件）')
  await page.getByTestId('css-input').fill('p { color: rgb(0, 128, 0); }')
  await page.getByTestId('css-inherit').uncheck()
  await page.getByTestId('css-save').click()
  await expect.poll(() => paraColor(page)).toBe(GREEN)

  // ワークスペースのCSS
  await page.getByTestId('ws-menu').click()
  await page.getByTestId('ws-css').click()
  await page.getByTestId('css-input').fill('p { font-style: italic; color: rgb(9, 9, 9); }')
  await page.getByTestId('css-save').click()
  await expect.poll(() => paraStyle(page)).toBe('italic')
  await expect.poll(() => paraColor(page)).toBe(GREEN) // ページのCSSが後から重なる

  await page.goto(`/w/${ws.id}/p/${grandchild.id}`)
  await expect.poll(() => paraColor(page)).toBe(BLUE) // 子の「このページだけ」は効かず、親のものが効く
  await expect.poll(() => paraStyle(page)).toBe('italic')
  await page.context().close()
})

test('ゲスト向けの公開ページにもCSSが効く', async ({ browser }) => {
  const sh = await api<{ token: string }>(u, 'PUT', `/api/pages/${parent.id}/share`, { includeChildren: true })
  const ctx = await browser.newContext() // ログインなし
  const page = await ctx.newPage()
  await page.goto(`/s/${sh.token}/${child.id}`)
  await expect(page.getByTestId('guest-title')).toHaveText('子')
  await expect.poll(() => paraColor(page)).toBe(GREEN)
  await expect.poll(() => paraStyle(page)).toBe('italic')
  await ctx.close()
})

test('ページのCSS: @font-face・@keyframes の名前は自動で区別され、画面のほかの部分のフォントやアニメーションを置き換えない', async ({ browser }) => {
  const page = await open(browser, `/w/${ws.id}/p/${grandchild.id}`)
  await page.getByTestId('page-css').click()
  await page.getByTestId('css-input').fill([
    '@font-face { font-family: "Hiragino Sans"; src: url(/api/attachments/00000000-0000-0000-0000-000000000000); }',
    '@keyframes spin { from { opacity: 0; } to { opacity: 1; } }',
    'p { font-family: "Hiragino Sans", sans-serif; animation: spin 1s; }',
  ].join('\n'))
  // 入力中にその場で反映される（<style data-custom-css>）
  await expect.poll(() => page.evaluate(() => document.querySelector('style[data-custom-css]')?.textContent ?? '')).toContain('skiwi-u-Hiragino Sans')
  const css = await page.evaluate(() => document.querySelector('style[data-custom-css]')?.textContent ?? '')
  expect(css).toMatch(/@keyframes\s+"?skiwi-u-spin/)
  expect(css).toMatch(/animation:[^;]*skiwi-u-spin/)
  // 元の名前の定義は残らない
  expect(css).not.toMatch(/font-family:\s*"Hiragino Sans"/)
  expect(css).not.toMatch(/@keyframes\s+spin\b/)
  await page.context().close()
})

test('ページのCSS: 検査をすり抜ける書き方（文字列の中の url(・var() を含む一括指定・入れ子・グループの中の @font-face）も取り除かれる', async ({ browser }) => {
  const page = await open(browser, `/w/${ws.id}/p/${parent.id}`)
  const external: string[] = []
  page.on('request', (r) => { if (r.url().includes('evil.example')) external.push(r.url()) })
  await page.getByTestId('page-css').click()
  await page.getByTestId('css-input').fill([
    // a) 文字列リテラルの中の url( から読ませて、後ろの外部の url() を data: の一部に見せる
    'p::before { content: "url(data:image/" url("https://evil.example/e"); }',
    // b) var() を含む一括指定（個別のプロパティの値は空になる）
    'p { background: var(--z,) url(https://evil.example/a); }',
    // c) 入れ子のルールの後ろの宣言
    'p { .y {} background-image: url(https://evil.example/b); position: fixed; }',
    // d) グループの中の @font-face
    '@media all { @font-face { font-family: evilfont; src: url(https://evil.example/c); } p { font-family: evilfont; } }',
    // e) 入れ子の & で表示エリアの外に効かせる
    'p { html:not(&) [data-testid="side"] { display: none; } }',
    // 兄弟の指定で表示エリアの外に出る
    'body:not(.zz) ~ * { display: none; }',
    // 使える書き方はそのまま効く（グループの中のルールも表示エリアの中に限られる）
    '@media all { p { color: rgb(0, 0, 255); } body { outline-color: rgb(1, 2, 3); } }',
    '@supports (display: grid) { p { font-style: italic; } }',
  ].join('\n'))
  await expect.poll(() => paraColor(page)).toBe(BLUE)
  await expect.poll(() => paraStyle(page)).toBe('italic')
  const css = await page.evaluate(() => document.querySelector('style[data-custom-css]')?.textContent ?? '')
  expect(css).not.toContain('evil.example')
  expect(css).not.toMatch(/position:\s*fixed/)
  expect(css).not.toContain('@font-face')
  expect(css).not.toContain('data-testid')
  expect(css).not.toContain('&')
  expect(css).not.toContain('~')
  // グループは組み立て直され、中のルールは表示エリアの中に限られる
  expect(css).toMatch(/@media all \{\s*\.skiwi-page-css p \{/)
  expect(await page.evaluate(() => getComputedStyle(document.body).outlineColor)).not.toBe('rgb(1, 2, 3)')
  expect(await page.locator('p').first().evaluate((el) => getComputedStyle(el).position)).not.toBe('fixed')
  await expect(page.getByTestId('side')).toBeVisible()
  const warnings = page.getByTestId('css-warnings')
  await expect(warnings).toContainText('外部の url()')
  await expect(warnings).toContainText('入れ子のルール')
  await expect(warnings).toContainText('@font-face')
  await expect(warnings).toContainText('表示エリアの外に効く指定')
  expect(external).toEqual([])
  await page.context().close()
})
