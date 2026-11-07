import { expect, test } from '@playwright/test'
import { BASE, api, cleanup, editorRun, makeUser, open, waitContent, type TestUser } from './helpers'

/** ページの複製・閲覧権限の申請・公開版の固定／URL名／ゲスト向け検索／OGP */
test.describe.configure({ mode: 'serial' })

let owner: TestUser
let other: TestUser
test.beforeAll(() => {
  owner = makeUser('公開 担当')
  other = makeUser('申請 する人')
})
test.afterAll(() => cleanup())


test('複製: 子ページごとコピーし、一緒に複製したページへのリンクは付け替える', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: '複製の確認' })
  const root = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '元のページ' })
  const child = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '子ページ', parentId: root.id })
  const page = await open(browser, owner, `/w/${ws.id}/p/${root.id}?edit=1`)
  await editorRun(page, `editor.chain().focus().insertContent([{ type: 'paragraph', content: [{ type: 'text', text: '複製する本文 ' }, { type: 'pageLink', attrs: arg }] }]).run()`,
    { pageId: child.id, workspaceId: ws.id, title: '子ページ' })
  await waitContent(owner, root.id, (t) => t.includes('複製する本文'))

  await page.getByTestId('tree-row').filter({ hasText: '元のページ' }).first().click({ button: 'right' })
  await page.getByTestId('tree-duplicate').click()
  await expect(page.getByTestId('duplicate-title')).toHaveValue('元のページ のコピー')
  await page.getByTestId('duplicate-run').click()
  await expect(page.getByTestId('duplicate-dialog')).toHaveCount(0)
  await expect(page.locator('#page-title')).toHaveValue('元のページ のコピー')
  const copyId = page.url().split('/p/')[1].split('?')[0]
  expect(copyId).not.toBe(root.id)
  await expect(page.locator('.ProseMirror')).toContainText('複製する本文')
  // リンクは複製した子ページを指す
  const href = await page.getByTestId('page-link').getAttribute('href')
  expect(href).toContain(`/w/${ws.id}/p/`)
  expect(href).not.toContain(child.id)
  const pages = await api<{ id: string; title: string; parentId: string | null }[]>(owner, 'GET', `/api/workspaces/${ws.id}/pages`)
  expect(pages.filter((p) => p.title === '子ページ').map((p) => p.parentId).sort()).toEqual([root.id, copyId].sort())
  expect(href).toContain(pages.find((p) => p.title === '子ページ' && p.parentId === copyId)!.id)
  await page.context().close()
})

test('閲覧権限の申請: 開けないページから申請し、編集者が共有画面で許可すると開ける', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: '申請の確認' })
  const p = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '社外秘の手順' })

  const req = await open(browser, other, `/w/${ws.id}/p/${p.id}`)
  await expect(req.getByTestId('access-request')).toBeVisible()
  await expect(req.getByTestId('access-request')).not.toContainText('社外秘') // ページ名は出さない
  await req.getByTestId('access-message').fill('作業のため確認したいです')
  await req.getByTestId('access-submit').click()
  await expect(req.getByTestId('access-status')).toContainText('閲覧権限を申請しました')

  // 編集者: 通知に届き、共有画面で許可する
  const ed = await open(browser, owner, `/w/${ws.id}/p/${p.id}`)
  await ed.getByTestId('bell').click()
  await expect(ed.getByTestId('notification').first()).toContainText('閲覧権限を申請しました: 作業のため確認したいです')
  await ed.goto(`/w/${ws.id}/p/${p.id}?access=requests`)
  const item = ed.getByTestId('access-request-item')
  await expect(item).toContainText('申請 する人')
  await item.getByTestId('req-approve-view').click()
  await expect(ed.getByTestId('access-request-item')).toHaveCount(0)
  await ed.context().close()

  await req.reload()
  await expect(req.locator('#page-title')).toHaveValue('社外秘の手順')
  await req.context().close()
})

