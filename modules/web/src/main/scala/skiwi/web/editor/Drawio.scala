package skiwi.web.editor

import org.scalajs.dom
import skiwi.web.Api
import skiwi.web.content.Bytes

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.typedarray.{DataView, Uint8Array}
import scala.util.Try

/**
 * draw.io（diagrams.net）の図のための補助: エディタの URL、図の文字（検索用）、PNG に埋め込まれた図の XML.
 * エディタ（editor/DrawioView）・貼り付け・取り込み（content.Importer）で使う.
 */
object Drawio {

  private val DefaultUrl = "https://embed.diagrams.net"

  private lazy val configured: Future[String] =
    dom.fetch("/api/client-config", Api.init(dom.HttpMethod.GET)).toFuture
      .flatMap(r =>
        if (r.ok) r.json().toFuture.map(j =>
          j.asInstanceOf[js.Dynamic].drawioUrl.asInstanceOf[js.UndefOr[String]].getOrElse(DefaultUrl)
        )
        else Future.successful(DefaultUrl)
      )
      .recover { case _ => DefaultUrl }

  /** サーバーの設定（DRAWIO_URL）. 空ならエディタを使えない */
  def url(): Future[String] = configured

  /** 埋め込みモードのエディタの URL（日本語・「保存して終了」ボタン付き） */
  def editorSrc(base: String): String = {
    val u = new dom.URL(base, dom.window.location.origin)
    List(
      "embed" -> "1",
      "proto" -> "json",
      "spin" -> "1",
      "lang" -> "ja",
      "saveAndExit" -> "1",
      "noSaveBtn" -> "1",
      "noExitBtn" -> "0",
      "libraries" -> "1"
    )
      .foreach((k, v) => u.searchParams.set(k, v))
    u.toString
  }

  /** 圧縮されたデータ（deflate・deflate-raw）を文字列に戻す（ブラウザの DecompressionStream） */
  private def decompress(data: Uint8Array, format: String): Future[String] = {
    val stream = new dom.Blob(js.Array(data)).asInstanceOf[js.Dynamic].stream()
      .pipeThrough(js.Dynamic.newInstance(js.Dynamic.global.DecompressionStream)(format))
    js.Dynamic.newInstance(js.Dynamic.global.Response)(stream).text().asInstanceOf[js.Promise[String]].toFuture
  }

  /** 圧縮された図（<diagram> の中が base64 + deflate）を XML に戻す */
  private def inflate(b64: String): Future[Option[String]] =
    Try(Bytes.fromBase64(b64.trim)).fold(
      _ => Future.successful(None),
      bin => decompress(bin, "deflate-raw").map(t => Some(js.URIUtils.decodeURIComponent(t))).recover { case _ => None }
    )

  private def parseXml(s: String): Option[dom.Document] = {
    val d = new dom.DOMParser().parseFromString(s, dom.MIMEType.`application/xml`)
    if (d.querySelector("parsererror") != null) None else Some(d)
  }

  private def stripTags(s: String): String = {
    val d = new dom.DOMParser().parseFromString(
      s"<body>${s.replaceAll("(?i)<br\\s*/?>", " ")}</body>",
      dom.MIMEType.`text/html`
    )
    Option(d.asInstanceOf[dom.HTMLDocument].body.textContent).getOrElse("").replaceAll("\\s+", " ").trim
  }

  private val DefaultPageName = "^(Page|ページ)-\\d+$".r

  /** 図の中の文字（図形・矢印のラベル、ページ名）. ページの検索・本文の文字に使う */
  def labelsOf(xml: String): Future[String] =
    Option(xml).filter(_.nonEmpty).flatMap(parseXml) match {
      case None => Future.successful("")
      case Some(doc) =>
        val diagrams = doc.getElementsByTagName("diagram").toList
        val names =
          diagrams.flatMap(d => Option(d.getAttribute("name"))).filter(n => n.nonEmpty && !DefaultPageName.matches(n))
        // 圧縮されていない図は doc にそのまま入っている
        val inner = diagrams.filter(_.getElementsByTagName("mxGraphModel").length == 0)
          .map(d => inflate(Option(d.textContent).getOrElse("")).map(_.flatMap(parseXml)))
        Future.sequence(inner).map { models =>
          val labels = (doc :: models.flatten).flatMap { m =>
            m.querySelectorAll("mxCell[value], object[label], UserObject[label]").toList.map { el =>
              val e = el.asInstanceOf[dom.Element]
              stripTags(Option(e.getAttribute("value")).orElse(Option(e.getAttribute("label"))).getOrElse(""))
            }.filter(_.nonEmpty)
          }
          (names ++ labels).distinct.mkString("\n").take(20000)
        }
    }

