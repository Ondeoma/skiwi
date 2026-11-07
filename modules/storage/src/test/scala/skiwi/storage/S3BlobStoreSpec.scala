package skiwi.storage

import cats.effect.*
import fs2.io.file.Files
import munit.CatsEffectSuite

import java.nio.charset.StandardCharsets
import java.util.UUID

/** TEST_S3_ENDPOINT（例: http://localhost:3900）が設定されている場合のみ、S3 互換ストレージ（ScalaS3 など）に対して実行。 */
class S3BlobStoreSpec extends CatsEffectSuite:
  private val cfg = sys.env.get("TEST_S3_ENDPOINT").map { ep =>
    S3Config(ep, "us-east-1", "skiwi-test", sys.env.getOrElse("TEST_S3_ACCESS_KEY", "skiwi"), sys.env.getOrElse("TEST_S3_SECRET_KEY", "skiwiskiwiskiwi"))
  }

  test("put / get / delete roundtrip (Japanese content)") {
    cfg match
      case None => IO(assume(false, "TEST_S3_ENDPOINT not set"))
      case Some(c) =>
        S3BlobStore.resource(c).use { s3 =>
          val key = s"test/${UUID.randomUUID()}"
          val body = "日本語の添付ファイル".getBytes(StandardCharsets.UTF_8)
          Files[IO].tempFile.use { tmp =>
            for
              _   <- s3.ensureBucket
              _   <- fs2.Stream.emits(body).through(Files[IO].writeAll(tmp)).compile.drain
              _   <- s3.put(key, tmp, "text/plain; charset=utf-8")
              got <- s3.get(key)
              (meta, stream) = got.get
              bytes <- stream.compile.to(Array)
              _   <- s3.delete(key)
              gone <- s3.get(key)
            yield
              assertEquals(meta.size, body.length.toLong)
              assert(meta.contentType.startsWith("text/plain"))
              assertEquals(new String(bytes, StandardCharsets.UTF_8), "日本語の添付ファイル")
              assertEquals(gone, None)
          }
        }
  }

  extension [A](io: IO[A]) private def withFilter(p: A => Boolean): IO[A] = io
