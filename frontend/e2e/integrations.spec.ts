import { expect, test } from '@playwright/test'
import { api, cleanup, editorRun, makeAdmin, makeUser, open, psql, waitContent } from './helpers'

/** skea・skawau 連携: 管理者の設定画面と、本文の表示（未連携・連携済みは API の応答を差し替え）。本物の skea・skawau は使わない */
test.describe.configure({ mode: 'serial' })

const SKEA = 'https://skea.invalid'
// 開発用DBの既存の設定と、各ユーザーの連携を退避し、終わったら戻す（設定は全体で1つ。連携が残っていると、
// 設定を変えるときの「連携が外れます」の確認が出るうえ、保存すると連携が消えるため）
let saved = ''
let savedConnections = ''
const sqlText = (v: string) => `'${v.replace(/'/g, "''")}'`
test.beforeAll(() => {
  saved = psql(`select coalesce(json_agg(json_build_object('k', key, 'v', value)), '[]') from integration_setting where key like 'skea.%' or key like 'skawau.%'`).trim()
  savedConnections = psql(`select coalesce(json_agg(c), '[]') from integration_connection c`).trim()
  psql(`delete from integration_connection`)
})
test.afterAll(() => {
  psql(`delete from integration_setting where key like 'skea.%' or key like 'skawau.%'`)
  const rows = JSON.parse(saved || '[]') as { k: string; v: string }[]
  for (const r of rows) psql(`insert into integration_setting (key, value) values (${sqlText(r.k)}, ${sqlText(r.v)})`)
  psql(`delete from integration_connection`)
  psql(`insert into integration_connection select * from json_populate_recordset(null::integration_connection, ${sqlText(savedConnections || '[]')}::json)`)
  cleanup()
})

test('管理者: skea 連携の設定（redirect_uri の表示・client_secret は書き込みだけ・不正なキーは拒否）。一般ユーザーは使えない', async ({ browser }) => {
  const admin = makeAdmin(makeUser('skea 管理者'))
  const ws = await api(admin, 'POST', '/api/workspaces', { name: 'skea 設定の確認' })
  const page = await open(browser, admin, `/w/${ws.id}`)
  await page.getByTestId('admin-integrations').click()
  await expect(page).toHaveURL(/\/admin\/integrations$/)
  await expect(page.getByTestId('skea-redirect-uri')).toHaveText(/\/api\/integrations\/skea\/callback$/)
  await page.getByTestId('skea-url').fill(`${SKEA}/`)
  await page.getByTestId('skea-client-id').fill('skiwi')
  await page.getByTestId('skea-client-secret').fill('e2e-secret')
  // 「UTF-8」を誤検出しないよう、キーの形は決まっている
  await page.getByTestId('skea-project-keys').fill('SKEA, utf-8')
  await page.getByTestId('skea-save').click()
  await expect(page.getByTestId('skea-save-error')).toContainText('UTF-8')
  await page.getByTestId('skea-project-keys').fill('skea, demo')
  await page.getByTestId('skea-save').click()
  await expect(page.getByTestId('skea-save')).toHaveText('保存しました')
  await expect(page.getByTestId('skea-project-keys')).toHaveValue('SKEA, DEMO')
  await expect(page.getByTestId('skea-url')).toHaveValue(SKEA)
  // 秘密は返さない（入力欄は空で「設定済み」）
  await expect(page.getByTestId('skea-client-secret')).toHaveValue('')
  await expect(page.getByTestId('skea-client-secret')).toHaveAttribute('placeholder', /設定済み/)
  const s = await api(admin, 'GET', '/api/admin/integrations/skea')
  expect(JSON.stringify(s)).not.toContain('e2e-secret')
  await page.context().close()

  const user = makeUser('skea 一般')
  const r = await fetch(`${process.env.BASE_URL ?? 'http://localhost:8080'}/api/admin/integrations/skea`, { headers: { Cookie: `skiwi_session=${user.token}` } })
  expect(r.status).toBe(403)
})

