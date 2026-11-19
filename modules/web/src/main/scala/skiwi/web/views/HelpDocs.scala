package skiwi.web.views

import skiwi.web.{JsModule, Ui}

import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

// ユーザーマニュアル（help/chapters/*.md）の章・目次・検索（旧 help/manual.ts）.
// Markdown の読み込みと HTML 化だけは JS（JsModule "help" の files・render）.
// 正規表現は元の TS と同じ動き（\s が全角空白も含むなど）にするため JS の RegExp を使う.

final case class HelpSection(
  id: String,
  title: String,
  level: Int)

/** html: Markdown を変換した本文 */
final case class HelpChapter(
  id: String,
  title: String,
  icon: String,
  summary: String,
  html: String,
  sections: List[HelpSection])

object HelpChapter {
  val empty: HelpChapter = HelpChapter("", "", "", "", "", Nil)
}

final case class HelpHit(
  chapter: HelpChapter,
  section: Option[HelpSection],
  snippet: String)

/** 検索用の本文の塊. flat: 空白を詰めた本文（抜粋用）、norm / normFlat: それぞれの検索用の正規化 */
final case class HelpBlock(
  section: Option[HelpSection],
  flat: String,
  norm: String,
  normFlat: String)

/** chapters: 章（並び順） / search: マニュアルの検索 */
final class HelpDocs(
  val chapters: List[HelpChapter],
  blocks: Map[String, List[HelpBlock]]) {

  /** マニュアルの検索（すべての語を含む節. ひらがな/カタカナ・全角/半角の違いは無視. 50件まで） */
  def search(
    q: String
  ): List[HelpHit] = {
    val words = HelpDocs.split(Ui.normalizeForSearch(q), """\s+""").filter(_.nonEmpty)
    if (words.isEmpty) Nil
    else chapters.iterator.flatMap(c => blocks.getOrElse(c.id, Nil).iterator.map(c -> _))
      .filter { case (_, b) => words.forall(b.norm.contains) }
      .map { case (c, b) =>
        val flat = b.flat
        val i = b.normFlat.indexOf(words.head)
        val from = math.max(0, i - 30).min(flat.length)
        val to = math.max(from, math.min(flat.length, i + 90))
        HelpHit(
          c,
          b.section,
          (if (i > 30) "…" else "") + HelpDocs.trim(flat.substring(from, to)) + (if (i + 90 < flat.length) "…" else "")
        )
      }
      .take(50).toList
  }

}

object HelpDocs {

  private def re(
    src: String,
    flags: String = ""
  ): js.RegExp = new js.RegExp(src, flags)

  private def replace(
    s: String,
    src: String,
    flags: String,
    rep: String
  ): String =
    s.asInstanceOf[js.Dynamic].replace(re(src, flags), rep).asInstanceOf[String]

  private def split(
    s: String,
    src: String
  ): List[String] =
    s.asInstanceOf[js.Dynamic].split(re(src)).asInstanceOf[js.Array[String]].toList

  private def trim(
    s: String
  ): String = s.asInstanceOf[js.Dynamic].trim().asInstanceOf[String]

  /** 見出しの文字からアンカーを作る（日本語もそのまま. 記号・空白はハイフン）. HelpLink の #見出し もこれに合わせる */
  def slug(
    s: String
  ): String = {
    val a = replace(trim(s).toLowerCase, """[\s　]+""", "g", "-")
    val b = replace(a, """[!-/:-@[-`{-~、。・（）「」『』【】]""", "g", "")
    val c = if (b.length > 60) b.substring(0, 60) else b
    if (c.isEmpty) "section" else c
  }

  private def build(
    path: String,
    raw: String,
    render: js.Dynamic
  ): Option[(HelpChapter, Int, List[HelpBlock])] = {
    val m = re("""\/(\d+)-([a-z0-9-]+)\.md$""").exec(path)
    if (m == null) None
    else {
      val id = m(2).get
      // 先頭の front matter（--- で囲んだ title / icon / summary）
      val fm = re("""^---\n([\s\S]*?)\n---\n""").exec(raw)
      val meta: Map[String, String] =
        if (fm == null) Map.empty
        else fm(1).get.split("\n").toList.flatMap { line =>
          val i = line.indexOf(':')
          if (i > 0) Some(trim(line.substring(0, i)) -> trim(line.substring(i + 1))) else None
        }.toMap
      val body = if (fm == null) raw else raw.substring(fm(0).get.length)
      val sections = mutable.ListBuffer.empty[HelpSection]
      val used = mutable.Set.empty[String]
      val heading: js.Function2[String, Int, String] = (
        text,
        depth
      ) => {
        var hid = slug(text)
        var n = 2
        while (used(hid)) { hid = s"${slug(text)}-$n"; n += 1 }
        used += hid
        if (depth <= 3) sections += HelpSection(hid, text.replace("`", ""), depth)
        hid
      }
      val html = render(body, heading).asInstanceOf[String]
      val secs = sections.toList
      // 検索用: 見出し（見出し3まで）ごとに本文を分ける
      val raws = mutable.ListBuffer[(Option[HelpSection], StringBuilder)]((None, new StringBuilder))
      var k = 0
      body.split("\n", -1).foreach { line =>
        val h = re("""^(#{1,6})\s+(.+)$""").exec(line)
        if (h != null && h(1).get.length <= 3) {
          val sec = secs.lift(k)
          k += 1
          raws += ((sec.filter(_.level > 1), new StringBuilder(h(2).get)))
        } else {
          val t = replace(
            replace(replace(line, """<\/?kbd>""", "g", ""), """-{3,}""", "g", " "),
            """[*`>|]|\[|\]\([^)]*\)""",
            "g",
            " "
          )
          raws.last._2.append("\n").append(t)
        }
      }
      val blocks = raws.toList.map(
        (
          sec,
          sb
        ) => (sec, sb.toString)
      ).filter(
        (
          _,
          t
        ) => trim(t).nonEmpty
      ).map {
        (
          sec,
          t
        ) =>
          val flat = replace(t, """\s+""", "g", " ")
          HelpBlock(sec, flat, Ui.normalizeForSearch(t), Ui.normalizeForSearch(flat))
      }
      val ch = HelpChapter(
        id,
        meta.getOrElse("title", id),
        meta.getOrElse("icon", "📄"),
        meta.getOrElse("summary", ""),
        html,
        secs
      )
      Some((ch, m(1).get.toInt, blocks))
    }
  }

  private var cache: Option[Future[HelpDocs]] = None

  def load(): Future[HelpDocs] = cache.getOrElse {
    val f = JsModule.load("help").map { m =>
      val files = m.files.asInstanceOf[js.Dictionary[String]]
      val built = files.toList.flatMap(
        (
          p,
          raw
        ) => build(p, raw, m.render)
      ).sortBy(_._2)
      new HelpDocs(built.map(_._1), built.map(b => b._1.id -> b._3).toMap)
    }
    cache = Some(f)
    f
  }

}
