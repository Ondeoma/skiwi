import { defineConfig } from 'vite'
import scalaJS from './vite-scalajs'

const backend = process.env.BACKEND ?? 'http://127.0.0.1:8080'  // DEV_LOGIN のバックエンドは 127.0.0.1 だけで待ち受ける

export default defineConfig({
  // 画面（Scalactive. Scala.js）は sbt の web プロジェクトから読み込む
  plugins: [scalaJS({ cwd: '..', projectID: 'web' })],
  server: {
    // IPv4 で待ち受ける（LAN には公開しない）
    host: '127.0.0.1',
    proxy: {
      '/api': backend,
      '/auth': backend,
      '/mcp': backend,
      '/oauth': backend,
      '/scim': backend,
      '/ws': { target: backend.replace(/^http/, 'ws'), ws: true },
    },
  },
})
