package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.{`Content-Type`, Authorization, Location}
import skiwi.auth.Crypto
import skiwi.core.AuthVia
import skiwi.db.{IntegrationStore, OAuthCode, OpsStore, Store}

import java.time.Instant
import java.util.{Base64, UUID}

/** 管理画面でのクライアント（skea）の登録（client_secret は返さない。設定済みかどうかだけ） */
final case class OAuthClientDto(clientId: String, clientSecretSet: Boolean, redirectUri: String, name: String, connections: Int) derives Encoder.AsObject
/** clientSecret: 空か省略なら今の値のまま（新しく登録するときは必須） */
final case class SaveOAuthClientReq(clientId: String, clientSecret: Option[String], redirectUri: String, name: String) derives Decoder
final case class PageLookupDto(id: UUID, workspaceId: UUID, title: String, icon: Option[String]) derives Encoder.AsObject

/** 登録済みのクライアント（client_secret は SHA-256 のハッシュだけ持つ） */
final case class OAuthClient(id: String, secretHash: String, redirectUri: String, name: String):
  def secretMatches(secret: String): Boolean =
    Crypto.constantTimeEquals(Base64.getDecoder.decode(secretHash), Crypto.sha256(secret))

object OAuthProviderRoutes:
  val CodeTtlSeconds = 60L
  val MaxLookupIds   = 100
  val CsrfCookie     = "skiwi_oauth_csrf"
  val ClientIdRe     = "^[A-Za-z0-9._-]{1,100}$".r

  /** integration_setting のキー */
  private object Keys:
    val id       = "oauth_client.client_id"
    val secret   = "oauth_client.secret_hash"
    val redirect = "oauth_client.redirect_uri"
    val name     = "oauth_client.name"

  def hashSecret(secret: String): String = Base64.getEncoder.encodeToString(Crypto.sha256(secret))

  /** 入力を確かめて整える（エラーは画面に出す文）。hasSecret: すでに client_secret が登録されているか */
  def validate(b: SaveOAuthClientReq, hasSecret: Boolean): Either[String, SaveOAuthClientReq] =
    val id = b.clientId.trim
    val redirect = b.redirectUri.trim
    val name = b.name.trim
    val secret = b.clientSecret.map(_.trim).filter(_.nonEmpty)
    val redirectOk = redirect.length <= 2000 && Uri.fromString(redirect).toOption.exists(u =>
      u.scheme.exists(sc => sc == Uri.Scheme.http || sc == Uri.Scheme.https) && u.host.nonEmpty && u.fragment.isEmpty)
    if !ClientIdRe.matches(id) then Left("client_id は英数字と . _ - の100文字までにしてください")
    else if !redirectOk then Left("redirect_uri は http:// または https:// で始まる URL にしてください（# は付けられません）")
    else if name.isEmpty || name.length > 50 then Left("表示名は1〜50文字にしてください")
    else if secret.isEmpty && !hasSecret then Left("client_secret を入力してください")
    else if secret.exists(s => s.length < 16 || s.length > 500) then Left("client_secret は16〜500文字にしてください")
    else Right(SaveOAuthClientReq(id, secret, redirect, name))

/**
 * skea → skiwi の連携（docs/skea-integration.md）。skiwi が OAuth 2.0 の認可サーバー（認可コード + PKCE。RFC 6749・7636・7009）になり、
 * skea が各ユーザーの権限でページのタイトルを読めるようにする。発行するトークンはそのユーザーの読み取り専用の API トークン
 * （「<表示名> 連携」。期限なし。API トークンの画面から失効できる）。クライアントは管理者が1つ登録する（integration_setting）。
 */
