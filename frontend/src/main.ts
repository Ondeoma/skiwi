// 画面の標準フォント（M PLUS Rounded 1c。npm から取り込んで自前で配信し、使う文字の分だけ読み込む）
// 太さは 400・500・700（600 は 700 で表示される）
import '@fontsource/m-plus-rounded-1c/400.css'
import '@fontsource/m-plus-rounded-1c/500.css'
import '@fontsource/m-plus-rounded-1c/700.css'
import './style.css'
import { installFileDropGuard } from './ui/fileDrop'
import './bridge'

installFileDropGuard()

// 画面（Scalactive. Scala.js の modules/web）. ここまでに置いた window.skiwiLoad などを使う
import 'scalajs:main.js'
