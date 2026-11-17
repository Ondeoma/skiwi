package skiwi.web.content

import io.circe.*
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.web.{Api, JsModule}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

// 取り込み・書き出し・複製・エディタのアップロードが使う API

final case class Attachment(id: String, url: String, filename: String = "", contentType: String = "", size: Double = 0) derives Codec.AsObject

final case class IdPair(from: String, to: String) derives Codec.AsObject

final case class DuplicateResult(rootId: String, workspaceId: String, pages: List[IdPair], attachments: List[IdPair]) derives Codec.AsObject

object ContentApi {

  private def okOrNull[A](f: Future[A]): Future[Option[A]] = Api.okOrNone(f)

  /** ページの本文（ProseMirror JSON）. 読めなければ None */
  def pageContent(id: String): Future[Option[Json]] =
    okOrNull(Api.request(dom.HttpMethod.GET, s"/api/pages/$id/content", redirectOn401 = false))
      .map(_.flatten.flatMap(_.hcursor.downField("content").focus).filterNot(_.isNull))

  /** 空のページに本文を入れる（取り込み・複製）. state: io の encodeJson / encodeHtml の結果 */
  def importContent(id: String, state: Json): Future[Unit] =
    Api.request(dom.HttpMethod.POST, s"/api/pages/$id/import", Some(state), redirectOn401 = false).map(_ => ())

  def uploadAttachment(pageId: String, file: dom.File): Future[Attachment] = {
    val fd = new dom.FormData()
    fd.append("file", file, file.name)
    Api.request(dom.HttpMethod.POST, s"/api/pages/$pageId/attachments", Some(fd), redirectOn401 = false).map(Api.as[Attachment])
  }

  /** ページ（と子ページ）・添付ファイルをサーバーで複製する. parentId: None=元と同じ階層、Some(None)=ワークスペースの直下 */
  def duplicate(id: String, workspaceId: String, parentId: Option[Option[String]], includeChildren: Boolean, title: String): Future[DuplicateResult] = {
    val base = Json.obj("workspaceId" -> workspaceId.asJson, "includeChildren" -> includeChildren.asJson, "title" -> title.asJson)
    Api.post[DuplicateResult](s"/api/pages/$id/duplicate", parentId.fold(base)(p => base.deepMerge(Json.obj("parentId" -> p.asJson))))
  }

}

/** JS のままの部分（frontend/src/io/index.ts. JsModule の "io"） */
object Io {

  private def io: Future[js.Dynamic] = JsModule.load("io")

  private def toJson(v: js.Any): Json = parser.parse(js.JSON.stringify(v)).getOrElse(Json.Null)

  /** 本文（ProseMirror JSON）→ サーバーに保存する形（{ydoc, content, text}） */
  def encodeJson(doc: Json): Future[Json] = io.map(m => toJson(m.encodeJson(js.JSON.parse(doc.noSpaces))))

  /** HTML → サーバーに保存する形（{ydoc, content, text}） */
  def encodeHtml(html: String): Future[Json] = io.map(m => toJson(m.encodeHtml(html)))

  def markdownToHtml(text: String): Future[String] = io.map(_.markdownToHtml(text).asInstanceOf[String])

  def docxToHtml(data: Uint8Array): Future[String] = io.flatMap(_.docxToHtml(data).asInstanceOf[js.Promise[String]].toFuture)

  /** ZIP を開く（パス → 中身. ZIP の中の順） */
  def unzip(data: Uint8Array): Future[List[(String, Uint8Array)]] =
    io.map(m => js.Object.entries(m.unzip(data).asInstanceOf[js.Object]).toList.map(e => (e._1, e._2.asInstanceOf[Uint8Array])))

  def zip(files: Seq[(String, Uint8Array)]): Future[Uint8Array] =
    io.map(_.zip(js.Dictionary(files*)).asInstanceOf[Uint8Array])

}

/** バイト列・文字列の補助 */
private[web] object Bytes {

  def fromBuffer(b: js.Any): Uint8Array = new Uint8Array(b.asInstanceOf[js.typedarray.ArrayBuffer])

  def read(f: dom.Blob): Future[Uint8Array] =
    f.asInstanceOf[js.Dynamic].arrayBuffer().asInstanceOf[js.Promise[js.Any]].toFuture.map(fromBuffer)

  /** UTF-8 として読む（new TextDecoder().decode） */
  def utf8(data: Uint8Array): String = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)().decode(data).asInstanceOf[String]

  def utf8Bytes(s: String): Uint8Array = js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)().encode(s).asInstanceOf[Uint8Array]

  /** バイト列 → base64（大きな配列でも引数の上限を超えないように分けて変換する） */
  def toBase64(u: Uint8Array): String = {
    val sb = new StringBuilder
    var i = 0
    while (i < u.length) {
      val part = u.subarray(i, math.min(i + 0x8000, u.length))
      sb ++= js.Dynamic.global.String.fromCharCode.applyDynamic("apply")(null, part).asInstanceOf[String]
      i += 0x8000
    }
    dom.window.btoa(sb.result())
  }

  def fromBase64(b64: String): Uint8Array = {
    val s = dom.window.atob(b64)
    val u = new Uint8Array(s.length)
    (0 until s.length).foreach(i => u(i) = s.charAt(i).toShort)
    u
  }

  /** Blob → data: URL */
  def dataUrl(blob: dom.Blob): Future[String] = {
    val p = scala.concurrent.Promise[String]()
    val r = new dom.FileReader()
    r.onload = _ => p.success(r.result.toString)
    r.onerror = _ => p.failure(new Exception("ファイルを読めませんでした"))
    r.readAsDataURL(blob)
    p.future
  }

  def file(data: Uint8Array, name: String, mime: String): dom.File =
    new dom.File(js.Array(data.asInstanceOf[dom.BlobPart]), name, new dom.FilePropertyBag { `type` = mime })

  /** 失敗の理由（JS の Error なら message） */
  def message(e: Throwable): String = e match {
    case js.JavaScriptException(err: js.Error) => err.message
    case js.JavaScriptException(v) => js.Dynamic.global.String(v.asInstanceOf[js.Any]).asInstanceOf[String]
    case _ => e.getMessage
  }

}
