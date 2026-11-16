package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json, JsonObject}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.core.*
import skiwi.db.{AutomationRule, AutomationStore, DatabaseStore, OpsStore, Periodic, Store, WebhookStore}

import java.time.{Instant, LocalDate, ZoneId}
import java.util.UUID
import scala.concurrent.duration.*

/** 自動化ルールのきっかけになる出来事。data: 種類ごとの情報 */
final case class AutoEvent(kind: String, workspaceId: UUID, pageId: UUID, actor: String, data: JsonObject = JsonObject.empty)

/** 自動化ルール（きっかけ → 動作）。
  * きっかけ（trigger.type）:
  *   - status_changed { to?: 状態 }            ページの状態が変わった
  *   - label_added { label }                    ラベルが付いた
  *   - page_created {}                          ページが作られた
  *   - value_changed { database, prop, value? } データベースの項目の値が変わった（value: 選択肢のID・"true"/"false"・文字列）
  *   - date_reached { database, prop, offset, unlessProp?, unlessValue? } 日付の列の日（offset 日ずらす）になった（1時間ごとに確認）
  *   共通: under?: ページID（その下のページだけ）
  * 動作（action.type）:
  *   - notify { to: owner | watchers | users | prop, users?, prop?, message? }  画面内とメールで通知
  *   - webhook { webhook, message? }    外部への通知（Webhook）で送る
  *   - set_status { status }            状態を変える
  *   - add_label { label }              ラベルを付ける
  * 動作でページを変えても、ほかのルールは動かさない（連鎖しない）。 */
