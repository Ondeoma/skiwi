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
import org.http4s.server.staticcontent.*
import skiwi.api.*
import sbirds.auth.{OidcClient, OidcConfig}
import skiwi.collab.{ClusterConfig, ClusterManager, Collab, CollabConfig}
import doobie.implicits.*
import skiwi.db.{
  DbConfig, Periodic, AccessRequestStore, AttachmentStore, ClusterStore, Database, DocStore, OpsStore, CommentStore,
  PgBus, ScimStore, SearchStore, ShareStore, Store, WebhookStore, DatabaseStore, AutomationStore, InboundStore,
  BackupStore, IntegrationStore
}
import skiwi.notify.Notifier
import sbirds.mail.{SmtpConfig, SmtpMailer}
import sbirds.observe.{HttpClientTelemetry, HttpTelemetry, Metrics, RumRoutes, Telemetry}
import sbirds.push.{PushService, PushStore}
import sbirds.storage.{S3BlobStore, S3Config}
import sbirds.http.Errors
import sbirds.shared.AppError
import cats.data.Kleisli

import scala.concurrent.duration.*
import skiwi.core.ErrorCode

final case class AppConfig(
  port: Int,
  db: DbConfig,
  /** OIDC（DEV_LOGIN のときは None） */
  oidc: Option[OidcConfig],
  api: ApiConfig,
  staticDir: Option[String],
  s3: Option[S3Config],
  maxUploadBytes: Long,
  publicUrl: String,
  scimToken: Option[String],
  smtp: Option[SmtpConfig],
  cluster: Option[ClusterConfig],
  /** ゴミ箱の保存日数（過ぎたら完全に削除） */
  trashRetentionDays: Int = AppConfig.DefaultTrashRetentionDays,
  /** ToDoの期限の「今日」を決める時間帯 */
  taskTimezone: String = AppConfig.DefaultTimezone,
  /** Webhook の送り先に http:// を許す（開発用。既定は https のみ） */
  webhookAllowHttp: Boolean = false,
  webhookAllowPrivate: Boolean = false,
  /** リンクのカード表示で、社内（プライベート）のアドレスにも接続する */
  unfurlAllowPrivate: Boolean = false,
  /** draw.io（diagrams.net）のエディタの URL。社内に置いたものを使うときに指定する。空にすると図を編集できない（表示はできる） */
  drawioUrl: String = AppConfig.DefaultDrawioUrl,
  /** 外部サービスのトークンなどの暗号化に使う秘密の値（APP_SECRET. 16文字以上。全ノードで同じ値） */
  appSecret: String = "",
  /**
   * skea → skiwi の連携のクライアントの初期登録（OAUTH_CLIENT_ID・OAUTH_CLIENT_SECRET・OAUTH_CLIENT_REDIRECT_URI・OAUTH_CLIENT_NAME）。
   * まだ登録されていないときだけ使う（管理画面での変更を優先する）
   */
  oauthClientSeed: Option[SaveOAuthClientReq] = None,
  /**
   * OAUTH_CLIENTS: 読み書きの API トークンを発行してよいクライアント（skakapo など）.
   * [{"id":"skakapo","secret":"…","redirectUri":"https://…/api/connections/callback","name":"skakapo","scopes":["read","write"]}]
   */
  oauthClients: List[skiwi.api.OAuthClient] = Nil,
  /** DEV_LOGIN: 開発用のログイン（名前だけでログインできる。OIDC を設定しないときだけ） */
  devLogin: Boolean = false,
  /** 待ち受けるアドレス（HTTP_BIND. 既定は 0.0.0.0、DEV_LOGIN のときは 127.0.0.1） */
  httpBind: String = "0.0.0.0")
object AppConfig {
  val DefaultTrashRetentionDays = 30
  val DefaultTimezone = "Asia/Tokyo"
  val DefaultDrawioUrl = "https://embed.diagrams.net"
}

object Main extends IOApp.Simple {

  // ログにトレースの ID・操作したユーザーを載せるため、IOLocal の文脈を Java のライブラリから見えるようにする
  // （IORuntime を作る前に設定する必要がある. オブジェクトの初期化は main より先に動く）
  Telemetry.prepare()

  private given org.typelevel.log4cats.LoggerFactory[IO] = org.typelevel.log4cats.slf4j.Slf4jFactory.create[IO]

  private val version =
    Option(getClass.getPackage.getImplementationVersion).orElse(sys.env.get("APP_VERSION")).getOrElse("dev")

