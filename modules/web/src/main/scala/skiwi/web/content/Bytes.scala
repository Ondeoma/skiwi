package skiwi.web.content

import org.scalajs.dom

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/** バイト列・文字列の補助 */
private[web] object Bytes {

  def fromBuffer(
    b: js.Any
  ): Uint8Array = new Uint8Array(b.asInstanceOf[js.typedarray.ArrayBuffer])

  def read(
    f: dom.Blob
  ): Future[Uint8Array] =
    f.asInstanceOf[js.Dynamic].arrayBuffer().asInstanceOf[js.Promise[js.Any]].toFuture.map(fromBuffer)

  /** UTF-8 として読む（new TextDecoder().decode） */
  def utf8(
    data: Uint8Array
  ): String = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)().decode(data).asInstanceOf[String]

  def utf8Bytes(
    s: String
  ): Uint8Array = js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)().encode(s).asInstanceOf[Uint8Array]

  /** バイト列 → base64（大きな配列でも引数の上限を超えないように分けて変換する） */
  def toBase64(
    u: Uint8Array
  ): String = {
    val sb = new StringBuilder
    var i = 0
    while (i < u.length) {
      val part = u.subarray(i, math.min(i + 0x8000, u.length))
      sb ++= js.Dynamic.global.String.fromCharCode.applyDynamic("apply")(null, part).asInstanceOf[String]
      i += 0x8000
    }
    dom.window.btoa(sb.result())
  }

  def fromBase64(
    b64: String
  ): Uint8Array = {
    val s = dom.window.atob(b64)
    val u = new Uint8Array(s.length)
    (0 until s.length).foreach(i => u(i) = s.charAt(i).toShort)
    u
  }

  /** Blob → data: URL */
  def dataUrl(
    blob: dom.Blob
  ): Future[String] = {
    val p = scala.concurrent.Promise[String]()
    val r = new dom.FileReader()
    r.onload = _ => p.success(r.result.toString)
    r.onerror = _ => p.failure(new Exception("ファイルを読めませんでした"))
    r.readAsDataURL(blob)
    p.future
  }

  def file(
    data: Uint8Array,
    name: String,
    mime: String
  ): dom.File =
    new dom.File(js.Array(data.asInstanceOf[dom.BlobPart]), name, new dom.FilePropertyBag { `type` = mime })

  /** 失敗の理由（JS の Error なら message） */
  def message(
    e: Throwable
  ): String = e match {
    case js.JavaScriptException(err: js.Error) => err.message
    case js.JavaScriptException(v) => js.Dynamic.global.String(v.asInstanceOf[js.Any]).asInstanceOf[String]
    case _ => e.getMessage
  }

}