test('未連携: 本文のタスクIDはリンクだけ（タイトルなし）。コード内は対象外。閲覧中のクリックで skea を開く', async ({ browser }) => {
  const u = makeUser('skea 未連携')
  const ws = await api(u, 'POST', '/api/workspaces', { name: 'skea 表示の確認' })
  const p = await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: 'タスクの参照' })
  const page = await open(browser, u, `/w/${ws.id}/p/${p.id}?edit=1`)
  await expect(page.getByTestId('skea-connect')).toBeVisible()
  await expect(page.getByTestId('skea-connect')).toHaveAttribute('href', new RegExp(`^/api/integrations/skea/connect\\?returnTo=${encodeURIComponent(`/w/${ws.id}/p/${p.id}`)}`))
  await editorRun(page, `editor.chain().focus().insertContent('<p>対応は SKEA-12 と DEMO-3 で。<code>SKEA-13</code> と UTF-8 と XSKEA-1 と skea-2 は対象外</p>').run()`)
  const keys = page.getByTestId('skea-key')
  await expect(keys).toHaveText(['SKEA-12', 'DEMO-3'])
  await expect(keys.first()).toHaveAttribute('title', 'skea と連携するとタイトルを表示します')
  const chips = page.getByTestId('skea-chip')
  await expect(chips).toHaveCount(2)
  await expect(chips.first()).toHaveText('↗')
  await expect(chips.first()).toHaveAttribute('href', `${SKEA}/tasks/SKEA-12`)
  // 本文は書き換えない
  await waitContent(u, p.id, (t) => t.includes('対応は SKEA-12 と DEMO-3 で。'))
  const c = await api(u, 'GET', `/api/pages/${p.id}/content`)
  expect(JSON.stringify(c.content)).not.toContain('skea.invalid')
  await page.context().close()

  // 閲覧中はタスクIDのクリックで新しいタブに開く
  const view = await open(browser, u, `/w/${ws.id}/p/${p.id}`, { setup: (ctx) => ctx.route(`${SKEA}/**`, (r) => r.fulfill({ body: 'skea' })) })
  await expect(view.getByTestId('skea-key').first()).toBeVisible()
  const [tab] = await Promise.all([view.context().waitForEvent('page'), view.getByTestId('skea-key').first().click()])
  await expect(tab).toHaveURL(`${SKEA}/tasks/SKEA-12`)
  await view.context().close()
})

test('連携済み（API の応答を差し替え）: タイトルと状態を横に出し、まとめて1回だけ問い合わせる。編集中のクリックでは開かない', async ({ browser }) => {
  const u = makeUser('skea 連携済み')
  const ws = await api(u, 'POST', '/api/workspaces', { name: 'skea 連携済みの確認' })
  const p = await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: 'タスク' })
  const asked: string[][] = []
  const page = await open(browser, u, `/w/${ws.id}/p/${p.id}?edit=1`, {
    setup: async (ctx) => {
      await ctx.route('**/api/integrations/skea', (r) => r.fulfill({ json: { enabled: true, url: SKEA, projectKeys: ['SKEA'], connected: true } }))
      await ctx.route('**/api/integrations/skea/lookup', (r) => {
        const keys = (r.request().postDataJSON() as { keys: string[] }).keys
        asked.push(keys)
        r.fulfill({ json: { connected: true, items: keys.filter((k) => k !== 'SKEA-9').map((k) => ({ key: k, title: `タイトル ${k}`, status: { name: '進行中', category: 'in_progress', color: '#2563eb' }, url: `${SKEA}/tasks/${k}` })) } })
      })
    },
  })
  await expect(page.getByTestId('skea-disconnect')).toBeVisible()
  await editorRun(page, `editor.chain().focus().insertContent('<p>SKEA-1 と SKEA-9 と SKEA-1</p>').run()`)
  const chips = page.getByTestId('skea-chip')
  await expect(chips).toHaveText(['タイトル SKEA-1進行中', '↗', 'タイトル SKEA-1進行中'])
  await expect(chips.first().locator('.skea-status')).toHaveCSS('background-color', 'rgb(37, 99, 235)')
  await expect(page.getByTestId('skea-key').nth(1)).toHaveAttribute('title', 'skea で見つからないか、見る権限がありません')
  expect(asked).toEqual([['SKEA-1', 'SKEA-9']])
  // 編集中のふつうのクリックはカーソルを置くだけ（新しいタブは開かない）
  let opened = false
  page.context().on('page', () => { opened = true })
  await page.getByTestId('skea-key').first().click()
  await page.waitForTimeout(500)
  expect(opened).toBe(false)
  await page.context().close()
})

