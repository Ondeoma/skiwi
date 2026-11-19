package skiwi.web.content

import io.circe.*
import skiwi.web.JsModule

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/** JS のままの部分（frontend/src/io/index.ts. JsModule の "io"） */
object Io {

  private def io: Future[js.Dynamic] = JsModule.load("io")

  private def toJson(
    v: js.Any
  ): Json = parser.parse(js.JSON.stringify(v)).getOrElse(Json.Null)

  /** 本文（ProseMirror JSON）→ サーバーに保存する形（{ydoc, content, text}） */
  def encodeJson(
    doc: Json
  ): Future[Json] = io.map(m => toJson(m.encodeJson(js.JSON.parse(doc.noSpaces))))

  /** HTML → サーバーに保存する形（{ydoc, content, text}） */
  def encodeHtml(
    html: String
  ): Future[Json] = io.map(m => toJson(m.encodeHtml(html)))

  def markdownToHtml(
    text: String
  ): Future[String] = io.map(_.markdownToHtml(text).asInstanceOf[String])

  def docxToHtml(
    data: Uint8Array
  ): Future[String] = io.flatMap(_.docxToHtml(data).asInstanceOf[js.Promise[String]].toFuture)

  /** ZIP を開く（パス → 中身. ZIP の中の順） */
  def unzip(
    data: Uint8Array
  ): Future[List[(String, Uint8Array)]] =
    io.map(m =>
      js.Object.entries(m.unzip(data).asInstanceOf[js.Object]).toList.map(e => (e._1, e._2.asInstanceOf[Uint8Array]))
    )

  def zip(
    files: Seq[(String, Uint8Array)]
  ): Future[Uint8Array] =
    io.map(_.zip(js.Dictionary(files*)).asInstanceOf[Uint8Array])

}
