package skiwi.api

import cats.effect.*
import fs2.Stream
import fs2.io.file.{Files, Path}
import sbirds.storage.{BlobMeta, BlobStore}

import java.util.concurrent.ConcurrentHashMap

/** テスト用のメモリ実装。 */
final class InMemoryBlobStore extends BlobStore {
  private val data = new ConcurrentHashMap[String, (String, Array[Byte])]()

  def put(
    key: String,
    file: Path,
    contentType: String
  ): IO[Unit] =
    Files[IO].readAll(file).compile.to(Array).flatMap(b => IO(data.put(key, (contentType, b))).void)

  def get(
    key: String
  ): IO[Option[(BlobMeta, Stream[IO, Byte])]] =
    IO(Option(data.get(key)).map(
      (
        ct,
        b
      ) => BlobMeta(ct, b.length.toLong) -> Stream.emits(b).covary[IO]
    ))

  def delete(
    key: String
  ): IO[Unit] = IO(data.remove(key)).void

  def copy(
    from: String,
    to: String
  ): IO[Boolean] = IO(Option(data.get(from)).map(v => data.put(to, v)).isDefined)

  def keys: Set[String] = scala.jdk.CollectionConverters.SetHasAsScala(data.keySet()).asScala.toSet
}
