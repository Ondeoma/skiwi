package skiwi.api

import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*

/**
 * 連携先のサービスごとの違い（docs/skea-integration.md・docs/skawau-integration.md・docs/smohua-integration.md）。
 * どちらも OAuth 2.0 の認可コード + PKCE で、相手の `/oauth/authorize`・`/oauth/token`・`/oauth/revoke` を使う。
 * @param lookupPath 項目をまとめて読む相手の API（Bearer）。リクエストは `{requestField: [...]}`
 * @param parse      相手の応答から (キー, 画面に返す項目) を取り出す（形が違うものは捨てる）
 * @param details    項目1つの詳しい中身を読む相手の API（種類 → キーからパスを作る。GET・Bearer）。ページの中のブロックが使う
 */
final case class LinkedService(
  name: String,
  lookupPath: String,
  requestField: String,
  usesProjectKeys: Boolean,
  isKey: (IntegrationSettings, String) => Boolean,
  parse: (Json, IntegrationSettings) => List[(String, Json)],
  details: Map[String, String => String] = Map.empty) {
  def complete(
    s: IntegrationSettings
  ): Boolean =
    s.url.nonEmpty && s.clientId.nonEmpty && s.clientSecret.exists(_.nonEmpty) &&
      (!usesProjectKeys || s.projectKeys.nonEmpty)
}

object LinkedService {
  private val Color = "^#[0-9a-fA-F]{3,8}$".r
  private val Uuid = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$".r

  /** skea: 本文のタスクID（例: SKEA-123）にタイトルと状態を出す。対象は設定したプロジェクトキーだけ（大文字と小文字を区別） */
  val skea: LinkedService = LinkedService(
    "skea",
    "/api/tasks/lookup",
    "keys",
    usesProjectKeys = true,
    isKey = (
      s,
      k
    ) =>
      s.projectKeys.exists(p =>
        k.startsWith(p + "-") && k.length > p.length + 1 && k.drop(p.length + 1).forall(_.isDigit)
      ),
    parse = (
      j,
      s
    ) =>
      items(j, "tasks") { c =>
        (c.get[String]("key"), c.get[String]("title")).mapN {
          (
            k,
            title
          ) =>
            val st = c.downField("status")
            // 色は CSS に入れるので #RRGGBB などだけ
            val color = st.get[String]("color").toOption.filter(Color.matches).getOrElse("#6b7280")
            k -> Json.obj(
              "key" -> k.asJson,
              "title" -> title.asJson,
              "url" -> s"${s.url}/tasks/$k".asJson,
              "status" -> Json.obj(
                "name" -> st.get[String]("name").getOrElse("").asJson,
                "category" -> st.get[String]("category").getOrElse("").asJson,
                "color" -> color.asJson
              )
            )
        }
      }
  )

  /** skawau: 本文のファイル・フォルダのリンク（{URL}/view/{ID} など）にファイル名を出す。キーはファイルの ID（UUID、小文字にそろえる） */
  val skawau: LinkedService = LinkedService(
    "skawau",
    "/api/files/lookup",
    "ids",
    usesProjectKeys = false,
    isKey = (
      _,
      k
    ) => Uuid.matches(k) && k == k.toLowerCase,
    parse = (
      j,
      _
    ) =>
      items(j, "files") { c =>
        (c.get[String]("id").map(_.toLowerCase), c.get[String]("name")).mapN {
          (
            id,
            name
          ) =>
            id -> Json.obj(
              "key" -> id.asJson,
              "name" -> name.asJson,
              "isDir" -> c.get[Boolean]("isDir").getOrElse(false).asJson,
              "mimeType" -> c.get[Option[String]]("mimeType").toOption.flatten.asJson
            )
        }
      }
  )

  /**
   * smohua: ページの中に置いたブロックに、フォームの回答（集計・一覧）を出す。キーはフォームの ID（UUID、小文字にそろえる）。
   * 題名・状態・回答の数は lookup で、集計と回答は details（summary・responses）で、本人の smohua の権限で読む
   */
  val smohua: LinkedService = LinkedService(
    "smohua",
    "/api/forms/lookup",
    "ids",
    usesProjectKeys = false,
    isKey = (
      _,
      k
    ) => Uuid.matches(k) && k == k.toLowerCase,
    parse = (
      j,
      s
    ) =>
      items(j, "forms") { c =>
        (c.get[String]("id").map(_.toLowerCase), c.get[String]("title")).mapN {
          (
            id,
            title
          ) =>
            id -> Json.obj(
              "key" -> id.asJson,
              "title" -> title.asJson,
              "status" -> c.get[String]("status").getOrElse("").asJson,
              "responses" -> c.get[Int]("responses").getOrElse(0).asJson,
              "lastResponseAt" -> c.get[Option[String]]("lastResponseAt").toOption.flatten.asJson,
              // リンクは設定した URL から作る（相手の応答の URL はそのまま使わない）
              "url" -> s"${s.url}/forms/$id/responses".asJson
            )
        }
      },
    details =
      Map("summary" -> (id => s"/api/forms/$id/summary"), "responses" -> (id => s"/api/forms/$id/responses?limit=50"))
  )

  val all: List[LinkedService] = List(skea, skawau, smohua)

  private def items(
    j: Json,
    field: String
  )(
    f: io.circe.ACursor => io.circe.Decoder.Result[(String, Json)]
  ): List[(String, Json)] =
    j.hcursor.downField(field).values.toList.flatten.flatMap(t => f(t.hcursor).toOption)
}
