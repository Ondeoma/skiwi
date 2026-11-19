package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json, JsonObject}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import sbirds.auth.Crypto
import skiwi.core.*
import skiwi.db.{CommentStore, DatabaseStore, DocStore, InboundHook, InboundStore, OpsStore, Store}

import java.time.{Instant, LocalDate}
import java.util.UUID

/** url: 作成・作り直しのときだけ返す（トークンを含む） */
final case class InboundHookDto(
  id: UUID,
  name: String,
  mode: String,
  targetPageId: UUID,
  targetTitle: Option[String],
  enabled: Boolean,
  createdBy: Option[String],
  createdAt: Instant,
  lastAt: Option[Instant],
  useCount: Int,
  url: Option[String] = None) derives Encoder.AsObject
final case class CreateInboundReq(
  name: String,
  targetPageId: UUID,
  mode: String) derives Decoder
final case class UpdateInboundReq(
  name: Option[String],
  enabled: Option[Boolean]) derives Decoder

/**
 * 受信用の Webhook。URL（/api/hooks/{トークン}）に JSON を POST すると、作成した人の権限で次を行う:
 *  - row: データベースに項目を追加する { title, text?, values?: { 列名または列ID: 値 } }
 *  - child: 子ページを作る { title, text? }
 *  - comment: コメントを付ける { text }
 * text は本文（改行で段落に分ける）。Slack 形式の { "text": "…" } もそのまま受け付ける。
 */
