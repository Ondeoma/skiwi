package skiwi.web.content

import skiwi.shared.Titles

import io.circe.*
import org.scalajs.dom
import skiwi.web.{Dialog, Progress, Store, Ui}
import skiwi.web.model.Page

import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/** 書き出し（Markdown のファイル・フォルダ構成の ZIP. 旧 ui/importer.ts の exportZip・editor/markdown.ts の download） */
object Export {

  /** ファイル名に使えない文字を _ にする */
  private def fileName(s: String): String = s.replaceAll("[\\\\/:*?\"<>|]", "_")

  /** ページを Markdown のファイルとして保存させる */
  def markdown(page: Page, content: Json): Unit =
    Ui.downloadText(
      fileName(s"${page.displayTitle}.md"),
      Markdown.toMarkdown(content, page.title),
      "text/markdown;charset=utf-8"
    )

  private def safeName(s: String): String = {
    val t = JsLike.trim((Titles.display(s)).replaceAll("[\\\\/:*?\"<>|\\u0000-\\u001f]", "_")).take(80)
    Titles.display(t)
  }

  private val ExtOf = Map(
    "image/png" -> ".png",
    "image/jpeg" -> ".jpg",
    "image/gif" -> ".gif",
    "image/webp" -> ".webp",
    "application/pdf" -> ".pdf"
  )

  /** from のファイルから見た to への相対パス */
  private def relative(from: String, to: String): String = {
    val a = Importer.dirOf(from).split("/", -1).filter(_.nonEmpty).toList
    val b = to.split("/", -1).toList
    var i = 0
    while (i < a.length && i < b.length - 1 && a(i) == b(i)) i += 1
    "../" * (a.length - i) + b.drop(i).mkString("/")
  }

  private val AttRe = "/api/attachments/([0-9a-f-]{36})".r
  private val AttPrefix = "^/api/attachments/([0-9a-f-]{36})".r
  private val PagePrefix = "^/w/[0-9a-f-]{36}/p/([0-9a-f-]{36})".r
  private val DispositionRe = "filename\\*=UTF-8''([^;]+)".r

  /** ホワイトボードの属性 snapshot（文字列なら JSON として読む） */
  private def snapshot(attrs: JsonObject): js.Dynamic =
    attrs("snapshot").filter(j => JsLike.truthy(Some(j))) match {
      case Some(j) => j.asString match {
          case Some(s) => try js.JSON.parse(s)
            catch { case _: Throwable => null }
          case None => js.JSON.parse(j.noSpaces)
        }
      case None => null
    }

  private def orElse(v: js.Dynamic, d: js.Any): js.Any = if (v == null || js.isUndefined(v)) d else v

