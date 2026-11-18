package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.Files
import io.circe.Encoder
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.headers.`Content-Type`
import org.http4s.multipart.Multipart
import org.typelevel.ci.CIString
import skiwi.core.*
import skiwi.db.{Attachment, AttachmentStore, Store}
import skiwi.storage.BlobStore

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.{HexFormat, UUID}

final case class AttachmentDto(id: UUID, url: String, filename: String, contentType: String, size: Long) derives Encoder.AsObject

/** 添付ファイルのアップロード(要Edit)とダウンロード(要View)。実体はS3互換ストレージ。 */
final class AttachmentRoutes(
    protected val store: Store,
    attachments: AttachmentStore,
    blobs: Option[BlobStore],
    protected val sessions: SessionAuth,
    maxBytes: Long = 100L * 1024 * 1024,
    audit: Option[Audit] = None,
    indexer: Option[AttachmentIndexer] = None
) extends PageRouteSupport:

  private case object TooLarge extends Exception("ファイルが大きすぎます")

  /** ブラウザでインライン表示してよい種類。それ以外（HTML、SVGなど）はXSS対策で必ずダウンロードさせる。 */
  private val inlineTypes = Set(
    "image/png", "image/jpeg", "image/gif", "image/webp", "image/avif",
    "application/pdf", "video/mp4", "video/webm", "audio/mpeg", "audio/ogg", "text/plain"
  )


  private def sanitizeName(raw: Option[String]): String =
    val base = raw.getOrElse("file").split("[/\\\\]").lastOption.getOrElse("file").filterNot(_.isControl).trim
    if base.isEmpty then "file" else base.take(200)

  /** RFC 5987 の filename*=UTF-8''… があれば優先する（http4s/curl等はASCII化したfilenameと併記する）。
    * ブラウザは生のUTF-8で filename="…" を送るので、そちらは part.filename でそのまま読める。 */
  private def extendedFilename(part: org.http4s.multipart.Part[IO]): Option[String] =
    part.headers.get[org.http4s.headers.`Content-Disposition`]
      .flatMap(_.parameters.get(CIString("filename*"))) // http4sがRFC 5987デコード済みの値を返す
      .filter(_.nonEmpty)

  private def contentDisposition(kind: String, filename: String): String =
    val ascii = filename.map(c => if c < 0x20 || c > 0x7e || c == '"' || c == '\\' then '_' else c)
    val utf8  = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20")
    s"""$kind; filename="$ascii"; filename*=UTF-8''$utf8"""

  /** 添付ファイルの中身を返す（権限の確認は呼び出し側で行う）。公開リンク経由の配信でも使う。 */
  def serve(a: Attachment, cacheControl: String = "private, max-age=3600"): IO[Response[IO]] =
    blobs.flatTraverse(_.get(a.s3Key)).flatMap {
      case None => err(Status.NotFound, "添付ファイルの中身が見つかりません")
      case Some((meta, body)) =>
        val inline = inlineTypes.contains(a.contentType)
        val ct     = if inline then a.contentType else "application/octet-stream"
        val base = Response[IO](Status.Ok)
          .withBodyStream(body)
          .putHeaders(
            Header.Raw(CIString("Content-Type"), if ct == "text/plain" then "text/plain; charset=utf-8" else ct),
            Header.Raw(CIString("Content-Length"), meta.size.toString),
            Header.Raw(CIString("Content-Disposition"), contentDisposition(if inline then "inline" else "attachment", a.filename)),
            Header.Raw(CIString("X-Content-Type-Options"), "nosniff"),
            Header.Raw(CIString("Cache-Control"), cacheControl)
          )
        // PDFビューアはsandboxで動かないため除外。それ以外はスクリプト実行を禁止
        val resp =
          if a.contentType == "application/pdf" then base
          else base.putHeaders(Header.Raw(CIString("Content-Security-Policy"), "sandbox; default-src 'none'; img-src 'self'; media-src 'self'; style-src 'unsafe-inline'"))
        resp.pure[IO]
    }

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ POST -> Root / "api" / "pages" / UUIDVar(pageId) / "attachments" =>
      withPage(req, pageId, Role.Edit) { (s, _, _) =>
        blobs match
          case None => err(Status.ServiceUnavailable, "添付ファイルの保存先（S3）が設定されていません")
          case Some(blobStore) =>
            if req.contentLength.exists(_ > maxBytes + 64 * 1024) then err(Status.PayloadTooLarge, "ファイルが大きすぎます")
            else
              // multipart の解析は全パートを一時ファイルに書き切ってから返すので、Content-Length がなくても受け取る量をここで限る
              val limited = req.withBodyStream(req.body.through(Security.limitStream(maxBytes + 64 * 1024)))
              EntityDecoder.mixedMultipartResource[IO](maxSizeBeforeWrite = 1024 * 1024, maxParts = 5, failOnLimit = true).use { dec =>
                limited.decodeWith(dec, strict = true) { (mp: Multipart[IO]) =>
                  mp.parts.find(_.name.contains("file")) match
                    case None => err(Status.BadRequest, "multipart の file を指定してください")
                    case Some(part) =>
                      val filename = sanitizeName(extendedFilename(part).orElse(part.filename))
                      val ctype = part.headers.get[`Content-Type`]
                        .map(ct => s"${ct.mediaType.mainType}/${ct.mediaType.subType}".toLowerCase)
                        .getOrElse("application/octet-stream")
                      Files[IO].tempFile(None, "skiwi-upload-", ".bin", None).use { tmp =>
                        val md = MessageDigest.getInstance("SHA-256")
                        val write =
                          Ref.of[IO, Long](0L).flatMap { total =>
                            part.body.chunks
                              .evalTap(c =>
                                total.updateAndGet(_ + c.size).flatMap(n => if n > maxBytes then IO.raiseError(TooLarge) else IO(md.update(c.toArray)))
                              )
                              .flatMap(Stream.chunk)
                              .through(Files[IO].writeAll(tmp))
                              .compile
                              .drain *> total.get
                          }
                        write.attempt.flatMap {
                          case Left(TooLarge) => err(Status.PayloadTooLarge, "ファイルが大きすぎます")
                          case Left(e)        => IO.raiseError(e)
                          case Right(size) =>
                            val id  = UUID.randomUUID()
                            val key = s"pages/$pageId/$id"
                            for
                              _ <- skiwi.observe.Telemetry.span("attachment.store", "content.type" -> ctype)(blobStore.put(key, tmp, ctype))
                              a <- attachments.insert(id, pageId, key, filename, ctype, size, HexFormat.of().formatHex(md.digest()), s.user.id)
                              _ <- audit.traverse_(_.log(s, "attachment.upload", "attachment", a.id,
                                     io.circe.Json.obj("pageId" -> io.circe.Json.fromString(pageId.toString),
                                       "filename" -> io.circe.Json.fromString(a.filename), "size" -> io.circe.Json.fromLong(a.size))))
                              _ <- indexer.traverse_(_.kick(a)) // 検索用に中の文字を取り出す（裏で）
                              _ <- skiwi.observe.Metrics.count("skiwi.attachments.uploaded", "type" -> ctype.takeWhile(_ != '/'))
                              _ <- skiwi.observe.Metrics.add("skiwi.attachments.bytes", size)
                              r <- Created(AttachmentDto(a.id, s"/api/attachments/${a.id}", a.filename, a.contentType, a.size))
                            yield r
                        }
                      }
                }
              }.handleErrorWith {
                // 上限を超えた（解析の途中で limitStream が止めた）
                case e if Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(10).exists(_.isInstanceOf[Security.BodyTooLarge]) =>
                  err(Status.PayloadTooLarge, "ファイルが大きすぎます")
              }
      }

    case req @ GET -> Root / "api" / "attachments" / UUIDVar(id) =>
      attachments.get(id).flatMap {
        case None => err(Status.NotFound, "添付ファイルが見つかりません")
        case Some(a) =>
          withPage(req, a.pageId, Role.View)((_, _, _) => serve(a))
      }
  }
