import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import scalaJS from './vite-scalajs'

const backend = process.env.BACKEND ?? 'http://localhost:8080'

export default defineConfig({
  // Scalactive(Scala.js)の画面は sbt の web プロジェクトから読み込む
  plugins: [vue(), scalaJS({ cwd: '..', projectID: 'web' })],
  server: {
    proxy: {
      '/api': backend,
      '/auth': backend,
      '/ws': { target: backend.replace(/^http/, 'ws'), ws: true },
    },
  },
})