final class Automations(
    rules: AutomationStore, store: Store, ops: OpsStore, dbs: DatabaseStore, hooks: WebhookStore, tz: String = "Asia/Tokyo"
):
  /** 出来事に合うルールを実行する（失敗してもリクエストは止めない） */
  def fire(ev: AutoEvent): IO[Unit] =
    rules.enabledFor(ev.workspaceId, ev.kind).flatMap(_.traverse_ { r =>
      val t = parseOrNull(r.trigger).hcursor
      val under = t.get[String]("under").toOption.flatMap(uuidOpt)
      (under.fold(IO.pure(true))(u => rules.isUnder(ev.pageId, u)), IO.pure(matches(t, ev))).mapN(_ && _).flatMap {
        case false => IO.unit
        case true  => run(r, ev)
      }
    }).handleErrorWith(e => IO.println(s"[automation] failed: $e"))

  private def matches(t: io.circe.ACursor, ev: AutoEvent): Boolean =
    def s(k: String) = t.get[String](k).toOption.filter(_.nonEmpty)
    def d(k: String) = ev.data(k)
    ev.kind match
      case "status_changed" => s("to").forall(to => d("status").flatMap(_.asString).contains(to))
      case "label_added"    => s("label").forall(l => d("labels").flatMap(_.asArray).exists(_.flatMap(_.asString).exists(_.equalsIgnoreCase(l))))
      case "page_created"   => true
      case "value_changed" =>
        s("database").exists(db => d("database").flatMap(_.asString).contains(db)) &&
          s("prop").exists { p =>
            d("values").flatMap(_.asObject).flatMap(_(p)) match
              case None => false
              case Some(v) => s("value").forall(want => valueText(v).contains(want))
          }
      case _ => false

  /** 値の比較用の文字（選択肢のID・true/false・文字列・配列ならその中のどれか） */
  private def valueText(v: Json): List[String] =
    v.asString.toList ++ v.asBoolean.map(_.toString).toList ++ v.asNumber.map(_.toString).toList ++ v.asArray.toList.flatten.flatMap(_.asString)

  private def run(r: AutomationRule, ev: AutoEvent): IO[Unit] =
    perform(r, ev).attempt.flatMap {
      case Right(_) => rules.recordRun(r.id, None)
      case Left(e)  => rules.recordRun(r.id, Some(Option(e.getMessage).getOrElse(e.toString).take(300)))
    }

  private def perform(r: AutomationRule, ev: AutoEvent): IO[Unit] =
    val a = parseOrNull(r.action).hcursor
    def s(k: String) = a.get[String](k).toOption.filter(_.nonEmpty)
    store.pageMeta(List(ev.pageId)).flatMap { meta =>
      titleOf(ev.pageId).flatMap { title =>
        val message = render(s("message").getOrElse(defaultMessage(ev)), title, ev, r.name)
        a.get[String]("type").toOption match
          case Some("notify") =>
            recipients(a, ev, meta.get(ev.pageId).flatMap(_.ownerId)).flatMap { users =>
              ops.enqueueNotice("automation", users, Some(ev.pageId), r.name,
                Json.obj("excerpt" -> message.asJson, "rule" -> r.name.asJson).noSpaces, mail = true).void
            }
          case Some("webhook") =>
            s("webhook").flatMap(uuidOpt) match
              case None    => IO.raiseError(new IllegalArgumentException("webhook not set"))
              case Some(h) => hooks.enqueueTo(h, "automation", ev.pageId, Json.obj("text" -> message.asJson, "rule" -> r.name.asJson, "actor" -> ev.actor.asJson).noSpaces).void
          case Some("set_status") =>
            store.setPageMeta(ev.pageId, None, Some(s("status").filter(UpdatePageReq.Statuses))).void
          case Some("add_label") =>
            s("label").fold(IO.unit)(l => store.labels(ev.pageId).flatMap(cur => if cur.exists(_.equalsIgnoreCase(l)) then IO.unit else store.setLabels(ev.pageId, Labels.normalize(cur :+ l))))
          case other => IO.raiseError(new IllegalArgumentException(s"unknown action: $other"))
      }
    }

  private def titleOf(pageId: UUID): IO[String] =
    store.pageTitle(pageId).map(_.filter(_.trim.nonEmpty).getOrElse("無題"))

  /** 通知する人 */
  private def recipients(a: io.circe.ACursor, ev: AutoEvent, owner: Option[UUID]): IO[Set[UUID]] =
    a.get[String]("to").toOption match
      case Some("owner")    => IO.pure(owner.toSet)
      case Some("watchers") => ops.watchers(ev.pageId)
      case Some("users")    => IO.pure(a.get[List[UUID]]("users").toOption.getOrElse(Nil).toSet)
      case Some("prop") =>
        a.get[String]("prop").toOption.fold(IO.pure(Set.empty[UUID])) { p =>
          dbs.values(ev.pageId).map(_.flatMap(v => io.circe.parser.parse(v).toOption).flatMap(_.hcursor.downField(p).as[List[String]].toOption)
            .getOrElse(Nil).flatMap(uuidOpt).toSet)
        }
      case _ => IO.pure(Set.empty)

  private def defaultMessage(ev: AutoEvent): String = ev.kind match
    case "status_changed" => "「{title}」の状態が変わりました（{actor}）"
    case "label_added"    => "「{title}」にラベルが付きました（{actor}）"
    case "page_created"   => "「{title}」が作られました（{actor}）"
    case "value_changed"  => "「{title}」の値が変わりました（{actor}）"
    case "date_reached"   => "「{title}」の日付（{date}）になりました"
    case _                => "「{title}」"

  private def render(tpl: String, title: String, ev: AutoEvent, rule: String): String =
    tpl.replace("{title}", title).replace("{actor}", ev.actor).replace("{rule}", rule)
      .replace("{date}", ev.data("date").flatMap(_.asString).getOrElse(""))
      .replace("{status}", ev.data("status").flatMap(_.asString).getOrElse(""))
      .take(1000)

  /** 日付のルールを確かめる（同じ項目・同じ日付には1回だけ） */
  val checkDates: IO[Int] =
    IO(LocalDate.now(ZoneId.of(tz))).flatMap { today =>
      rules.enabledDateRules.flatMap(_.flatTraverse { r =>
        val t = parseOrNull(r.trigger).hcursor
        (t.get[String]("database").toOption.flatMap(uuidOpt), t.get[String]("prop").toOption) match
          case (Some(db), Some(prop)) =>
            val offset = t.get[Int]("offset").toOption.getOrElse(0)
            val unless = (t.get[String]("unlessProp").toOption, t.get[String]("unlessValue").toOption).tupled
            rules.datedRows(r.workspaceId, db, prop).flatMap(_.flatTraverse { (rowId, _, date, values) =>
              Either.catchNonFatal(LocalDate.parse(date)).toOption match
                case None => IO.pure(Nil)
                case Some(d) =>
                  val due = d.plusDays(offset.toLong)
                  // 当日から3日のうちに確かめられたものだけ（古い日付をまとめて通知しない）
                  val skip = unless.exists((p, v) => parseOrNull(values).hcursor.downField(p).focus.exists(j => valueText(j).contains(v)))
                  if today.isBefore(due) || today.isAfter(due.plusDays(3)) || skip then IO.pure(Nil)
                  else rules.markFired(r.id, s"$rowId:$date:$offset").flatMap {
                    case false => IO.pure(Nil)
                    case true  => run(r, AutoEvent("date_reached", r.workspaceId, rowId, "自動化", JsonObject("date" -> date.asJson))).as(List(1))
                  }
            })
          case _ => IO.pure(Nil)
      }).map(_.size)
    }

  val loop: IO[Nothing] =
    Periodic.run(2.minutes, 1.hour)(e => s"[automation] date check failed: $e")(checkDates)