  /** スパン・メトリクス・アクセスログの対象（バックエンドのパス. 画面のファイル・/healthz は除く） */
  private val tracedPaths = List("/api", "/auth", "/mcp", "/oauth", "/scim", "/ws")

  def run: IO[Unit] =
    for {
      cfg <- Config.load
      n <- migrateWithRetry(cfg)
      _ <- sbirds.observe.Log.println(s"[skiwi] database migrated ($n migration(s) applied)")
      _ <- (for {
        _ <- Telemetry.resource("skiwi", version, runtime)
        xa <- Database.transactor(cfg.db)
        _ <- gauges(xa)
        // 外への呼び出しもトレースに出す（traceparent を付けて送る）
        http <- EmberClientBuilder.default[IO].build.map(HttpClientTelemetry(_))
        // 利用者が指定した URL へ接続するもの（社内のアドレスへの接続を断る。NetGuard）
        hookHttp <- NetGuard.client(cfg.webhookAllowPrivate)
        unfurlHttp <- NetGuard.client(cfg.unfurlAllowPrivate)
        store = new Store(xa)
        docs = new DocStore(xa)
        pushSt = new PushStore(xa)
        push <- Resource.eval(PushService.create(pushSt, http, cfg.publicUrl, "skiwi"))
        // 画面内の通知を作ったら、同じ内容をプッシュ通知でも送る
        ops = new OpsStore(xa, new NoticePush(push).send)
        audit = new Audit(ops)
        oidc <- Resource.eval(cfg.oidc.traverse(OidcClient.create(_, http)))
        sess = new SessionAuth(store, ops, cfg.api)
        bus = new PgBus(cfg.db, xa)
        cluster = cfg.cluster.map(c => new ClusterManager(c, new ClusterStore(xa)))
        wsc <- cluster.traverse(_ => skiwi.collab.JdkWebSocket.client()) // 担当ノードへの転送用（クラスタ構成のときだけ）
        mentions = new Mentions(store, ops, sess)
        collab = new Collab(
          store,
          docs,
          sess,
          Some(ops),
          CollabConfig(),
          Some(bus),
          cluster,
          wsc,
          onMentions = (
            pid,
            users,
            by
          ) =>
            store.userNames(by.toList).flatMap(n =>
              mentions.notify("mention", pid, users -- by, n.values.toList.sorted.mkString("、"), "")
            )
        )
        _ <- Resource.eval(cluster.traverse_(c =>
          c.refresh *> sbirds.observe.Log.println(s"[skiwi] cluster mode: node ${c.self} (${c.cfg.internalUrl})")
        ))
        blobs <- blobStore(cfg)
        _ <- Resource.eval(startupNotes(cfg))
        notifier = new Notifier(store, ops, cfg.smtp.map(new SmtpMailer(_)), cfg.publicUrl, cfg.api.adminGroups)
        search = new SearchRoutes(store, new SearchStore(xa), sess)
        attachSt = new AttachmentStore(xa)
        shareSt = new ShareStore(xa)
        extractPermits <- Resource.eval(cats.effect.std.Semaphore[IO](2))
        indexer = new AttachmentIndexer(attachSt, blobs, extractPermits)
        attach = new AttachmentRoutes(store, attachSt, blobs, sess, cfg.maxUploadBytes, Some(audit), Some(indexer))
        hookSt = new WebhookStore(xa)
        dbSt = new DatabaseStore(xa)
        autoSt = new AutomationStore(xa)
        autos = new Automations(autoSt, store, ops, dbSt, hookSt, cfg.taskTimezone)
        autoR = new AutomationRoutes(store, autoSt, sess, audit)
        dbR = new DatabaseRoutes(store, dbSt, ops, sess, audit, Some(autos))
        hooks = new WebhookService(hookSt, store, cfg.publicUrl, hookHttp, cfg.webhookAllowHttp)
        hookR = new WebhookRoutes(store, hookSt, hooks, sess, audit)
        shareR = new ShareRoutes(
          store,
          shareSt,
          docs,
          attachSt,
          attach,
          sess,
          audit,
          cfg.api.publicSharing,
          cfg.publicUrl,
          Some(hooks)
        )
        api = new ApiRoutes(
          store,
          docs,
          ops,
          sess,
          audit,
          collab.accessChanged,
          Some(shareSt),
          Some(hooks),
          Some(dbSt),
          Some(autos)
        )
        opsR = new OpsRoutes(ops, sess, audit)
        trash = new TrashRoutes(store, sess, audit, blobs, cfg.trashRetentionDays.days, collab.accessChanged)
        extra = new PageExtraRoutes(store, docs, ops, sess, audit, Some(autos))
        cssR = new CssRoutes(store, sess, audit)
        copyR =
          new PageCopyRoutes(store, attachSt, blobs, new AccessRequestStore(xa), ops, sess, audit, collab.accessChanged)
        commentSt = new CommentStore(xa)
        commentR = new CommentRoutes(store, commentSt, ops, sess, audit, mentions, Some(hooks))
        inRate <- Resource.eval(Ref.of[IO, Map[java.util.UUID, List[Long]]](Map.empty))
        formRate <- Resource.eval(Ref.of[IO, Map[java.util.UUID, List[Long]]](Map.empty))
        formR = new FormRoutes(store, dbSt, sess, audit, cfg.publicUrl, formRate, Some(hooks), Some(autos))
        unfurlCache <- Resource.eval(Ref.of[IO, Map[String, (Long, UnfurlDto)]](Map.empty))
        unfurlR = new UnfurlRoutes(sess, unfurlHttp, unfurlCache)
        integCache <-
          Resource.eval(Ref.of[IO, Map[(String, java.util.UUID, String), (Long, Option[io.circe.Json])]](Map.empty))
        integSt = new IntegrationStore(xa)
        integR = new IntegrationRoutes(
          LinkedService.all,
          integSt,
          sess,
          audit,
          http,
          cfg.publicUrl,
          skiwi.auth.SecretBox.fromSecret(cfg.appSecret),
          integCache
        )
        oauthR = new OAuthProviderRoutes(integSt, ops, store, sess, audit, cfg.api.cookieSecure, cfg.oauthClients)
        _ <- Resource.eval(cfg.oauthClientSeed.traverse_(oauthR.seed))
        inboundR = new InboundRoutes(
          store,
          new InboundStore(xa),
          dbSt,
          docs,
          commentSt,
          ops,
          sess,
          audit,
          cfg.publicUrl,
          Some(mentions),
          Some(hooks),
          Some(autos),
          inRate
        )
        lockR = new LockRoutes(store, sess, audit, collab.accessChanged)
        backupSt = new BackupStore(xa)
        backupSvc = new BackupService(store, docs, attachSt, dbSt, backupSt, blobs, cfg.publicUrl, cfg.taskTimezone)
        backupR = new BackupRoutes(backupSt, backupSvc, blobs, sess, audit)
        bulkR = new BulkRoutes(store, sess, audit, collab.accessChanged, Some(hooks), Some(autos))
        scim = cfg.scimToken.map(t => new ScimRoutes(new ScimStore(xa), t, audit, collab.accessChanged, cfg.publicUrl))
        // 定期処理（停止時にキャンセルする）
        loops = List[IO[Unit]](
          purgeLoop(store),
          notificationPurgeLoop(ops),
          trash.purgeLoop,
          taskDueLoop(ops, cfg.taskTimezone),
          aclExpiryLoop(store, collab.accessChanged),
          collab.revalidateLoop,
          notifier.loop,
          hooks.loop,
          autos.loop,
          backupSvc.loop,
          hooks.purgeLoop,
          collab.busLoop
        ) ++ Option.when(blobs.isDefined)(indexer.loop)
        _ <- loops.traverse_(_.background)
        health = healthRoutes(xa, cluster.map(_.self)) <+> clientConfigRoutes(cfg)
        traced <- HttpTelemetry.middleware(tracedPaths)
        _ <- EmberServerBuilder.default[IO]
          .withHost(Host.fromString(cfg.httpBind).getOrElse(ipv4"0.0.0.0"))
          .withPort(Port.fromInt(cfg.port).getOrElse(port"8080"))
          // 停止時は接続（WebSocketを含む）を長く待たない。クライアントは他ノードへ再接続する
          .withShutdownTimeout(2.seconds)
          .withHttpWebSocketApp { wsb =>
            val rest =
              health <+>
                new AuthRoutes(
                  oidc,
                  store,
                  sess,
                  audit,
                  cfg.api.scimManagesGroups,
                  cfg.publicUrl,
                  cfg.devLogin
                ).routes <+> api.routes <+> opsR.routes <+>
                RumRoutes.routes <+> new PushRoutes(pushSt, push, sess).routes <+>
                search.routes <+> new ContentEditRoutes(store, docs, ops, sess, audit, collab.injectUpdate).routes <+>
                attach.routes <+> shareR.routes <+> trash.routes <+> extra.routes <+> commentR.routes <+>
                cssR.routes <+> copyR.routes <+> bulkR.routes <+> lockR.routes <+> autoR.routes <+> inboundR.routes <+>
                formR.routes <+> unfurlR.routes <+> integR.routes <+> new AvatarRoutes(ops, sess).routes <+>
                oauthR.routes <+> backupR.routes <+> hookR.routes <+> dbR.routes <+> collab.routes(wsb) <+>
                scim.fold(HttpRoutes.empty[IO])(_.routes)
            // MCP（/mcp）。ツールは REST API を同じトークンで内部から呼ぶ
            val backend = rest <+> new McpRoutes(rest, sess, cfg.publicUrl).routes
            val all = cfg.staticDir.fold(backend)(d => backend <+> spa(d, shareR))
            traced(Security.headers(cfg.publicUrl)(errors(Security.bodyLimit(Security.csrf(
              cfg.publicUrl,
              cfg.api.cookieName,
              cfg.devLogin
            )(all)).orNotFound)))
          }
          .build
        _ <- Resource.eval(
          sbirds.observe.Log.println(s"[skiwi] listening on ${cfg.httpBind}:${cfg.port} (${cfg.publicUrl})")
        )
        // HTTPサーバーが待ち受けを始めてからクラスタに登録する。停止時は逆順: ハートビート停止 → 登録削除 → サーバー停止
        _ <- Resource.onFinalize(cluster.traverse_(_.leave))
        _ <- cluster.traverse_(_.loop(collab.rebalance).background)
        _ <- cluster.traverse_(_ => collab.rebalanceLoop.background)
      } yield ()).useForever
    } yield ()

