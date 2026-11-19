package skiwi.web

import io.github.ondeoma.scalactive.ScalactiveConfig
import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom

/**
 * 画面の入口.
 * `@main def main()` にすると生成される `main` クラスと `Main` が、大文字小文字を区別しないファイルシステム（macOS）で
 * 同じファイル名（main.sjsir / Main.sjsir）になり差分コンパイルが壊れるため、通常の main(args) にする.
 */
object Main {

  def main(args: Array[String]): Unit = {
    ScalactiveConfig.titleTemplate = "%s | skiwi"
    ScalactiveConfig.defaultTitle = Some("skiwi")
    AppNames.init("skiwi")
    // 画面の移動で一番上へ戻すのは、各画面（スクロールする領域）に任せる
    ScalactiveConfig.scrollToTopOnNavigate = false
    Theme.apply()
    Host.exposeToJs()
    editor.EditorLib.exposeToJs()
    // 編集中のエディタ以外にファイルを落としても、ブラウザがファイルを開いてページから離れないようにする
    editor.EditorDom.installFileDropGuard()
    EmbedViews.register()
    // skorimako のブラウザの観測（設定があるときだけ）と、PWA（ホーム画面に追加・オフラインで開く・プッシュ通知）
    Observe.start()
    Pwa.register()
    // 存在しない URL はトップへ. 開いた直後の URL も確かめるよう、RouterComponent を描く前に登録する
    Fallback.install(App.known)
    App()(dom.document.body, AddMethod.appendS(IdSelector("app"))).init()
  }

}
