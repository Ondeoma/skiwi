package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.headers.{Accept, Authorization, Location}
import skiwi.auth.{Crypto, SecretBox}
import skiwi.db.IntegrationStore

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** 管理画面での連携の設定（client_secret は返さない。設定済みかどうかだけ）。projectKeys は skea だけ */
final case class IntegrationSettingsDto(url: String, clientId: String, clientSecretSet: Boolean, projectKeys: List[String], redirectUri: String, connections: Int) derives Encoder.AsObject
/** clientSecret: 空か省略なら今の値のまま */
final case class SaveIntegrationSettingsReq(url: String, clientId: String, clientSecret: Option[String], projectKeys: Option[List[String]]) derives Decoder
/** ログイン中のユーザーから見た連携の状態 */
final case class IntegrationStatusDto(enabled: Boolean, url: Option[String], projectKeys: List[String], connected: Boolean) derives Encoder.AsObject
final case class LookupReq(keys: List[String]) derives Decoder
/** items: サービスごとの形（skea はタスク、skawau はファイル）。見つからない・見られないものは入らない */
final case class LookupDto(connected: Boolean, items: List[Json]) derives Encoder.AsObject

/** 復号済みの連携の設定 */
final case class IntegrationSettings(url: String, clientId: String, clientSecret: Option[String], projectKeys: List[String])

/**
 * 連携先のサービスごとの違い（docs/skea-integration.md・docs/skawau-integration.md）。
 * どちらも OAuth 2.0 の認可コード + PKCE で、相手の `/oauth/authorize`・`/oauth/token`・`/oauth/revoke` を使う。
 * @param lookupPath 項目をまとめて読む相手の API（Bearer）。リクエストは `{requestField: [...]}`
 * @param parse      相手の応答から (キー, 画面に返す項目) を取り出す（形が違うものは捨てる）
 */
final case class LinkedService(
  name: String,
  lookupPath: String,
  requestField: String,
  usesProjectKeys: Boolean,
  isKey: (IntegrationSettings, String) => Boolean,
  parse: (Json, IntegrationSettings) => List[(String, Json)],
):
  def complete(s: IntegrationSettings): Boolean =
    s.url.nonEmpty && s.clientId.nonEmpty && s.clientSecret.exists(_.nonEmpty) && (!usesProjectKeys || s.projectKeys.nonEmpty)

object LinkedService:
  private val Color = "^#[0-9a-fA-F]{3,8}$".r
  private val Uuid  = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$".r

  /** skea: 本文のタスクID（例: SKEA-123）にタイトルと状態を出す。対象は設定したプロジェクトキーだけ（大文字と小文字を区別） */
  val skea: LinkedService = LinkedService("skea", "/api/tasks/lookup", "keys", usesProjectKeys = true,
    isKey = (s, k) => s.projectKeys.exists(p => k.startsWith(p + "-") && k.length > p.length + 1 && k.drop(p.length + 1).forall(_.isDigit)),
    parse = (j, s) => items(j, "tasks") { c =>
      (c.get[String]("key"), c.get[String]("title")).mapN { (k, title) =>
        val st = c.downField("status")
        // 色は CSS に入れるので #RRGGBB などだけ
        val color = st.get[String]("color").toOption.filter(Color.matches).getOrElse("#6b7280")
        k -> Json.obj("key" -> k.asJson, "title" -> title.asJson, "url" -> s"${s.url}/tasks/$k".asJson, "status" -> Json.obj(
          "name" -> st.get[String]("name").getOrElse("").asJson, "category" -> st.get[String]("category").getOrElse("").asJson, "color" -> color.asJson))
      }
    })

  /** skawau: 本文のファイル・フォルダのリンク（{URL}/view/{ID} など）にファイル名を出す。キーはファイルの ID（UUID、小文字にそろえる） */
  val skawau: LinkedService = LinkedService("skawau", "/api/files/lookup", "ids", usesProjectKeys = false,
    isKey = (_, k) => Uuid.matches(k) && k == k.toLowerCase,
    parse = (j, _) => items(j, "files") { c =>
      (c.get[String]("id").map(_.toLowerCase), c.get[String]("name")).mapN { (id, name) =>
        id -> Json.obj("key" -> id.asJson, "name" -> name.asJson, "isDir" -> c.get[Boolean]("isDir").getOrElse(false).asJson,
          "mimeType" -> c.get[Option[String]]("mimeType").toOption.flatten.asJson)
      }
    })

  val all: List[LinkedService] = List(skea, skawau)

  private def items(j: Json, field: String)(f: io.circe.ACursor => io.circe.Decoder.Result[(String, Json)]): List[(String, Json)] =
    j.hcursor.downField(field).values.toList.flatten.flatMap(t => f(t.hcursor).toOption)

