package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import sbirds.auth.Crypto
import sbirds.oauth.{OAuthCode, OAuthProvider}
import skiwi.core.{AuthSession, AuthVia, ErrorCode}
import skiwi.db.{IntegrationStore, OpsStore, Store}

import java.util.{Base64, UUID}

/** 管理画面でのクライアント（skea）の登録（client_secret は返さない。設定済みかどうかだけ） */
final case class OAuthClientDto(clientId: String,
                                clientSecretSet: Boolean,
                                redirectUri: String,
                                name: String,
                                connections: Int) derives Encoder.AsObject

/** clientSecret: 空か省略なら今の値のまま（新しく登録するときは必須） */
final case class SaveOAuthClientReq(clientId: String, clientSecret: Option[String], redirectUri: String, name: String)
  derives Decoder
final case class PageLookupDto(id: UUID, workspaceId: UUID, title: String, icon: Option[String])
  derives Encoder.AsObject

/**
 * 登録済みのクライアント（sbirds.oauth.OAuthClient. secret には client_secret の SHA-256 のハッシュ〈Base64〉だけを入れる）.
 * scopes が空なら、管理画面で登録したクライアント（skea）で、ページの一括参照だけのトークンを発行する.
 * 環境変数 OAUTH_CLIENTS のクライアント（skakapo など）は、scopes（read・write）の中で求められたスコープの普通のトークンを発行する
 */
type OAuthClient = sbirds.oauth.OAuthClient
val OAuthClient = sbirds.oauth.OAuthClient

object OAuthProviderRoutes {
  val CodeTtlSeconds = 60L
  val MaxLookupIds = 100

  /** sbirds.oauth.OAuthProvider が付ける名前（<service>_oauth_csrf） */
  val CsrfCookie = "skiwi_oauth_csrf"
  val ClientIdRe = "^[A-Za-z0-9._-]{1,100}$".r

  /** integration_setting のキー */
  private object Keys {
    val id = "oauth_client.client_id"
    val secret = "oauth_client.secret_hash"
    val redirect = "oauth_client.redirect_uri"
    val name = "oauth_client.name"
  }

  def hashSecret(secret: String): String = Base64.getEncoder.encodeToString(Crypto.sha256(secret))

  /** 環境変数 OAUTH_CLIENTS のクライアントか（普通のトークンを発行する）. そうでなければ、ページの一括参照だけ */
  def full(c: OAuthClient): Boolean = c.scopes.nonEmpty

  /** 入力を確かめて整える（エラーは画面に出す文）。hasSecret: すでに client_secret が登録されているか */
  def validate(b: SaveOAuthClientReq, hasSecret: Boolean): Either[String, SaveOAuthClientReq] = {
    val id = b.clientId.trim
    val redirect = b.redirectUri.trim
    val name = b.name.trim
    val secret = b.clientSecret.map(_.trim).filter(_.nonEmpty)
    val redirectOk = redirect.length <= 2000 && Uri.fromString(redirect).toOption.exists(u =>
      u.scheme.exists(sc => sc == Uri.Scheme.http || sc == Uri.Scheme.https) && u.host.nonEmpty && u.fragment.isEmpty
    )
    if !ClientIdRe.matches(id) then Left("client_id は英数字と . _ - の100文字までにしてください")
    else if !redirectOk then Left("redirect_uri は http:// または https:// で始まる URL にしてください（# は付けられません）")
    else if name.isEmpty || name.length > 50 then Left("表示名は1〜50文字にしてください")
    else if secret.isEmpty && !hasSecret then Left("client_secret を入力してください")
    else if secret.exists(s => s.length < 16 || s.length > 500) then Left("client_secret は16〜500文字にしてください")
    else Right(SaveOAuthClientReq(id, secret, redirect, name))
  }
}

/**
 * skea → skiwi の連携（docs/skea-integration.md）。skiwi が OAuth 2.0 の認可サーバー（認可コード + PKCE。RFC 6749・7636・7009）になり、
 * skea が各ユーザーの権限でページのタイトルを読めるようにする。発行するトークンはそのユーザーの読み取り専用の API トークン
 * （「<表示名> 連携」。期限なし。API トークンの画面から失効できる）。クライアントは管理者が1つ登録する（integration_setting）。
 * 認可の流れ（/oauth/authorize・/oauth/token・/oauth/revoke）は sbirds.oauth.OAuthProvider。ここでは skiwi の部分だけを差し込む。
 */