test('skawau（API の応答を差し替え）: 貼り付けた URL は閲覧中ファイル名だけ、編集中は URL の後ろに名前。文字を付けたリンクとコードはそのまま', async ({ browser }) => {
  const SKAWAU = 'https://skawau.invalid'
  const F = '6f1c0a2e-1111-4a2b-8c3d-000000000001'
  const D = '6f1c0a2e-2222-4a2b-8c3d-000000000002'
  const X = '6f1c0a2e-3333-4a2b-8c3d-000000000003'
  const u = makeUser('skawau 連携済み')
  const ws = await api(u, 'POST', '/api/workspaces', { name: 'skawau の確認' })
  const p = await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: 'ファイル' })
  const asked: string[][] = []
  const setup = async (ctx: import('@playwright/test').BrowserContext) => {
    await ctx.route('**/api/integrations/skawau', (r) => r.fulfill({ json: { enabled: true, url: SKAWAU, projectKeys: [], connected: true } }))
    await ctx.route('**/api/integrations/skawau/lookup', (r) => {
      const keys = (r.request().postDataJSON() as { keys: string[] }).keys
      asked.push(keys)
      r.fulfill({ json: { connected: true, items: keys.filter((k) => k !== X).map((k) => ({ key: k, name: k === D ? '設計資料' : '見積書.xlsx', isDir: k === D, mimeType: null })) } })
    })
  }
  const page = await open(browser, u, `/w/${ws.id}/p/${p.id}?edit=1`, { setup })
  const fu = `${SKAWAU}/view/${F.toUpperCase()}`
  const du = `${SKAWAU}/files/${D}`
  const xu = `${SKAWAU}/view/${X}`
  await editorRun(page, `editor.chain().focus().insertContent('<p><a href="${fu}">${fu}</a> と <a href="${du}">資料フォルダ</a> と ${xu} と <code>${SKAWAU}/view/${F}</code></p>').run()`)
  const links = page.getByTestId('skawau-link')
  await expect(links).toHaveCount(3)
  await expect(links.nth(1)).toHaveAttribute('title', '設計資料（skawau）')
  await expect(links.nth(2)).toHaveAttribute('title', 'skawau で見つからないか、見る権限がありません')
  const chips = page.getByTestId('skawau-chip')
  await expect(chips).toHaveText(['📊見積書.xlsx'])
  await expect(chips.first()).toHaveAttribute('href', fu)
  await expect(links.first()).toBeVisible()
  expect(asked).toEqual([[F, D, X]])
  await waitContent(u, p.id, (t) => t.includes('資料フォルダ'))
  await page.context().close()

  // 閲覧中は URL の文字を隠して名前だけ
  const view = await open(browser, u, `/w/${ws.id}/p/${p.id}`, { setup })
  await expect(view.getByTestId('skawau-chip')).toHaveText(['📊見積書.xlsx'])
  await expect(view.getByTestId('skawau-link').first()).toBeHidden()
  await expect(view.getByText('資料フォルダ')).toBeVisible()
  await expect(view.getByText(xu)).toBeVisible()
  await view.context().close()
})