final class OAuthProviderRoutes(st: IntegrationStore, ops: OpsStore, store: Store, protected val sessions: SessionAuth, audit: Audit,
                                cookieSecure: Boolean) extends RouteSupport:
  import OAuthProviderRoutes.*

  // ---------------------------------------------------------------- クライアントの登録

  /** 登録済みのクライアント（揃っていなければ None） */
  def client: IO[Option[OAuthClient]] = st.settings("oauth_client.").map { m =>
    (m.get(Keys.id), m.get(Keys.secret), m.get(Keys.redirect)).mapN((id, sec, uri) => OAuthClient(id, sec, uri, m.getOrElse(Keys.name, id)))
  }

  private def save(v: SaveOAuthClientReq, by: Option[UUID]): IO[Unit] =
    st.setSettings(Map(Keys.id -> Some(v.clientId), Keys.redirect -> Some(v.redirectUri), Keys.name -> Some(v.name))
      ++ v.clientSecret.map(sec => Keys.secret -> Some(hashSecret(sec))), by)

  /** 起動時の初期登録（環境変数 OAUTH_CLIENT_*）。まだ登録されていないときだけ書く（管理画面での変更を上書きしない） */
  def seed(raw: SaveOAuthClientReq): IO[Unit] =
    client.flatMap {
      case Some(_) => IO.unit
      case None => validate(raw, hasSecret = false) match
        case Left(msg) => IO.println(s"[skiwi] OAUTH_CLIENT_* is invalid and ignored: $msg")
        case Right(v)  => save(v, None) *> audit.system("system", "integration.oauth_client.seed", "integration", v.clientId) *>
                            IO.println(s"[skiwi] registered OAuth client '${v.clientId}' from OAUTH_CLIENT_*")
    }

  private def clientDto(c: Option[OAuthClient]): IO[OAuthClientDto] = c match
    case None    => IO.pure(OAuthClientDto("", clientSecretSet = false, "", "skea", 0))
    case Some(c) => ops.countOAuthTokens(c.id).map(n => OAuthClientDto(c.id, clientSecretSet = true, c.redirectUri, c.name, n))

  // ---------------------------------------------------------------- 許可の画面（サーバーで HTML を組み立てる）

  private def enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

  /** redirect_uri に結果を付けて戻す */
  private def back(uri: String, params: (String, Option[String])*): IO[Response[IO]] =
    val q = params.collect { case (k, Some(v)) => s"$k=${enc(v)}" }.mkString("&")
    IO.pure(Response[IO](Status.Found).putHeaders(Location(Uri.unsafeFromString(uri + (if uri.contains("?") then "&" else "?") + q))))

  /** 画面を返す。CSP に form-action は付けない（送信後のリダイレクトの連鎖にも効くため、連携元がさらに別の
    * origin（開発中の画面のサーバーなど）へ戻すと、許可の後の戻りが止まる。送信先は自分の固定のパスだけ） */
  private def page(title: String, body: String): IO[Response[IO]] =
    val html = s"""<!DOCTYPE html><html lang="ja"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
      |<title>${Html.escape(title)} | skiwi</title><style>
      |:root{color-scheme:light dark}body{margin:0;font-family:system-ui,-apple-system,"Hiragino Sans","Noto Sans JP",sans-serif;background:#f5f5f4;color:#1f2937;
      |display:flex;min-height:100vh;align-items:center;justify-content:center;padding:1rem;box-sizing:border-box}
      |@media(prefers-color-scheme:dark){body{background:#18181b;color:#e5e7eb}.card{background:#27272a!important;border-color:#3f3f46!important}}
      |.card{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:1.6rem 1.8rem;max-width:460px;width:100%;box-shadow:0 10px 30px rgba(0,0,0,.12)}
      |h1{font-size:1.15rem;margin:0 0 .8rem}p{line-height:1.7;margin:.4rem 0}.muted{color:#6b7280;font-size:.85rem}.logo{font-weight:800;margin-bottom:.6rem}
      |.actions{display:flex;gap:.6rem;justify-content:flex-end;margin-top:1.2rem}button{font:inherit;border-radius:8px;padding:.5rem 1.1rem;cursor:pointer;
      |border:1px solid #d1d5db;background:transparent;color:inherit}button.primary{background:#2563eb;border-color:#2563eb;color:#fff}
      |ul{padding-left:1.2rem;margin:.4rem 0}</style></head><body><div class="card"><div class="logo">skiwi</div>$body</div></body></html>""".stripMargin
    IO.pure(Response[IO](Status.Ok).withEntity(html)(using EntityEncoder.stringEncoder).putHeaders(`Content-Type`(MediaType.text.html, Charset.`UTF-8`),
      "Cache-Control" -> "no-store", "X-Frame-Options" -> "DENY", "Referrer-Policy" -> "no-referrer",
      "Content-Security-Policy" -> s"default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'"))

  private def errorPage(msg: String): IO[Response[IO]] =
    page("認可できません", s"<h1>認可できません</h1><p>${Html.escape(msg)}</p><p class=\"muted\">連携元のサービスの設定を確認してください。</p>")
      .map(_.withStatus(Status.BadRequest))

  /** 認可の要求を確かめる。client_id・redirect_uri が不正ならリダイレクトせずに画面でエラー、それ以外の不正は redirect_uri へエラーを返す。
    * 正しければ (クライアント, state, code_challenge) */
  private def validateAuthorize(p: String => Option[String]): IO[Either[IO[Response[IO]], (OAuthClient, Option[String], String)]] =
    client.map { reg =>
      (reg.filter(c => p("client_id").contains(c.id)), p("redirect_uri")) match
        case (None, _) => Left(errorPage("登録されていないクライアントです（client_id）"))
        case (Some(c), r) if !r.contains(c.redirectUri) => Left(errorPage("戻り先（redirect_uri）が登録されたものと一致しません"))
        case (Some(c), _) =>
          val state = p("state")
          if !p("response_type").contains("code") then Left(back(c.redirectUri, "error" -> Some("unsupported_response_type"), "state" -> state))
          else if !p("code_challenge_method").contains("S256") || !p("code_challenge").exists(_.matches("^[A-Za-z0-9_-]{43}$")) then
            Left(back(c.redirectUri, "error" -> Some("invalid_request"), "error_description" -> Some("PKCE（S256）が必要です"), "state" -> state))
          else if p("scope").exists(_.split(' ').exists(_ != "read")) then Left(back(c.redirectUri, "error" -> Some("invalid_scope"), "state" -> state))
          else Right((c, state, p("code_challenge").get))
    }

  private def queryParam(req: Request[IO], k: String): Option[String] = req.uri.query.params.get(k).map(_.trim).filter(_.nonEmpty)

  // ---------------------------------------------------------------- トークンの発行・失効（クライアント認証は HTTP Basic）

  private def oauthError(status: Status, code: String, desc: String): IO[Response[IO]] =
    IO.pure(Response[IO](status).withEntity(Json.obj("error" -> code.asJson, "error_description" -> desc.asJson))
      .putHeaders("Cache-Control" -> "no-store", "Pragma" -> "no-cache")
      .putHeaders((if status == Status.Unauthorized then List[Header.ToRaw]("WWW-Authenticate" -> "Basic realm=\"skiwi\"") else Nil)*))

  private def invalidClient = oauthError(Status.Unauthorized, "invalid_client", "クライアントの認証に失敗しました")

  private def basicClient(req: Request[IO]): IO[Option[OAuthClient]] =
    req.headers.get[Authorization].collect { case Authorization(BasicCredentials(id, secret)) => (id, secret) } match
      case None => IO.pure(None)
      case Some((id, secret)) => client.map(_.filter(c => c.id == id && c.secretMatches(secret)))

  private def form(req: Request[IO])(f: Map[String, String] => IO[Response[IO]]): IO[Response[IO]] =
    req.as[UrlForm].attempt.flatMap {
      case Left(_)  => oauthError(Status.BadRequest, "invalid_request", "application/x-www-form-urlencoded で送ってください")
      case Right(u) => f(u.values.view.mapValues(_.headOption.getOrElse("")).toMap)
    }

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // ------------------------------------------------------------ 管理者の設定
    case req @ GET -> Root / "api" / "admin" / "integrations" / "skea" / "client" =>
      admin(req)(_ => client.flatMap(clientDto).flatMap(Ok(_)))

    case req @ PUT -> Root / "api" / "admin" / "integrations" / "skea" / "client" =>
      admin(req) { s =>
        body[SaveOAuthClientReq](req) { raw =>
          client.flatMap { old =>
            validate(raw, hasSecret = old.exists(_.id == raw.clientId.trim)) match
              case Left(msg) => err(Status.BadRequest, msg)
              case Right(v) =>
                for
                  _   <- save(v, Some(s.user.id))
                  // client_id が変わったら、前のクライアントに発行したトークンは失効させる
                  n   <- old.filter(_.id != v.clientId).fold(IO.pure(0))(o => ops.revokeAllOAuthTokens(o.id))
                  _   <- audit.log(s, "integration.oauth_client.save", "integration", v.clientId, Json.obj(
                           "redirectUri" -> v.redirectUri.asJson, "name" -> v.name.asJson,
                           "secretChanged" -> v.clientSecret.isDefined.asJson, "tokensRevoked" -> n.asJson))
                  dto <- client.flatMap(clientDto)
                  res <- Ok(dto)
                yield res
          }
        }
      }

    // 登録を消す（発行したトークンもすべて失効させる）
    case req @ DELETE -> Root / "api" / "admin" / "integrations" / "skea" / "client" =>
      admin(req) { s =>
        client.flatMap {
          case None => NoContent()
          case Some(c) =>
            st.setSettings(Map(Keys.id -> None, Keys.secret -> None, Keys.redirect -> None, Keys.name -> None), Some(s.user.id)) *>
              ops.revokeAllOAuthTokens(c.id).flatMap(n =>
                audit.log(s, "integration.oauth_client.delete", "integration", c.id, Json.obj("tokensRevoked" -> n.asJson))) *> NoContent()
        }
      }

    // ------------------------------------------------------------ 認可
    case req @ GET -> Root / "oauth" / "authorize" =>
      validateAuthorize(queryParam(req, _)).flatMap {
        case Left(r) => r
        case Right((c, _, _)) =>
          sessions.fromRequest(req).flatMap {
            case None =>
              // ログインしてからこの画面に戻る
              IO.pure(Response[IO](Status.Found).putHeaders(Location(Uri.unsafeFromString(s"/auth/login?returnTo=${enc(req.uri.renderString)}"))))
            case Some(s) =>
              val csrf = Crypto.randomToken(24)
              val hidden = List("client_id", "redirect_uri", "state", "code_challenge", "code_challenge_method", "response_type", "scope")
                .flatMap(k => queryParam(req, k).map(v => s"""<input type="hidden" name="$k" value="${Html.escape(v)}">""")).mkString
              val name = Html.escape(c.name)
              page(s"${c.name} との連携", s"""<h1>$name との連携</h1>
                |<p><b>$name</b> が、あなた（${Html.escape(s.user.name)}）の権限で skiwi のページのタイトルを読み取ります。</p>
                |<ul class="muted"><li>読み取り専用です（ページの作成・変更・削除はしません）</li><li>あなたが見られるページだけが対象です</li>
                |<li>「APIトークン」の画面から、いつでも連携を解除できます</li></ul>
                |<form method="post" action="/oauth/authorize">$hidden<input type="hidden" name="csrf" value="$csrf">
                |<div class="actions"><button type="submit" name="decision" value="deny">拒否する</button>
                |<button type="submit" name="decision" value="allow" class="primary">許可する</button></div></form>""".stripMargin)
                .map(_.addCookie(ResponseCookie(CsrfCookie, csrf, path = Some("/oauth"), httpOnly = true, secure = cookieSecure,
                  sameSite = Some(SameSite.Strict), maxAge = Some(600))))
          }
      }

    case req @ POST -> Root / "oauth" / "authorize" =>
      req.as[UrlForm].attempt.flatMap {
        case Left(_) => errorPage("要求の形が正しくありません")
        case Right(u) =>
          val p = (k: String) => u.getFirst(k).map(_.trim).filter(_.nonEmpty)
          validateAuthorize(p).flatMap {
            case Left(r) => r
            case Right((c, state, challenge)) =>
              // CSRF: SameSite=Strict の Cookie とフォームの値が一致すること
              val csrfOk = req.cookies.find(_.name == CsrfCookie).exists(ck => p("csrf").exists(v => Crypto.constantTimeEquals(ck.content, v)))
              sessions.fromRequest(req).flatMap {
                case None => errorPage("ログインが切れました。連携元の画面からやり直してください")
                case Some(_) if !csrfOk => errorPage("画面の有効期限が切れました。連携元の画面からやり直してください")
                // 許可はブラウザのログイン（Cookie）でだけ行う（API トークンでトークンを作らせない）
                case Some(s) if s.via != AuthVia.Session => errorPage("ブラウザでログインして許可してください")
                case Some(s) if p("decision").contains("allow") =>
                  val code = Crypto.randomToken(32)
                  st.createOAuthCode(Crypto.sha256(code), OAuthCode(c.id, c.redirectUri, s.user.id, challenge), Instant.now().plusSeconds(CodeTtlSeconds)) *>
                    audit.log(s, "oauth.authorize", "integration", c.id) *>
                    back(c.redirectUri, "code" -> Some(code), "state" -> state)
                case Some(_) => back(c.redirectUri, "error" -> Some("access_denied"), "state" -> state)
              }.map(_.removeCookie(ResponseCookie(CsrfCookie, "", path = Some("/oauth"))))
          }
      }

    case req @ POST -> Root / "oauth" / "token" =>
      basicClient(req).flatMap {
        case None => invalidClient
        case Some(c) =>
          form(req) { f =>
            (f.get("grant_type").filter(_.nonEmpty), f.get("code"), f.get("redirect_uri"), f.get("code_verifier")) match
              case (g, _, _, _) if !g.contains("authorization_code") =>
                oauthError(Status.BadRequest, if g.isEmpty then "invalid_request" else "unsupported_grant_type", "grant_type は authorization_code です")
              case (_, Some(code), Some(uri), Some(verifier)) if code.nonEmpty && verifier.length >= 43 && verifier.length <= 128 =>
                st.consumeOAuthCode(Crypto.sha256(code)).flatMap {
                  case Some(oc) if oc.clientId == c.id && oc.redirectUri == uri && Crypto.constantTimeEquals(oc.codeChallenge, Crypto.pkceChallenge(verifier)) =>
                    val token = "skiwi_" + Crypto.randomToken(32)
                    // 許可し直したら、そのユーザーにこのクライアントが以前に発行したトークンは失効する
                    ops.replaceOAuthToken(oc.userId, c.id, s"${c.name} 連携", Crypto.sha256(token), "read").flatMap { t =>
                      audit.system("system", "token.create", "token", t.id, Json.obj("userId" -> oc.userId.asJson, "client" -> c.id.asJson, "scope" -> "read".asJson)) *>
                        Ok(Json.obj("access_token" -> token.asJson, "token_type" -> "Bearer".asJson, "scope" -> "read".asJson))
                          .map(_.putHeaders("Cache-Control" -> "no-store", "Pragma" -> "no-cache"))
                    }
                  case _ => oauthError(Status.BadRequest, "invalid_grant", "認可コードが無効です（期限切れ・使用済み・不一致）")
                }
              case _ => oauthError(Status.BadRequest, "invalid_request", "code・redirect_uri・code_verifier（43〜128文字）を指定してください")
          }
      }

    // RFC 7009: 知らないトークンでも 200。失効させるのはこのクライアントが発行したトークンだけ
    case req @ POST -> Root / "oauth" / "revoke" =>
      basicClient(req).flatMap {
        case None => invalidClient
        case Some(c) =>
          form(req) { f =>
            f.get("token").filter(_.nonEmpty).flatTraverse(t => ops.revokeOAuthToken(Crypto.sha256(t), c.id)).flatMap {
              _.traverse_((uid, t) => audit.system("system", "token.revoke", "token", t.id, Json.obj("userId" -> uid.asJson, "client" -> c.id.asJson)))
            } *> Ok(Json.obj()).map(_.putHeaders("Cache-Control" -> "no-store"))
          }
      }

    // ------------------------------------------------------------ ページの一括参照（skea のリンクの表示）
    // 削除済み・存在しない・権限のないページは区別せず返さない。読み取り専用のトークンでも使える（SessionAuth）
    case req @ POST -> Root / "api" / "pages" / "lookup" =>
      authed(req) { s =>
        body[Json](req) { j =>
          j.hcursor.get[List[String]]("ids") match
            case Left(_) => err(Status.BadRequest, "ids（ページIDの配列）を指定してください")
            case Right(ids) if ids.size > MaxLookupIds => err(Status.BadRequest, s"ids は${MaxLookupIds}件までです")
            case Right(ids) =>
              val uuids = ids.flatMap(i => scala.util.Try(UUID.fromString(i.trim)).toOption).distinct
              store.visiblePages(uuids, s).flatMap(ps =>
                Ok(Json.obj("pages" -> ps.map((p, _) => PageLookupDto(p.id, p.workspaceId, p.title, p.icon.filter(_.nonEmpty))).asJson)))
        }
      }
  }
