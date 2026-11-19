package skiwi.api

import io.circe.{Decoder, Json}
import skiwi.core.*

import java.util.UUID

object AutomationSchema {
  val Triggers = Set("status_changed", "label_added", "page_created", "value_changed", "date_reached")
  private def uuid(
    s: Option[String]
  ) = s.exists(uuidOpt(_).isDefined)

  def trigger(
    j: Json
  ): Either[String, Unit] = {
    val c = j.hcursor
    def s(
      k: String
    ) = c.get[String](k).toOption.filter(_.nonEmpty)
    s("type") match {
      case Some(t) if !Triggers(t) => Left("きっかけが正しくありません")
      case None => Left("きっかけが正しくありません")
      case Some(t) =>
        if s("under").exists(u => !uuid(Some(u))) then Left("きっかけの対象のページが正しくありません")
        else
          t match {
            case "label_added" if s("label").isEmpty => Left("きっかけのラベルを指定してください")
            case "value_changed" | "date_reached" if !uuid(s("database")) || s("prop").isEmpty =>
              Left("きっかけのデータベースと列を指定してください")
            case "status_changed" if s("to").exists(x => !UpdatePageReq.Statuses(x)) => Left("状態が正しくありません")
            case _ if j.noSpaces.length > 4000 => Left("きっかけの設定が大きすぎます")
            case _ => Right(())
          }
    }
  }

  def action(
    j: Json
  ): Either[String, Unit] = {
    val c = j.hcursor
    def s(
      k: String
    ) = c.get[String](k).toOption.filter(_.nonEmpty)
    val base: Either[String, Unit] = s("type") match {
      case Some("notify") =>
        s("to") match {
          case Some("owner" | "watchers") => Right(())
          case Some("users") if c.get[List[UUID]]("users").toOption.exists(us => us.nonEmpty && us.size <= 50) =>
            Right(())
          case Some("prop") if s("prop").isDefined => Right(())
          case _ => Left("通知先を指定してください")
        }
      case Some("webhook") if uuid(s("webhook")) => Right(())
      case Some("set_status") if s("status").forall(UpdatePageReq.Statuses) => Right(())
      case Some("add_label") if s("label").isDefined => Right(())
      case _ => Left("アクションが正しくありません")
    }
    base.flatMap(_ => if s("message").exists(_.length > 1000) then Left("メッセージが長すぎます（1000文字まで）") else Right(()))
  }
}
