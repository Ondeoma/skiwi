import { expect, test, type Page } from '@playwright/test'
import { api, cleanup, editorRun, login, makeUser, type TestUser } from './helpers'

/** ホワイトボード内のドラッグでブロックごと動かないこと（移動はタイトルのつまみでだけ） */
test.describe.configure({ mode: 'serial' })

let u: TestUser
let ws: { id: string }
let pg: { id: string }

test.beforeAll(async () => {
  u = makeUser('ボード ドラッグ')
  ws = await api(u, 'POST', '/api/workspaces', { name: 'ボードのドラッグ' })
  pg = await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: 'ボード' })
})
test.afterAll(() => cleanup())

const topTypes = (page: Page) => page.evaluate(() => (document.querySelector('.ProseMirror') as any).editor.getJSON().content.map((n: any) => n.type).join(','))
const rects = (page: Page) => page.evaluate(() => {
  const ed = (document.querySelector('.ProseMirror') as any).editor
  const doc = ed.extensionManager.extensions.find((e: any) => e.name === 'whiteboard').options.ydoc
  const id = ed.getJSON().content.find((n: any) => n.type === 'whiteboard').attrs.id
  return [...doc.getMap('whiteboards').get(id).get('e').values()].filter((e: any) => !e.isDeleted && e.type === 'rectangle').map((e: any) => Math.round(e.x))
})

test('ボード内で図形をドラッグしても、ブロックは動かず図形が動く', async ({ browser }) => {
  const ctx = await browser.newContext()
  await login(ctx, u)
  const page = await ctx.newPage()
  await page.goto(`/w/${ws.id}/p/${pg.id}?edit=1`)
  await editorRun(page, `editor.chain().focus().insertContent('<p>上の段落</p>').insertWhiteboard().run(); editor.chain().focus('end').insertContent('<p>下の段落</p>').run()`)
  const canvas = page.locator('.excalidraw canvas.interactive')
  await expect(canvas).toHaveCount(1, { timeout: 30_000 })
  if (process.env.FORCE_OLD_DRAGGABLE) await page.evaluate(() => { (document.querySelector('[data-testid=whiteboard]') as HTMLElement).draggable = true })
  const before = await topTypes(page)
  await canvas.scrollIntoViewIfNeeded()
  const box = (await canvas.boundingBox())!
  // 四角形を描く
  await page.getByTestId('toolbar-rectangle').click({ force: true })
  await page.mouse.move(box.x + 420, box.y + 180)
  await page.mouse.down()
  await page.mouse.move(box.x + 560, box.y + 280, { steps: 10 })
  await page.mouse.up()
  await expect.poll(() => rects(page)).toHaveLength(1)
  const x0 = (await rects(page))[0]
  // 選択ツールで四角形をつかんで右へ動かす
  await page.getByTestId('toolbar-selection').click({ force: true })
  await page.mouse.move(box.x + 490, box.y + 230)
  await page.mouse.down()
  await page.mouse.move(box.x + 640, box.y + 250, { steps: 15 })
  await page.mouse.up()
  await expect.poll(async () => (await rects(page))[0] - x0).toBeGreaterThan(100)
  expect(await topTypes(page)).toBe(before) // ブロックの並び・数は変わらない
  await ctx.close()
})

test('タイトルのつまみをドラッグすると、ブロックを移動できる', async ({ browser }) => {
  const ctx = await browser.newContext()
  await login(ctx, u)
  const page = await ctx.newPage()
  await page.goto(`/w/${ws.id}/p/${pg.id}?edit=1`)
  await expect(page.locator('.excalidraw canvas.interactive')).toHaveCount(1, { timeout: 30_000 })
  const first = page.locator('.ProseMirror > p').first()
  await expect(first).toHaveText('上の段落')
  await page.locator('.wb-title').dragTo(first, { targetPosition: { x: 5, y: 2 } })
  await expect.poll(() => topTypes(page)).toMatch(/^whiteboard,paragraph/)
  await ctx.close()
})
