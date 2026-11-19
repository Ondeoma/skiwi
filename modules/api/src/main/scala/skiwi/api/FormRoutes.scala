package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json, JsonObject}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import sbirds.auth.Crypto
import skiwi.core.*
import skiwi.db.{DatabaseStore, DbForm, Store}

import java.time.Instant
import java.util.UUID

/** フォームの項目。prop: 列のID（"title" は項目の名前）、label: 表示名（省くと列の名前）、help: 説明 */
final case class FormFieldDto(prop: String,
                              label: Option[String] = None,
                              required: Boolean = false,
                              help: Option[String] = None) derives Encoder.AsObject
object FormFieldDto {
  given Decoder[FormFieldDto] = Decoder.instance { c =>
    for {
      prop <- c.get[String]("prop")
      label <- c.get[Option[String]]("label")
      req <- c.get[Option[Boolean]]("required")
      help <- c.get[Option[String]]("help")
    } yield FormFieldDto(prop, label, req.getOrElse(false), help)
  }
}
final case class FormDto(id: UUID,
                         title: String,
                         description: Option[String],
                         fields: List[FormFieldDto],
                         requireLogin: Boolean,
                         respondentProp: Option[String],
                         enabled: Boolean,
                         url: String,
                         createdBy: Option[String],
                         createdAt: Instant,
                         lastAt: Option[Instant],
                         useCount: Int) derives Encoder.AsObject
final case class SaveFormReq(title: String,
                             description: Option[String],
                             fields: List[FormFieldDto],
                             requireLogin: Boolean = false,
                             respondentProp: Option[String] = None,
                             enabled: Option[Boolean] = None)
object SaveFormReq {
  given Decoder[SaveFormReq] = Decoder.instance { c =>
    for {
      title <- c.get[String]("title")
      desc <- c.get[Option[String]]("description")
      fields <- c.get[List[FormFieldDto]]("fields")
      login <- c.get[Option[Boolean]]("requireLogin")
      resp <- c.get[Option[String]]("respondentProp")
      en <- c.get[Option[Boolean]]("enabled")
    } yield SaveFormReq(title, desc, fields, login.getOrElse(false), resp, en)
  }
}

/** 回答者に見せるフォーム（データは含まない） */
final case class PublicFieldDto(prop: String,
                                label: String,
                                `type`: String,
                                required: Boolean,
                                help: Option[String],
                                options: List[Json]) derives Encoder.AsObject
final case class PublicFormDto(title: String,
                               description: Option[String],
                               fields: List[PublicFieldDto],
                               requireLogin: Boolean,
                               loggedIn: Boolean,
                               userName: Option[String]) derives Encoder.AsObject
final case class SubmitFormReq(values: JsonObject) derives Decoder

/**
 * データベースのフォーム。回答はデータベースの項目になる（フォームを作った人の権限で作る）。
 *  - 設定: /api/pages/{データベース}/forms（データベースの編集権限）
 *  - 回答: /api/forms/public/{トークン}（ログイン不要。「ログインが必要」にすると回答者を記録できる）
 */
