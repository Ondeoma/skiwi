import { expect, test } from '@playwright/test'
import { api, cleanup, editorRun, makeUser, open, waitContent, type TestUser } from './helpers'

/** 主要な画面操作の通しテスト（2人のユーザーで、同じワークスペースを使う） */
test.describe.configure({ mode: 'serial' })

let owner: TestUser
let member: TestUser
let ws: { id: string }
let ws2: { id: string }
let pageA: { id: string } // 「手順書」
let pageB: { id: string } // 「議事録」

const pageUrl = (id: string, w = ws.id) => `/w/${w}/p/${id}`


test.beforeAll(async () => {
  owner = makeUser('テスト 所有')
  member = makeUser('テスト 参加')
  ws = await api(owner, 'POST', '/api/workspaces', { name: '画面テスト' })
  ws2 = await api(owner, 'POST', '/api/workspaces', { name: '画面テスト（移動先）' })
  await api(owner, 'PUT', `/api/workspaces/${ws.id}/acl`, {
    defaultRole: null,
    entries: [
      { principalType: 'user', principalId: owner.id, role: 'edit' },
      { principalType: 'user', principalId: member.id, role: 'edit' },
    ],
  })
  pageA = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '手順書' })
  pageB = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '議事録' })
})
test.afterAll(() => cleanup())

test('⌘K のクイック切り替えでページを開ける', async ({ browser }) => {
  const page = await open(browser, owner, pageUrl(pageA.id))
  await expect(page.locator('#page-title')).toHaveValue('手順書')
  await page.keyboard.press('ControlOrMeta+k')
  await expect(page.getByTestId('palette')).toBeVisible()
  await page.getByTestId('palette-input').fill('議事録')
  await expect(page.getByTestId('palette-item').first()).toContainText('議事録')
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(new RegExp(`/p/${pageB.id}`))
  await expect(page.locator('#page-title')).toHaveValue('議事録')
  await page.context().close()
})

test('本文を選んでコメントし、解決できる', async ({ browser }) => {
  const page = await open(browser, owner, `${pageUrl(pageA.id)}?edit=1`)
  await editorRun(page, `editor.chain().focus().insertContent('<p>電源を切ってから作業する。</p>').run()`)
  await page.getByTestId('done').click()
  await editorRun(page, `editor.commands.setTextSelection({ from: 1, to: 6 })`)
  await page.getByTestId('comment-selection').click()
  await page.getByTestId('comment-input').first().fill('理由も書いてください')
  await page.getByTestId('comment-post').click()
  await expect(page.getByTestId('thread')).toContainText('理由も書いてください')
  await expect(page.locator('.ProseMirror .comment-mark')).toHaveCount(1)
  await expect(page.getByTestId('comments-toggle')).toContainText('1')
  await page.getByTestId('thread-resolve').click()
  await expect(page.getByTestId('comments-toggle')).not.toContainText('1')
  await page.context().close()
})

test('同じページを開いている人が表示される', async ({ browser }) => {
  const a = await open(browser, owner, pageUrl(pageA.id))
  const b = await open(browser, member, `${pageUrl(pageA.id)}?edit=1`)
  await expect(a.getByTestId('presence')).toContainText('テ')
  await expect(a.getByTestId('presence')).toHaveAttribute('title', /テスト 参加（編集中）/)
  await expect(b.getByTestId('presence')).toHaveAttribute('title', /テスト 所有（閲覧中）/)
  await b.context().close()
  await expect(a.getByTestId('presence')).toHaveCount(0)
  await a.context().close()
})

// 1x1 の PNG（ブラウザで正方形に縮めてから送る）
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64')

