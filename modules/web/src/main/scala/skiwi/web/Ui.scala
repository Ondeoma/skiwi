package skiwi.web

import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom

import scala.scalajs.js

object Ui {

  /** Mac・iPhone・iPad（ショートカットの修飾キーが ⌘） */
  val isMac: Boolean = "Mac|iPhone|iPad".r.findFirstIn(dom.window.navigator.platform).isDefined

  /** 検索用の正規化: 全角/半角・大文字/小文字・カタカナ/ひらがなを区別しない */
  def normalizeForSearch(s: String): String =
    s.asInstanceOf[js.Dynamic].normalize("NFKC").asInstanceOf[String].toLowerCase.map(c =>
      if (c >= 'ァ' && c <= 'ヶ') (c - 0x60).toChar else c
    )

  /** クリップボードへコピーし、copied を key にして 1.5 秒後に戻す */
  def copy[K](text: String, copied: RV[Option[K]], key: K): Unit =
    dom.window.navigator.clipboard.writeText(text).`then`[Unit](_ => {
      copied := Some(key)
      dom.window.setTimeout(() => copied := None, 1500)
      ()
    })

  /** 日時の表示（2026/11/13 10:48） */
  def date(iso: String): String =
    new js.Date(iso).asInstanceOf[js.Dynamic]
      .toLocaleString(
        "ja-JP",
        js.Dynamic.literal(year = "numeric", month = "2-digit", day = "2-digit", hour = "2-digit", minute = "2-digit")
      )
      .asInstanceOf[String]

  /** 文字をファイルとして保存させる */
  def downloadText(filename: String, text: String, mime: String = "text/plain;charset=utf-8"): Unit =
    sbirds.web.Dom.download(new dom.Blob(js.Array(text), new dom.BlobPropertyBag { `type` = mime }), filename)

}
