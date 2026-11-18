package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.typelevel.ci.CIString
import skiwi.core.*
import skiwi.db.{AttachmentStore, BackupConfig, BackupRow, BackupStore, DatabaseStore, DocStore, Periodic, Store}
import skiwi.storage.BlobStore

import java.io.{BufferedOutputStream, FileOutputStream}
import java.net.URLEncoder
import java.time.{Instant, ZoneId, ZonedDateTime}
import java.util.UUID
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.concurrent.duration.*

/** ワークスペースのバックアップ（ZIP）。画面の「書き出し」→ ZIP と同じ形（フォルダ構成の Markdown・attachments/・.excalidraw）で、
  * そのまま「取り込む」で戻せる。加えて skiwi-backup.json にページの親子・本文（JSON）・ラベル・データベースの定義と値を入れる。
  * すべてのページ（権限を絞ったページも）を含むので、扱えるのは管理者だけ。 */
final class BackupService(
    store: Store, docs: DocStore, attachments: AttachmentStore, dbs: DatabaseStore, backups: BackupStore,
    blobs: Option[BlobStore], publicUrl: String, tz: String = "Asia/Tokyo"
):
  private val base = publicUrl.stripSuffix("/")
  /** すべてのページを読むための管理者の権限 */
  private val system = AuthSession(User(new UUID(0, 0), "system", None, "バックアップ"), Nil, isAdmin = true)

  private def safeName(s: String) =
    val x = s.replaceAll("""[\\/:*?"<>|\u0000-\u001f]""", "_").trim.take(80)
    // 「.」「..」だけの名前は、展開したときに ZIP の外へ書き出されうる（zip slip）
    if x.isEmpty then "無題" else if x.forall(_ == '.') then x.replace('.', '_') else x

  /** 1回分を作って保存する。戻り値: (保存先のキー, 大きさ, ページ数, 添付の数) */
  def build(wsId: UUID, id: UUID): IO[(String, Long, Int, Int)] =
    blobs match
      case None => IO.raiseError(new IllegalStateException("オブジェクトストレージ（S3）が設定されていません"))
      case Some(bs) =>
        for
          ws    <- store.workspaceRole(wsId, system).map(_.map(_._1)).flatMap(IO.fromOption(_)(new IllegalStateException("ワークスペースが見つかりません")))
          pages <- store.listPages(wsId, system).map(_.map(_._1))
          labels <- store.labelsIn(wsId)
          dbIds <- dbs.idsIn(wsId)
          atts  <- attachments.byPages(pages.map(_.id))
          tmp   <- IO.blocking(java.nio.file.Files.createTempFile("skiwi-backup-", ".zip"))
          result <- writeZip(ws, pages, labels, dbIds, atts, bs, tmp).guarantee(IO.unit)
          key    = s"backups/$wsId/${ZonedDateTime.now(ZoneId.of(tz)).toLocalDateTime.toString.take(19).replace(':', '-')}-$id.zip"
          size  <- IO.blocking(java.nio.file.Files.size(tmp))
          _     <- bs.put(key, fs2.io.file.Path.fromNioPath(tmp), "application/zip").guarantee(IO.blocking(java.nio.file.Files.deleteIfExists(tmp)).void)
        yield (key, size, pages.size, result)

  private def writeZip(ws: Workspace, pages: List[Page], labels: Map[UUID, List[String]], dbIds: Set[UUID],
                       atts: List[skiwi.db.Attachment], bs: BlobStore, tmp: java.nio.file.Path): IO[Int] =
    // ページ → ファイルのパス（同じ階層の同じ名前には (2) を付ける）
    val kids = pages.groupBy(_.parentId)
    val ids = pages.map(_.id).toSet
    val roots = pages.filter(p => p.parentId.forall(pid => !ids(pid)))
    val pathOf = scala.collection.mutable.LinkedHashMap.empty[UUID, String]
    val usedIn = scala.collection.mutable.HashMap.empty[String, scala.collection.mutable.Set[String]] // フォルダ → 使ったファイル名
    def assign(p: Page, dir: String): Unit =
      val used = usedIn.getOrElseUpdate(dir, scala.collection.mutable.Set.empty)
      var name = safeName(p.title); var n = 2
      while used(s"$name.md") do { name = s"${safeName(p.title)} ($n)"; n += 1 }
      used += s"$name.md"
      pathOf(p.id) = if dir.isEmpty then s"$name.md" else s"$dir/$name.md"
      kids.getOrElse(Some(p.id), Nil).sortBy(_.position).foreach(c => assign(c, if dir.isEmpty then name else s"$dir/$name"))
    roots.sortBy(_.position).foreach(assign(_, ""))
    val attPath = atts.map(a => a.id -> s"attachments/${a.id.toString.take(8)}-${safeName(a.filename)}").toMap
    def relative(from: String, to: String): String =
      val f = from.split('/').dropRight(1).toList
      val t = to.split('/').toList
      val common = f.zip(t).takeWhile(_ == _).size
      ("../" * (f.size - common)) + t.drop(common).mkString("/")

    // skiwi-backup.json の pages（全ページの本文を含む）は、メモリに溜めずに一時ファイルへ書いておき、最後に ZIP へ流す
    val metaFile = Resource.make(IO.blocking(java.nio.file.Files.createTempFile("skiwi-backup-meta-", ".json")))(f => IO.blocking(java.nio.file.Files.deleteIfExists(f)).void)
    (Resource.fromAutoCloseable(IO.blocking(new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp.toFile))))), metaFile).tupled.use { (zip, metaPath) =>
      def entry(name: String, bytes: Array[Byte]): IO[Unit] = IO.blocking { zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
      /** 中身をストリームのまま書く（添付ファイルをメモリに読み込まない） */
      def streamEntry(name: String, body: fs2.Stream[IO, Byte]): IO[Unit] =
        IO.blocking(zip.putNextEntry(new ZipEntry(name))) *> body.chunks.evalMap(c => IO.blocking(zip.write(c.toArray))).compile.drain *> IO.blocking(zip.closeEntry())
      Resource.fromAutoCloseable(IO.blocking(java.nio.file.Files.newBufferedWriter(metaPath, java.nio.charset.StandardCharsets.UTF_8))).use { meta =>
      var first = true
      def addMeta(j: Json): IO[Unit] = IO.blocking { meta.write(if first then "" else ","); first = false; meta.write(j.noSpaces) }
      for
        // 本文・データベースの定義・項目の値は 200 ページずつまとめて読む
        _ <- pages.grouped(200).toList.traverse_ { group =>
          val gids = group.map(_.id)
          (docs.currentContents(gids), dbs.getMany(gids.filter(dbIds)), dbs.valuesMany(gids)).flatMapN { (contents, dbDefs, values) =>
            group.traverse_ { p =>
               val path = pathOf(p.id)
               val up = "../" * (path.count(_ == '/'))
               val (db, vals) = (dbDefs.get(p.id), values.get(p.id))
               locally {
                 val doc = contents.get(p.id).fold(Json.Null)(parseOrNull)
                 var boards = 0
                 val boardFiles = scala.collection.mutable.ListBuffer.empty[(String, Array[Byte])]
                 val md = new MarkdownExport(
                   url = u =>
                     val att = """^/api/attachments/([0-9a-f-]{36})""".r.findFirstMatchIn(u).flatMap(m => uuidOpt(m.group(1)))
                     val pl = """^/w/[0-9a-f-]{36}/p/([0-9a-f-]{36})""".r.findFirstMatchIn(u).flatMap(m => uuidOpt(m.group(1)))
                     att.flatMap(attPath.get).map(x => URLEncoder.encode(up + x, "UTF-8").replace("+", "%20").replace("%2F", "/"))
                       .orElse(pl.flatMap(pathOf.get).map(x => URLEncoder.encode(relative(path, x), "UTF-8").replace("+", "%20").replace("%2F", "/")))
                       .getOrElse(if u.startsWith("/") then base + u else u)
                   ,
                   whiteboard = a =>
                     boards += 1
                     val snap = a("snapshot").flatMap(_.asString).flatMap(io.circe.parser.parse(_).toOption)
                     val name = s"${path.stripSuffix(".md")}.whiteboard-$boards.excalidraw"
                     val file = Json.obj("type" -> "excalidraw".asJson, "version" -> 2.asJson, "source" -> base.asJson,
                       "elements" -> snap.flatMap(_.hcursor.downField("elements").focus).getOrElse(Json.arr()),
                       "appState" -> Json.obj("viewBackgroundColor" -> snap.flatMap(_.hcursor.get[String]("background").toOption).getOrElse("#ffffff").asJson),
                       "files" -> Json.obj())
                     boardFiles += name -> file.spaces2.getBytes("UTF-8")
                     s"[🧩 ホワイトボード（Excalidraw で開けます）](${URLEncoder.encode(name.split('/').last, "UTF-8").replace("+", "%20")})"
                 ).page(doc, p.title)
                 addMeta(Json.obj(
                   "id" -> p.id.asJson, "parentId" -> p.parentId.asJson, "position" -> p.position.asJson, "title" -> p.title.asJson,
                   "icon" -> p.icon.asJson, "path" -> path.asJson, "updatedAt" -> p.updatedAt.asJson, "labels" -> labels.getOrElse(p.id, Nil).asJson,
                   "content" -> doc, "values" -> vals.flatMap(io.circe.parser.parse(_).toOption).getOrElse(Json.obj()),
                   "database" -> db.fold(Json.Null)(d => Json.obj("props" -> io.circe.parser.parse(d.props).getOrElse(Json.arr()),
                     "views" -> io.circe.parser.parse(d.views).getOrElse(Json.arr()))))) *>
                 entry(path, md.getBytes("UTF-8")) *> boardFiles.toList.traverse_((n, b) => entry(n, b))
               }
            }
          }
        }
        n <- atts.traverse { a =>
               bs.get(a.s3Key).flatMap {
                 case None => IO.pure(0)
                 case Some((_, body)) => streamEntry(attPath(a.id), body).as(1)
               }
             }.map(_.sum)
        _ <- IO.blocking(meta.flush())
        // pages の配列だけを一時ファイルから差し込む（ほかの項目は小さいので組み立てる）
        head = Json.obj("format" -> "skiwi-backup/1".asJson, "workspace" -> Json.obj("id" -> ws.id.asJson, "name" -> ws.name.asJson),
                 "createdAt" -> Instant.now().asJson,
                 "attachments" -> atts.map(a => Json.obj("id" -> a.id.asJson, "pageId" -> a.pageId.asJson, "filename" -> a.filename.asJson,
                   "contentType" -> a.contentType.asJson, "size" -> a.size.asJson, "path" -> attPath(a.id).asJson)).asJson).noSpaces
        _ <- streamEntry("skiwi-backup.json",
               fs2.Stream.emits(head.dropRight(1).getBytes("UTF-8")) ++ fs2.Stream.emits(""","pages":[""".getBytes("UTF-8")) ++
                 fs2.io.file.Files[IO].readAll(fs2.io.file.Path.fromNioPath(metaPath)) ++ fs2.Stream.emits("]}".getBytes("UTF-8")))
      yield n
      }
    }

  /** 実行して記録し、古いものを消す */
  def run(wsId: UUID, id: UUID, keep: Option[Int]): IO[Unit] =
    skiwi.observe.Telemetry.span("backup.build", "workspace.id" -> wsId.toString)(build(wsId, id)).attempt.flatMap {
      case Right((key, size, pages, n)) =>
        backups.finish(id, key, size, pages, n) *>
          keep.traverse_(k => backups.prune(wsId, k).flatMap(keys => blobs.traverse_(bs => keys.traverse_(k => bs.delete(k).attempt.void))))
      case Left(e) =>
        skiwi.observe.Log.println(s"[backup] failed for $wsId: $e") *> backups.fail(id, Option(e.getMessage).getOrElse(e.toString).take(500))
    }

  /** 定期バックアップ: 設定した時刻を過ぎていて、今日（設定の時刻以降）のものがまだなければ作る */
  val runDue: IO[Int] =
    backups.enabledConfigs.flatMap(_.traverseFilter { c =>
      IO(ZonedDateTime.now(ZoneId.of(tz))).flatMap { now =>
        val todayAt = now.toLocalDate.atTime(c.hour, 0).atZone(ZoneId.of(tz))
        val since = (if now.isBefore(todayAt) then todayAt.minusDays(1) else todayAt).toInstant
        backups.claimScheduled(c.workspaceId, since).flatMap(_.traverse(id => run(c.workspaceId, id, Some(c.keep)).as(1)))
      }
    }).map(_.sum)

  val loop: IO[Nothing] =
    Periodic.run(3.minutes, 10.minutes)(e => s"[backup] check failed: $e")(runDue)

// ---------------------------------------------------------------- 管理のAPI（管理者のみ）

final case class BackupDto(id: UUID, createdAt: Instant, finishedAt: Option[Instant], status: String, size: Option[Long],
                           pages: Option[Int], attachments: Option[Int], error: Option[String], manual: Boolean) derives Encoder.AsObject
final case class BackupConfigDto(enabled: Boolean, hour: Int, keep: Int) derives Encoder.AsObject, Decoder
final case class BackupsDto(config: Option[BackupConfigDto], backups: List[BackupDto], storage: Boolean) derives Encoder.AsObject

final class BackupRoutes(backups: BackupStore, svc: BackupService, blobs: Option[BlobStore], protected val sessions: SessionAuth, audit: Audit) extends RouteSupport:

  private def dto(b: BackupRow) = BackupDto(b.id, b.createdAt, b.finishedAt, b.status, b.size, b.pages, b.attachments, b.error, b.manual)
  private def cfgDto(c: BackupConfig) = BackupConfigDto(c.enabled, c.hour, c.keep)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "admin" / "workspaces" / UUIDVar(ws) / "backups" =>
      admin(req)(_ => (backups.config(ws), backups.list(ws)).flatMapN((c, bs) => Ok(BackupsDto(c.map(cfgDto), bs.map(dto), blobs.isDefined))))

    case req @ PUT -> Root / "api" / "admin" / "workspaces" / UUIDVar(ws) / "backups" / "config" =>
      admin(req) { s =>
        decodeBody[BackupConfigDto](req).flatMap {
          case Right(b) if b.hour >= 0 && b.hour <= 23 && b.keep >= 1 && b.keep <= 90 =>
            backups.setConfig(ws, b.enabled, b.hour, b.keep, s.user.id).flatMap { c =>
              audit.log(s, "backup.config", "workspace", ws, Json.obj("enabled" -> c.enabled.asJson, "hour" -> c.hour.asJson, "keep" -> c.keep.asJson)) *> Ok(cfgDto(c))
            }
          case _ => err(Status.BadRequest, "時刻は0〜23、残す世代は1〜90にしてください")
        }
      }

    // 今すぐバックアップする（終わるまで待たない）
    case req @ POST -> Root / "api" / "admin" / "workspaces" / UUIDVar(ws) / "backups" =>
      admin(req) { s =>
        if blobs.isEmpty then err(Status.Conflict, "オブジェクトストレージ（S3）が設定されていません")
        else backups.claimManual(ws).flatMap {
          case None => err(Status.Conflict, "実行中のバックアップがあります")
          case Some(id) =>
            backups.config(ws).flatMap { c =>
              audit.log(s, "backup.run", "workspace", ws) *> svc.run(ws, id, c.map(_.keep)).start *>
                backups.get(id).flatMap(b => Accepted(dto(b.get)))
            }
        }
      }

    case req @ GET -> Root / "api" / "admin" / "backups" / UUIDVar(id) / "download" =>
      admin(req) { s =>
        backups.get(id).flatMap {
          case Some(b) if b.status == "done" && b.s3Key.isDefined =>
            blobs.flatTraverse(_.get(b.s3Key.get)).flatMap {
              case None => err(Status.NotFound, "バックアップのファイルが見つかりません")
              case Some((meta, body)) =>
                audit.log(s, "backup.download", "workspace", b.workspaceId, Json.obj("backupId" -> id.asJson)) *>
                  IO.pure(Response[IO](Status.Ok).withBodyStream(body).putHeaders(
                    Header.Raw(CIString("Content-Type"), "application/zip"),
                    Header.Raw(CIString("Content-Length"), meta.size.toString),
                    Header.Raw(CIString("Content-Disposition"), s"attachment; filename=\"backup.zip\"; filename*=UTF-8''${URLEncoder.encode(b.s3Key.get.split('/').last, "UTF-8")}"),
                    Header.Raw(CIString("Cache-Control"), "no-store")))
            }
          case Some(_) => err(Status.Conflict, "バックアップはまだできていません")
          case None    => err(Status.NotFound, "バックアップが見つかりません")
        }
      }

    case req @ DELETE -> Root / "api" / "admin" / "backups" / UUIDVar(id) =>
      admin(req) { s =>
        backups.get(id).flatMap {
          case None => err(Status.NotFound, "バックアップが見つかりません")
          case Some(b) if b.status == "running" => err(Status.Conflict, "バックアップを実行中です")
          case Some(b) =>
            backups.delete(id).flatMap(key => key.traverse_(k => blobs.traverse_(_.delete(k)))) *>
              audit.log(s, "backup.delete", "workspace", b.workspaceId, Json.obj("backupId" -> id.asJson)) *> NoContent()
        }
      }
  }
