import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router'
// 画面の標準フォント（M PLUS Rounded 1c。npm から取り込んで自前で配信し、使う文字の分だけ読み込む）
// 太さは 400・500・700（600 は 700 で表示される）
import '@fontsource/m-plus-rounded-1c/400.css'
import '@fontsource/m-plus-rounded-1c/500.css'
import '@fontsource/m-plus-rounded-1c/700.css'
import './style.css'
import { installFileDropGuard } from './ui/fileDrop'
import './ui/theme' // 明暗の設定を <html> に反映し、OS の設定の変化も追う

installFileDropGuard()

createApp(App).use(createPinia()).use(router).mount('#app')
