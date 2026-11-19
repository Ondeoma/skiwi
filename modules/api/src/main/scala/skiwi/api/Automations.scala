package skiwi.api

import skiwi.shared.Titles

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json, JsonObject}
import io.circe.syntax.*
import skiwi.core.*
import skiwi.db.{AutomationRule, AutomationStore, DatabaseStore, OpsStore, Periodic, Store, WebhookStore}

import java.time.{Instant, LocalDate, ZoneId}
import java.util.UUID
import scala.concurrent.duration.*

/** 自動化ルールのきっかけになる出来事。data: 種類ごとの情報 */
final case class AutoEvent(
  kind: String,
  workspaceId: UUID,
  pageId: UUID,
  actor: String,
  data: JsonObject = JsonObject.empty)

/**
 * 自動化ルール（きっかけ → 動作）。
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
 * 動作でページを変えても、ほかのルールは動かさない（連鎖しない）。
 */
final class Automations(
  rules: AutomationStore,
  store: Store,
  ops: OpsStore,
  dbs: DatabaseStore,
  hooks: WebhookStore,
  tz: String = "Asia/Tokyo") {

  /** 出来事に合うルールを実行する（失敗してもリクエストは止めない） */
  def fire(
    ev: AutoEvent
  ): IO[Unit] =
    rules.enabledFor(ev.workspaceId, ev.kind).flatMap(_.traverse_ { r =>
      val t = parseOrNull(r.trigger).hcursor
      val under = t.get[String]("under").toOption.flatMap(uuidOpt)
      (under.fold(IO.pure(true))(u => rules.isUnder(ev.pageId, u)), IO.pure(matches(t, ev))).mapN(_ && _).flatMap {
        case false => IO.unit
        case true => run(r, ev)
      }
    }).handleErrorWith(e => sbirds.observe.Log.report(ErrorCode.AutomationFailed, e.toString, e))

  private def matches(
    t: io.circe.ACursor,
    ev: AutoEvent
  ): Boolean = {
    def s(
      k: String
    ) = t.get[String](k).toOption.filter(_.nonEmpty)
    def d(
      k: String
    ) = ev.data(k)
    ev.kind match {
      case "status_changed" => s("to").forall(to => d("status").flatMap(_.asString).contains(to))
      case "label_added" => s("label").forall(l =>
          d("labels").flatMap(_.asArray).exists(_.flatMap(_.asString).exists(_.equalsIgnoreCase(l)))
        )
      case "page_created" => true
      case "value_changed" =>
        s("database").exists(db => d("database").flatMap(_.asString).contains(db)) &&
        s("prop").exists { p =>
          d("values").flatMap(_.asObject).flatMap(_(p)) match {
            case None => false
            case Some(v) => s("value").forall(want => valueText(v).contains(want))
          }
        }
      case _ => false
    }
  }

  /** 値の比較用の文字（選択肢のID・true/false・文字列・配列ならその中のどれか） */
  private def valueText(
    v: Json
  ): List[String] =
    v.asString.toList ++ v.asBoolean.map(_.toString).toList ++ v.asNumber.map(_.toString).toList ++
      v.asArray.toList.flatten.flatMap(_.asString)

  private def run(
    r: AutomationRule,
    ev: AutoEvent
  ): IO[Unit] =
    sbirds.observe.Telemetry.span("automation.run", "automation.event" -> ev.kind)(perform(r, ev)).attempt.flatMap {
      case Right(_) => rules.recordRun(r.id, None)
      case Left(e) => rules.recordRun(r.id, Some(Option(e.getMessage).getOrElse(e.toString).take(300)))
    }

  private def perform(
    r: AutomationRule,
    ev: AutoEvent
  ): IO[Unit] = {
    val a = parseOrNull(r.action).hcursor
    def s(
      k: String
    ) = a.get[String](k).toOption.filter(_.nonEmpty)
    store.pageMeta(List(ev.pageId)).flatMap { meta =>
      titleOf(ev.pageId).flatMap { title =>
        val message = render(s("message").getOrElse(defaultMessage(ev)), title, ev, r.name)
        a.get[String]("type").toOption match {
          case Some("notify") =>
            recipients(a, ev, meta.get(ev.pageId).flatMap(_.ownerId)).flatMap { users =>
              ops.enqueueNotice(
                "automation",
                users,
                Some(ev.pageId),
                r.name,
                Json.obj("excerpt" -> message.asJson, "rule" -> r.name.asJson).noSpaces,
                mail = true
              ).void
            }
          case Some("webhook") =>
            s("webhook").flatMap(uuidOpt) match {
              case None => IO.raiseError(new IllegalArgumentException("Webhook が設定されていません"))
              case Some(h) => hooks.enqueueTo(
                  h,
                  "automation",
                  ev.pageId,
                  Json.obj("text" -> message.asJson, "rule" -> r.name.asJson, "actor" -> ev.actor.asJson).noSpaces
                ).void
            }
          case Some("set_status") =>
            store.setPageMeta(ev.pageId, None, Some(s("status").filter(UpdatePageReq.Statuses))).void
          case Some("add_label") =>
            s("label").fold(IO.unit)(l =>
              store.labels(ev.pageId).flatMap(cur =>
                if cur.exists(_.equalsIgnoreCase(l)) then IO.unit
                else store.setLabels(ev.pageId, Labels.normalize(cur :+ l))
              )
            )
          case other => IO.raiseError(new IllegalArgumentException(s"unknown action: $other"))
        }
      }
    }
  }

  private def titleOf(
    pageId: UUID
  ): IO[String] =
    store.pageTitle(pageId).map(Titles.display(_))

  /** 通知する人 */
  private def recipients(
    a: io.circe.ACursor,
    ev: AutoEvent,
    owner: Option[UUID]
  ): IO[Set[UUID]] =
    a.get[String]("to").toOption match {
      case Some("owner") => IO.pure(owner.toSet)
      case Some("watchers") => ops.watchers(ev.pageId)
      case Some("users") => IO.pure(a.get[List[UUID]]("users").toOption.getOrElse(Nil).toSet)
      case Some("prop") =>
        a.get[String]("prop").toOption.fold(IO.pure(Set.empty[UUID])) { p =>
          dbs.values(ev.pageId).map(
            _.flatMap(v => io.circe.parser.parse(v).toOption).flatMap(_.hcursor.downField(p).as[List[String]].toOption)
              .getOrElse(Nil).flatMap(uuidOpt).toSet
          )
        }
      case _ => IO.pure(Set.empty)
    }

  private def defaultMessage(
    ev: AutoEvent
  ): String = ev.kind match {
    case "status_changed" => "「{title}」の状態が変わりました（{actor}）"
    case "label_added" => "「{title}」にラベルが付きました（{actor}）"
    case "page_created" => "「{title}」が作られました（{actor}）"
    case "value_changed" => "「{title}」の値が変わりました（{actor}）"
    case "date_reached" => "「{title}」の日付（{date}）になりました"
    case _ => "「{title}」"
  }

  private def render(
    tpl: String,
    title: String,
    ev: AutoEvent,
    rule: String
  ): String =
    tpl.replace("{title}", title).replace("{actor}", ev.actor).replace("{rule}", rule)
      .replace("{date}", ev.data("date").flatMap(_.asString).getOrElse(""))
      .replace("{status}", ev.data("status").flatMap(_.asString).getOrElse(""))
      .take(1000)

  /** 日付のルールを確かめる（同じ項目・同じ日付には1回だけ） */
  val checkDates: IO[Int] =
    IO(LocalDate.now(ZoneId.of(tz))).flatMap { today =>
      rules.enabledDateRules.flatMap(_.flatTraverse { r =>
        val t = parseOrNull(r.trigger).hcursor
        (t.get[String]("database").toOption.flatMap(uuidOpt), t.get[String]("prop").toOption) match {
          case (Some(db), Some(prop)) =>
            val offset = t.get[Int]("offset").toOption.getOrElse(0)
            val unless = (t.get[String]("unlessProp").toOption, t.get[String]("unlessValue").toOption).tupled
            rules.datedRows(r.workspaceId, db, prop).flatMap(_.flatTraverse {
              (
                rowId,
                _,
                date,
                values
              ) =>
                Either.catchNonFatal(LocalDate.parse(date)).toOption match {
                  case None => IO.pure(Nil)
                  case Some(d) =>
                    val due = d.plusDays(offset.toLong)
                    // 当日から3日のうちに確かめられたものだけ（古い日付をまとめて通知しない）
                    val skip = unless.exists(
                      (
                        p,
                        v
                      ) => parseOrNull(values).hcursor.downField(p).focus.exists(j => valueText(j).contains(v))
                    )
                    if today.isBefore(due) || today.isAfter(due.plusDays(3)) || skip then IO.pure(Nil)
                    else
                      rules.markFired(r.id, s"$rowId:$date:$offset").flatMap {
                        case false => IO.pure(Nil)
                        case true => run(
                            r,
                            AutoEvent("date_reached", r.workspaceId, rowId, "自動化", JsonObject("date" -> date.asJson))
                          ).as(List(1))
                      }
                }
            })
          case _ => IO.pure(Nil)
        }
      }).map(_.size)
    }

  val loop: IO[Nothing] =
    Periodic.run(2.minutes, 1.hour, ErrorCode.AutomationDateCheckFailed)(checkDates)
}

// ---------------------------------------------------------------- 設定のAPI

final case class AutomationDto(
  id: UUID,
  name: String,
  enabled: Boolean,
  trigger: Json,
  action: Json,
  createdBy: Option[String],
  createdAt: Instant,
  lastRunAt: Option[Instant],
  lastError: Option[String],
  runCount: Int) derives Encoder.AsObject
final case class SaveAutomationReq(
  name: Option[String],
  enabled: Option[Boolean],
  trigger: Option[Json],
  action: Option[Json]) derives Decoder
