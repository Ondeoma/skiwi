import { expect, test } from '@playwright/test'
import { api, cleanup, makeUser, open, type TestUser } from './helpers'

/** URL の ID（/w/:wid/p/:pid）は、そのまま API のパスになる。ID の形でないものは開かない */
let u: TestUser
let ws: { id: string }
let pg: { id: string }, pg2: { id: string }

test.beforeAll(async () => {
  u = makeUser('経路 担当')
  ws = await api(u, 'POST', '/api/workspaces', { name: '経路の確認' })
  pg = await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '開くページ' })
  pg2 = await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '別のページ' })
})
test.afterAll(() => cleanup())

test('ページの ID に「/」「?」を入れた URL を開いても、ほかの API（公開など）は呼ばれない', async ({ browser }) => {
  const page = await open(browser, u, '/')
  const posts: string[] = []
  page.on('request', (r) => { if (r.method() !== 'GET') posts.push(`${r.method()} ${new URL(r.url()).pathname}`) })
  // ふつうの URL では、開いたことの記録（visit）が送られる
  await page.goto(`/w/${ws.id}/p/${pg.id}`)
  await expect.poll(() => posts).toContain(`POST /api/pages/${pg.id}/visit`)
  posts.length = 0
  // 細工した URL: そのままパスに入れると POST /api/pages/<id>/share/publish?/visit になる
  // （サーバーは %2F を含むパスの画面を返さないので、画面の中のリンクと同じく、読み込み直さずに移る）
  const go = (path: string) => page.evaluate((p) => {
    history.pushState({}, '', p)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, path)
  // この移り方でページが開くこと（ふつうの ID なら visit が送られる）を先に確かめる
  await go(`/w/${ws.id}/p/${pg2.id}`)
  await expect.poll(() => posts).toContain(`POST /api/pages/${pg2.id}/visit`)
  posts.length = 0
  const crafted = `/w/${ws.id}/p/${pg.id}%2Fshare%2Fpublish%3F`
  await go(crafted)
  await page.waitForTimeout(1500)
  expect(new URL(page.url()).pathname).toBe(crafted)
  // 「?」だけを入れたもの（サーバーが画面を返す）を直接開いても同じ
  await page.goto(`/w/${ws.id}/p/${pg.id}%3Fx`)
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(1000)
  expect(posts.filter((p) => p.includes('/share'))).toEqual([])
  expect(posts).toEqual([])
  await page.context().close()
})