  /** 図のページ名（既定の「ページ-1」以外）. 図を保存するときの題名にする */
  def titleOf(xml: String): Option[String] =
    "<diagram[^>]*\\bname=\"([^\"]*)\"".r.findFirstMatchIn(xml).map(_.group(1).replace(
      "&amp;",
      "&"
    ).replace("&quot;", "\"").trim)
      .filter(n => n.nonEmpty && !DefaultPageName.matches(n))

  def dataUrlToFile(dataUrl: String, name: String): dom.File = {
    val (head, b64) = dataUrl.split(",", 2) match {
      case Array(h, b) => (h, b)
      case Array(h) => (h, "")
    }
    val tpe = "data:([^;]+)".r.findFirstMatchIn(head).map(_.group(1)).getOrElse("image/png")
    Bytes.file(Bytes.fromBase64(b64), name, tpe)
  }

  /** 添付ファイルを data: URL にする（draw.io のエディタに PNG を渡す） */
  def urlToDataUrl(url: String): Future[String] =
    SafeUrl.fetchAttachment(url).flatMap(_.blob().toFuture).flatMap(Bytes.dataUrl)

  /** 添付ファイルの名前（「図.drawio.png」の形） */
  def fileName(title: Option[String]): String = {
    val t = title.filter(_.nonEmpty).getOrElse("図").replaceAll("[\\\\/:*?\"<>|\\n\\r]+", " ").trim.take(80)
    s"${if (t.isEmpty) "図" else t}.drawio.png"
  }

  /** draw.io の図のファイルか（.drawio.png は図の XML を埋め込んだ PNG、.drawio / .dio は XML） */
  def isPng(name: String): Boolean = "(?i).*\\.(drawio|dio)\\.png$".r.matches(name)
  def isXml(name: String): Boolean = "(?i).*\\.(drawio|dio)$".r.matches(name)

  /** PNG に埋め込まれた図の XML（tEXt / zTXt / iTXt の "mxfile"）. なければ・読めなければ None */
  def xmlFromPng(b: Uint8Array): Future[Option[String]] = {
    val dv = new DataView(b.buffer, b.byteOffset, b.byteLength)
    def latin1(a: Uint8Array): String = { val sb = new StringBuilder; a.foreach(c => sb += c.toChar); sb.result() }
    def indexOf(a: Uint8Array, v: Short, from: Int): Int = a.asInstanceOf[js.Dynamic].indexOf(v, from).asInstanceOf[Int]
    def decoded(raw: String): String = Try(js.URIUtils.decodeURIComponent(raw)).getOrElse(raw)
    if (b.length < 8 || b(1) != 0x50 || b(2) != 0x4e || b(3) != 0x47) Future.successful(None)
    else {
      var i = 8
      var found: Option[Future[String]] = None
      var end = false
      while (!end && found.isEmpty && i + 8 <= b.length) {
        val len = dv.getUint32(i).toInt
        val tpe = latin1(b.subarray(i + 4, i + 8))
        val data = b.subarray(i + 8, i + 8 + len)
        if (tpe == "tEXt" || tpe == "zTXt" || tpe == "iTXt") {
          val z = indexOf(data, 0, 0)
          val key = latin1(data.subarray(0, z))
          if (key == "mxfile" || key == "mxGraphModel") found = Some(tpe match {
            case "tEXt" => Future.successful(latin1(data.subarray(z + 1, data.length)))
            case "zTXt" => decompress(data.subarray(z + 2, data.length), "deflate")
            case _ =>
              // iTXt: key\0 圧縮フラグ 圧縮方式 言語\0 訳語\0 本文
              val comp = data(z + 1)
              var p = z + 3
              p = indexOf(data, 0, p) + 1
              p = indexOf(data, 0, p) + 1
              val body = data.subarray(p, data.length)
              if (comp != 0) decompress(body, "deflate")
              else Future.successful(
                js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)().decode(body).asInstanceOf[String]
              )
          })
        }
        if (tpe == "IEND") end = true
        i += 12 + len
      }
      found.fold(Future.successful(Option.empty[String]))(_.map(r => Some(decoded(r))))
    }
  }.recover { case _ => None }

}