object IntegrationRoutes:
  /** (サービス, ユーザー, キー) → (期限のミリ秒, 項目。None は「見つからない・見られない」) */
  type Cache = Ref[IO, Map[(String, UUID, String), (Long, Option[Json])]]
  val MaxKeys     = 100
  val CacheMillis = 60000L
  val MaxCache    = 20000
  /** プロジェクトキー: 英大文字で始まる英大文字・数字・_（UTF-8 などを誤ってタスクIDと見なさないよう、対象を絞る） */
  val ProjectKey = "^[A-Z][A-Z0-9_]{0,19}$".r

  /** 戻り先に結果の目印（?skea=connected など）を付ける */
  def withFlag(path: String, name: String, v: String): String =
    val (p, hash) = path.indexOf('#') match
      case -1 => (path, "")
      case i  => (path.take(i), path.drop(i))
    p + (if p.contains('?') then "&" else "?") + s"$name=$v" + hash

/**
 * 外部サービス（skea・skawau）との連携。各ユーザーが OAuth 2.0（認可コード + PKCE）で相手を許可し、
 * 発行された読み取り専用のトークンを暗号化して保存する。本文中の項目（タスク・ファイル）は、本人のトークンで相手から読む。
 * box（ENCRYPTION_KEY）がなければ連携の機能は使えない（503）。パスは /api/integrations/{サービス名}/…。
 */
