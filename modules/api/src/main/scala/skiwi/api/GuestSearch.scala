package skiwi.api

import skiwi.db.TextNorm

import java.text.Normalizer
import java.util.UUID

/**
 * ゲスト向けの検索（公開範囲のページだけが対象なので、全文索引を使わずにその場で照合する）。
 * ひらがな/カタカナ・全角/半角・大文字/小文字・小さい文字（ゃ・ゅ）・長音とハイフンの違いを無視する。
 */
object GuestSearch {

  final case class Doc(id: UUID, title: String, text: String)

  /** snippet: 一致箇所を <mark> で囲んだHTML（それ以外はエスケープ済み） */
  final case class Hit(id: UUID, titleHit: Boolean, snippet: Option[String])

  private val small = "ぁぃぅぇぉっゃゅょゎゕゖ".zip("あいうえおつやゆよわかけ").toMap
  private val dashes = Set('ー', '-', '‐', '‑', '–', '—', '−', 'ｰ', '―')

  private def normChar(cp: Int): String =
    Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFKC).toLowerCase.map { c0 =>
      val c = if c0 >= 'ァ' && c0 <= 'ヶ' then (c0 - 0x60).toChar else c0
      if dashes(c) then '-' else small.getOrElse(c, c)
    }

  /** 正規化した文字列と、正規化後の各文字が元の文字列のどこ（開始・終了）から来たか。 */
  def normMap(s: String): (String, Array[Int], Array[Int]) = {
    val sb = new StringBuilder
    val starts = scala.collection.mutable.ArrayBuffer.empty[Int]
    val ends = scala.collection.mutable.ArrayBuffer.empty[Int]
    var i = 0
    while i < s.length do {
      val cp = s.codePointAt(i)
      val n = Character.charCount(cp)
      val ns = normChar(cp)
      if (ns == "\u3099" || ns == "\u309a") && sb.nonEmpty then {
        // 半角カナの濁点・半濁点（ｶﾞ）: 前の文字と合成する（が）
        val composed = Normalizer.normalize(sb.last.toString + ns, Normalizer.Form.NFC)
        if composed.length == 1 then {
          sb.setCharAt(sb.length - 1, small.getOrElse(composed.head, composed.head)); ends(ends.length - 1) = i + n
        } else { sb.append(ns); starts += i; ends += i + n }
      } else {
        sb.append(ns)
        for _ <- 0 until ns.length do { starts += i; ends += i + n }
      }
      i += n
    }
    (sb.result(), starts.toArray, ends.toArray)
  }

  def norm(s: String): String = normMap(s)._1

  private def escape(s: String): String =
    Html.escape(s.map(c => if c == '\n' || c == '\r' || c == '\t' then ' ' else c))

  /** 本文の一致箇所の前後を切り出す。 */
  private def snippet(text: String, words: List[String], width: Int = 140): Option[String] = {
    val (n, st, en) = normMap(text)
    // 一致箇所（元の文字列の位置）
    val ranges = words.flatMap { w =>
      Iterator.iterate(n.indexOf(w))(i => n.indexOf(w, i + 1)).takeWhile(_ >= 0).take(50).map(i =>
        (st(i), en(i + w.length - 1))
      ).toList
    }.sortBy(_._1)
    ranges.headOption.map {
      (first, _) =>
        val from = math.max(0, first - 30)
        val to = math.min(text.length, from + width)
        val sb = new StringBuilder
        if from > 0 then sb.append("…")
        var pos = from
        for (a, b) <- ranges if a >= pos && b <= to do {
          sb.append(escape(text.substring(pos, a))).append("<mark>").append(escape(text.substring(
            a,
            b
          ))).append("</mark>")
          pos = b
        }
        sb.append(escape(text.substring(pos, to)))
        if to < text.length then sb.append("…")
        sb.result()
    }
  }

  /** すべての語をタイトルか本文に含むページ。タイトルに一致したものを先に並べる（同じなら元の順）。 */
  def search(query: String, docs: List[Doc], limit: Int = 50): List[Hit] = {
    val words = TextNorm.words(query).map(norm).filter(_.nonEmpty).distinct
    if words.isEmpty then Nil
    else {
      val hits = docs.flatMap { d =>
        val t = norm(d.title)
        val b = norm(d.text)
        if !words.forall(w => t.contains(w) || b.contains(w)) then None
        else Some(Hit(d.id, words.forall(t.contains), snippet(d.text, words.filter(b.contains))))
      }
      (hits.filter(_.titleHit) ++ hits.filterNot(_.titleHit)).take(limit)
    }
  }
}
