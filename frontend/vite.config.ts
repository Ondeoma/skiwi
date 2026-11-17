import { defineConfig } from 'vite'
import scalaJS from './vite-scalajs'

const backend = process.env.BACKEND ?? 'http://localhost:8080'

export default defineConfig({
  // 画面（Scalactive. Scala.js）は sbt の web プロジェクトから読み込む
  plugins: [scalaJS({ cwd: '..', projectID: 'web' })],
  server: {
    proxy: {
      '/api': backend,
      '/auth': backend,
      '/oauth': backend,
      '/ws': { target: backend.replace(/^http/, 'ws'), ws: true },
    },
  },
})