  /** 起動時、DB（Patroniのプライマリ）が使えるまで待ってからマイグレーションする。3ノード同時起動でもFlywayのロックで1回だけ適用される。 */
  private def migrateWithRetry(
    cfg: AppConfig,
    attempt: Int = 1
  ): IO[Int] =
    Database.migrate(cfg.db).handleErrorWith { e =>
      if attempt >= 60 then IO.raiseError(e)
      else
        sbirds.observe.Log.println(s"[skiwi] database not ready (${e.getMessage.take(120)}); retry $attempt/60") *>
          IO.sleep(2.seconds) *> migrateWithRetry(cfg, attempt + 1)
    }

  /** DB から定期的に読むメトリクス（skorimako のダッシュボード・アラート用）。 */
  private def gauges(
    xa: doobie.Transactor[IO]
  ): Resource[IO, Unit] = {
    def q(
      sqlText: doobie.Fragment
    ): IO[Long] = sqlText.query[Long].unique.transact(xa)
    List(
      Metrics.gauge("skiwi.users.active", "有効なユーザーの数")(q(sql"select count(*) from app_user where not disabled")),
      Metrics.gauge(
        "skiwi.users.signed_in",
        "ログイン中（有効なセッションがある）のユーザーの数"
      )(q(sql"select count(distinct user_id) from session where expires_at > now()")),
      Metrics.gauge(
        "skiwi.workspaces.total",
        "ワークスペースの数（ゴミ箱を除く）"
      )(q(sql"select count(*) from workspace where deleted_at is null")),
      Metrics.gauge("skiwi.pages.total", "ページの数（ゴミ箱を除く）")(q(sql"select count(*) from page where deleted_at is null")),
      Metrics.gauge(
        "skiwi.pages.edited_24h",
        "24時間以内に更新されたページの数"
      )(q(sql"select count(*) from page where deleted_at is null and updated_at > now() - interval '24 hours'")),
      Metrics.gauge(
        "skiwi.shares.public",
        "ゲスト向けに公開中のページの数"
      )(q(sql"select count(*) from page_share where expires_at is null or expires_at > now()")),
      Metrics.gauge(
        "skiwi.mail.outbox",
        "送信待ちのメールの数"
      )(q(sql"select count(*) from mail_outbox where sent_at is null and not skipped")),
      Metrics.gauge(
        "skiwi.webhook.pending",
        "送信待ちの Webhook の数"
      )(q(sql"select count(*) from webhook_delivery where sent_at is null and not skipped"))
    ).sequence_
  }