final class IntegrationRoutes(services: List[LinkedService], st: IntegrationStore, protected val sessions: SessionAuth, audit: Audit,
                              http: Client[IO], publicUrl: String, box: Option[SecretBox], cache: IntegrationRoutes.Cache) extends RouteSupport:
  import IntegrationRoutes.*

  private object Svc:
    private val byName = services.map(s => s.name -> s).toMap
    def unapply(s: String): Option[LinkedService] = byName.get(s)

  def redirectUri(svc: LinkedService) = s"$publicUrl/api/integrations/${svc.name}/callback"

  private def unavailable(svc: LinkedService) = s"${svc.name} 連携は使えません（サーバーに ENCRYPTION_KEY が設定されていません）"
  private def secretCtx(svc: LinkedService)           = s"${svc.name}.client_secret"
  private def tokenCtx(svc: LinkedService, uid: UUID) = s"${svc.name}.token:$uid"

  private def withBox(svc: LinkedService)(f: SecretBox => IO[Response[IO]]): IO[Response[IO]] =
    box.fold(err(Status.ServiceUnavailable, unavailable(svc)))(f)

  private def load(svc: LinkedService, b: SecretBox): IO[IntegrationSettings] = st.settings(svc.name + ".").map { m =>
    def get(k: String) = m.get(s"${svc.name}.$k")
    IntegrationSettings(
      get("url").getOrElse(""), get("client_id").getOrElse(""),
      get("client_secret").flatMap(b.decrypt(_, secretCtx(svc))),
      get("project_keys").toList.flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty)
    )
  }

  /** 設定が揃っていれば (鍵, 設定) */
  private def active(svc: LinkedService): IO[Option[(SecretBox, IntegrationSettings)]] =
    box.flatTraverse(b => load(svc, b).map(s => Option.when(svc.complete(s))((b, s))))

  /** 本人のトークン（復号できなければ None） */
  private def token(svc: LinkedService, b: SecretBox, uid: UUID): IO[Option[String]] =
    st.token(svc.name, uid).map(_.flatMap(b.decrypt(_, tokenCtx(svc, uid))))

  private def redirect(to: String): IO[Response[IO]] =
    Response[IO](Status.Found).putHeaders(Location(Uri.unsafeFromString(to))).pure[IO]

  private def endpoint(s: IntegrationSettings, path: String) = Uri.unsafeFromString(s.url + path)

  /** client_secret を使う相手の OAuth エンドポイントへの POST（HTTP Basic のクライアント認証） */
  private def oauthPost(s: IntegrationSettings, path: String, form: UrlForm) =
    Request[IO](Method.POST, endpoint(s, path)).withEntity(form)
      .putHeaders(Authorization(BasicCredentials(s.clientId, s.clientSecret.getOrElse(""))), Accept(MediaType.application.json))

  private def dropCache(svc: LinkedService, uid: UUID) = cache.update(_.filterNot(e => e._1._1 == svc.name && e._1._2 == uid))

  private def warn(svc: LinkedService, what: String)(e: Any) = IO.println(s"[${svc.name}] $what failed: $e")

  /** 認可コードをアクセストークンに替える */
  private def exchange(svc: LinkedService, s: IntegrationSettings, code: String, verifier: String): IO[Option[String]] =
    http.run(oauthPost(s, "/oauth/token", UrlForm(
      "grant_type" -> "authorization_code", "code" -> code, "redirect_uri" -> redirectUri(svc), "code_verifier" -> verifier
    ))).use { r =>
      if r.status.isSuccess then r.as[Json].map(_.hcursor.get[String]("access_token").toOption.filter(_.nonEmpty))
      else r.bodyText.compile.string.flatMap(b => warn(svc, "token exchange")(s"${r.status.code} ${b.take(200)}")).as(None)
    }.timeout(10.seconds).handleErrorWith(e => warn(svc, "token exchange")(e).as(None))

  private enum Lookup:
    case Found(items: List[(String, Json)])
    case Revoked
    case Failed

  private def lookup(svc: LinkedService, s: IntegrationSettings, tok: String, keys: List[String]): IO[Lookup] =
    val req = Request[IO](Method.POST, endpoint(s, svc.lookupPath)).withEntity(Json.obj(svc.requestField -> keys.asJson))
      .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, tok)), Accept(MediaType.application.json))
    http.run(req).use { r =>
      if r.status == Status.Unauthorized then IO.pure(Lookup.Revoked)
      else if r.status.isSuccess then r.as[Json].map(j => Lookup.Found(svc.parse(j, s).filter(i => keys.contains(i._1))))
      else warn(svc, "lookup")(r.status.code).as(Lookup.Failed)
    }.timeout(10.seconds).handleErrorWith(e => warn(svc, "lookup")(e).as(Lookup.Failed))

  private def settingsDto(svc: LinkedService, s: IntegrationSettings): IO[IntegrationSettingsDto] =
    st.countTokens(svc.name).map(n => IntegrationSettingsDto(s.url, s.clientId, s.clientSecret.exists(_.nonEmpty), s.projectKeys, redirectUri(svc), n))

  /** 入力を確かめて整える（エラーは画面に出す文） */
  private def validate(svc: LinkedService, b: SaveIntegrationSettingsReq): Either[String, SaveIntegrationSettingsReq] =
    val url  = b.url.trim.stripSuffix("/")
    val keys = if svc.usesProjectKeys then b.projectKeys.getOrElse(Nil).map(_.trim.toUpperCase).filter(_.nonEmpty).distinct else Nil
    val urlOk = url.isEmpty || Uri.fromString(url).toOption.exists(u =>
      u.scheme.exists(sc => sc == Uri.Scheme.http || sc == Uri.Scheme.https) && u.host.nonEmpty && u.query.isEmpty && u.fragment.isEmpty)
    if !urlOk then Left(s"${svc.name} の URL は http:// または https:// で始まる URL にしてください")
    else if b.clientId.trim.length > 200 || b.clientSecret.exists(_.length > 500) then Left("client_id・client_secret が長すぎます")
    else if keys.size > 50 then Left("プロジェクトキーは50個までです")
    else keys.find(k => !ProjectKey.matches(k)) match
      case Some(k) => Left(s"プロジェクトキー「$k」は使えません（英大文字で始まる英大文字・数字・_、20文字まで）")
      case None    => Right(SaveIntegrationSettingsReq(url, b.clientId.trim, b.clientSecret.map(_.trim).filter(_.nonEmpty), Some(keys)))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // ------------------------------------------------------------ 管理者の設定
    case req @ GET -> Root / "api" / "admin" / "integrations" / Svc(svc) =>
      admin(req)(_ => withBox(svc)(b => load(svc, b).flatMap(settingsDto(svc, _)).flatMap(Ok(_))))

    case req @ PUT -> Root / "api" / "admin" / "integrations" / Svc(svc) =>
      admin(req) { s =>
        withBox(svc) { b =>
          body[SaveIntegrationSettingsReq](req) { raw =>
            validate(svc, raw) match
              case Left(msg) => err(Status.BadRequest, msg)
              case Right(v) =>
                val keys = v.projectKeys.getOrElse(Nil)
                def k(name: String) = s"${svc.name}.$name"
                for
                  old <- load(svc, b)
                  // 接続先かクライアントが変わったら、これまでのトークンは使えないので全員の連携を外す
                  reset = old.url.nonEmpty && (old.url != v.url || old.clientId != v.clientId)
                  _   <- st.setSettings(Map(
                           k("url") -> Some(v.url).filter(_.nonEmpty),
                           k("client_id") -> Some(v.clientId).filter(_.nonEmpty),
                           k("project_keys") -> Some(keys.mkString(",")).filter(_.nonEmpty)
                         ) ++ v.clientSecret.map(sec => k("client_secret") -> Some(b.encrypt(sec, secretCtx(svc)))), Some(s.user.id))
                  n   <- if reset then st.deleteAllTokens(svc.name) else IO.pure(0)
                  _   <- cache.update(_.filterNot(_._1._1 == svc.name))
                  _   <- audit.log(s, s"integration.${svc.name}.settings", "integration", svc.name, Json.obj(
                           "url" -> v.url.asJson, "clientId" -> v.clientId.asJson, "projectKeys" -> keys.asJson,
                           "secretChanged" -> v.clientSecret.isDefined.asJson, "connectionsCleared" -> n.asJson))
                  dto <- load(svc, b).flatMap(settingsDto(svc, _))
                  res <- Ok(dto)
                yield res
          }
        }
      }

    // ------------------------------------------------------------ ユーザーごとの連携
    case req @ GET -> Root / "api" / "integrations" / Svc(svc) =>
      authed(req) { s =>
        active(svc).flatMap {
          case None => Ok(IntegrationStatusDto(enabled = false, None, Nil, connected = false))
          case Some((b, cfg)) => token(svc, b, s.user.id).flatMap(t => Ok(IntegrationStatusDto(enabled = true, Some(cfg.url), cfg.projectKeys, t.isDefined)))
        }
      }

    case req @ GET -> Root / "api" / "integrations" / Svc(svc) / "connect" =>
      authed(req) { s =>
        withBox(svc) { _ =>
          active(svc).flatMap {
            case None => err(Status.ServiceUnavailable, s"${svc.name} 連携が設定されていません（管理者が設定してください）")
            case Some((_, cfg)) =>
              safeReturnTo(req.uri.query.params.get("returnTo")) match
                case None => err(Status.BadRequest, "returnTo は同じサイトの相対パス（/ で始まる）にしてください")
                case Some(back) =>
                  val state, verifier = Crypto.randomToken()
                  st.saveState(svc.name, state, s.user.id, verifier, back, Instant.now()) *>
                    redirect(endpoint(cfg, "/oauth/authorize").withQueryParams(Map(
                      "response_type" -> "code", "client_id" -> cfg.clientId, "redirect_uri" -> redirectUri(svc), "scope" -> "read",
                      "state" -> state, "code_challenge" -> Crypto.pkceChallenge(verifier), "code_challenge_method" -> "S256"
                    )).renderString)
          }
        }
      }

    // 相手の許可画面から戻ってくる（ブラウザの画面遷移なので、結果は戻り先の ?{サービス名}=connected|denied|error で伝える）
    case req @ GET -> Root / "api" / "integrations" / Svc(svc) / "callback" =>
      val p = req.uri.query.params
      def back(to: String, v: String) = redirect(withFlag(to, svc.name, v))
      sessions.fromRequest(req).flatMap {
        case None => back("/", "error")
        case Some(s) =>
          p.get("state").fold(IO.pure(Option.empty[(String, String)]))(st.takeState(svc.name, _, s.user.id, Instant.now())).flatMap {
            case None => back("/", "error")
            case Some((verifier, to)) =>
              (p.get("error"), p.get("code")) match
                case (Some("access_denied"), _) => back(to, "denied")
                case (None, Some(code)) =>
                  active(svc).flatMap {
                    case None => back(to, "error")
                    case Some((b, cfg)) =>
                      exchange(svc, cfg, code, verifier).flatMap {
                        case None => back(to, "error")
                        case Some(tok) =>
                          st.saveToken(svc.name, s.user.id, b.encrypt(tok, tokenCtx(svc, s.user.id))) *> dropCache(svc, s.user.id) *>
                            audit.log(s, s"integration.${svc.name}.connect", "integration", svc.name, Json.obj("url" -> cfg.url.asJson)) *>
                            back(to, "connected")
                      }
                  }
                case _ => back(to, "error")
          }
      }

    case req @ DELETE -> Root / "api" / "integrations" / Svc(svc) / "connection" =>
      authed(req) { s =>
        withBox(svc) { b =>
          (token(svc, b, s.user.id), active(svc)).flatMapN { (tok, cfg) =>
            // 相手側の失効は失敗しても続ける（相手の API キーの画面からも失効できる）
            val revoke = (tok, cfg).mapN { case (t, (_, c)) =>
              http.run(oauthPost(c, "/oauth/revoke", UrlForm("token" -> t))).use(r => IO.whenA(!r.status.isSuccess)(warn(svc, "revoke")(r.status.code)))
                .timeout(10.seconds).handleErrorWith(warn(svc, "revoke"))
            }.getOrElse(IO.unit)
            revoke *> st.deleteToken(svc.name, s.user.id).flatMap { had =>
              dropCache(svc, s.user.id) *> IO.whenA(had)(audit.log(s, s"integration.${svc.name}.disconnect", "integration", svc.name)) *> NoContent()
            }
          }
        }
      }

    case req @ POST -> Root / "api" / "integrations" / Svc(svc) / "lookup" =>
      authed(req) { s =>
        withBox(svc) { _ =>
          body[LookupReq](req) {
            case b if b.keys.size > MaxKeys => err(Status.BadRequest, s"keys は${MaxKeys}件までです")
            case b =>
              val notConnected = Ok(LookupDto(connected = false, Nil))
              active(svc).flatMap {
                case None => notConnected
                case Some((bx, cfg)) =>
                  token(svc, bx, s.user.id).flatMap {
                    case None => notConnected
                    case Some(tok) => items(svc, s.user.id, cfg, tok, b.keys.filter(svc.isKey(cfg, _)).distinct)
                  }
              }
          }
        }
      }
  }

  /** キャッシュにないものだけ相手に問い合わせる。401 なら連携が切れたとして接続を消す */
  private def items(svc: LinkedService, uid: UUID, cfg: IntegrationSettings, tok: String, keys: List[String]): IO[Response[IO]] =
    IO.realTime.map(_.toMillis).flatMap { now =>
      cache.get.flatMap { m =>
        val hits   = keys.flatMap(k => m.get((svc.name, uid, k)).filter(_._1 > now).map(k -> _._2)).toMap
        val misses = keys.filterNot(hits.contains)
        def reply(found: Map[String, Option[Json]]) = Ok(LookupDto(connected = true, keys.flatMap(k => found.get(k).flatten)))
        if misses.isEmpty then reply(hits)
        else lookup(svc, cfg, tok, misses).flatMap {
          case Lookup.Revoked => st.deleteToken(svc.name, uid) *> dropCache(svc, uid) *> Ok(LookupDto(connected = false, Nil))
          case Lookup.Failed  => err(Status.BadGateway, s"${svc.name} から取得できませんでした")
          case Lookup.Found(got) =>
            val gotMap = got.toMap
            val fresh  = misses.map(k => k -> gotMap.get(k)).toMap
            val exp    = now + CacheMillis
            cache.update { c =>
              val live = if c.size + fresh.size > MaxCache then c.filter(_._2._1 > now) else c
              (if live.size + fresh.size > MaxCache then Map.empty else live) ++ fresh.map((k, v) => (svc.name, uid, k) -> (exp, v))
            } *> reply(hits ++ fresh)
        }
      }
    }