// ---------------------------------------------------------------- 設定のAPI

final case class AutomationDto(
    id: UUID, name: String, enabled: Boolean, trigger: Json, action: Json,
    createdBy: Option[String], createdAt: Instant, lastRunAt: Option[Instant], lastError: Option[String], runCount: Int
) derives Encoder.AsObject
final case class SaveAutomationReq(name: Option[String], enabled: Option[Boolean], trigger: Option[Json], action: Option[Json]) derives Decoder

object AutomationSchema:
  val Triggers = Set("status_changed", "label_added", "page_created", "value_changed", "date_reached")
  val Actions = Set("notify", "webhook", "set_status", "add_label")
  private def uuid(s: Option[String]) = s.exists(uuidOpt(_).isDefined)

  def trigger(j: Json): Either[String, Unit] =
    val c = j.hcursor
    def s(k: String) = c.get[String](k).toOption.filter(_.nonEmpty)
    s("type") match
      case Some(t) if !Triggers(t) => Left("invalid trigger")
      case None => Left("invalid trigger")
      case Some(t) =>
        if s("under").exists(u => !uuid(Some(u))) then Left("invalid trigger: under")
        else t match
          case "label_added" if s("label").isEmpty => Left("trigger needs a label")
          case "value_changed" | "date_reached" if !uuid(s("database")) || s("prop").isEmpty => Left("trigger needs a database and a column")
          case "status_changed" if s("to").exists(x => !UpdatePageReq.Statuses(x)) => Left("invalid status")
          case _ if j.noSpaces.length > 4000 => Left("trigger too large")
          case _ => Right(())

  def action(j: Json): Either[String, Unit] =
    val c = j.hcursor
    def s(k: String) = c.get[String](k).toOption.filter(_.nonEmpty)
    val base: Either[String, Unit] = s("type") match
      case Some("notify") =>
        s("to") match
          case Some("owner" | "watchers") => Right(())
          case Some("users") if c.get[List[UUID]]("users").toOption.exists(us => us.nonEmpty && us.size <= 50) => Right(())
          case Some("prop") if s("prop").isDefined => Right(())
          case _ => Left("notify needs recipients")
      case Some("webhook") if uuid(s("webhook")) => Right(())
      case Some("set_status") if s("status").forall(UpdatePageReq.Statuses) => Right(())
      case Some("add_label") if s("label").isDefined => Right(())
      case _ => Left("invalid action")
    base.flatMap(_ => if s("message").exists(_.length > 1000) then Left("message too long") else Right(()))