final class InboundRoutes(
  protected val store: Store,
  inbound: InboundStore,
  dbs: DatabaseStore,
  docs: DocStore,
  comments: CommentStore,
  ops: OpsStore,
  protected val sessions: SessionAuth,
  audit: Audit,
  publicUrl: String,
  mentions: Option[Mentions] = None,
  webhooks: Option[WebhookService] = None,
  automations: Option[Automations] = None,
  rate: Ref[IO, Map[UUID, List[Long]]]) extends PageRouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // ------------------------------------------------------------ 受信（認証なし。URLのトークンで判断）
    case req @ POST -> Root / "api" / "hooks" / token => postHook(req, token)
    case req @ GET -> Root / "api" / "workspaces" / UUIDVar(wsId) / "inbound-hooks" =>
      getWorkspaceInboundHooks(req, wsId)
    case req @ POST -> Root / "api" / "workspaces" / UUIDVar(wsId) / "inbound-hooks" =>
      postWorkspaceInboundHooks(req, wsId)
    case req @ PATCH -> Root / "api" / "inbound-hooks" / UUIDVar(id) => patchInboundHook(req, id)
    case req @ POST -> Root / "api" / "inbound-hooks" / UUIDVar(id) / "rotate" => postInboundHookRotate(req, id)
    case req @ DELETE -> Root / "api" / "inbound-hooks" / UUIDVar(id) => deleteInboundHook(req, id)
  }

  private def postHook(
    req: Request[IO],
    token: String
  ): IO[Response[IO]] =
    if token.length > 100 then err(ErrorCode.ItemNotFound)
    else
      inbound.byToken(Crypto.sha256(token)).flatMap {
        case None => err(ErrorCode.ItemNotFound)
        case Some(h) if !h.enabled => err(ErrorCode.InboundWebhookDisabled)
        case Some(h) =>
          allow(h.id).flatMap {
            case false => err(ErrorCode.TooManyRequests)
            case true =>
              req.body.take(MaxBody + 1).compile.to(Array).flatMap { bytes =>
                if bytes.length > MaxBody then err(ErrorCode.InboundBodyTooLarge)
                else {
                  val raw = new String(bytes, "UTF-8")
                  val body = io.circe.parser.parse(raw).toOption.flatMap(_.asObject).getOrElse(JsonObject("text" ->
                    raw.asJson))
                  h.createdById.flatTraverse(sessions.sessionFor).flatMap {
                    case None => err(ErrorCode.InboundWebhookOwnerInactive)
                    case Some(s) =>
                      store.pageAccess(h.targetPageId, s).flatMap {
                        case Some((p, Role.Edit)) => receive(h, s, p, body)
                        case _ => err(ErrorCode.InboundWebhookOwnerCannotEdit)
                      }
                  }
                }
              }
          }
      }

  private def getWorkspaceInboundHooks(
    req: Request[IO],
    wsId: UUID
  ): IO[Response[IO]] = {
    // 受け取り先のページを閲覧できるものだけ
    withWsMember(req, wsId)(s =>
      inbound.list(wsId).flatMap(hs =>
        store.pagesAccess(hs.map(_.targetPageId), s).flatMap(ok =>
          Ok(hs.filter(h => ok.contains(h.targetPageId)).map(dto(_)))
        )
      )
    )
  }

  private def postWorkspaceInboundHooks(
    req: Request[IO],
    wsId: UUID
  ): IO[Response[IO]] =
    withWsMember(req, wsId) { s =>
      body[CreateInboundReq](req) {
        case b if !Modes(b.mode) => err(ErrorCode.InboundModeInvalid)
        case b if validName(b.name).isEmpty => err(ErrorCode.NameInvalid)
        case b =>
          store.pageAccess(b.targetPageId, s).flatMap {
            case Some((p, Role.Edit)) if p.workspaceId == wsId =>
              (if b.mode == "row" then dbs.isDatabase(p.id) else IO.pure(true)).flatMap {
                case false => err(ErrorCode.InboundTargetNotDatabase)
                case true =>
                  val t = newToken
                  inbound.create(wsId, p.id, b.name.trim, b.mode, Crypto.sha256(t), s.user.id).flatMap { h =>
                    audit.log(
                      s,
                      "inbound_hook.create",
                      "page",
                      p.id,
                      Json.obj("name" -> h.name.asJson, "mode" -> h.mode.asJson)
                    ) *> Created(dto(h, Some(t)))
                  }
              }
            case Some((p, _)) if p.workspaceId == wsId => err(ErrorCode.PermissionDenied)
            case _ => err(ErrorCode.PageNotFound)
          }
      }
    }

  private def patchInboundHook(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withHook(req, id) {
      (
        s,
        h
      ) =>
        body[UpdateInboundReq](req) {
          case b if b.name.exists(validName(_).isEmpty) => err(ErrorCode.NameInvalid)
          case b =>
            inbound.update(id, b.name.map(_.trim), b.enabled).flatMap {
              case None => err(ErrorCode.InboundWebhookNotFound)
              case Some(nh) => audit.log(
                  s,
                  "inbound_hook.update",
                  "page",
                  h.targetPageId,
                  Json.obj("name" -> nh.name.asJson, "enabled" -> nh.enabled.asJson)
                ) *> Ok(dto(nh))
            }
        }
    }

  private def postInboundHookRotate(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withHook(req, id) {
      (
        s,
        h
      ) =>
        val t = newToken
        inbound.rotate(id, Crypto.sha256(t)) *>
          audit.log(s, "inbound_hook.rotate", "page", h.targetPageId, Json.obj("name" -> h.name.asJson)) *>
          inbound.get(id).flatMap(nh => Ok(dto(nh.get, Some(t))))
    }

  private def deleteInboundHook(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withHook(req, id) {
      (
        s,
        h
      ) =>
        inbound.delete(id) *>
          audit.log(s, "inbound_hook.delete", "page", h.targetPageId, Json.obj("name" -> h.name.asJson)) *>
          NoContent()
    }

  private val base = publicUrl.stripSuffix("/")
  private def dto(
    h: InboundHook,
    token: Option[String] = None
  ) =
    InboundHookDto(
      h.id,
      h.name,
      h.mode,
      h.targetPageId,
      h.targetTitle,
      h.enabled,
      h.createdBy,
      h.createdAt,
      h.lastAt,
      h.useCount,
      token.map(t => s"$base/api/hooks/$t")
    )
  private val Modes = Set("row", "child", "comment")
  private val MaxBody = 65536L
  private val PerMinute = 60

  private def withWsMember(
    req: Request[IO],
    wsId: UUID
  )(
    f: AuthSession => IO[Response[IO]]
  ): IO[Response[IO]] =
    authed(req) { s =>
      store.workspaceRole(wsId, s).flatMap {
        case None => store.workspaceVisible(
            wsId,
            s
          ).ifM(err(ErrorCode.PermissionDenied), err(ErrorCode.WorkspaceNotFound))
        case Some(_) => f(s)
      }
    }

  /** 設定の変更: 受け取り先のページの編集権限 */
  private def withHook(
    req: Request[IO],
    id: UUID
  )(
    f: (AuthSession, InboundHook) => IO[Response[IO]]
  ) =
    authed(req) { s =>
      inbound.get(id).flatMap {
        case None => err(ErrorCode.InboundWebhookNotFound)
        case Some(h) =>
          store.pageAccess(h.targetPageId, s).flatMap {
            case Some((_, Role.Edit)) => f(s, h)
            case Some(_) => err(ErrorCode.PermissionDenied)
            case None => if s.isAdmin then f(s, h) else err(ErrorCode.InboundWebhookNotFound)
          }
      }
    }

  private def newToken = Crypto.randomToken(24)

  private def allow(
    id: UUID
  ): IO[Boolean] = perMinute(rate, id, PerMinute)

  // ---------------------------------------------------------------- 値の変換（列名・選択肢の名前も受け付ける）
  private def convert(
    props: Json,
    values: JsonObject
  ): IO[Either[String, JsonObject]] = {
    val ps = props.asArray.getOrElse(Vector.empty).flatMap(_.asObject)
    values.toList.traverse {
      (
        k,
        v
      ) =>
        ps.find(p => p("id").flatMap(_.asString).contains(k) || p("name").flatMap(_.asString).contains(k)) match {
          case None => IO.pure(Left(s"不明な列です: $k"))
          case Some(p) =>
            val id = p("id").flatMap(_.asString).get
            val opts = p("options").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asObject)
            def opt(
              x: String
            ) = opts.find(o =>
              o("id").flatMap(_.asString).contains(x) || o("name").flatMap(_.asString).contains(x)
            ).flatMap(_("id"))
            def strs = v.asArray.map(
              _.flatMap(_.asString).toList
            ).orElse(v.asString.map(_.split("[,;、]").map(_.trim).filter(_.nonEmpty).toList)).getOrElse(Nil)
            val conv: IO[Json] = p("type").flatMap(_.asString) match {
              case _ if v.isNull => IO.pure(Json.Null)
              case Some("select") => IO.pure(v.asString.flatMap(opt).getOrElse(v))
              case Some("multi_select") => IO.pure(Json.fromValues(strs.map(x => opt(x).getOrElse(Json.fromString(x)))))
              case Some("number") => IO.pure(
                  v.asString.flatMap(x => x.replace(",", "").toDoubleOption).map(Json.fromDoubleOrNull).getOrElse(v)
                )
              case Some("checkbox") => IO.pure(v.asString.map(x =>
                  Json.fromBoolean(Set("true", "1", "yes", "はい", "✓").contains(x.trim.toLowerCase))
                ).getOrElse(v))
              case Some("date") =>
                IO.pure(v.asString.map(_.trim.replace('/', '-').take(10)).flatMap { x =>
                  val parts = x.split("-").toList
                  parts match {
                    case y :: m :: d :: Nil => Either.catchNonFatal(LocalDate.of(
                        y.toInt,
                        m.toInt,
                        d.toInt
                      ).toString).toOption.map(Json.fromString)
                    case _ => None
                  }
                }.getOrElse(v))
              case Some("person") =>
                // ユーザーID、またはメールアドレス
                strs.traverse { x =>
                  if uuidOpt(x).isDefined then IO.pure(Some(x))
                  else
                    ops.searchDirectory(
                      x,
                      5
                    ).map(_.find(d => d.detail.exists(_.equalsIgnoreCase(x))).map(_.id.toString))
                }.map(xs => Json.fromValues(xs.flatten.map(Json.fromString)))
              case _ => IO.pure(v)
            }
            conv.map(c => Right(id -> c))
        }
    }.map(_.sequence.map(JsonObject.fromIterable))
  }

  private def receive(
    h: InboundHook,
    s: AuthSession,
    target: Page,
    body: JsonObject
  ): IO[Response[IO]] = {
    val title = body("title").flatMap(_.asString).map(_.trim.take(500)).getOrElse("")
    val text = body("text").orElse(body("body")).orElse(body("markdown")).flatMap(_.asString).getOrElse("")
    val values = body("values").flatMap(_.asObject).getOrElse(JsonObject.empty)
    def done = inbound.used(h.id)
    h.mode match {
      case "comment" =>
        val t = text.trim.take(10000)
        if t.isEmpty then err(ErrorCode.InboundBodyRequired)
        else
          for {
            c <- comments.create(target.id, None, None, None, t, s.user.id)
            _ <- ops.watchers(target.id).flatMap(us =>
              mentions.traverse_(_.notify(
                "comment",
                target.id,
                us - s.user.id,
                s"${s.user.name}（${h.name}）",
                Mentions.excerpt(t),
                Some(c.root)
              ))
            )
            _ <- webhooks.traverse_(_.emit(
              "comment.created",
              target.id,
              s"${s.user.name}（${h.name}）",
              Json.obj("excerpt" -> Mentions.excerpt(t).asJson, "threadId" -> c.root.toString.asJson)
            ))
            _ <- done
            resp <- Created(Json.obj(
              "commentId" -> c.id.asJson,
              "url" -> s"$base/w/${target.workspaceId}/p/${target.id}?comment=${c.root}".asJson
            ))
          } yield resp
      case mode =>
        val withValues: IO[Either[String, JsonObject]] =
          if mode != "row" then IO.pure(Right(JsonObject.empty))
          else
            dbs.get(target.id).flatMap {
              case None => IO.pure(Left("対象がデータベースではありません"))
              case Some(d) =>
                val props = io.circe.parser.parse(d.props).getOrElse(Json.arr())
                convert(props, values).map(_.flatMap(v =>
                  DatabaseSchema.props(props).flatMap(ps => DatabaseSchema.patch(ps, v))
                ))
            }
        withValues.flatMap {
          case Left(m) => err(ErrorCode.InvalidInput, m)
          case Right(vals) =>
            store.createPage(
              target.workspaceId,
              Some(target.id),
              Placement.Last,
              if title.isEmpty then s"${h.name} ${Instant.now().toString.take(16).replace('T', ' ')}" else title,
              None,
              s.user.id,
              Json.fromJsonObject(vals).noSpaces
            ).flatMap {
              case Left(e) => fail(e)
              case Right(np) =>
                val paras = YDocBuilder.split(text)
                for {
                  _ <- if paras.isEmpty then IO.unit
                  else {
                    val (y, json, plain) = YDocBuilder.paragraphs(paras)
                    docs.saveCompaction(np.id, y, json, plain, Set.empty, Set.empty, s.user.id).void
                  }
                  _ <- if mode == "row" then dbs.ensureSeq(target.id).void else IO.unit
                  _ <- audit.log(
                    s,
                    "page.create",
                    "page",
                    np.id,
                    Json.obj(
                      "title" -> np.title.asJson,
                      "workspaceId" -> np.workspaceId.toString.asJson,
                      "via" -> s"inbound:${h.name}".asJson
                    )
                  )
                  _ <- webhooks.traverse_(_.emit("page.created", np.id, s"${s.user.name}（${h.name}）"))
                  _ <- automations.traverse_(_.fire(AutoEvent(
                    "page_created",
                    np.workspaceId,
                    np.id,
                    s"${s.user.name}（${h.name}）"
                  )))
                  _ <- done
                  resp <-
                    Created(Json.obj("pageId" -> np.id.asJson, "url" -> s"$base/w/${np.workspaceId}/p/${np.id}".asJson))
                } yield resp
            }
        }
    }
  }
}
