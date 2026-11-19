package skiwi.web.content

import io.circe.*
import org.scalajs.dom
import skiwi.web.model.Page

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** 複製の結果: 複製したページ（起点）とワークスペース、本文を複製できなかったページの数 */
final case class Duplicated(
  rootId: String,
  workspaceId: String,
  failed: Int)

/** ページの複製（旧 ui/duplicate.ts） */
object Duplicate {

  private val Uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
  private val AttRe = s"/api/attachments/($Uuid)".r
  private val PageRe = s"/w/$Uuid/p/($Uuid)".r

  /**
   * 複製した本文を整える.
   *  - 添付ファイル（画像・ファイル・ホワイトボードの画像）は複製したものに付け替える
   *  - 一緒に複製したページへのリンク・埋め込みは、複製したページに付け替える（それ以外は元のページのまま）
   *  - コメントの印は外す（コメントは複製しない）
   */
  def rewriteContent(
    content: Json,
    pages: Map[String, String],
    attachments: Map[String, String],
    workspaceId: String
  ): Json = {
    import scala.util.matching.Regex.quoteReplacement as q
    val s0 = content.noSpaces
    val s1 = AttRe.replaceAllIn(s0, m => q(attachments.get(m.group(1)).fold(m.matched)(to => s"/api/attachments/$to")))
    val s2 = PageRe.replaceAllIn(s1, m => q(pages.get(m.group(1)).fold(m.matched)(to => s"/w/$workspaceId/p/$to")))
    def walk(
      j: Json
    ): Json = j.asObject.fold(j) { o0 =>
      var o = o0
      o("marks").flatMap(_.asArray).foreach { ms =>
        val kept = ms.filterNot(_.hcursor.get[String]("type").toOption.contains("comment"))
        o = if (kept.isEmpty) o.remove("marks") else o.add("marks", Json.fromValues(kept))
      }
      val tpe = o("type").flatMap(_.asString)
      if (tpe.contains("pageLink") || tpe.contains("syncedRef")) {
        o("attrs").flatMap(_.asObject).foreach { a =>
          a("pageId").flatMap(_.asString).flatMap(pages.get).foreach { to =>
            o = o.add(
              "attrs",
              Json.fromJsonObject(a.add("pageId", Json.fromString(to)).add("workspaceId", Json.fromString(workspaceId)))
            )
          }
        }
      }
      o("content").flatMap(_.asArray).foreach(cs => o = o.add("content", Json.fromValues(cs.map(walk))))
      Json.fromJsonObject(o)
    }
    walk(parser.parse(s2).getOrElse(content))
  }

  /**
   * ページ（と子ページ）を複製する. ページ・添付ファイルはサーバーが作り、本文はここで付け替えてから入れる.
   * parentId: None=元と同じ階層、Some(None)=ワークスペースの直下
   */
  def duplicatePage(
    src: Page,
    workspaceId: String,
    parentId: Option[Option[String]],
    includeChildren: Boolean,
    title: String,
    onProgress: (Int, Int) => Unit = (
      _,
      _
    ) => ()
  ): Future[Duplicated] =
    ContentApi.duplicate(src.id, workspaceId, parentId, includeChildren, title).flatMap { r =>
      val pages = r.pages.map(x => x.from -> x.to).toMap
      val atts = r.attachments.map(x => x.from -> x.to).toMap
      val total = r.pages.size
      onProgress(0, total)
      // 1ページずつ順に入れる
      r.pages.zipWithIndex.foldLeft(Future.successful(0)) { case (acc, (p, i)) =>
        acc.flatMap { failed =>
          ContentApi.pageContent(p.from).flatMap {
            case Some(c) if c.hcursor.downField("content").focus.flatMap(_.asArray).exists(_.nonEmpty) =>
              Io.encodeJson(rewriteContent(c, pages, atts, r.workspaceId)).flatMap(ContentApi.importContent(
                p.to,
                _
              )).map(_ => failed)
            case _ => Future.successful(failed)
          }.recover { case e =>
            dom.console.warn("[duplicate] content copy failed", p.from, e.toString)
            failed + 1
          }.map { f =>
            onProgress(i + 1, total)
            f
          }
        }
      }.map(failed => Duplicated(r.rootId, r.workspaceId, failed))
    }

}