final class FormRoutes(protected val store: Store,
                       dbs: DatabaseStore,
                       protected val sessions: SessionAuth,
                       audit: Audit,
                       publicUrl: String,
                       rate: Ref[IO, Map[UUID, List[Long]]],
                       webhooks: Option[WebhookService] = None,
                       automations: Option[Automations] = None) extends PageRouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // ------------------------------------------------------------ 設定
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "forms" => getPageForms(req, id)
    case req @ POST -> Root / "api" / "pages" / UUIDVar(id) / "forms" => postPageForms(req, id)
    case req @ PUT -> Root / "api" / "forms" / UUIDVar(fid) => putForm(req, fid)
    case req @ DELETE -> Root / "api" / "forms" / UUIDVar(fid) => deleteForm(req, fid)
    case req @ GET -> Root / "api" / "forms" / "public" / token => getFormsPublic(req, token)
    case req @ POST -> Root / "api" / "forms" / "public" / token => postFormsPublic(req, token)
  }

  private def getPageForms(req: Request[IO], id: UUID): IO[Response[IO]] =
    withDb(req, id)((_, _) => dbs.forms(id).flatMap(fs => Ok(fs.map(dto))))

  private def postPageForms(req: Request[IO], id: UUID): IO[Response[IO]] =
    withDb(req, id) {
      (s, p) =>
        (decodeBody[SaveFormReq](req), dbs.get(id)).flatMapN {
          case (Left(_), _) | (_, None) => err(ErrorCode.RequestBodyInvalid)
          case (Right(b), Some(d)) =>
            val props = DatabaseSchema.props(parseOrNull(d.props)).getOrElse(Nil)
            validate(b, props) match {
              case Some(m) => err(ErrorCode.InvalidInput, m)
              case None =>
                dbs.createForm(
                  id,
                  Crypto.randomToken(18),
                  b.title.trim,
                  b.description.map(_.trim).filter(_.nonEmpty),
                  b.fields.asJson.noSpaces,
                  b.requireLogin,
                  b.respondentProp,
                  s.user.id
                ).flatMap { f =>
                  audit.log(
                    s,
                    "form.create",
                    "page",
                    id,
                    Json.obj("title" -> p.title.asJson, "form" -> f.title.asJson)
                  ) *> Created(dto(f))
                }
            }
        }
    }

  private def putForm(req: Request[IO], fid: UUID): IO[Response[IO]] =
    withForm(req, fid) {
      (s, fm) =>
        (decodeBody[SaveFormReq](req), dbs.get(fm.databasePageId)).flatMapN {
          case (Left(_), _) | (_, None) => err(ErrorCode.RequestBodyInvalid)
          case (Right(b), Some(d)) =>
            validate(b, DatabaseSchema.props(parseOrNull(d.props)).getOrElse(Nil)) match {
              case Some(m) => err(ErrorCode.InvalidInput, m)
              case None =>
                dbs.updateForm(
                  fid,
                  b.title.trim,
                  b.description.map(_.trim).filter(_.nonEmpty),
                  b.fields.asJson.noSpaces,
                  b.requireLogin,
                  b.respondentProp,
                  b.enabled.getOrElse(fm.enabled)
                ).flatMap {
                  case None => err(ErrorCode.FormNotFound)
                  case Some(f) => audit.log(
                      s,
                      "form.update",
                      "page",
                      fm.databasePageId,
                      Json.obj("form" -> f.title.asJson, "enabled" -> f.enabled.asJson)
                    ) *> Ok(dto(f))
                }
            }
        }
    }

  private def deleteForm(req: Request[IO], fid: UUID): IO[Response[IO]] =
    withForm(req, fid)((s, fm) =>
      dbs.deleteForm(fid) *>
        audit.log(s, "form.delete", "page", fm.databasePageId, Json.obj("form" -> fm.title.asJson)) *> NoContent()
    )

  private def getFormsPublic(req: Request[IO], token: String): IO[Response[IO]] =
    (dbs.formByToken(token), sessions.fromRequest(req)).flatMapN {
      case (Some(f), s) if f.enabled =>
        dbs.get(f.databasePageId).flatMap {
          case None => err(ErrorCode.ItemNotFound)
          case Some(d) =>
            val props = parseOrNull(d.props).asArray.getOrElse(Vector.empty).flatMap(_.asObject)
            val fields = fieldsOf(f).flatMap { fd =>
              if fd.prop == "title" then
                Some(PublicFieldDto("title", fd.label.getOrElse("件名"), "title", fd.required, fd.help, Nil))
              else
                props.find(_("id").flatMap(_.asString).contains(fd.prop)).map { p =>
                  val tpe = p("type").flatMap(_.asString).getOrElse("text")
                  PublicFieldDto(
                    fd.prop,
                    fd.label.getOrElse(p("name").flatMap(_.asString).getOrElse("")),
                    tpe,
                    fd.required,
                    fd.help,
                    p("options").flatMap(_.asArray).toList.flatten.map(o =>
                      Json.obj(
                        "id" -> o.hcursor.get[String]("id").toOption.asJson,
                        "name" -> o.hcursor.get[String]("name").toOption.asJson
                      )
                    )
                  )
                }
            }
            Ok(PublicFormDto(f.title, f.description, fields, f.requireLogin, s.isDefined, s.map(_.user.name)))
        }
      case _ => err(ErrorCode.ItemNotFound)
    }

  private def postFormsPublic(req: Request[IO], token: String): IO[Response[IO]] =
    (dbs.formByToken(token), sessions.fromRequest(req)).flatMapN {
      case (Some(f), _) if !f.enabled => err(ErrorCode.ItemNotFound)
      case (None, _) => err(ErrorCode.ItemNotFound)
      case (Some(f), None) if f.requireLogin => err(ErrorCode.NotLoggedIn)
      case (Some(f), respondent) =>
        allow(f.id).flatMap {
          case false => err(ErrorCode.TooManyRequests)
          case true =>
            body[SubmitFormReq](req) { b =>
              submit(f, respondent, b.values)
            }
        }
    }

  private val base = publicUrl.stripSuffix("/")
  private def fieldsOf(f: DbForm) = parseOrNull(f.fields).as[List[FormFieldDto]].getOrElse(Nil)
  private def dto(f: DbForm) = FormDto(
    f.id,
    f.title,
    f.description,
    fieldsOf(f),
    f.requireLogin,
    f.respondentProp,
    f.enabled,
    s"$base/f/${f.token}",
    f.createdBy,
    f.createdAt,
    f.lastAt,
    f.useCount
  )

  /** フォームで回答できる列の種類 */
  private val Answerable = Set("text", "number", "select", "multi_select", "date", "checkbox", "url")
  private val PerMinute = 30

  private def withDb(req: Request[IO], id: UUID)(f: (AuthSession, Page) => IO[Response[IO]]) =
    withPage(req, id, Role.Edit) {
      (s, p, _) =>
        dbs.isDatabase(id).flatMap(if _ then f(s, p) else err(ErrorCode.NotADatabase))
    }
  private def withForm(req: Request[IO], fid: UUID)(f: (AuthSession, DbForm) => IO[Response[IO]]) =
    dbs.form(fid).flatMap {
      case None => err(ErrorCode.FormNotFound)
      case Some(fm) => withDb(req, fm.databasePageId)((s, _) => f(s, fm))
    }

  /** 設定の確認: 回答できる列だけ、名前は1つまで */
  private def validate(b: SaveFormReq, props: List[DatabaseSchema.Prop]): Option[String] =
    if b.title.trim.isEmpty || b.title.length > 200 then Some("タイトルは1〜200文字にしてください")
    else if b.fields.isEmpty || b.fields.size > 50 then Some("select 1-50 fields")
    else if b.fields.map(_.prop).distinct.size != b.fields.size then Some("項目が重複しています")
    else if b.fields.exists(f => f.prop != "title" && !props.exists(p => p.id == f.prop && Answerable(p.tpe))) then
      Some("使えない種類の項目があります")
    else if b.respondentProp.exists(r => !props.exists(p => p.id == r && p.tpe == "person")) then
      Some("回答者の列はユーザーの列にしてください")
    else if b.description.exists(_.length > 4000) ||
      b.fields.exists(f => f.label.exists(_.length > 200) || f.help.exists(_.length > 1000))
    then Some("説明が長すぎます（1000文字まで）")
    else None

  private def allow(id: UUID): IO[Boolean] = perMinute(rate, id, PerMinute)

  private def submit(f: DbForm, respondent: Option[AuthSession], in: JsonObject): IO[Response[IO]] = {
    val fields = fieldsOf(f)
    // 件名も、フォームの項目にあるときだけ受け付ける
    val title =
      in("title").filter(_ => fields.exists(_.prop == "title")).flatMap(_.asString).map(_.trim.take(500)).getOrElse("")
    val missing = fields.filter(_.required).filter { fd =>
      in(fd.prop).forall(v =>
        v.isNull || v.asString.exists(_.trim.isEmpty) || v.asArray.exists(_.isEmpty) || v.asBoolean.contains(false)
      )
    }
    if missing.nonEmpty then
      err(ErrorCode.FormRequiredMissing, s"必須の項目が入力されていません: ${missing.map(_.prop).mkString(",")}")
    else {
      // フォームの項目に含まれる列だけを受け付ける
      val values = JsonObject.fromIterable(in.toList.filter((k, _) => k != "title" && fields.exists(_.prop == k)))
      (f.createdById.flatTraverse(sessions.sessionFor), dbs.get(f.databasePageId)).flatMapN {
        case (None, _) | (_, None) => err(ErrorCode.FormDisabled)
        case (Some(owner), Some(d)) =>
          store.pageAccess(f.databasePageId, owner).flatMap {
            case Some((db, Role.Edit)) =>
              val withRespondent = (f.respondentProp, respondent) match {
                case (Some(rp), Some(r)) => values.add(rp, Json.arr(r.user.id.toString.asJson))
                case _ => values
              }
              DatabaseSchema.props(parseOrNull(d.props)).flatMap(ps =>
                DatabaseSchema.patch(
                  ps,
                  withRespondent.filter((_, v) => !v.isNull)
                )
              ) match {
                case Left(m) => err(ErrorCode.InvalidInput, m)
                case Right(vals) =>
                  val name =
                    if title.nonEmpty then title else s"${f.title} ${Instant.now().toString.take(16).replace('T', ' ')}"
                  store.createPage(
                    db.workspaceId,
                    Some(db.id),
                    Placement.Last,
                    name,
                    None,
                    owner.user.id,
                    Json.fromJsonObject(vals).noSpaces
                  ).flatMap {
                    case Left(e) => fail(e)
                    case Right(np) =>
                      val who = respondent.fold(s"フォーム「${f.title}」")(r => s"${r.user.name}（フォーム「${f.title}」）")
                      for {
                        _ <- dbs.ensureSeq(db.id)
                        _ <- dbs.formUsed(f.id)
                        _ <- audit.log(
                          owner,
                          "page.create",
                          "page",
                          np.id,
                          Json.obj("title" -> np.title.asJson, "via" -> s"form:${f.title}".asJson)
                        )
                        _ <- webhooks.traverse_(_.emit("page.created", np.id, who))
                        _ <- automations.traverse_(_.fire(AutoEvent("page_created", np.workspaceId, np.id, who)))
                        r <- Created(Json.obj("ok" -> true.asJson))
                      } yield r
                  }
              }
            case _ => err(ErrorCode.FormDisabled)
          }
      }
    }
  }
}