  /** 画面が使う設定（ログイン不要。秘密は含めない） */
  private def clientConfigRoutes(
    cfg: AppConfig
  ): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "api" / "client-config" =>
      Ok(io.circe.Json.obj(
        "drawioUrl" -> io.circe.Json.fromString(cfg.drawioUrl),
        "devLogin" -> io.circe.Json.fromBoolean(cfg.devLogin && cfg.oidc.isEmpty)
      ))
  }

  /** ロードバランサー(HAProxy)用のヘルスチェック。DB(プライマリ)に書けない状態なら 503。 */
  private def healthRoutes(
    xa: doobie.Transactor[IO],
    node: Option[String]
  ): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "healthz" =>
      sql"select not pg_is_in_recovery()".query[Boolean].unique.transact(xa).timeout(3.seconds).attempt.flatMap {
        case Right(true) => Ok(s"ok ${node.getOrElse("")}".trim)
        case Right(false) => Errors.respond(ErrorCode.HealthNotPrimary)
        // 例外の内容（接続先など）は外に出さず、ログにだけ残す
        case Left(e) => Errors.report(ErrorCode.HealthDatabaseUnavailable, e.getMessage, e) *>
            ServiceUnavailable(ErrorDto(
              ErrorCode.HealthDatabaseUnavailable.message,
              Some(ErrorCode.HealthDatabaseUnavailable.code)
            ))
      }
  }

  private def blobStore(
    cfg: AppConfig
  ): Resource[IO, Option[sbirds.storage.BlobStore]] = cfg.s3 match {
    case Some(c) => S3BlobStore.resource(c).evalTap(_.ensureBucket).map(b => Some(b))
    case None => Resource.eval(sbirds.observe.Log.println("[skiwi] WARNING: S3_ENDPOINT が未設定のため、添付ファイルは使えません").as(None))
  }

  private def startupNotes(
    cfg: AppConfig
  ): IO[Unit] =
    (if cfg.smtp.isEmpty then sbirds.observe.Log.println("[skiwi] WARNING: SMTP_HOST が未設定のため、メールは送りません")
     else IO.unit) *>
      (if cfg.scimToken.isEmpty then
         sbirds.observe.Log.println("[skiwi] SCIM は使いません（SCIM_TOKEN が未設定）。グループの所属はログイン時の OIDC のクレームから同期します")
       else sbirds.observe.Log.println("[skiwi] SCIM を /scim/v2 で受け付けます（グループの所属は SCIM を正とします）")) *>
      IO.whenA(
        cfg.oidc.isEmpty
      )(sbirds.observe.Log.println("[skiwi] WARNING: OIDC が未設定です。開発用のログイン（DEV_LOGIN）を使います。本番では使わないでください"))

  /** 例外をコード付きのエラーの応答にする（大きすぎる本文は 413、予期しない例外は 500）. */
  private def errors(
    app: HttpApp[IO]
  ): HttpApp[IO] =
    Errors.handle(
      Kleisli(req =>
        app.run(req).adaptError { case e: Security.BodyTooLarge => AppError(ErrorCode.RequestTooLarge, e.getMessage) }
      ),
      ErrorCode.Unexpected
    )

  /** /s/{key} または /s/{key}/{pageKey} */
  private object GuestPath {
    def unapply(
      path: Uri.Path
    ): Option[(String, Option[String])] =
      path.segments.map(_.decoded()).toList match {
        case "s" :: key :: Nil => Some((key, None))
        case "s" :: key :: page :: Nil => Some((key, Some(page).filter(_.nonEmpty)))
        case _ => None
      }
  }

  /** ビルド済みフロントエンドの配信。存在しないパスは index.html にフォールバック（SPA）。 */
  /**
   * 画面（index.html）の CSP. スクリプトは自分のオリジン（/assets）・index.html の中のインラインのもの（ハッシュ）・RUM の送り先だけ.
   * wasm-unsafe-eval: ホワイトボード（Excalidraw）がフォントの書き出しに WebAssembly を使う。ほかの読み込み先は制限しない（埋め込み・画像・draw.io）
   */
  private def spaCsp(
    html: String
  ): String = {
    val inline = "(?s)<script>(.*?)</script>".r.findAllMatchIn(html).map { m =>
      val d = java.security.MessageDigest.getInstance(
        "SHA-256"
      ).digest(m.group(1).getBytes(java.nio.charset.StandardCharsets.UTF_8))
      s"'sha256-${java.util.Base64.getEncoder.encodeToString(d)}'"
    }.toList
    val scripts = ("'self'" :: inline) ++ RumRoutes.origin.toList :+ "'wasm-unsafe-eval'"
    s"script-src ${scripts.mkString(" ")}; object-src 'none'; base-uri 'self'; frame-ancestors 'self'"
  }

  private val SandboxCsp =
    "sandbox allow-scripts allow-popups allow-popups-to-escape-sandbox allow-forms allow-top-navigation-by-user-activation"

  private def spa(
    dir: String,
    shares: ShareRoutes
  ): HttpRoutes[IO] = {
    val files = fileService[IO](FileService.Config(dir))
    def readIndex = fs2.io.file.Files[IO].readUtf8(Path(dir) / "index.html").compile.string
    def html(
      body: String
    ) = Response[IO](Status.Ok).withEntity(body)(using EntityEncoder.stringEncoder)
      .withContentType(headers.`Content-Type`(
        MediaType.text.html,
        Charset.`UTF-8`
      )).putHeaders("Content-Security-Policy" -> spaCsp(body))
    val index = HttpRoutes.of[IO] {
      // 公開ページ: タイトル・OGP・検索エンジン向けの指定を埋め込む
      case GET -> GuestPath(key, pageKey) =>
        readIndex.attempt.flatMap {
          case Left(_) => Errors.respond(ErrorCode.ItemNotFound)
          case Right(h) =>
            shares.headFor(key, pageKey).handleError(_ => None).map(head => html(shares.renderHead(h, head)))
        }
      case req @ GET -> _
          if !req.pathInfo.renderString.startsWith("/api") && !req.pathInfo.renderString.startsWith("/scim") =>
        readIndex.attempt.flatMap(_.fold(_ => Errors.respond(ErrorCode.ItemNotFound), h => IO.pure(html(h))))
    }
    // index.html は毎回確認させる（更新後に古い画面が残らないように）。/assets/ はファイル名に内容のハッシュが入るので長期キャッシュ
    HttpRoutes[IO] { req =>
      val cc =
        if req.pathInfo.renderString.startsWith("/assets/") then "public, max-age=31536000, immutable" else "no-cache"
      // index.html は（/ と /index.html も）CSP を付ける処理を通す
      val path = req.pathInfo.renderString
      // /sandbox.html: ゲスト向けの HTML を表示する枠（parts/SandboxedHtml）。別オリジン扱いにして、画面の Cookie・API に触れさせない
      val sandbox: List[Header.ToRaw] =
        if path == "/sandbox.html" then List("Content-Security-Policy" -> SandboxCsp) else Nil
      (if path == "/" || path == "/index.html" then index else files <+> index).run(
        req
      ).map(_.putHeaders("Cache-Control" -> cc).putHeaders(sandbox*))
    }
  }

  /** 期限の近いToDoを1時間ごとに確認して通知する（同じToDoには1回だけ。複数ノードで動いても重複しにくいよう少しずらす） */
  private def taskDueLoop(
    ops: OpsStore,
    tz: String
  ): IO[Unit] =
    Periodic.run(1.minute, 1.hour, ErrorCode.TaskDueCheckFailed)(ops.enqueueTaskDue(tz))

  /** 期限を過ぎた権限を1分ごとに消し、接続中の画面の権限を確かめ直す。 */
  private def aclExpiryLoop(
    store: Store,
    accessChanged: IO[Unit]
  ): IO[Unit] =
    Periodic.run(1.minute, 1.minute, ErrorCode.AclExpiryFailed)(store.purgeExpiredAcl.flatMap(n =>
      IO.whenA(n > 0)(accessChanged)
    ))

  /** 古い通知を1時間ごとに消す（既読は90日、未読でも1年を過ぎたもの） */
  private def notificationPurgeLoop(
    ops: OpsStore
  ): IO[Unit] =
    Periodic.run(5.minutes, 1.hour, ErrorCode.NotificationPurgeFailed)(ops.purgeNotifications().void)

  /** 期限切れセッションを定期的に削除する（基準の時刻は毎回取り直す） */
  private def purgeLoop(
    store: Store
  ): IO[Unit] =
    Periodic.run(
      10.minutes,
      10.minutes,
      ErrorCode.SessionPurgeFailed
    )(IO.realTimeInstant.flatMap(store.purgeExpiredSessions))
}
