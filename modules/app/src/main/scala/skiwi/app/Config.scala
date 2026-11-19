package skiwi.app

import cats.effect.*
import cats.syntax.all.*
import skiwi.api.*
import sbirds.auth.OidcConfig
import skiwi.collab.ClusterConfig
import skiwi.db.DbConfig
import sbirds.mail.SmtpConfig
import sbirds.storage.S3Config

/** 設定は環境変数から読む。 */
object Config {

  /** 空でない値 */
  private def opt(
    name: String
  ): Option[String] = sys.env.get(name).map(_.trim).filter(_.nonEmpty)
  private def int(
    name: String
  ): Option[Int] = opt(name).flatMap(_.toIntOption)

  /** true と書かれているときだけ有効（既定は無効） */
  private def enabled(
    name: String
  ): Boolean = sys.env.get(name).exists(_.trim.equalsIgnoreCase("true"))

  /** false と書かれているときだけ無効（既定は有効） */
  private def disabled(
    name: String
  ): Boolean = sys.env.get(name).exists(_.trim.equalsIgnoreCase("false"))

  private def required(
    name: String
  ): Either[String, String] =
    opt(name).toRight(s"環境変数 $name を設定してください")

  /** OAUTH_CLIENTS（JSON の配列）. 各要素は id・secret（16文字以上）・redirectUri（https:// か http://localhost）・name・scopes（read・write. 省くと read） */
  private def parseOAuthClients: Either[String, List[skiwi.api.OAuthClient]] =
    opt("OAUTH_CLIENTS").fold(Right(Nil)) { s =>
      io.circe.parser.parse(s).flatMap(_.as[List[io.circe.Json]]).left.map(_ =>
        "OAUTH_CLIENTS は JSON の配列で指定してください"
      ).flatMap(_.traverse { j =>
        val c = j.hcursor
        (c.get[String]("id"), c.get[String]("secret"), c.get[String]("redirectUri")).tupled.toOption
          .filter(
            (
              id,
              sec,
              uri
            ) =>
              skiwi.api.OAuthProviderRoutes.ClientIdRe.matches(id) && sec.length >= 16 &&
                (uri.startsWith("https://") || uri.startsWith("http://localhost"))
          )
          .map(
            (
              id,
              sec,
              uri
            ) =>
              skiwi.api.OAuthClient(
                id,
                skiwi.api.OAuthProviderRoutes.hashSecret(sec),
                uri,
                c.get[String]("name").getOrElse(id),
                c.get[List[String]]("scopes").toOption.map(_.filter(Set(
                  "read",
                  "write"
                ))).filter(_.nonEmpty).getOrElse(List("read"))
              )
          )
          .toRight("OAUTH_CLIENTS の各要素には id・secret（16文字以上）・redirectUri（https://）を指定してください")
      })
    }

  def load: IO[AppConfig] = IO.fromEither(
    (for {
      dbUrl <- required("DATABASE_URL")
      dbUser <- required("DATABASE_USER")
      dbPass <- required("DATABASE_PASSWORD")
      pub <- required("PUBLIC_URL")
      devLogin = enabled("DEV_LOGIN")
      _ <- Either.cond(!(devLogin && pub.startsWith("https://")), (), "DEV_LOGIN（開発用のログイン）は https の PUBLIC_URL では使えません")
      oidc <- (opt("OIDC_ISSUER"), opt("OIDC_CLIENT_ID"), opt("OIDC_CLIENT_SECRET")) match {
        case (Some(_), Some(_), Some(_)) if devLogin =>
          Left("DEV_LOGIN（開発用のログイン）は OIDC と同時には使えません。本番では DEV_LOGIN を外してください")
        case (Some(issuer), Some(cid), Some(secret)) =>
          Right(Some(OidcConfig(
            issuer = issuer,
            clientId = cid,
            clientSecret = secret,
            redirectUri = s"${pub.stripSuffix("/")}/auth/callback",
            groupsClaim = sys.env.getOrElse("OIDC_GROUPS_CLAIM", "groups")
          )))
        case (None, None, None) if devLogin => Right(None)
        case _ => Left("OIDC_ISSUER・OIDC_CLIENT_ID・OIDC_CLIENT_SECRET を設定してください（開発中は DEV_LOGIN=true で省略できます）")
      }
      secret <- opt("APP_SECRET") match {
        case Some(s) if s.toLowerCase.contains("change-me") => Left("APP_SECRET が雛形の値のままです。ランダムな値にしてください")
        case Some(s) if s.length >= 16 => Right(s)
        case Some(_) => Left("APP_SECRET は16文字以上にしてください")
        case None if devLogin => Right("skiwi-development-secret")
        case None => Left("APP_SECRET（16文字以上の秘密の値。全ノードで同じ値）を設定してください")
      }
      oauthClients <- parseOAuthClients
    } yield {
      val base = pub.stripSuffix("/")
      // 秘密値: 短いもの・テンプレートのまま（change-me）のものは起動時にエラーにする
      def secretOpt(
        name: String,
        min: Int
      ): Option[String] = opt(name).map { v =>
        if v.length < min || v.toLowerCase.contains("change-me") then
          throw new IllegalArgumentException(s"$name は${min}文字以上のランダムな値にしてください（雛形の値のままでは使えません）")
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
            .getOrElse(throw new IllegalArgumentException("NODE_URL を設定したときは CLUSTER_SECRET（16文字以上）も設定してください"))
        )
      }
      AppConfig(
        port = int("HTTP_PORT").getOrElse(8080),
        db = DbConfig(dbUrl, dbUser, dbPass, int("DATABASE_POOL_SIZE").getOrElse(10)),
        oidc = oidc,
        api = ApiConfig(
          adminGroups = opt("ADMIN_GROUPS").map(_.split(',').map(_.trim).filter(_.nonEmpty).toSet).getOrElse(Set.empty),
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
        // TIMEZONE（兄弟のサービスと同じ名前）。TASK_TIMEZONE は前の名前（どちらでもよい）
        taskTimezone = opt("TIMEZONE").orElse(opt("TASK_TIMEZONE")).getOrElse(AppConfig.DefaultTimezone),
        webhookAllowHttp = enabled("WEBHOOK_ALLOW_HTTP"),
        webhookAllowPrivate = enabled("WEBHOOK_ALLOW_PRIVATE"),
        unfurlAllowPrivate = enabled("UNFURL_ALLOW_PRIVATE"),
        // 空にすると図を編集できない（空のまま使う）
        drawioUrl = sys.env.get("DRAWIO_URL").map(_.trim.stripSuffix("/")).getOrElse(AppConfig.DefaultDrawioUrl),
        appSecret = secret,
        oauthClientSeed = (opt("OAUTH_CLIENT_ID"), opt("OAUTH_CLIENT_SECRET"), opt("OAUTH_CLIENT_REDIRECT_URI")).mapN(
          (
            id,
            sec,
            uri
          ) =>
            SaveOAuthClientReq(id, Some(sec), uri, opt("OAUTH_CLIENT_NAME").getOrElse("skea"))
        ),
        oauthClients = oauthClients,
        devLogin = devLogin,
        // 開発用のログインは誰でも管理者になれるので、既定ではこの機械の中からだけ受ける
        httpBind = opt("HTTP_BIND").getOrElse(if devLogin then "127.0.0.1" else "0.0.0.0")
      )
    }).left.map(msg => new IllegalArgumentException(msg))
  )
}