/** ワークスペースの自動化ルールの設定（ワークスペースの編集権限が必要） */
final class AutomationRoutes(protected val store: Store, rules: AutomationStore, protected val sessions: SessionAuth, audit: Audit) extends PageRouteSupport:

  private def dto(r: AutomationRule) = AutomationDto(r.id, r.name, r.enabled, parseOrNull(r.trigger), parseOrNull(r.action), r.createdBy, r.createdAt, r.lastRunAt, r.lastError, r.runCount)

  /** きっかけで指定したページ（データベース・配下の起点）が、このワークスペースにあり、作る人が見られること */
  private def targetsOk(s: AuthSession, wsId: UUID, trigger: Json): IO[Boolean] =
    List("database", "under").flatMap(k => trigger.hcursor.get[String](k).toOption.flatMap(uuidOpt)).forallM { id =>
      store.pageAccess(id, s).map(_.exists((p, _) => p.workspaceId == wsId))
    }

  private def withRule(req: Request[IO], id: UUID)(f: (AuthSession, AutomationRule) => IO[Response[IO]]) =
    withWsItem(req, rules.get(id), "rule")(_.workspaceId)((s, _, r) => f(s, r))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "workspaces" / UUIDVar(wsId) / "automations" =>
      withWs(req, wsId, Role.Edit)((_, _, _) => rules.list(wsId).flatMap(rs => Ok(rs.map(dto))))

    case req @ POST -> Root / "api" / "workspaces" / UUIDVar(wsId) / "automations" =>
      withWs(req, wsId, Role.Edit) { (s, _, _) =>
        req.as[SaveAutomationReq].attempt.flatMap {
          case Right(SaveAutomationReq(Some(name), _, Some(t), Some(a))) if name.trim.nonEmpty && name.length <= 100 =>
            (AutomationSchema.trigger(t) *> AutomationSchema.action(a)) match
              case Left(m) => err(Status.BadRequest, m)
              case Right(_) =>
                targetsOk(s, wsId, t).ifM(
                rules.create(wsId, name.trim, t.noSpaces, a.noSpaces, s.user.id).flatMap { r =>
                  audit.log(s, "automation.create", "workspace", wsId, Json.obj("name" -> r.name.asJson, "trigger" -> t, "action" -> a)) *> Created(dto(r))
                },
                err(Status.BadRequest, "trigger: このワークスペースの見られるページを指定してください"))
          case _ => err(Status.BadRequest, "name, trigger and action are required")
        }
      }

    case req @ PATCH -> Root / "api" / "automations" / UUIDVar(id) =>
      withRule(req, id) { (s, r) =>
        body[SaveAutomationReq](req) {
          case b if b.name.exists(n => n.trim.isEmpty || n.length > 100) => err(Status.BadRequest, "invalid name")
          case b =>
            (b.trigger.traverse_(AutomationSchema.trigger) *> b.action.traverse_(AutomationSchema.action)) match
              case Left(m) => err(Status.BadRequest, m)
              case Right(_) =>
                b.trigger.forallM(targetsOk(s, r.workspaceId, _)).ifM(
                rules.update(id, b.name.map(_.trim), b.enabled, b.trigger.map(_.noSpaces), b.action.map(_.noSpaces)).flatMap {
                  case None => err(Status.NotFound, "rule not found")
                  case Some(nr) =>
                    audit.log(s, "automation.update", "workspace", r.workspaceId, Json.obj("name" -> nr.name.asJson, "enabled" -> nr.enabled.asJson)) *> Ok(dto(nr))
                },
                err(Status.BadRequest, "trigger: このワークスペースの見られるページを指定してください"))
        }
      }

    case req @ DELETE -> Root / "api" / "automations" / UUIDVar(id) =>
      withRule(req, id) { (s, r) =>
        rules.delete(id) *> audit.log(s, "automation.delete", "workspace", r.workspaceId, Json.obj("name" -> r.name.asJson)) *> NoContent()
      }
  }