test('公開版の固定・URL名・ゲスト向け検索・OGP', async ({ browser }) => {
  const slug = `guide-${Date.now().toString(36)}`
  const ws = await api(owner, 'POST', '/api/workspaces', { name: '公開の確認' })
  const root = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '利用ガイド' })
  const child = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '導入', parentId: root.id })
  const ed = await open(browser, owner, `/w/${ws.id}/p/${child.id}?edit=1`)
  await editorRun(ed, `editor.chain().focus().insertContent('最初の説明です。インストールは簡単です。').run()`)
  await waitContent(owner, child.id, (t) => t.includes('最初の説明'))

  // 共有画面: 公開・公開版を固定・URL名
  await ed.goto(`/w/${ws.id}/p/${root.id}`)
  await ed.getByTestId('share').click()
  await ed.getByTestId('public-toggle').check()
  await ed.getByTestId('public-mode-pinned').check()
  await expect(ed.getByTestId('public-pinned')).toContainText('公開版は現在の内容と同じです')
  await ed.getByTestId('public-slug').fill(slug)
  await ed.getByTestId('public-slug-save').click()
  await expect(ed.getByTestId('public-url')).toHaveValue(new RegExp(`/s/${slug}$`))
  await ed.keyboard.press('Escape')
  // 子ページのURL名（親の公開リンクに含まれるページの共有画面）
  await ed.goto(`/w/${ws.id}/p/${child.id}`)
  await ed.getByTestId('share').click()
  await ed.getByTestId('page-slug').fill('setup')
  await ed.getByTestId('page-slug-save').click()
  await expect(ed.getByTestId('inherited-url')).toHaveValue(new RegExp(`/s/${slug}/setup$`))

  // ゲスト: URL名で開ける
  const gctx = await browser.newContext()
  const guest = await gctx.newPage()
  await guest.goto(`/s/${slug}/setup`)

  await expect(guest.getByTestId('guest-title')).toHaveText('導入')
  await expect(guest.getByTestId('guest-content')).toContainText('最初の説明です')

  // 編集してもゲストには公開版のまま
  await ed.goto(`/w/${ws.id}/p/${child.id}?edit=1`)
  await editorRun(ed, `editor.chain().focus().setContent('<p>改訂した説明です。インストールは簡単です。</p>').run()`)
  await waitContent(owner, child.id, (t) => t.includes('改訂した説明'))
  await guest.reload()
  await expect(guest.getByTestId('guest-content')).toContainText('最初の説明です')
  // 「現在の内容を公開」で反映
  await ed.goto(`/w/${ws.id}/p/${root.id}`)
  await ed.getByTestId('share').click()
  await expect(ed.getByTestId('public-pending')).toBeVisible()
  await ed.getByTestId('public-publish').click()
  await expect(ed.getByTestId('public-pinned')).toContainText('公開版は現在の内容と同じです')
  await guest.reload()
  await expect(guest.getByTestId('guest-content')).toContainText('改訂した説明です')

  // ゲスト向けの検索（本文・ひらがなでカタカナに一致）
  await guest.getByTestId('guest-side-filter').fill('いんすとーる')
  const hit = guest.getByTestId('guest-search-hit')
  await expect(hit).toHaveCount(1)
  await expect(hit.locator('mark')).toHaveText('インストール')

  // ページのHTMLにタイトル・OGPが入る（既定では検索エンジンに載せない）
  const html = await (await fetch(`${BASE}/s/${slug}/setup`)).text()
  expect(html).toContain('<title>導入 - skiwi</title>')
  expect(html).toContain('<meta property="og:description" content="改訂した説明です。インストールは簡単です。">')
  expect(html).toContain(`<link rel="canonical" href="http://localhost:8080/s/${slug}/setup">`)
  expect(html).toContain('noindex')
  await ed.getByTestId('public-indexable').check()
  await expect(ed.getByTestId('public-indexable')).toBeChecked()
  await expect.poll(async () => (await (await fetch(`${BASE}/s/${slug}`)).text()).includes('noindex')).toBe(false)
  expect(await (await fetch(`${BASE}/sitemap.xml`)).text()).toContain(`/s/${slug}/setup</loc>`)

  // 公開の予約: 開始前はゲストに案内を出す
  await api(owner, 'PUT', `/api/pages/${root.id}/share`, { startsAt: new Date(Date.now() + 3600_000).toISOString() })
  await guest.reload()
  await expect(guest.getByTestId('guest-error')).toContainText('から公開されます')
  await gctx.close()
  await ed.context().close()
})
