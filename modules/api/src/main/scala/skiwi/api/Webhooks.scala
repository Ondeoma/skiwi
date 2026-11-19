package skiwi.api

import io.circe.{Decoder, Encoder}

import java.time.Instant
import java.util.UUID

// ---------------------------------------------------------------- 設定のAPI

/** secret は作成時だけ返す（署名の確認用） */
final case class WebhookDto(id: UUID,
                            name: String,
                            url: String,
                            format: String,
                            events: List[String],
                            enabled: Boolean,
                            createdAt: Instant,
                            createdBy: Option[String],
                            lastStatus: Option[Int],
                            lastError: Option[String],
                            lastAt: Option[Instant],
                            secret: Option[String] = None) derives Encoder.AsObject
final case class CreateWebhookReq(name: String, url: String, format: String, events: List[String]) derives Decoder
final case class UpdateWebhookReq(name: Option[String],
                                  url: Option[String],
                                  format: Option[String],
                                  events: Option[List[String]],
                                  enabled: Option[Boolean]) derives Decoder
final case class WebhookTestDto(ok: Boolean, status: Option[Int], error: Option[String]) derives Encoder.AsObject