  /**
   * ページと子ページを、フォルダ構成の Markdown として ZIP に書き出す.
   * 添付ファイル・画像は attachments/ に入れて相対リンクにし、ホワイトボードは .excalidraw ファイルとして入れる.
   * currentContent: 開いているページの最新の本文（保存の反映待ちを避けるため）
   */
  def zip(rootId: String, currentContent: Option[Json] = None): Future[Unit] = {
    val all = Store.pages.v
    all.find(_.id == rootId).fold(Future.successful(())) { root =>
      val children = Store.groupByParent(all, orphansToRoot = false)
      def kids(id: String) = children.getOrElse(Some(id), Nil)

      // ページ → ファイルパス（同じ階層の同名は (2) を付ける）
      val pathOf = mutable.LinkedHashMap.empty[String, String]
      def assign(p: Page, dir: String): Unit = {
        val used = pathOf.values.filter(x => Importer.dirOf(x) == dir).map(_.split("/", -1).last).toSet
        var base = safeName(p.title)
        var n = 2
        while (used(s"$base.md")) { base = s"${safeName(p.title)} ($n)"; n += 1 }
        pathOf(p.id) = if (dir.nonEmpty) s"$dir/$base.md" else s"$base.md"
        kids(p.id).foreach(c => assign(c, if (dir.nonEmpty) s"$dir/$base" else base))
      }
      assign(root, "")

      val files = mutable.LinkedHashMap.empty[String, Uint8Array]
      val attachments = mutable.Map.empty[String, String] // 添付ID → zip内パス
      val ids = pathOf.keys.toList
      Store.progress := Some(Progress("ZIPを作成中", 0, ids.size))

      def fetchAttachment(aid: String): Future[Unit] =
        if (attachments.contains(aid)) Future.successful(())
        else {
          val init = new dom.RequestInit {}
          init.credentials = dom.RequestCredentials.`same-origin`
          dom.fetch(s"/api/attachments/$aid", init).toFuture.flatMap { r =>
            if (!r.ok) Future.successful(())
            else r.blob().toFuture.flatMap { blob =>
              val disp = Option(r.headers.get("Content-Disposition")).getOrElse("")
              val fromHeader =
                DispositionRe.findFirstMatchIn(disp).fold("")(m => js.URIUtils.decodeURIComponent(m.group(1)))
              val name = if (fromHeader.nonEmpty) fromHeader else aid + ExtOf.getOrElse(blob.`type`, "")
              val p = s"attachments/${aid.take(8)}-${safeName(name)}"
              Bytes.read(blob).map { data =>
                files(p) = data
                attachments(aid) = p
              }
            }
          }
        }

      def onePage(id: String): Future[Unit] = {
        val page = all.find(_.id == id).get
        val path = pathOf(id)
        val up = "../" * (path.split("/", -1).length - 1)
        val contentF =
          if (id == rootId && currentContent.exists(c => !c.isNull)) Future.successful(currentContent)
          else ContentApi.pageContent(id)
        contentF.flatMap { content =>
          // 添付ファイルを集める（先に取得しておき、変換時は同期的に相対パスへ置き換える）
          val urls = AttRe.findAllMatchIn(content.getOrElse(Json.obj()).noSpaces).map(_.group(1)).toList.distinct
          Importer.sequentially(urls)(fetchAttachment).map { _ =>
            var boards = 0
            val md = Markdown.toMarkdown(
              content.getOrElse(Json.Null),
              page.title,
              url = Some { u =>
                AttPrefix.findFirstMatchIn(u).flatMap(m => attachments.get(m.group(1))).map(a =>
                  js.URIUtils.encodeURI(up + a)
                )
                  .orElse(PagePrefix.findFirstMatchIn(u).flatMap(m => pathOf.get(m.group(1))).map(to =>
                    js.URIUtils.encodeURI(relative(path, to))
                  ))
                  .getOrElse(if (u.startsWith("/")) dom.window.location.origin + u else u)
              },
              whiteboard = Some { attrs =>
                val snap = snapshot(attrs)
                boards += 1
                val name = s"${path.stripSuffix(".md")}.whiteboard-$boards.excalidraw"
                val scene = js.Dynamic.literal(
                  `type` = "excalidraw",
                  version = 2,
                  source = dom.window.location.origin,
                  elements = if (snap == null) js.Array() else orElse(snap.elements, js.Array()),
                  appState = js.Dynamic.literal(viewBackgroundColor =
                    if (snap == null) "#ffffff" else orElse(snap.background, "#ffffff")
                  ),
                  files = js.Dynamic.literal()
                )
                files(name) = Bytes.utf8Bytes(js.JSON.stringify(scene, null.asInstanceOf[js.Array[js.Any]], 2))
                s"[🧩 ホワイトボード（Excalidraw で開けます）](${js.URIUtils.encodeURI(name.split("/", -1).last)})"
              }
            )
            files(path) = Bytes.utf8Bytes(md)
            Importer.stepProgress()
          }
        }
      }

      Importer.sequentially(ids)(onePage)
        .flatMap(_ => Io.zip(files.toList))
        .map(z =>
          Ui.download(
            s"${safeName(root.title)}.zip",
            new dom.Blob(js.Array(z.asInstanceOf[dom.BlobPart]), new dom.BlobPropertyBag { `type` = "application/zip" })
          )
        )
        .recoverWith { case e => Dialog.alert(s"書き出せませんでした: ${Bytes.message(e)}") }
        .andThen(_ => Store.progress := None)
    }
  }

}
