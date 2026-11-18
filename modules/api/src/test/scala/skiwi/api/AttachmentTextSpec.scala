package skiwi.api

import cats.effect.*
import cats.effect.std.Semaphore
import cats.syntax.semigroupk.*
import fs2.Stream
import io.circe.Json
import org.apache.pdfbox.pdmodel.{PDDocument, PDPage, PDPageContentStream}
import org.apache.pdfbox.pdmodel.font.{PDType1Font, Standard14Fonts}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.`Content-Type`
import org.http4s.multipart.{Multiparts, Part}
import skiwi.auth.Crypto
import skiwi.core.*
import skiwi.db.*
import skiwi.storage.InMemoryBlobStore

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.zip.{ZipEntry, ZipOutputStream}

/** 添付ファイルの文字の取り出し（PDF・Office・テキスト）と、添付ファイルの中身での検索。検索のテストは TEST_DATABASE_URL が必要。 */
class AttachmentTextSpec extends DbSuite:

  // ------------------------------------------------------------ ファイルを作る

  private def zip(entries: (String, String)*): Array[Byte] =
    val bo = new ByteArrayOutputStream()
    val z  = new ZipOutputStream(bo)
    for (n, body) <- entries do
      z.putNextEntry(new ZipEntry(n))
      z.write(body.getBytes(StandardCharsets.UTF_8))
      z.closeEntry()
    z.close()
    bo.toByteArray

  private val W = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main""""
  private val A = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main""""
  private val S = """xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main""""

  private def docx = zip(
    "[Content_Types].xml" -> "<Types/>",
    "word/document.xml" -> s"""<w:document $W><w:body><w:p><w:r><w:t>障害対応</w:t></w:r><w:r><w:t xml:space="preserve">の手順</w:t></w:r></w:p><w:p><w:r><w:t>二行目</w:t><w:tab/><w:t>タブ</w:t></w:r></w:p></w:body></w:document>""",
    "word/footer1.xml" -> s"""<w:ftr $W><w:p><w:r><w:t>社外秘</w:t></w:r></w:p></w:ftr>""",
  )
  private def xlsx = zip(
    "xl/workbook.xml" -> s"""<workbook $S><sheets><sheet name="売上" sheetId="1"/></sheets></workbook>""",
    "xl/sharedStrings.xml" -> s"""<sst $S><si><t>東京支店</t></si><si><r><t>大阪</t></r><r><t>支店</t></r></si></sst>""",
    "xl/worksheets/sheet1.xml" -> s"""<worksheet $S><sheetData><row><c r="A1" t="s"><v>0</v></c><c r="B1"><v>12345</v></c></row><row><c r="A2" t="s"><v>1</v></c><c r="B2" t="inlineStr"><is><t>直接の文字</t></is></c><c r="C2"><f>B1*2</f><v>24690</v></c></row></sheetData></worksheet>""",
  )
  private def pptx = zip(
    "ppt/slides/slide2.xml" -> s"""<p:sld xmlns:p="x" $A><a:p><a:r><a:t>二枚目</a:t></a:r></a:p></p:sld>""",
    "ppt/slides/slide10.xml" -> s"""<p:sld xmlns:p="x" $A><a:p><a:r><a:t>十枚目</a:t></a:r></a:p></p:sld>""",
    "ppt/slides/slide1.xml" -> s"""<p:sld xmlns:p="x" $A><a:p><a:r><a:t>一枚目の題名</a:t></a:r></a:p></p:sld>""",
  )
  private def pdf(text: String): Array[Byte] =
    val doc = new PDDocument()
    try
      val page = new PDPage()
      doc.addPage(page)
      val cs = new PDPageContentStream(doc, page)
      cs.beginText()
      cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12)
      cs.newLineAtOffset(72, 700)
      cs.showText(text)
      cs.endText()
      cs.close()
      val bo = new ByteArrayOutputStream()
      doc.save(bo)
      bo.toByteArray
    finally doc.close()

  private def extract(name: String, ctype: String, bytes: Array[Byte]): String =
    val f = Files.createTempFile("t", ".bin")
    try
      Files.write(f, bytes)
      TextExtract.extract(f, TextExtract.kindOf(name, ctype).get)
    finally Files.delete(f)

  test("Word: paragraphs, tabs and footers") {
    val t = extract("手順.docx", "application/octet-stream", docx)
    assertEquals(t, "障害対応の手順\n二行目 タブ\n社外秘")
  }

  test("Excel: sheet names, shared and inline strings, numbers but not formulas") {
    val t = extract("売上.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx)
    assert(t.contains("売上"), t)
    assert(t.contains("東京支店") && t.contains("大阪支店") && t.contains("直接の文字") && t.contains("12345") && t.contains("24690"), t)
    assert(!t.contains("B1*2"), t)
  }

  test("PowerPoint: slides in numeric order") {
    assertEquals(extract("説明.pptx", "", pptx), "一枚目の題名\n二枚目\n十枚目")
  }

  test("PDF, text in Shift_JIS, HTML, and unsupported kinds") {
    assert(extract("a.pdf", "application/pdf", pdf("Incident response runbook")).contains("Incident response runbook"))
    assertEquals(extract("メモ.txt", "text/plain", "議事録です".getBytes("windows-31j")), "議事録です")
    assertEquals(extract("a.html", "text/html", "<html><head><style>x{}</style></head><body><p>本文&amp;説明</p><script>alert(1)</script></body></html>".getBytes("UTF-8")), "本文&説明")
    assertEquals(TextExtract.kindOf("写真.jpg", "image/jpeg"), None)
    assertEquals(TextExtract.kindOf("古い.doc", "application/msword"), None)
  }

  test("broken files raise errors rather than returning garbage") {
    intercept[Exception](extract("壊れた.docx", "", "not a zip".getBytes))
    intercept[Exception](extract("壊れた.pdf", "application/pdf", "%PDF-1.4 garbage".getBytes))
  }

  // ------------------------------------------------------------ 検索（DB が必要）

  private val apiCfg = ApiConfig(adminGroups = Set.empty, cookieSecure = false)

  final case class Env(store: Store, docs: DocStore, attachments: AttachmentStore, indexer: AttachmentIndexer, app: HttpApp[IO])

  private val res: Resource[IO, Env] = cfg match
    case None => Resource.eval(IO.raiseError[Env](new IllegalStateException("TEST_DATABASE_URL not set")))
    case Some(c) =>
      for
        _  <- Resource.eval(Database.migrate(c))
        xa <- Database.transactor(c)
        sem <- Resource.eval(Semaphore[IO](2))
      yield
        val store = new Store(xa)
        val sess  = new SessionAuth(store, new OpsStore(xa), apiCfg)
        val blobs = new InMemoryBlobStore
        val att   = new AttachmentStore(xa)
        // アップロードの直後の取り出しは使わず、テストの中で index を呼ぶ
        val indexer = new AttachmentIndexer(att, Some(blobs), sem, maxBytes = 200 * 1024)
        val app = (new SearchRoutes(store, new SearchStore(xa), sess).routes <+>
          new AttachmentRoutes(store, att, Some(blobs), sess, maxBytes = 1024 * 1024).routes).orNotFound
        Env(store, new DocStore(xa), att, indexer, app)

  private val fixture = ResourceSuiteLocalFixture("attachment-text", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil
  private def env = fixture()

  private def user(name: String): IO[(AuthSession, String)] =
    val token = Crypto.randomToken()
    for
      u <- env.store.upsertUser(s"$name-${UUID.randomUUID()}", None, name)
      _ <- env.store.createSession(Crypto.sha256(token), u.id, Instant.now().plusSeconds(600))
    yield (AuthSession(u, Nil, false), token)

  private def upload(token: String, pageId: UUID, name: String, ctype: String, body: Array[Byte]): IO[UUID] =
    Multiparts.forSync[IO].flatMap { mps =>
      val part = Part.fileData[IO]("file", name, Entity.stream(Stream.emits(body).covary[IO]), `Content-Type`(MediaType.unsafeParse(ctype)))
      mps.multipart(Vector(part)).flatMap { mp =>
        val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/pages/$pageId/attachments"))
          .withEntity(mp).putHeaders(mp.headers).addCookie("skiwi_session", token)
        env.app.run(req).flatMap(_.as[Json]).map(_.hcursor.get[UUID]("id").toOption.get)
      }
    }

  /** 本文を保存する（添付ファイルを貼ったページ・はがしたページ） */
  private def setBody(pageId: UUID, by: UUID, text: String, attachmentIds: List[UUID]): IO[Unit] =
    val files = attachmentIds.map(id => s"""{"type":"fileBlock","attrs":{"id":"$id","url":"/api/attachments/$id"}}""").mkString(",")
    val json = s"""{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"$text"}]}${if files.isEmpty then "" else "," + files}]}"""
    env.docs.saveCompaction(pageId, Array[Byte](0, 0), io.circe.parser.parse(json).toOption.get, text, Set.empty, Set.empty, by).void

  private def search(token: String, ws: UUID, q: String): IO[List[Json]] =
    env.app.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/api/search?workspaceId=$ws&q=" + java.net.URLEncoder.encode(q, "UTF-8"))).addCookie("skiwi_session", token))
      .flatMap(_.as[Json]).map(_.asArray.get.toList)

  test("search finds pages by the contents of attached files that are still in the page, respecting permissions") {
    run {
      for
        (o, ot) <- user("att-owner")
        (v, vt) <- user("att-viewer")
        ws      <- env.store.createWorkspace("添付検索", o.user.id)
        p1      <- env.store.createPage(ws.id, None, Placement.Last, "手順書置き場", None, o.user.id).map(_.toOption.get)
        p2      <- env.store.createPage(ws.id, None, Placement.Last, "はがしたページ", None, o.user.id).map(_.toOption.get)
        a1      <- upload(ot, p1.id, "障害手順.docx", "application/octet-stream", docx)
        a2      <- upload(ot, p2.id, "障害手順(古い).docx", "application/octet-stream", docx)
        img     <- upload(ot, p1.id, "写真.png", "image/png", Array[Byte](1, 2, 3))
        _       <- setBody(p1.id, o.user.id, "ファイルを置いています", List(a1, img))
        _       <- setBody(p2.id, o.user.id, "ここには何もない", Nil)
        // 取り出す前は見つからない
        before  <- search(ot, ws.id, "障害対応")
        _        = assertEquals(before.size, 0)
        pending <- env.attachments.withoutText(1000).map(_.map(_.id).toSet)
        _        = assert(Set(a1, a2, img).subsetOf(pending))
        s1      <- env.attachments.get(a1).flatMap(a => env.indexer.index(a.get))
        s2      <- env.attachments.get(a2).flatMap(a => env.indexer.index(a.get))
        s3      <- env.attachments.get(img).flatMap(a => env.indexer.index(a.get))
        _        = assertEquals((s1, s2, s3), ("ok", "ok", "unsupported"))
        hits    <- search(ot, ws.id, "障害対応 社外秘")
        _        = assertEquals(hits.map(_.hcursor.downField("page").get[String]("title").toOption.get), List("手順書置き場"))
        h        = hits.head.hcursor
        _        = assertEquals(h.downField("attachment").get[String]("filename").toOption, Some("障害手順.docx"))
        _        = assertEquals(h.downField("attachment").get[String]("url").toOption, Some(s"/api/attachments/$a1"))
        _        = assert(h.get[String]("snippet").toOption.exists(_.contains("障害対応")), hits.head.noSpaces)
        // 本文で見つかるページは、ふつうの結果として先に出る（添付ファイルの情報は付かない）
        both    <- search(ot, ws.id, "ファイル")
        _        = assertEquals(both.map(_.hcursor.downField("attachment").focus.forall(_.isNull)), List(true))
        // 閲覧できない人には出ない
        none    <- search(vt, ws.id, "障害対応")
        _        = assertEquals(none.size, 0)
        // 同じものは二度取り出さない
        again   <- env.attachments.withoutText(1000).map(_.map(_.id).toSet)
        _        = assert(!again.contains(a1) && !again.contains(img))
        // ページを消すと出ない
        _       <- env.store.deletePage(p1.id, Some(o.user.id))
        gone    <- search(ot, ws.id, "障害対応")
        _        = assertEquals(gone.size, 0)
      yield ()
    }
  }

  test("files that are too large or broken are recorded and not retried") {
    run {
      for
        (o, ot) <- user("att-big")
        ws      <- env.store.createWorkspace("大きい", o.user.id)
        p       <- env.store.createPage(ws.id, None, Placement.Last, "大きいファイル", None, o.user.id).map(_.toOption.get)
        big     <- upload(ot, p.id, "大きい.txt", "text/plain", Array.fill(300 * 1024)('a'.toByte))
        bad     <- upload(ot, p.id, "壊れた.pdf", "application/pdf", "%PDF-1.4 broken".getBytes)
        r1      <- env.attachments.get(big).flatMap(a => env.indexer.index(a.get))
        r2      <- env.attachments.get(bad).flatMap(a => env.indexer.index(a.get))
        _        = assertEquals((r1, r2), ("too_large", "error"))
        t       <- env.attachments.textOf(bad)
        _        = assertEquals(t, Some(("error", "")))
      yield ()
    }
  }
