package skiwi.shared

import io.circe.*

import java.util.UUID

// バックエンド(JVM)とフロントエンド(Scala.js)の両方で使う API の型。日時は Timestamp（JVM では Instant）。
// 画面を Scalactive に移すときに、その画面が使う DTO をここへ移す（skiwi.api からは export で見える）。

final case class TokenDto(
  id: UUID,
  name: String,
  scope: String,
  createdAt: Timestamp,
  expiresAt: Option[Timestamp],
  lastUsedAt: Option[Timestamp])
  derives Encoder.AsObject, Decoder
final case class CreateTokenReq(
  name: String,
  scope: String,
  expiresInDays: Option[Int]) derives Encoder.AsObject, Decoder
final case class CreatedTokenDto(
  token: String,
  info: TokenDto) derives Encoder.AsObject, Decoder

final case class AuditDto(
  id: Long,
  at: Timestamp,
  actorId: Option[UUID],
  actorName: Option[String],
  via: String,
  action: String,
  targetType: Option[String],
  targetId: Option[String],
  detail: Json) derives Encoder.AsObject, Decoder

/** 管理者の画面: エージェント専用アカウントとその API キー（キーのハッシュは返さない） */
final case class AgentKeyDto(
  id: UUID,
  name: String,
  scope: String,
  createdAt: Timestamp,
  lastUsedAt: Option[Timestamp],
  expiresAt: Option[Timestamp],
  revoked: Boolean) derives Encoder.AsObject, Decoder
final case class AgentDto(
  id: UUID,
  name: String,
  disabled: Boolean,
  createdAt: Timestamp,
  keys: List[AgentKeyDto])
  derives Encoder.AsObject, Decoder
final case class CreateAgentReq(
  name: String) derives Encoder.AsObject, Decoder
final case class UpdateAgentReq(
  name: Option[String] = None,
  disabled: Option[Boolean] = None) derives Encoder.AsObject, Decoder