test('アイコン: 設定するとヘッダ・同じページを開いている人・コメントに出て、外すと名前の1文字に戻る', async ({ browser }) => {
  const a = await open(browser, owner, pageUrl(pageA.id))
  await expect(a.getByTestId('me-avatar').locator('img')).toHaveCount(0)
  await a.getByTestId('user-menu').click()
  await a.getByTestId('avatar-open').click()
  await a.getByTestId('avatar-file').setInputFiles({ name: 'me.png', mimeType: 'image/png', buffer: PNG })
  await expect(a.getByTestId('avatar-preview').locator('img')).toHaveAttribute('src', /^blob:/)
  await a.getByTestId('avatar-save').click()
  await expect(a.getByTestId('avatar-dialog')).toHaveCount(0)
  await expect(a.getByTestId('me-avatar').locator('img')).toHaveAttribute('src', new RegExp(`^/api/users/${owner.id}/avatar\\?v=\\d+$`))
  // 保存したのはブラウザで縮めた WebP か JPEG
  const me = await api(owner, 'GET', '/api/me')
  const res = await a.request.get(me.avatarUrl)
  expect(res.headers()['content-type']).toMatch(/^image\/(webp|jpeg)$/)

  // 後から開いた人には、同じページを開いている人としてアイコンが出る（読み込み直した a の状態が届く）
  await a.reload()
  await api(owner, 'POST', `/api/pages/${pageA.id}/comments`, { body: 'アイコン付きのコメント' })
  const b = await open(browser, member, pageUrl(pageA.id))
  await expect(b.getByTestId('presence').locator('img')).toHaveAttribute('src', me.avatarUrl)
  await b.getByTestId('comments-toggle').click()
  await expect(b.locator('.c', { hasText: 'アイコン付きのコメント' }).locator('.user-avatar img')).toHaveAttribute('src', me.avatarUrl)
  await b.context().close()

  await a.getByTestId('user-menu').click()
  await a.getByTestId('avatar-open').click()
  await a.getByTestId('avatar-remove').click()
  await expect(a.getByTestId('me-avatar').locator('img')).toHaveCount(0)
  await expect(a.getByTestId('me-avatar')).toHaveText('テ')
  await a.context().close()
})

test('ToDo の担当者と期限が「自分のToDo」に出る', async ({ browser }) => {
  const a = await open(browser, owner, `${pageUrl(pageB.id)}?edit=1`)
  const today = new Date()
  const ymd = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
  await editorRun(a, `editor.chain().focus('end').insertContent({ type: 'taskList', content: [{ type: 'taskItem', attrs: { checked: false }, content: [{ type: 'paragraph', content: [
    { type: 'text', text: '見積もりを出す ' }, { type: 'mention', attrs: { userId: arg.id, label: arg.name } }, { type: 'text', text: ' ' }, { type: 'dueDate', attrs: { date: arg.ymd } } ] }] }] }).run()`,
    { id: member.id, name: member.name, ymd })
  await expect(a.getByTestId('due-date')).toContainText('📅')
  await waitContent(owner, pageB.id, (t) => t.includes('見積もりを出す'))
  await a.context().close()

  const b = await open(browser, member, `/w/${ws.id}`)
  await b.getByTestId('panel-tasks').click()
  await expect(b.getByTestId('task-item')).toContainText('見積もりを出す')
  await expect(b.getByTestId('tasks-panel')).toContainText('今日・明日')
  await b.getByTestId('task-item').first().click()
  await expect(b).toHaveURL(new RegExp(`/p/${pageB.id}`))
  await b.context().close()
})

test('ページの状態と所有者を設定できる', async ({ browser }) => {
  const page = await open(browser, owner, `${pageUrl(pageA.id)}?edit=1`)
  await page.getByTestId('page-status').click()
  await page.getByTestId('dialog-choice').filter({ hasText: 'レビュー中' }).click()
  await expect(page.getByTestId('page-status')).toContainText('レビュー中')
  await expect(page.getByTestId('tree-status').first()).toContainText('レビュー中')
  await page.getByTestId('page-owner').click()
  await page.getByTestId('app-dialog').locator('input').fill('テスト 参加')
  await page.getByTestId('dialog-ok').click()
  await expect(page.getByTestId('page-owner')).toContainText('所有者: テスト 参加')
  await page.context().close()
})

