package skiwi.api

import io.circe.{Decoder, Encoder}

import java.time.Instant
import java.util.UUID

// ---------------------------------------------------------------- 管理のAPI（管理者のみ）

final case class BackupDto(id: UUID,
                           createdAt: Instant,
                           finishedAt: Option[Instant],
                           status: String,
                           size: Option[Long],
                           pages: Option[Int],
                           attachments: Option[Int],
                           error: Option[String],
                           manual: Boolean) derives Encoder.AsObject
final case class BackupConfigDto(enabled: Boolean, hour: Int, keep: Int) derives Encoder.AsObject, Decoder
final case class BackupsDto(config: Option[BackupConfigDto], backups: List[BackupDto], storage: Boolean)
  derives Encoder.AsObject
