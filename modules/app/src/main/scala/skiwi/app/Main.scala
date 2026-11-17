package skiwi.app

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.file.Path
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.staticcontent.*
import skiwi.api.*
import skiwi.auth.OidcClient
import skiwi.collab.{ClusterManager, Collab, CollabConfig}
import doobie.implicits.*
import org.http4s.jdkhttpclient.JdkWSClient
import skiwi.db.{Periodic, AccessRequestStore, AttachmentStore, ClusterStore, Database, DocStore, OpsStore, CommentStore, PgBus, ScimStore, SearchStore, ShareStore, Store, WebhookStore, DatabaseStore, AutomationStore, InboundStore, BackupStore, IntegrationStore}
import skiwi.notify.{Notifier, SmtpMailer}
import skiwi.observe.{HttpClientTelemetry, HttpTelemetry, Metrics, RumRoutes, Telemetry}
import skiwi.push.{PushService, PushStore}
import skiwi.storage.S3BlobStore

import scala.concurrent.duration.*

object Main extends IOApp.Simple:

  // ログにトレースの ID・操作したユーザーを載せるため、IOLocal の文脈を Java のライブラリから見えるようにする
  // （IORuntime を作る前に設定する必要がある. オブジェクトの初期化は main より先に動く）
  Telemetry.prepare()

  private val version = Option(getClass.getPackage.getImplementationVersion).orElse(sys.env.get("APP_VERSION")).getOrElse("dev")

  /** スパン・メトリクス・アクセスログの対象（バックエンドのパス. 画面のファイル・/healthz は除く） */
  private val tracedPaths = List("/api", "/auth", "/mcp", "/oauth", "/scim", "/ws")

  def run: IO[Unit] =
    for
      cfg <- Config.load
      n   <- migrateWithRetry(cfg)
      _   <- skiwi.observe.Log.println(s"[skiwi] database migrated ($n migration(s) applied)")
      _ <- (for
        _      <- Telemetry.resource("skiwi", version, runtime)
        xa     <- Database.transactor(cfg.db)
        _      <- gauges(xa)
        // 外への呼び出しもトレースに出す（traceparent を付けて送る）
        http   <- EmberClientBuilder.default[IO].build.map(HttpClientTelemetry(_))
        store   = new Store(xa)
        docs    = new DocStore(xa)
        pushSt  = new PushStore(xa)
        push   <- Resource.eval(PushService.create(pushSt, http, cfg.publicUrl, "skiwi"))
        // 画面内の通知を作ったら、同じ内容をプッシュ通知でも送る
        ops     = new OpsStore(xa, new NoticePush(push).send)
        audit   = new Audit(ops)
        oidc   <- Resource.eval(OidcClient.create(cfg.oidc, http))
        sess    = new SessionAuth(store, ops, cfg.api)
        bus     = new PgBus(cfg.db, xa)
        cluster = cfg.cluster.map(c => new ClusterManager(c, new ClusterStore(xa)))
        wsc    <- cluster.traverse(_ => JdkWSClient.simple[IO]) // 担当ノードへの転送用（クラスタ構成のときだけ）
        mentions = new Mentions(store, ops, sess)
        collab  = new Collab(store, docs, sess, Some(ops), CollabConfig(), Some(bus), cluster, wsc,
                    onMentions = (pid, users, by) => store.userNames(by.toList).flatMap(n =>
                      mentions.notify("mention", pid, users -- by, n.values.toList.sorted.mkString("、"), "")))
        _      <- Resource.eval(cluster.traverse_(c => c.refresh *> skiwi.observe.Log.println(s"[skiwi] cluster mode: node ${c.self} (${c.cfg.internalUrl})")))
        blobs  <- blobStore(cfg)
        _      <- Resource.eval(startupNotes(cfg))
        notifier = new Notifier(store, ops, cfg.smtp.map(new SmtpMailer(_)), cfg.publicUrl, cfg.api.adminGroups)
        search  = new SearchRoutes(store, new SearchStore(xa), sess)
        attachSt = new AttachmentStore(xa)
        shareSt = new ShareStore(xa)
        extractPermits <- Resource.eval(cats.effect.std.Semaphore[IO](2))
        indexer = new AttachmentIndexer(attachSt, blobs, extractPermits)
        attach  = new AttachmentRoutes(store, attachSt, blobs, sess, cfg.maxUploadBytes, Some(audit), Some(indexer))
        hookSt  = new WebhookStore(xa)
        dbSt    = new DatabaseStore(xa)
        autoSt  = new AutomationStore(xa)
        autos   = new Automations(autoSt, store, ops, dbSt, hookSt, cfg.taskTimezone)
        autoR   = new AutomationRoutes(store, autoSt, sess, audit)
        dbR     = new DatabaseRoutes(store, dbSt, ops, sess, audit, Some(autos))
        hooks   = new WebhookService(hookSt, store, cfg.publicUrl, cfg.webhookAllowHttp, allowPrivate = cfg.webhookAllowPrivate)
        hookR   = new WebhookRoutes(store, hookSt, hooks, sess, audit)
        shareR  = new ShareRoutes(store, shareSt, docs, attachSt, attach, sess, audit, cfg.api.publicSharing, cfg.publicUrl, Some(hooks))
        api     = new ApiRoutes(store, docs, ops, sess, audit, collab.accessChanged, Some(shareSt), Some(hooks), Some(dbSt), Some(autos))
        opsR    = new OpsRoutes(ops, sess, audit)
        trash   = new TrashRoutes(store, sess, audit, blobs, cfg.trashRetentionDays.days, collab.accessChanged)
        extra   = new PageExtraRoutes(store, docs, ops, sess, audit, Some(autos))
        cssR    = new CssRoutes(store, sess, audit)
        copyR   = new PageCopyRoutes(store, attachSt, blobs, new AccessRequestStore(xa), ops, sess, audit, collab.accessChanged)
        commentSt = new CommentStore(xa)
        commentR = new CommentRoutes(store, commentSt, ops, sess, audit, mentions, Some(hooks))
        inRate  <- Resource.eval(Ref.of[IO, Map[java.util.UUID, List[Long]]](Map.empty))
        formRate <- Resource.eval(Ref.of[IO, Map[java.util.UUID, List[Long]]](Map.empty))
        formR   = new FormRoutes(store, dbSt, sess, audit, cfg.publicUrl, formRate, Some(hooks), Some(autos))
        unfurlCache <- Resource.eval(Ref.of[IO, Map[String, (Long, UnfurlDto)]](Map.empty))
        unfurlR = new UnfurlRoutes(sess, cfg.unfurlAllowPrivate, unfurlCache)
        integCache <- Resource.eval(Ref.of[IO, Map[(String, java.util.UUID, String), (Long, Option[io.circe.Json])]](Map.empty))
        integSt = new IntegrationStore(xa)
        integR  = new IntegrationRoutes(LinkedService.all, integSt, sess, audit, http, cfg.publicUrl, cfg.secretBox, integCache)
        oauthR  = new OAuthProviderRoutes(integSt, ops, store, sess, audit, cfg.api.cookieSecure)
        _      <- Resource.eval(cfg.oauthClientSeed.traverse_(oauthR.seed))
        inboundR = new InboundRoutes(store, new InboundStore(xa), dbSt, docs, commentSt, ops, sess, audit, cfg.publicUrl, Some(mentions), Some(hooks), Some(autos), inRate)
        lockR   = new LockRoutes(store, sess, audit, collab.accessChanged)
        backupSt = new BackupStore(xa)
        backupSvc = new BackupService(store, docs, attachSt, dbSt, backupSt, blobs, cfg.publicUrl, cfg.taskTimezone)
        backupR = new BackupRoutes(backupSt, backupSvc, blobs, sess, audit)
        bulkR   = new BulkRoutes(store, sess, audit, collab.accessChanged, Some(hooks), Some(autos))
        scim    = cfg.scimToken.map(t => new ScimRoutes(new ScimStore(xa), t, audit, collab.accessChanged, cfg.publicUrl))
        // 定期処理（停止時にキャンセルする）
        loops   = List[IO[Unit]](
                    purgeLoop(store), trash.purgeLoop, taskDueLoop(ops, cfg.taskTimezone), aclExpiryLoop(store, collab.accessChanged),
                    collab.revalidateLoop, notifier.loop, hooks.loop, autos.loop, backupSvc.loop, hooks.purgeLoop, collab.busLoop
                  ) ++ Option.when(blobs.isDefined)(indexer.loop)
        _      <- loops.traverse_(_.background)
        health  = healthRoutes(xa, cluster.map(_.self)) <+> clientConfigRoutes(cfg)
        traced <- HttpTelemetry.middleware(tracedPaths)
        _      <- EmberServerBuilder.default[IO]
                    .withHost(ipv4"0.0.0.0")
                    .withPort(Port.fromInt(cfg.port).getOrElse(port"8080"))
                    // 停止時は接続（WebSocketを含む）を長く待たない。クライアントは他ノードへ再接続する
                    .withShutdownTimeout(2.seconds)
                    .withHttpWebSocketApp { wsb =>
                      val rest =
                        health <+> new AuthRoutes(oidc, store, sess, audit, cfg.api.scimManagesGroups).routes <+> api.routes <+> opsR.routes <+>
                          RumRoutes.routes <+> new PushRoutes(pushSt, push, sess).routes <+>
                          search.routes <+> new ContentEditRoutes(store, docs, ops, sess, audit, collab.injectUpdate).routes <+> attach.routes <+> shareR.routes <+> trash.routes <+> extra.routes <+> commentR.routes <+> cssR.routes <+> copyR.routes <+> bulkR.routes <+> lockR.routes <+> autoR.routes <+> inboundR.routes <+> formR.routes <+> unfurlR.routes <+> integR.routes <+> new AvatarRoutes(ops, sess).routes <+> oauthR.routes <+> backupR.routes <+> hookR.routes <+> dbR.routes <+> collab.routes(wsb) <+> scim.fold(HttpRoutes.empty[IO])(_.routes)
                      // MCP（/mcp）。ツールは REST API を同じトークンで内部から呼ぶ
                      val backend = rest <+> new McpRoutes(rest, sess, cfg.publicUrl).routes
                      val all = cfg.staticDir.fold(backend)(d => backend <+> spa(d, shareR))
                      traced(Security.headers(cfg.publicUrl)(ErrorHandling.Custom.recoverWith(Security.bodyLimit(Security.csrf(cfg.publicUrl, cfg.api.cookieName)(all)).orNotFound)(handler)))
                    }
                    .build
        // HTTPサーバーが待ち受けを始めてからクラスタに登録する。停止時は逆順: ハートビート停止 → 登録削除 → サーバー停止
        _      <- Resource.onFinalize(cluster.traverse_(_.leave))
        _      <- cluster.traverse_(_.loop(collab.rebalance).background)
        _      <- cluster.traverse_(_ => collab.rebalanceLoop.background)
      yield ()).useForever
    yield ()

  /** 起動時、DB（Patroniのプライマリ）が使えるまで待ってからマイグレーションする。3ノード同時起動でもFlywayのロックで1回だけ適用される。 */
  private def migrateWithRetry(cfg: AppConfig, attempt: Int = 1): IO[Int] =
    Database.migrate(cfg.db).handleErrorWith { e =>
      if attempt >= 60 then IO.raiseError(e)
      else skiwi.observe.Log.println(s"[skiwi] database not ready (${e.getMessage.take(120)}); retry $attempt/60") *>
        IO.sleep(2.seconds) *> migrateWithRetry(cfg, attempt + 1)
    }

  /** DB から定期的に読むメトリクス（skorimako のダッシュボード・アラート用）。 */
  private def gauges(xa: doobie.Transactor[IO]): Resource[IO, Unit] =
    def q(sqlText: doobie.Fragment): IO[Long] = sqlText.query[Long].unique.transact(xa)
    List(
      Metrics.gauge("skiwi.users.active", "有効なユーザーの数")(q(sql"select count(*) from app_user where active")),
      Metrics.gauge("skiwi.users.signed_in", "ログイン中（有効なセッションがある）のユーザーの数")(q(sql"select count(distinct user_id) from session where expires_at > now()")),
      Metrics.gauge("skiwi.workspaces.total", "ワークスペースの数（ゴミ箱を除く）")(q(sql"select count(*) from workspace where deleted_at is null")),
      Metrics.gauge("skiwi.pages.total", "ページの数（ゴミ箱を除く）")(q(sql"select count(*) from page where deleted_at is null")),
      Metrics.gauge("skiwi.pages.edited_24h", "24時間以内に更新されたページの数")(q(sql"select count(*) from page where deleted_at is null and updated_at > now() - interval '24 hours'")),
      Metrics.gauge("skiwi.shares.public", "ゲスト向けに公開中のページの数")(q(sql"select count(*) from page_share where expires_at is null or expires_at > now()")),
      Metrics.gauge("skiwi.mail.outbox", "送信待ちのメールの数")(q(sql"select count(*) from mail_outbox where sent_at is null and not skipped")),
      Metrics.gauge("skiwi.webhook.pending", "送信待ちの Webhook の数")(q(sql"select count(*) from webhook_delivery where sent_at is null and not skipped"))
    ).sequence_

  /** 画面が使う設定（ログイン不要。秘密は含めない） */
  private def clientConfigRoutes(cfg: AppConfig): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "api" / "client-config" =>
      Ok(io.circe.Json.obj("drawioUrl" -> io.circe.Json.fromString(cfg.drawioUrl)))
  }

  /** ロードバランサー(HAProxy)用のヘルスチェック。DB(プライマリ)に書けない状態なら 503。 */
  private def healthRoutes(xa: doobie.Transactor[IO], node: Option[String]): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "healthz" =>
      sql"select not pg_is_in_recovery()".query[Boolean].unique.transact(xa).timeout(3.seconds).attempt.flatMap {
        case Right(true) => Ok(s"ok ${node.getOrElse("")}".trim)
        case Right(false) => ServiceUnavailable("database is read-only (not primary)")
        // 例外の内容（接続先など）は外に出さず、ログにだけ残す
        case Left(e)      => skiwi.observe.Log.println(s"[skiwi] health check failed: ${e.getMessage}") *> ServiceUnavailable("database unavailable")
      }
  }

  private def blobStore(cfg: AppConfig): Resource[IO, Option[skiwi.storage.BlobStore]] = cfg.s3 match
    case Some(c) => S3BlobStore.resource(c).evalTap(_.ensureBucket).map(b => Some(b))
    case None    => Resource.eval(skiwi.observe.Log.println("[skiwi] WARNING: S3_ENDPOINT not set; attachments are disabled").as(None))

  private def startupNotes(cfg: AppConfig): IO[Unit] =
    (if cfg.smtp.isEmpty then skiwi.observe.Log.println("[skiwi] WARNING: SMTP_HOST not set; email notifications are disabled") else IO.unit) *>
      (if cfg.scimToken.isEmpty then skiwi.observe.Log.println("[skiwi] SCIM is disabled (SCIM_TOKEN not set); groups are synced from the OIDC claim at login")
       else skiwi.observe.Log.println("[skiwi] SCIM enabled at /scim/v2 (group membership is managed by SCIM)")) *>
      IO.whenA(cfg.secretBox.isEmpty)(skiwi.observe.Log.println("[skiwi] ENCRYPTION_KEY not set; the skea/skawau integrations are disabled"))

  private val handler: PartialFunction[Throwable, IO[Response[IO]]] = {
    case ReadOnlyTokenError => Forbidden(ErrorDto("this API token is read-only"))
    case e: Security.BodyTooLarge => PayloadTooLarge(ErrorDto(e.getMessage))
    case e => IO(org.slf4j.LoggerFactory.getLogger("skiwi").error("unhandled error", e)) *> InternalServerError("internal error")
  }

  /** /s/{key} または /s/{key}/{pageKey} */
  private object GuestPath:
    def unapply(path: Uri.Path): Option[(String, Option[String])] =
      path.segments.map(_.decoded()).toList match
        case "s" :: key :: Nil          => Some((key, None))
        case "s" :: key :: page :: Nil  => Some((key, Some(page).filter(_.nonEmpty)))
        case _                          => None

  /** ビルド済みフロントエンドの配信。存在しないパスは index.html にフォールバック（SPA）。 */
  private def spa(dir: String, shares: ShareRoutes): HttpRoutes[IO] =
    val files = fileService[IO](FileService.Config(dir))
    val index = HttpRoutes.of[IO] {
      // 公開ページ: タイトル・OGP・検索エンジン向けの指定を埋め込む
      case GET -> GuestPath(key, pageKey) =>
        fs2.io.file.Files[IO].readUtf8(Path(dir) / "index.html").compile.string.attempt.flatMap {
          case Left(_) => NotFound()
          case Right(html) =>
            shares.headFor(key, pageKey).handleError(_ => None).map { h =>
              Response[IO](Status.Ok).withEntity(shares.renderHead(html, h))(using EntityEncoder.stringEncoder[IO])
                .withContentType(headers.`Content-Type`(MediaType.text.html, Charset.`UTF-8`))
            }
        }
      case req @ GET -> _ if !req.pathInfo.renderString.startsWith("/api") && !req.pathInfo.renderString.startsWith("/scim") =>
        StaticFile.fromPath(Path(dir) / "index.html", Some(req)).getOrElseF(NotFound())
    }
    // index.html は毎回確認させる（更新後に古い画面が残らないように）。/assets/ はファイル名に内容のハッシュが入るので長期キャッシュ
    HttpRoutes[IO] { req =>
      val cc = if req.pathInfo.renderString.startsWith("/assets/") then "public, max-age=31536000, immutable" else "no-cache"
      (files <+> index).run(req).map(_.putHeaders("Cache-Control" -> cc))
    }

  /** 期限の近いToDoを1時間ごとに確認して通知する（同じToDoには1回だけ。複数ノードで動いても重複しにくいよう少しずらす） */
  private def taskDueLoop(ops: OpsStore, tz: String): IO[Unit] =
    Periodic.run(1.minute, 1.hour)(e => s"[skiwi] task due check failed: $e")(ops.enqueueTaskDue(tz))

  /** 期限を過ぎた権限を1分ごとに消し、接続中の画面の権限を確かめ直す。 */
  private def aclExpiryLoop(store: Store, accessChanged: IO[Unit]): IO[Unit] =
    Periodic.run(1.minute, 1.minute)(e => s"[skiwi] acl expiry failed: $e")(store.purgeExpiredAcl.flatMap(n => IO.whenA(n > 0)(accessChanged)))

  /** 期限切れセッションを定期的に削除する（基準の時刻は毎回取り直す） */
  private def purgeLoop(store: Store): IO[Unit] =
    Periodic.run(10.minutes, 10.minutes)(e => s"[skiwi] purge failed: $e")(IO.realTimeInstant.flatMap(store.purgeExpiredSessions))
