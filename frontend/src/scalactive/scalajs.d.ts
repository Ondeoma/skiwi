// Scala.js(modules/web)の出力. skiwi.web.Main の @JSExportTopLevel
declare module 'scalajs:main.js' {
  /** name の画面を el に描画し、片付ける関数を返す */
  export function mountView(name: string, el: Element, params: Record<string, string>): () => void
}
