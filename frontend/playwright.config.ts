import { defineConfig } from '@playwright/test'

/**
 * 画面操作の自動テスト。前提: DB・アプリ（scripts/dev-restart.sh）・画面の開発サーバー（npm run dev。BASE_URL、既定 http://localhost:5173）が起動済み。
 * ブラウザはインストール済みの Google Chrome を使う（npx playwright install は不要）。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:5173',
    channel: 'chrome',
    headless: true,
    viewport: { width: 1280, height: 860 },
    locale: 'ja-JP',
    trace: 'retain-on-failure',
  },
})