final class OAuthProviderRoutes(st: IntegrationStore,
                                ops: OpsStore,
                                store: Store,
                                protected val sessions: SessionAuth,
                                audit: Audit,
                                cookieSecure: Boolean,
                                extraClients: List[OAuthClient] = Nil) extends RouteSupport {

  import OAuthProviderRoutes.*

  val routes: HttpRoutes[IO] = new OAuthProvider(host).routes <+> HttpRoutes.of[IO] {

    // ------------------------------------------------------------ 管理者の設定
    case req @ GET -> Root / "api" / "admin" / "integrations" / "skea" / "client" => getAdminIntegrationsSkeaClient(req)
    case req @ PUT -> Root / "api" / "admin" / "integrations" / "skea" / "client" => putAdminIntegrationsSkeaClient(req)
    case req @ DELETE -> Root / "api" / "admin" / "integrations" / "skea" / "client" =>
      deleteAdminIntegrationsSkeaClient(req)
    case req @ POST -> Root / "api" / "pages" / "lookup" => postPagesLookup(req)
  }

  private def getAdminIntegrationsSkeaClient(req: Request[IO]): IO[Response[IO]] =
    admin(req)(_ => client.flatMap(clientDto).flatMap(Ok(_)))

  private def putAdminIntegrationsSkeaClient(req: Request[IO]): IO[Response[IO]] =
    admin(req) { s =>
      body[SaveOAuthClientReq](req) { raw =>
        client.flatMap { old =>
          validate(raw, hasSecret = old.exists(_.id == raw.clientId.trim)) match {
            case Left(msg) => err(ErrorCode.InvalidInput, msg)
            case Right(v) =>
              for {
                _ <- save(v, Some(s.user.id))
                // client_id が変わったら、前のクライアントに発行したトークンは失効させる
                n <- old.filter(_.id != v.clientId).fold(IO.pure(0))(o => ops.revokeAllOAuthTokens(o.id))
                _ <- audit.log(
                  s,
                  "integration.oauth_client.save",
                  "integration",
                  v.clientId,
                  Json.obj(
                    "redirectUri" -> v.redirectUri.asJson,
                    "name" -> v.name.asJson,
                    "secretChanged" -> v.clientSecret.isDefined.asJson,
                    "tokensRevoked" -> n.asJson
                  )
                )
                dto <- client.flatMap(clientDto)
                res <- Ok(dto)
              } yield res
          }
        }
      }
    }

  private def deleteAdminIntegrationsSkeaClient(req: Request[IO]): IO[Response[IO]] =
    admin(req) { s =>
      client.flatMap {
        case None => NoContent()
        case Some(c) =>
          st.setSettings(
            Map(Keys.id -> None, Keys.secret -> None, Keys.redirect -> None, Keys.name -> None),
            Some(s.user.id)
          ) *>
            ops.revokeAllOAuthTokens(c.id).flatMap(n =>
              audit.log(
                s,
                "integration.oauth_client.delete",
                "integration",
                c.id,
                Json.obj("tokensRevoked" -> n.asJson)
              )
            ) *> NoContent()
      }
    }

  private def postPagesLookup(req: Request[IO]): IO[Response[IO]] =
    authed(req) { s =>
      body[Json](req) { j =>
        j.hcursor.get[List[String]]("ids") match {
          case Left(_) => err(ErrorCode.LookupIdsRequired)
          case Right(ids) if ids.size > MaxLookupIds => err(ErrorCode.LookupTooManyIds, s"ids は${MaxLookupIds}件までです")
          case Right(ids) =>
            val uuids = ids.flatMap(i => scala.util.Try(UUID.fromString(i.trim)).toOption).distinct
            store.visiblePages(uuids, s).flatMap(ps =>
              Ok(Json.obj("pages" ->
                ps.map((p, _) => PageLookupDto(p.id, p.workspaceId, p.title, p.icon.filter(_.nonEmpty))).asJson))
            )
        }
      }
    }

  // ---------------------------------------------------------------- クライアントの登録

  /** 登録済みのクライアント（揃っていなければ None） */
  def client: IO[Option[OAuthClient]] = st.settings("oauth_client.").map { m =>
    (m.get(Keys.id), m.get(Keys.secret), m.get(Keys.redirect)).mapN((id, sec, uri) =>
      OAuthClient(id, sec, uri, m.getOrElse(Keys.name, id), Nil)
    )
  }

  /** 管理画面のクライアントと、環境変数 OAUTH_CLIENTS のクライアント（同じ ID なら環境変数のものは使わない） */
  def clients: IO[List[OAuthClient]] = client.map(c => c.toList ++ extraClients.filterNot(x => c.exists(_.id == x.id)))

  private def save(v: SaveOAuthClientReq, by: Option[UUID]): IO[Unit] =
    st.setSettings(
      Map(Keys.id -> Some(v.clientId), Keys.redirect -> Some(v.redirectUri), Keys.name -> Some(v.name))
        ++ v.clientSecret.map(sec => Keys.secret -> Some(hashSecret(sec))),
      by
    )

  /** 起動時の初期登録（環境変数 OAUTH_CLIENT_*）。まだ登録されていないときだけ書く（管理画面での変更を上書きしない） */
  def seed(raw: SaveOAuthClientReq): IO[Unit] =
    client.flatMap {
      case Some(_) => IO.unit
      case None => validate(raw, hasSecret = false) match {
          case Left(msg) => sbirds.observe.Log.println(s"[skiwi] WARNING: OAUTH_CLIENT_* が正しくないため使いません: $msg")
          case Right(v) => save(v, None) *>
              audit.system("system", "integration.oauth_client.seed", "integration", v.clientId) *>
              sbirds.observe.Log.println(s"[skiwi] registered OAuth client '${v.clientId}' from OAUTH_CLIENT_*")
        }
    }

  private def clientDto(c: Option[OAuthClient]): IO[OAuthClientDto] = c match {
    case None => IO.pure(OAuthClientDto("", clientSecretSet = false, "", "skea", 0))
    case Some(c) =>
      ops.countOAuthTokens(c.id).map(n => OAuthClientDto(c.id, clientSecretSet = true, c.redirectUri, c.name, n))
  }

  // ---------------------------------------------------------------- 認可の流れ（sbirds.oauth.OAuthProvider）に差し込む、skiwi の部分

  private lazy val host: OAuthProvider.Host[AuthSession] = new OAuthProvider.Host[AuthSession] {
    val settings = OAuthProvider.Settings(
      "skiwi",
      cookieSecure,
      codeTtlSeconds = CodeTtlSeconds,
      revokeNoStore = true,
      formValue = v => Some(v).filter(_.nonEmpty),
      unreadableForm = "要求の形が正しくありません"
    )
    val codes = st

    def client(id: String): IO[Option[OAuthClient]] = clients.map(_.find(_.id == id))

    // client_secret はハッシュだけを持つ
    override def secretMatches(c: OAuthClient, secret: String): Boolean =
      java.security.MessageDigest.isEqual(Base64.getDecoder.decode(c.secret), Crypto.sha256(secret))

    // 許可はブラウザのログイン（Cookie）でだけ行う（API トークンでトークンを作らせない）
    def browserSession(req: Request[IO]): IO[Option[AuthSession]] =
      sessions.fromRequest(req).map(_.filter(_.via == AuthVia.Session))

    override def decisionSession(req: Request[IO]): IO[Either[String, AuthSession]] =
      sessions.fromRequest(req).map {
        case None => Left(settings.loginExpired)
        case Some(s) if s.via != AuthVia.Session => Left("ブラウザでログインして許可してください")
        case Some(s) => Right(s)
      }

    def userId(s: AuthSession): UUID = s.user.id

    // 一括参照だけのクライアントは read だけ
    override def scopes(c: OAuthClient, requested: Option[String]): Option[List[String]] =
      OAuthProvider.requestedScopes(if full(c) then c.scopes else List("read"), requested)

    def consent(req: Request[IO], c: OAuthClient, s: AuthSession, scopes: List[String]): IO[OAuthProvider.Consent] = {
      val name = Html.escape(c.name)
      val write = full(c) && scopes.contains("write")
      val what = if !full(c) then "skiwi のページのタイトルを読み取ります"
      else if write then "skiwi のページを読み書きします" else "skiwi のページを読み取ります"
      val note = if write then "<li>ページの作成・変更を、あなたの名前で行います</li>" else "<li>読み取り専用です（ページの作成・変更・削除はしません）</li>"
      IO.pure(OAuthProvider.Consent(
        s"${c.name} との連携",
        s"""<h1>$name との連携</h1>
           |<p><b>$name</b> が、あなた（${Html.escape(s.user.name)}）の権限で $what。</p>
           |<ul class="muted">$note<li>あなたが見られるページだけが対象です</li>
           |<li>「APIトークン」の画面から、いつでも連携を解除できます</li></ul>""".stripMargin
      ))
    }

    /**
     * skiwi の色の枠. Referrer-Policy は same-origin（no-referrer だとフォームの POST に Origin: null が付き、Security.csrf に弾かれる）.
     * CSP に form-action は付けない（送信後のリダイレクトの連鎖にも効くため、連携元がさらに別の origin へ戻すと、許可の後の戻りが止まる）
     */
    override def page(title: String, body: String, status: Status): IO[Response[IO]] = {
      val html =
        s"""<!DOCTYPE html><html lang="ja"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
           |<title>${Html.escape(title)} | skiwi</title><style>
           |:root{color-scheme:light dark}body{margin:0;font-family:system-ui,-apple-system,"Hiragino Sans","Noto Sans JP",sans-serif;background:#f5f5f4;color:#1f2937;
           |display:flex;min-height:100vh;align-items:center;justify-content:center;padding:1rem;box-sizing:border-box}
           |@media(prefers-color-scheme:dark){body{background:#18181b;color:#e5e7eb}.card{background:#27272a!important;border-color:#3f3f46!important}}
           |.card{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:1.6rem 1.8rem;max-width:460px;width:100%;box-shadow:0 10px 30px rgba(0,0,0,.12)}
           |h1{font-size:1.15rem;margin:0 0 .8rem}p{line-height:1.7;margin:.4rem 0}.muted{color:#6b7280;font-size:.85rem}.logo{font-weight:800;margin-bottom:.6rem}
           |.actions{display:flex;gap:.6rem;justify-content:flex-end;margin-top:1.2rem}button{font:inherit;border-radius:8px;padding:.5rem 1.1rem;cursor:pointer;
           |border:1px solid #d1d5db;background:transparent;color:inherit}button.primary{background:#2563eb;border-color:#2563eb;color:#fff}
           |ul{padding-left:1.2rem;margin:.4rem 0}</style></head><body><div class="card"><div class="logo">skiwi</div>$body</div></body></html>""".stripMargin
      IO.pure(OAuthProvider.htmlResponse(html, status).putHeaders("Referrer-Policy" -> "same-origin"))
    }

    override def authorized(req: Request[IO], s: AuthSession, c: OAuthClient, scopes: List[String]): IO[Unit] =
      audit.log(s, "oauth.authorize", "integration", c.id)

    def issue(req: Request[IO], c: OAuthClient, oc: OAuthCode): IO[OAuthProvider.Token] = {
      val token = "skiwi_" + Crypto.randomToken(32)
      // 許可し直したら、そのユーザーにこのクライアントが以前に発行したトークンは失効する
      // 一括参照だけのクライアントは read. OAUTH_CLIENTS のクライアントは、許可したスコープ（write を含めば読み書き）
      val scope = if full(c) && oc.scope.split(' ').contains("write") then "write" else "read"
      ops.replaceOAuthToken(oc.userId, c.id, s"${c.name} 連携", Crypto.sha256(token), scope, full(c)).flatMap(t =>
        audit.system(
          "system",
          "token.create",
          "token",
          t.id,
          Json.obj("userId" -> oc.userId.asJson, "client" -> c.id.asJson, "scope" -> scope.asJson)
        ).as(OAuthProvider.Token(token, if scope == "write" then "read write" else "read"))
      )
    }

    def revoke(req: Request[IO], c: OAuthClient, token: String): IO[Unit] =
      ops.revokeOAuthToken(Crypto.sha256(token), c.id).flatMap(_.traverse_((uid, t) =>
        audit.system("system", "token.revoke", "token", t.id, Json.obj("userId" -> uid.asJson, "client" -> c.id.asJson))
      ))
  }
}
