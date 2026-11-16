package skiwi.app

import cats.effect.IO
import cats.syntax.all.*
import skiwi.api.{ApiConfig, SaveOAuthClientReq}
import skiwi.auth.{OidcConfig, SecretBox}
import skiwi.db.DbConfig
import skiwi.collab.ClusterConfig
import skiwi.notify.SmtpConfig
import skiwi.storage.S3Config

final case class AppConfig(
    port: Int,
    db: DbConfig,
    oidc: OidcConfig,
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
    /** 外部サービスのトークンなどを暗号化する鍵（ENCRYPTION_KEY）。なければ skea・skawau 連携は使えない */
    secretBox: Option[SecretBox] = None,
    /** skea → skiwi の連携のクライアントの初期登録（OAUTH_CLIENT_ID・OAUTH_CLIENT_SECRET・OAUTH_CLIENT_REDIRECT_URI・OAUTH_CLIENT_NAME）。
      * まだ登録されていないときだけ使う（管理画面での変更を優先する） */
    oauthClientSeed: Option[SaveOAuthClientReq] = None
)
object AppConfig:
  val DefaultTrashRetentionDays = 30
  val DefaultTimezone           = "Asia/Tokyo"
  val DefaultDrawioUrl          = "https://embed.diagrams.net"

/** 設定は環境変数から読む。 */
object Config:
  /** 空でない値 */
  private def opt(name: String): Option[String] = sys.env.get(name).filter(_.nonEmpty)
  private def int(name: String): Option[Int]    = sys.env.get(name).flatMap(_.toIntOption)
  /** true と書かれているときだけ有効（既定は無効） */
  private def enabled(name: String): Boolean  = sys.env.get(name).exists(_.trim.equalsIgnoreCase("true"))
  /** false と書かれているときだけ無効（既定は有効） */
  private def disabled(name: String): Boolean = sys.env.get(name).exists(_.trim.equalsIgnoreCase("false"))

  private def required(name: String): Either[String, String] =
    opt(name).toRight(s"missing environment variable: $name")

  def load: IO[AppConfig] = IO.fromEither(
    (for
      dbUrl  <- required("DATABASE_URL")
      dbUser <- required("DATABASE_USER")
      dbPass <- required("DATABASE_PASSWORD")
      issuer <- required("OIDC_ISSUER")
      cid    <- required("OIDC_CLIENT_ID")
      secret <- required("OIDC_CLIENT_SECRET")
      pub    <- required("PUBLIC_URL")
      // 設定されているのに読めない鍵は起動時にエラーにする（黙って連携が使えなくなるのを防ぐ）
      box    <- opt("ENCRYPTION_KEY").traverse(SecretBox.fromBase64)
    yield
      val base = pub.stripSuffix("/")
      // 秘密値: 短いもの・テンプレートのまま（change-me）のものは起動時にエラーにする
      def secretOpt(name: String, min: Int): Option[String] = opt(name).map { v =>
        if v.length < min || v.toLowerCase.contains("change-me") then
          throw new IllegalArgumentException(s"$name must be at least $min random characters (not the template value)")
        v
      }
      // SCIM: 設定するとSCIMエンドポイントを有効化し、グループ所属はSCIMを正とする
      val scimToken = secretOpt("SCIM_TOKEN", 24)
      // 3ノード構成: NODE_URL（他ノードから届くこのノードのURL）を設定すると有効。CLUSTER_SECRET は全ノード共通の秘密値
      val cluster = opt("NODE_URL").map { url =>
        ClusterConfig(
          nodeId = opt("NODE_ID").getOrElse(java.net.InetAddress.getLocalHost.getHostName),
          internalUrl = url.stripSuffix("/"),
          secret = secretOpt("CLUSTER_SECRET", 16)
            .getOrElse(throw new IllegalArgumentException("CLUSTER_SECRET (16+ chars) is required when NODE_URL is set"))
        )
      }
      AppConfig(
        port = int("HTTP_PORT").getOrElse(8080),
        db = DbConfig(dbUrl, dbUser, dbPass),
        oidc = OidcConfig(
          issuer = issuer, clientId = cid, clientSecret = secret,
          redirectUri = s"$base/auth/callback",
          groupsClaim = sys.env.getOrElse("OIDC_GROUPS_CLAIM", "groups")
        ),
        api = ApiConfig(
          adminGroups = sys.env.get("ADMIN_GROUPS").map(_.split(',').map(_.trim).filter(_.nonEmpty).toSet).getOrElse(Set.empty),
          cookieSecure = base.startsWith("https://"),
          scimManagesGroups = scimToken.isDefined,
          publicSharing = !disabled("PUBLIC_SHARING")
        ),
        staticDir = opt("STATIC_DIR"),
        s3 = opt("S3_ENDPOINT").map { ep =>
          S3Config(
            endpoint = ep,
            region = sys.env.getOrElse("S3_REGION", "us-east-1"),
            bucket = sys.env.getOrElse("S3_BUCKET", "skiwi"),
            accessKey = sys.env.getOrElse("S3_ACCESS_KEY", ""),
            secretKey = sys.env.getOrElse("S3_SECRET_KEY", ""),
            pathStyle = sys.env.get("S3_PATH_STYLE").forall(_ != "false") // 小文字の false だけ（前後の空白も見ない）
          )
        },
        maxUploadBytes = sys.env.get("MAX_UPLOAD_MB").flatMap(_.toLongOption).getOrElse(100L) * 1024 * 1024,
        publicUrl = base,
        scimToken = scimToken,
        smtp = opt("SMTP_HOST").map { h =>
          val tls = sys.env.getOrElse("SMTP_TLS", "starttls").toLowerCase
          SmtpConfig(
            host = h,
            port = int("SMTP_PORT").getOrElse(if tls == "ssl" then 465 else if tls == "none" then 25 else 587),
            user = opt("SMTP_USER"),
            password = opt("SMTP_PASSWORD"),
            tls = tls,
            from = sys.env.getOrElse("MAIL_FROM", s"skiwi@${h}")
          )
        },
        cluster = cluster,
        trashRetentionDays = int("TRASH_RETENTION_DAYS").filter(_ > 0).getOrElse(AppConfig.DefaultTrashRetentionDays),
        taskTimezone = opt("TASK_TIMEZONE").getOrElse(AppConfig.DefaultTimezone),
        webhookAllowHttp = enabled("WEBHOOK_ALLOW_HTTP"),
        webhookAllowPrivate = enabled("WEBHOOK_ALLOW_PRIVATE"),
        unfurlAllowPrivate = enabled("UNFURL_ALLOW_PRIVATE"),
        // 空にすると図を編集できない（空のまま使う）
        drawioUrl = sys.env.get("DRAWIO_URL").map(_.trim.stripSuffix("/")).getOrElse(AppConfig.DefaultDrawioUrl),
        secretBox = box,
        oauthClientSeed = (opt("OAUTH_CLIENT_ID"), opt("OAUTH_CLIENT_SECRET"), opt("OAUTH_CLIENT_REDIRECT_URI")).mapN((id, sec, uri) =>
          SaveOAuthClientReq(id, Some(sec), uri, opt("OAUTH_CLIENT_NAME").getOrElse("skea")))
      )
    ).left.map(msg => new IllegalArgumentException(msg))
  )
