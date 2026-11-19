package skiwi.web.content

import io.circe.*

// 取り込み・書き出し・複製・エディタのアップロードが使う API

final case class Attachment(
  id: String,
  url: String,
  filename: String = "",
  contentType: String = "",
  size: Double = 0) derives Codec.AsObject

final case class IdPair(
  from: String,
  to: String) derives Codec.AsObject

final case class DuplicateResult(
  rootId: String,
  workspaceId: String,
  pages: List[IdPair],
  attachments: List[IdPair]) derives Codec.AsObject
