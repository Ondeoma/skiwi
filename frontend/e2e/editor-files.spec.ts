import { expect, test, type Page } from '@playwright/test'
import { api, cleanup, login, makeUser, waitContent } from './helpers'

/** 画像の貼り付け・ファイルのドラッグ＆ドロップ */
test.afterAll(() => cleanup())

const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='

/** ファイルを貼り付ける（クリップボードの画像。名前は image.png になることが多い） */
async function paste(page: Page, files: { name: string; type: string; b64?: string; text?: string }[]) {
  await page.evaluate((fs) => {
    const dt = new DataTransfer()
    for (const f of fs) dt.items.add(new File([f.b64 ? Uint8Array.from(atob(f.b64), (c) => c.charCodeAt(0)) : f.text ?? ''], f.name, { type: f.type }))
    document.querySelector('.ProseMirror')!.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }))
  }, files)
}
/** selector の要素の上にファイルを落とす。戻り値: 既定の動作（ブラウザがファイルを開く）が止められたか */
async function drop(page: Page, selector: string, files: { name: string; type: string; b64?: string; text?: string }[]) {
  return page.evaluate(([sel, fs]) => {
    const dt = new DataTransfer()
    for (const f of fs) dt.items.add(new File([f.b64 ? Uint8Array.from(atob(f.b64), (c) => c.charCodeAt(0)) : f.text ?? ''], f.name, { type: f.type }))
    const el = document.querySelector(sel as string)!
    const r = el.getBoundingClientRect()
    const at = { clientX: r.left + 8, clientY: r.top + 8 }
    el.dispatchEvent(new DragEvent('dragenter', { dataTransfer: dt, bubbles: true, cancelable: true, ...at }))
    el.dispatchEvent(new DragEvent('dragover', { dataTransfer: dt, bubbles: true, cancelable: true, ...at }))
    const ev = new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true, ...at })
    el.dispatchEvent(ev)
    return ev.defaultPrevented
  }, [selector, files] as const)
}

test('画像を貼り付けると名前を付けて入り、複数のファイルを落とすと順番どおりに入る', async ({ browser }) => {
  const u = makeUser('添付 確認')
  const ws = await api(u, 'POST', '/api/workspaces', { name: '添付の確認' })
  const p = await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '画像と資料' })
  const ctx = await browser.newContext()
  await login(ctx, u)
  const page = await ctx.newPage()
  await page.goto(`/w/${ws.id}/p/${p.id}?edit=1`)
  await page.locator('.ProseMirror').click()

  // スクリーンショットの貼り付け（名前のない画像）
  await paste(page, [{ name: 'image.png', type: 'image/png', b64: PNG }])
  await expect(page.locator('.ProseMirror img')).toHaveCount(1)
  await expect(page.locator('.ProseMirror img')).toHaveAttribute('alt', /^貼り付け-\d{8}-\d{6}\.png$/)

  // 画像と PDF をまとめて落とす → 落とした順（画像 → ファイルのカード）
  const prevented = await drop(page, '.ProseMirror', [
    { name: '構成図.png', type: 'image/png', b64: PNG },
    { name: '仕様書.pdf', type: 'application/pdf', text: '%PDF-1.4' },
  ])
  expect(prevented).toBe(true) // エディタが受け取った
  await expect(page.locator('.ProseMirror img')).toHaveCount(2)
  const c = await waitContent(u, p.id, (_t, json) => JSON.stringify(json).includes('仕様書.pdf'))
  const kinds = ((c.content as any).content as any[]).filter((n) => n.type === 'image' || n.type === 'fileBlock')
    .map((n) => n.type === 'image' ? n.attrs.alt : n.attrs.filename)
  // 落とした2つは、選んだ順に並ぶ（貼り付けた画像との前後は落とした位置による）
  expect(kinds.filter((k) => !k.startsWith('貼り付け-'))).toEqual(['構成図.png', '仕様書.pdf'])
  expect(kinds.filter((k) => k.startsWith('貼り付け-'))).toHaveLength(1)

  // エディタの外（サイドメニュー）に落としても、ページから離れない
  const url = page.url()
  expect(await drop(page, '[data-testid=side]', [{ name: 'x.png', type: 'image/png', b64: PNG }])).toBe(true)
  expect(page.url()).toBe(url)
  // 表示モードの本文に落としても何も入らない
  await page.getByTestId('done').click()
  expect(await drop(page, '.ProseMirror', [{ name: 'y.png', type: 'image/png', b64: PNG }])).toBe(true)
  await page.waitForTimeout(500)
  await expect(page.locator('.ProseMirror img')).toHaveCount(2)
  await ctx.close()
})