test('Markdown を取り込んでページにできる', async ({ browser }) => {
  const page = await open(browser, owner, `/w/${ws.id}`)
  await page.getByTestId('ws-menu').click()
  const chooser = page.waitForEvent('filechooser')
  await page.getByTestId('ws-import').click()
  await (await chooser).setFiles({ name: 'メモ.md', mimeType: 'text/markdown', buffer: Buffer.from('# 取り込んだメモ\n\n- [ ] 確認する\n\n| a | b |\n| - | - |\n| 1 | 2 |\n') })
  await expect(page.locator('#page-title')).toHaveValue('取り込んだメモ')
  await expect(page.locator('.ProseMirror table')).toHaveCount(1)
  await expect(page.locator('.ProseMirror ul[data-type=taskList]')).toHaveCount(1)
  await page.context().close()
})

test('リンク切れの一覧に、削除したページへのリンクが出る', async ({ browser }) => {
  const target = await api<{ id: string }>(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '消す予定' })
  const page = await open(browser, owner, `${pageUrl(pageA.id)}?edit=1`)
  await editorRun(page, `editor.chain().focus('end').insertPageLink({ pageId: arg.id, workspaceId: arg.ws, title: '消す予定' }).run()`, { id: target.id, ws: ws.id })
  await waitContent(owner, pageA.id, (_, json) => JSON.stringify(json).includes(target.id))
  await api(owner, 'DELETE', `/api/pages/${target.id}`)
  await page.getByTestId('ws-menu').click()
  await page.getByTestId('ws-broken').click()
  await expect(page.getByTestId('broken-links')).toContainText('ゴミ箱にあるページ')
  await expect(page.getByTestId('broken-links')).toContainText('消す予定')
  await page.context().close()
})

test('ページを別のワークスペースへ移動できる', async ({ browser }) => {
  const moving = await api<{ id: string }>(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '移すページ' })
  const page = await open(browser, owner, pageUrl(moving.id))
  await expect(page.locator('#page-title')).toHaveValue('移すページ')
  const row = page.getByTestId('tree-row').filter({ hasText: '移すページ' })
  await row.click({ button: 'right' })
  await page.getByTestId('tree-move-ws').click()
  await page.getByTestId('dialog-choice').filter({ hasText: '画面テスト（移動先）' }).click()
  await expect(page).toHaveURL(new RegExp(`/w/${ws2.id}/p/${moving.id}`))
  await expect(page.getByTestId('tree-row').filter({ hasText: '移すページ' })).toHaveCount(1)
  // 古いURLで開いても、移動先で開き直す
  await page.goto(pageUrl(moving.id))
  await expect(page).toHaveURL(new RegExp(`/w/${ws2.id}/p/${moving.id}`))
  await page.context().close()
})

test('ホワイトボードが2人の間で同期される', async ({ browser }) => {
  const a = await open(browser, owner, `${pageUrl(pageB.id)}?edit=1`)
  await editorRun(a, `editor.chain().focus('end').insertWhiteboard().run()`)
  await expect(a.locator('.excalidraw canvas.interactive')).toHaveCount(1, { timeout: 30_000 })
  const b = await open(browser, member, `${pageUrl(pageB.id)}?edit=1`)
  const canvas = b.locator('.excalidraw canvas.interactive')
  await canvas.scrollIntoViewIfNeeded()
  await expect(canvas).toHaveCount(1, { timeout: 30_000 })
  await b.getByTestId('toolbar-rectangle').click({ force: true }) // 四角形（ツールバーの見えないラジオボタン）
  const box = (await canvas.boundingBox())!
  // 左側の設定パネルを避けて、キャンバスの右寄りに描く
  await b.mouse.move(box.x + 420, box.y + 180)
  await b.mouse.down()
  await b.mouse.move(box.x + 560, box.y + 280, { steps: 10 })
  await b.mouse.up()
  const count = () => a.evaluate(() => {
    const ed = (document.querySelector('.ProseMirror') as any).editor
    const doc = ed.extensionManager.extensions.find((e: any) => e.name === 'whiteboard').options.ydoc
    const id = ed.getJSON().content.find((n: any) => n.type === 'whiteboard').attrs.id
    const board = doc.getMap('whiteboards').get(id)
    return board ? [...board.get('e').values()].filter((e: any) => !e.isDeleted && e.type === 'rectangle').length : 0
  })
  await expect.poll(count, { timeout: 15_000 }).toBe(1)
  await a.context().close()
  await b.context().close()
})
