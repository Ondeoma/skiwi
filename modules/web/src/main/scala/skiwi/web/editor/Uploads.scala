package skiwi.web.editor

import org.scalajs.dom
import skiwi.web.ApiError
import skiwi.web.content.{Attachment, Bytes, ContentApi}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.{Failure, Success}

/** エディタへのファイルの追加（貼り付け・ドロップ・「/画像」など）. アップロードして、本文に入れるノードを作る */
object Uploads {

  private val Unnamed = "(?i)^image\\.(png|jpe?g|gif|webp)$".r

  /** 貼り付けた画像に名前がない（スクリーンショットなど）ときは、日時から名前を付ける */
  def named(
    files: List[dom.File]
  ): List[dom.File] = {
    val stamp = new js.Date().toISOString().take(19).replaceAll("[-:]", "").replace('T', '-')
    files.zipWithIndex.map {
      (
        f,
        i
      ) =>
        if (f.name.nonEmpty && !Unnamed.matches(f.name)) f
        else {
          val ext = f.`type`.split('/').lift(1).filter(_.nonEmpty).getOrElse("png").replace("jpeg", "jpg")
          new dom.File(
            js.Array(f: dom.BlobPart),
            s"貼り付け-$stamp${if (files.size > 1) s"-${i + 1}" else ""}.$ext",
            new dom.FilePropertyBag { `type` = f.`type` }
          )
        }
    }
  }

  /** draw.io の図のファイル（図を埋め込んだ PNG・図の XML）なら、図の中の文字 */
  private def drawioText(
    f: dom.File
  ): Future[Option[String]] = {
    val xml: Future[Option[String]] =
      if (Drawio.isPng(f.name)) Bytes.read(f).flatMap(Drawio.xmlFromPng)
      else if (Drawio.isXml(f.name))
        f.asInstanceOf[js.Dynamic].text().asInstanceOf[js.Promise[String]].toFuture.map(Some(_))
      else Future.successful(None)
    xml.flatMap {
      case Some(x) if x.contains("<mx") => Drawio.labelsOf(x).map(Some(_))
      case _ => Future.successful(None)
    }.recover { case _ => None }
  }

  /** アップロードした添付を本文に入れる形に（画像は画像、それ以外はファイルのカード. draw.io の図は図のブロック） */
  def nodeOf(
    a: Attachment,
    drawio: Option[String]
  ): js.Any = drawio match {
    case Some(text) =>
      val title = "(?i)\\.(drawio|dio)(\\.png)?$".r.replaceFirstIn(a.filename, "")
      if (Drawio.isPng(a.filename))
        js.Dynamic.literal(`type` = "drawio", attrs = js.Dynamic.literal(src = a.url, title = title, text = text))
      else js.Dynamic.literal(`type` = "drawio", attrs = js.Dynamic.literal(xmlSrc = a.url, title = title, text = text))
    case None =>
      if (a.contentType.startsWith("image/") && a.contentType != "image/svg+xml")
        js.Dynamic.literal(`type` = "image", attrs = js.Dynamic.literal(src = a.url, alt = a.filename))
      else
        js.Dynamic.literal(
          `type` = "fileBlock",
          attrs = js.Dynamic.literal(
            id = a.id,
            url = a.url,
            filename = a.filename,
            size = a.size,
            contentType = a.contentType
          )
        )
  }

  private def message(
    e: Throwable
  ): String = e match {
    case a: ApiError => a.message
    case other => Bytes.message(other)
  }

  /**
   * 複数のファイルを同時にアップロードし、選んだ順のノードにする.
   * 戻り値: { nodes: 入れるノード, failed: 失敗したファイルの知らせ（すべて成功なら null） }
   */
  def uploadAll(
    pageId: String,
    files: List[dom.File]
  ): Future[js.Dynamic] = {
    val fs = named(files)
    Future.sequence(fs.map(drawioText)).flatMap { drawio =>
      Future.sequence(fs.map(f => ContentApi.uploadAttachment(pageId, f).transform(Success(_)))).map { results =>
        val nodes = results.zip(drawio).collect { case (Success(a), d) => nodeOf(a, d) }
        val failed = results.zip(fs).collect { case (Failure(e), f) => s"・${f.name}: ${message(e)}" }
        js.Dynamic.literal(
          nodes = js.Array(nodes*),
          failed = if (failed.isEmpty) null else s"アップロードに失敗したファイルがあります:\n${failed.mkString("\n")}"
        )
      }
    }
  }

  /** 添付の JS の形（エディタの uploadFile などに返す） */
  def toJs(
    a: Attachment
  ): js.Any =
    js.Dynamic.literal(id = a.id, url = a.url, filename = a.filename, contentType = a.contentType, size = a.size)

}
