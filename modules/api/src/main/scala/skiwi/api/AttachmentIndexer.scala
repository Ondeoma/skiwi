package skiwi.api

import cats.effect.*
import cats.effect.std.Semaphore
import cats.syntax.all.*
import fs2.io.file.Files
import skiwi.db.{Attachment, AttachmentStore}
import sbirds.storage.BlobStore

import scala.concurrent.duration.*
import skiwi.core.ErrorCode

/**
 * 添付ファイルから文字を取り出し、検索できるようにする。
 * アップロードの直後に1件ずつ行い（kick）、まだのもの（以前からある添付ファイルや、失敗・再起動で漏れたもの）は loop で順に行う。
 * 同時に取り出すのは2件まで（PDF は CPU とメモリを使うため）。
 */
final class AttachmentIndexer(
  attachments: AttachmentStore,
  blobs: Option[BlobStore],
  permits: Semaphore[IO],
  maxBytes: Long = 50L * 1024 * 1024,
  timeout: FiniteDuration = 2.minutes) {

  /** 1件を取り出して保存する。失敗しても状態（error など）を記録し、同じものを何度も試さない。 */
  def index(
    a: Attachment
  ): IO[String] = sbirds.observe.Telemetry.span("attachment.index", "content.type" -> a.contentType)(indexImpl(a))

  private def indexImpl(
    a: Attachment
  ): IO[String] = {
    val result: IO[(String, String)] = TextExtract.kindOf(a.filename, a.contentType) match {
      case None => IO.pure(("unsupported", ""))
      case Some(_) if a.size > maxBytes => IO.pure(("too_large", ""))
      case Some(kind) =>
        blobs match {
          case None => IO.pure(("error", ""))
          case Some(bs) =>
            bs.get(a.s3Key).flatMap {
              case None => IO.pure(("error", ""))
              case Some((_, body)) =>
                Files[IO].tempFile(None, "skiwi-extract-", ".bin", None).use { tmp =>
                  body.through(Files[IO].writeAll(tmp)).compile.drain *>
                    IO.interruptible(TextExtract.extract(tmp.toNioPath, kind)).timeout(timeout)
                      .map(t => if t.trim.isEmpty then ("empty", "") else ("ok", t))
                }
            }
        }
    }
    permits.permit.use(_ => result)
      .handleErrorWith(e =>
        sbirds.observe.Log.report(
          ErrorCode.AttachmentTextExtractFailed,
          s"${a.id} (${a.filename}): ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("").take(200)}"
        ).as(("error", ""))
      )
      .flatMap(
        (
          status,
          body
        ) => attachments.saveText(a.id, status, body).as(status)
      )
  }

  /** アップロードの直後に、裏で取り出す */
  def kick(
    a: Attachment
  ): IO[Unit] = index(a).void.handleError(_ => ()).start.void

  /** まだのものを順に取り出す。なければ1分待つ。複数ノードで動いても、同じものを二重に取り出すだけで害はない */
  def loop: IO[Unit] = {
    val step = attachments.withoutText(10).flatMap { as =>
      if as.isEmpty then IO.sleep(1.minute)
      else as.traverse_(index) *> IO.sleep(1.second)
    }.handleErrorWith(e =>
      sbirds.observe.Log.report(ErrorCode.AttachmentIndexFailed, e.toString, e) *> IO.sleep(1.minute)
    )
    IO.sleep(20.seconds) *> step.foreverM
  }
}
