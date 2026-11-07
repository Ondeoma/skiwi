package skiwi.api

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

import java.io.{FilterInputStream, InputStream}
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, Path}
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import javax.xml.stream.{XMLInputFactory, XMLStreamConstants, XMLStreamReader}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * 添付ファイルから検索用の文字を取り出す。
 * PDF（PDFBox）、Word・Excel・PowerPoint（.docx / .xlsx / .pptx。ZIP の中の XML を読む）、
 * OpenDocument（.odt / .ods / .odp）、テキスト（.txt・.md・.csv・.html など。UTF-8 か Shift_JIS）。
 * 画像だけの PDF（スキャン）や、古い形式（.doc / .xls / .ppt）は読めない。
 */
object TextExtract:
  /** 取り出す文字数の上限（これを超えた分は検索の対象にしない） */
  val MaxChars = 1_000_000
  /** ZIP の中身を展開して読む量の上限（圧縮爆弾の対策） */
  private val MaxUnzipBytes = 200L * 1024 * 1024
  private val MaxPdfPages = 2000

  enum Kind:
    case Pdf, Docx, Xlsx, Pptx, Odf, Text, Html

  private val textExt = Set("txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "log", "ini", "conf", "sql",
    "py", "js", "ts", "java", "scala", "kt", "go", "rb", "sh", "c", "h", "cpp", "cs", "rs", "php", "css", "tex", "rst", "adoc")

  def kindOf(filename: String, contentType: String): Option[Kind] =
    val ext = filename.toLowerCase.split('.').lastOption.filter(_ => filename.contains('.')).getOrElse("")
    val ct  = contentType.toLowerCase
    if ext == "pdf" || ct == "application/pdf" then Some(Kind.Pdf)
    else if ext == "docx" || ext == "docm" || ct.contains("wordprocessingml") then Some(Kind.Docx)
    else if ext == "xlsx" || ext == "xlsm" || ct.contains("spreadsheetml") then Some(Kind.Xlsx)
    else if ext == "pptx" || ext == "pptm" || ct.contains("presentationml") then Some(Kind.Pptx)
    else if Set("odt", "ods", "odp")(ext) || ct.startsWith("application/vnd.oasis.opendocument") then Some(Kind.Odf)
    else if ext == "html" || ext == "htm" || ct == "text/html" then Some(Kind.Html)
    else if textExt(ext) || ct.startsWith("text/") then Some(Kind.Text)
    else None

  /** 文字を取り出す。読めない形式なら None（呼び出し側で kindOf を確かめておく）。失敗したら例外。 */
  def extract(file: Path, kind: Kind): String =
    val raw = kind match
      case Kind.Pdf  => pdf(file)
      case Kind.Docx => zipXml(file, n => n == "word/document.xml" || n.matches("word/(header|footer)\\d*\\.xml") || n == "word/footnotes.xml" || n == "word/endnotes.xml", ooxmlText)
      case Kind.Pptx => zipXml(file, n => n.matches("ppt/(slides/slide|notesSlides/notesSlide)\\d+\\.xml"), ooxmlText)
      case Kind.Xlsx => xlsx(file)
      case Kind.Odf  => zipXml(file, n => n == "content.xml", odfText)
      case Kind.Html => stripHtml(decode(readLimited(file)))
      case Kind.Text => decode(readLimited(file))
    clean(raw)

  /** 空白をまとめ、検索に使えない文字（NUL など）を除き、上限で切る */
  def clean(s: String): String =
    val noCtl = s.map(c => if c == '\n' || c == '\t' then c else if Character.isISOControl(c) || c == '�' then ' ' else c)
    val lines = noCtl.split('\n').iterator.map(_.replaceAll("[ \\t\\u3000]+", " ").trim).filter(_.nonEmpty)
    val out = lines.mkString("\n")
    if out.length > MaxChars then out.take(MaxChars) else out

  // ------------------------------------------------------------ PDF

  private def pdf(file: Path): String =
    Using.resource(Loader.loadPDF(file.toFile)) { doc =>
      val st = new PDFTextStripper()
      st.setSortByPosition(true)
      st.setEndPage(math.min(doc.getNumberOfPages, MaxPdfPages))
      st.getText(doc)
    }

  // ------------------------------------------------------------ テキスト

  private def readLimited(file: Path): Array[Byte] =
    Using.resource(Files.newInputStream(file))(in => in.readNBytes(MaxChars * 4))

  /** UTF-8 として正しければ UTF-8、そうでなければ Shift_JIS（Windows-31J）として読む */
  def decode(bytes: Array[Byte]): String =
    val b = if bytes.length >= 3 && bytes(0) == 0xEF.toByte && bytes(1) == 0xBB.toByte && bytes(2) == 0xBF.toByte then bytes.drop(3) else bytes
    try
      StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(b)).toString
    catch
      case _: CharacterCodingException =>
        // 末尾で文字が途切れただけ（上限で切った）なら UTF-8 のまま読む
        val lenient = new String(b, StandardCharsets.UTF_8)
        if lenient.count(_ == '�') <= 2 then lenient else new String(b, java.nio.charset.Charset.forName("windows-31j"))

  def stripHtml(html: String): String =
    Html.unescape(
      html
        .replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ")
        .replaceAll("(?i)<(br|/p|/div|/li|/tr|/h[1-6])\\b[^>]*>", "\n")
        .replaceAll("(?s)<[^>]*>", " ")
    )


  // ------------------------------------------------------------ ZIP の中の XML（Office・OpenDocument）

  private val xmlFactory =
    val f = XMLInputFactory.newInstance()
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false)
    f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
    f

  /** 読んだ量を数え、上限を超えたら止める */
  private final class Budget(var left: Long)
  private final class Limited(in: InputStream, budget: Budget) extends FilterInputStream(in):
    private def take(n: Int): Int =
      if n > 0 then
        budget.left -= n
        if budget.left < 0 then throw new IllegalStateException("archive too large when uncompressed")
      n
    override def read(): Int = { val r = super.read(); if r >= 0 then take(1); r }
    override def read(b: Array[Byte], off: Int, len: Int): Int = take(super.read(b, off, len))

  /** 名前の数字の順（slide2 → slide10）に並べる */
  private def natural(names: List[String]): List[String] =
    names.sortBy(n => (n.replaceAll("\\d+", ""), "\\d+".r.findAllIn(n).map(_.toLong).toList.lastOption.getOrElse(0L)))

  private def zipXml(file: Path, pick: String => Boolean, read: XMLStreamReader => String): String =
    Using.resource(new ZipFile(file.toFile)) { zip =>
      val budget = new Budget(MaxUnzipBytes)
      val names  = natural(zip.entries().asScala.map(_.getName).filter(pick).toList)
      val sb = new StringBuilder
      for n <- names if sb.length < MaxChars do
        Using.resource(new Limited(zip.getInputStream(zip.getEntry(n)), budget)) { in =>
          val r = xmlFactory.createXMLStreamReader(in)
          try sb.append(read(r)).append('\n') finally r.close()
        }
      sb.toString
    }

  /** Office Open XML: <w:t>・<a:t> の文字。段落（p）の終わりで改行、tab・br も反映 */
  private def ooxmlText(r: XMLStreamReader): String =
    val sb = new StringBuilder
    var inText = 0
    while r.hasNext && sb.length < MaxChars do
      r.next() match
        case XMLStreamConstants.START_ELEMENT =>
          r.getLocalName match
            case "t"   => inText += 1
            case "tab" => sb.append('\t')
            case "br" | "cr" => sb.append('\n')
            case _ =>
        case XMLStreamConstants.END_ELEMENT =>
          r.getLocalName match
            case "t" => inText -= 1
            case "p" => sb.append('\n')
            case _ =>
        case XMLStreamConstants.CHARACTERS | XMLStreamConstants.CDATA if inText > 0 => sb.append(r.getText)
        case _ =>
    sb.toString

  /** OpenDocument: 本文（office:body）の文字すべて。段落・見出しの終わりで改行 */
  private def odfText(r: XMLStreamReader): String =
    val sb = new StringBuilder
    var inBody = false
    while r.hasNext && sb.length < MaxChars do
      r.next() match
        case XMLStreamConstants.START_ELEMENT =>
          r.getLocalName match
            case "body" => inBody = true
            case "tab"  => sb.append('\t')
            case "s"    => sb.append(' ')
            case "line-break" => sb.append('\n')
            case _ =>
        case XMLStreamConstants.END_ELEMENT =>
          r.getLocalName match
            case "p" | "h" | "table-cell" => sb.append('\n')
            case _ =>
        case XMLStreamConstants.CHARACTERS | XMLStreamConstants.CDATA if inBody => sb.append(r.getText)
        case _ =>
    sb.toString

  /** Excel: シート名と、各セルの値（共有文字列・直接の文字・数値） */
  private def xlsx(file: Path): String =
    Using.resource(new ZipFile(file.toFile)) { zip =>
      val budget = new Budget(MaxUnzipBytes)
      def withXml[A](name: String)(f: XMLStreamReader => A): Option[A] =
        Option(zip.getEntry(name)).map { e =>
          Using.resource(new Limited(zip.getInputStream(e), budget)) { in =>
            val r = xmlFactory.createXMLStreamReader(in)
            try f(r) finally r.close()
          }
        }
      // 共有文字列（<si> ごとに1つ。中の <t> をつなげる）
      val shared = withXml("xl/sharedStrings.xml") { r =>
        val out = scala.collection.mutable.ArrayBuffer.empty[String]
        val cur = new StringBuilder
        var inT = false
        while r.hasNext do
          r.next() match
            case XMLStreamConstants.START_ELEMENT if r.getLocalName == "si" => cur.clear()
            case XMLStreamConstants.START_ELEMENT if r.getLocalName == "t"  => inT = true
            case XMLStreamConstants.END_ELEMENT if r.getLocalName == "t"    => inT = false
            case XMLStreamConstants.END_ELEMENT if r.getLocalName == "si"   => out += cur.toString
            case XMLStreamConstants.CHARACTERS | XMLStreamConstants.CDATA if inT => cur.append(r.getText)
            case _ =>
        out.toVector
      }.getOrElse(Vector.empty)
      val sheetNames = withXml("xl/workbook.xml") { r =>
        val out = List.newBuilder[String]
        while r.hasNext do
          if r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName == "sheet" then
            Option(r.getAttributeValue(null, "name")).foreach(out += _)
        out.result()
      }.getOrElse(Nil)
      val sb = new StringBuilder
      sheetNames.foreach(n => sb.append(n).append('\n'))
      val sheets = natural(zip.entries().asScala.map(_.getName).filter(_.matches("xl/worksheets/sheet\\d+\\.xml")).toList)
      for s <- sheets if sb.length < MaxChars do
        withXml(s) { r =>
          var tpe: String = null
          var inV, inT = false
          val cell = new StringBuilder
          while r.hasNext && sb.length < MaxChars do
            r.next() match
              case XMLStreamConstants.START_ELEMENT =>
                r.getLocalName match
                  case "c"  => tpe = r.getAttributeValue(null, "t"); cell.clear()
                  case "v"  => inV = true
                  case "t"  => inT = true
                  case "f"  => () // 数式そのものは使わない（計算した値 <v> を使う）
                  case _ =>
              case XMLStreamConstants.END_ELEMENT =>
                r.getLocalName match
                  case "v" => inV = false
                  case "t" => inT = false
                  case "c" =>
                    val v = cell.toString.trim
                    val text =
                      if tpe == "s" then v.toIntOption.flatMap(shared.lift).getOrElse("")
                      else if tpe == "b" then "" // TRUE/FALSE は検索に役立たない
                      else v
                    if text.nonEmpty then sb.append(text).append('\t')
                  case "row" => sb.append('\n')
                  case _ =>
              case XMLStreamConstants.CHARACTERS | XMLStreamConstants.CDATA if inV || inT => cell.append(r.getText)
              case _ =>
        }
      sb.toString
    }
