package skiwi.web.editor

import org.scalajs.dom
import skiwi.web.Embeds
import skiwi.web.parts.{BlockPicker, IconPicker}

import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/**
 * スラッシュコマンド（「/」）の項目の分類と絞り込み、ブロックの一覧（parts/BlockPicker）・絵文字アイコンの選択（parts/IconPicker）を
 * エディタ（JS）から開く. 項目そのもの（挿入の処理）はエディタの JS が持ち、ここでは表示と選択だけを行う.
 */
object Blocks {

  /** ブロックの分類（一覧での見出し・並び順） */
  val Categories: List[String] = List("基本", "リスト", "構成・装飾", "表・メディア", "技術・図")

  private val CategoryOf: Map[String, String] = Map(
    "テキスト" -> "基本", "見出し1" -> "基本", "見出し2" -> "基本", "見出し3" -> "基本", "引用" -> "基本", "区切り線" -> "基本", "ページへのリンク" -> "基本",
    "箇条書きリスト" -> "リスト", "番号付きリスト" -> "リスト", "ToDoリスト" -> "リスト", "期限" -> "リスト", "担当者" -> "リスト",
    "開閉ブロック" -> "構成・装飾", "段組み（2列）" -> "構成・装飾", "段組み（3列）" -> "構成・装飾", "段組み（列数を指定）" -> "構成・装飾", "目次" -> "構成・装飾",
    "同期ブロックを作成" -> "構成・装飾", "同期ブロックを埋め込む" -> "構成・装飾",
    "データベース（ページ内）" -> "表・メディア", "表" -> "表・メディア", "ホワイトボード" -> "表・メディア", "画像" -> "表・メディア", "ファイル" -> "表・メディア",
    "埋め込み" -> "表・メディア", "ブックマーク" -> "表・メディア", "フォームの回答（smohua）" -> "表・メディア",
    "コード" -> "技術・図", "数式ブロック" -> "技術・図", "インライン数式" -> "技術・図", "Mermaid図" -> "技術・図", "draw.io 図" -> "技術・図",
  )

  /** 項目の分類（決まっていないパネルは「構成・装飾」、ほかは「基本」） */
  def categoryOf(title: String): String = CategoryOf.getOrElse(title, if (title.endsWith("パネル")) "構成・装飾" else "基本")

  def str(it: js.Dynamic, key: String): String = {
    val v = it.selectDynamic(key)
    if (js.isUndefined(v) || v == null) "" else v.toString
  }

  def keywords(it: js.Dynamic): List[String] = {
    val v = it.keywords
    if (js.isUndefined(v) || v == null) Nil else v.asInstanceOf[js.Array[String]].toList
  }

  /** 名前・説明・キーワードで絞り込む */
  def filter(items: js.Array[js.Dynamic], query: String): js.Array[js.Dynamic] = {
    val q = query.trim.toLowerCase
    if (q.isEmpty) items
    else items.filter(i => str(i, "title").toLowerCase.contains(q) || str(i, "description").toLowerCase.contains(q) || keywords(i).exists(_.contains(q)))
  }

  /** ブロックの一覧のモーダルを開く. 選んだ項目（取り消しは None） */
  def openPicker(items: js.Array[js.Dynamic], query: String): Future[Option[js.Dynamic]] = {
    val p = Promise[Option[js.Dynamic]]()
    val box = dom.document.createElement("div")
    dom.document.body.appendChild(box)
    var unmount: () => Unit = () => ()
    unmount = Embeds.render(box)(BlockPicker(items = items, query = query, onDone = (it: Option[js.Dynamic]) => {
      unmount()
      box.remove()
      p.trySuccess(it)
    }))
    p.future
  }

  /**
   * 絵文字アイコンの選択を parent（position: relative の要素）の下に開く. 外側クリック・Esc で onClose. 戻り値で閉じる.
   * head: 一覧の上に出す要素（パネルの色など）/ at: 画面座標に固定表示
   */
  def openIconPicker(parent: dom.Element, onSelect: Option[String] => Unit, onClose: () => Unit, head: Option[dom.Element],
                     allowNone: Boolean, noneLabel: Option[String], at: Option[(Double, Double)]): () => Unit = {
    val box = dom.document.createElement("div")
    parent.appendChild(box)
    val unmount = Embeds.render(box)(IconPicker(onSelect = onSelect, onClose = (_: Unit) => onClose(), allowNone = allowNone,
      noneLabel = noneLabel.getOrElse("アイコンなし"), at = at, headEl = head))
    () => { unmount(); box.remove() }
  }

}
