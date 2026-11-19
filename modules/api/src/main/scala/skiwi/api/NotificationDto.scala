package skiwi.api

import io.circe.{Decoder, Encoder}
import skiwi.db.NotificationRow

import java.time.Instant
import java.util.UUID

final case class NotificationDto(id: Long,
                                 kind: String,
                                 actors: List[String],
                                 role: Option[String],
                                 pageId: Option[UUID],
                                 pageTitle: Option[String],
                                 pageIcon: Option[String],
                                 workspaceId: Option[UUID],
                                 workspaceName: Option[String],
                                 createdAt: Instant,
                                 read: Boolean,
                                 excerpt: Option[String] = None,
                                 threadId: Option[String] = None,
                                 due: Option[String] = None,
                                 requestId: Option[String] = None) derives Encoder.AsObject

object NotificationDto {

  /** 通知の行から（role・excerpt などは detail の JSON から取り出す）。画面のベルとプッシュ通知で使う */
  def of(n: NotificationRow): NotificationDto = {
    val detail = io.circe.parser.parse(n.detail).toOption.map(_.hcursor)
    def field(k: String) = detail.flatMap(_.get[String](k).toOption)
    NotificationDto(
      n.id,
      n.kind,
      n.actors,
      field("role"),
      n.pageId,
      n.pageTitle,
      n.pageIcon,
      n.pageWorkspaceId.orElse(n.workspaceId),
      n.workspaceName.orElse(field("workspace")),
      n.createdAt,
      n.readAt.isDefined,
      field("excerpt").filter(_.nonEmpty),
      field("threadId"),
      field("due"),
      field("requestId")
    )
  }
}
