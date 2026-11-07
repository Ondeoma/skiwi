// Excalidraw のフォントを自前で配信する（既定ではCDNから読み込むため）。public/excalidraw/fonts へコピーする。
import { cpSync, existsSync, mkdirSync } from 'node:fs'
const src = 'node_modules/@excalidraw/excalidraw/dist/prod/fonts'
const dst = 'public/excalidraw/fonts'
if (!existsSync(src)) { console.error(`not found: ${src}`); process.exit(1) }
mkdirSync('public/excalidraw', { recursive: true })
cpSync(src, dst, { recursive: true })
console.log(`copied Excalidraw fonts to ${dst}`)
