package skiwi.api

import skiwi.core.ErrorCode
import sbirds.http.Errors
import skiwi.shared.Titles

import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import io.circe.{ACursor, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{`WWW-Authenticate`, Allow, Authorization}

import java.util.UUID

/**
 * MCP（Model Context Protocol）のサーバー。POST /mcp に JSON-RPC を送る（Streamable HTTP。応答は JSON で返し、SSE は使わない）。
 * 認証は API トークン（Authorization: Bearer）だけを受け付ける（Cookie では使えない）。
 * 各ツールは REST API（api）をそのトークンで内部から呼ぶので、権限・監査ログは REST API と同じになる。
 * 読み取り専用のトークンでは、変更するツール（readOnly でないもの）を呼ぶ前に断る。
 */
final class McpRoutes(api: HttpRoutes[IO], sessions: SessionAuth, publicUrl: String) extends Http4sDsl[IO] {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "mcp" => postMcp(req)
    case (GET | DELETE) -> Root / "mcp" => mcpMethodNotAllowed()
  }

  private def postMcp(req: Request[IO]): IO[Response[IO]] =
    req.headers.get[Authorization] match {
      case Some(auth @ Authorization(Credentials.Token(AuthScheme.Bearer, _))) =>
        val call = Caller(auth)
        // トークンを確かめる（無効なら 401 を返し、クライアントに認証のやり直しを促す）。読み取り専用かは GET として確かめる
        sessions.fromRequest(Request[IO](Method.GET).putHeaders(auth)).flatMap {
          case None => unauthorized
          case Some(session) =>
            req.as[Json].attempt.flatMap {
              case Left(_) => BadRequest(fail(Json.Null, -32700, "parse error"))
              case Right(j) =>
                j.asArray match {
                  case Some(batch) =>
                    // 変更するツールを含まなければ並行して処理する（含むときは順序に意味があり得るので1件ずつ）
                    val each = (m: Json) => handle(call, session.readOnly, m)
                    val all = if batch.forall(changes(_).isEmpty) then batch.toList.parTraverseN(4)(each)
                    else batch.toList.traverse(each)
                    all.map(_.flatten).flatMap(rs => if rs.isEmpty then Accepted() else Ok(rs.asJson))
                  case None => handle(call, session.readOnly, j).flatMap(_.fold(Accepted())(Ok(_)))
                }
            }
        }
      case _ => unauthorized
    }

  private def mcpMethodNotAllowed(): IO[Response[IO]] =
    MethodNotAllowed(Allow(Method.POST))

  private val base = publicUrl.stripSuffix("/")
  private val markdown = MarkdownExport(u => if u.startsWith("/") then base + u else u, _ => "（ホワイトボード）")
  // 対応する版（新しい順）。クライアントが求めた版に対応していればそれを、なければ最新を返す
  private val Versions = List("2025-06-18", "2025-03-26", "2024-11-05")
  private val Version = "1.0"

  // ------------------------------------------------------------------ REST API の内部呼び出し

  private final class Caller(auth: Authorization) {
    def apply(method: Method, uri: Uri, body: Option[Json] = None): IO[Either[String, Json]] = {
      val req0 = Request[IO](method, uri).putHeaders(auth)
      val req = body.fold(req0)(req0.withEntity(_))
      api.run(req).value.attempt.flatMap {
        case Left(e) => IO.raiseError(e)
        case Right(None) => IO.pure(Left("404 見つかりません"))
        case Right(Some(r)) =>
          r.bodyText.compile.string.map { s =>
            val j = if s.isEmpty then Json.Null else parse(s).getOrElse(Json.fromString(s))
            if r.status.isSuccess then Right(j)
            else Left(s"${r.status.code} ${j.hcursor.get[String]("error").getOrElse(r.status.reason)}")
          }
      }
    }
  }

  private def path(segments: String*): Uri = Uri(path = Uri.Path.unsafeFromString(segments.mkString("/", "/", "")))

  // ------------------------------------------------------------------ ツール

  private type Result = IO[Either[String, String]]

  private final case class Tool(name: String,
                                description: String,
                                schema: Json,
                                readOnly: Boolean,
                                destructive: Boolean = false)(val run: (Caller, ACursor) => Result) {
    def json: Json = Json.obj(
      "name" -> name.asJson,
      "description" -> description.asJson,
      "inputSchema" -> schema,
      "annotations" -> Json.obj(
        "readOnlyHint" -> readOnly.asJson,
        "destructiveHint" -> destructive.asJson,
        "openWorldHint" -> false.asJson
      )
    )
  }

  private def schema(required: List[String], props: (String, Json)*): Json =
    Json.obj(
      "type" -> "object".asJson,
      "properties" -> Json.obj(props*),
      "required" -> required.asJson,
      "additionalProperties" -> false.asJson
    )
  private def str(desc: String): Json = Json.obj("type" -> "string".asJson, "description" -> desc.asJson)
  private def uuid(desc: String): Json =
    Json.obj("type" -> "string".asJson, "format" -> "uuid".asJson, "description" -> desc.asJson)
  private def int(desc: String): Json = Json.obj("type" -> "integer".asJson, "description" -> desc.asJson)
  private def bool(desc: String): Json = Json.obj("type" -> "boolean".asJson, "description" -> desc.asJson)
  private def strs(desc: String): Json =
    Json.obj("type" -> "array".asJson, "items" -> Json.obj("type" -> "string".asJson), "description" -> desc.asJson)
  private def obj(desc: String): Json = Json.obj("type" -> "object".asJson, "description" -> desc.asJson)
  private val pageIdP = "pageId" -> uuid("ページID")

  /** 必須の文字列の引数（なければエラー）。 */
  private def need(a: ACursor, k: String): Either[String, String] =
    a.get[String](k).toOption.map(_.trim).filter(_.nonEmpty).toRight(s"$k を指定してください")
  private def needId(a: ACursor, k: String): Either[String, String] =
    need(a, k).flatMap(s => Either.catchNonFatal(UUID.fromString(s).toString).leftMap(_ => s"$k が UUID ではありません"))
  private def opt(a: ACursor, k: String): Option[String] = a.get[String](k).toOption.map(_.trim).filter(_.nonEmpty)

  private def text(j: Json): String = j.noSpaces
  private def pageUrl(ws: String, id: String) = s"$base/w/$ws/p/$id"
  private def s(j: Json, k: String): String = j.hcursor.get[String](k).getOrElse("")

  /** 引数の確認に通ったら f を実行する（通らなければそのエラーを返す）。 */
  extension [A](e: Either[String, A]) {
    private def withArgs(f: A => Result): Result = e.fold(err => IO.pure(Left(err)), f)
  }
  extension (r: IO[Either[String, Json]]) {
    private def asText: Result = r.map(_.map(text))
  }

  /** 検索の抜粋（キーワードを span で囲んだ HTML）を文字にする。 */
  private def stripTags(h: String): String = Html.unescape(h.replaceAll("<[^>]+>", ""))

  /** ページ一覧を、親子を字下げした行にする（「- 🗒 タイトル [ID]（データベース）」）。 */
  private def tree(pages: List[Json]): String = {
    val byParent = pages.groupBy(p => p.hcursor.get[String]("parentId").toOption)
    def lines(parent: Option[String], depth: Int): List[String] =
      byParent.getOrElse(parent, Nil).sortBy(p => s(p, "position")).flatMap { p =>
        val id = s(p, "id")
        val icon = p.hcursor.get[String]("icon").toOption.filter(_.nonEmpty).fold("")(_ + " ")
        val db = if p.hcursor.get[Boolean]("database").getOrElse(false) then "（データベース）" else ""
        (("  " * depth) + s"- $icon${Titles.display(Option(s(p, "title")))} [$id]$db") :: lines(Some(id), depth + 1)
      }
    val ids = pages.map(p => s(p, "id")).toSet
    // 親が見えないページは最上位として並べる
    val orphans = byParent.keys.flatten.filterNot(ids).toList.flatMap(p => lines(Some(p), 0))
    (lines(None, 0) ++ orphans).mkString("\n")
  }

  /** 本文を書き換える（POST /api/pages/{id}/content）。 */
  private def editContent(call: Caller, a: ACursor, mode: String): Result =
    (needId(a, "pageId"), a.get[String]("markdown").toOption.toRight("markdown を指定してください")).tupled.withArgs(
      (id, md) => {
        val body =
          Json.obj("mode" -> mode.asJson, "markdown" -> md.asJson, "heading" -> opt(a, "heading").asJson).dropNullValues
        call(Method.POST, path("api", "pages", id, "content"), Some(body)).map(_.map { r =>
          val c = r.hcursor
          s"本文を変えました（消したブロック ${c.get[Int]("removed").getOrElse(0)}・足したブロック ${c.get[Int]("added").getOrElse(0)}・今のブロック ${c.get[Int]("blocks").getOrElse(0)}）"
        })
      }
    )

  private val tools: List[Tool] = List(
    Tool("get_me", "自分（この API トークンの持ち主）の名前・所属グループ・管理者かどうか。", schema(Nil), readOnly = true) {
      (call, _) =>
        call(Method.GET, path("api", "me")).asText
    },
    Tool("list_workspaces", "見られるワークスペースの一覧（ID・名前・自分の権限 view / edit）。", schema(Nil), readOnly = true) {
      (call, _) =>
        call(Method.GET, path("api", "workspaces")).map(_.map { j =>
          text(Json.fromValues(j.asArray.toList.flatten.map(w =>
            Json.obj("id" -> s(w, "id").asJson, "name" -> s(w, "name").asJson, "role" -> s(w, "role").asJson)
          )))
        })
    },
    Tool(
      "list_pages",
      "ワークスペースのページを、親子を字下げした一覧で返す（各行の [ ] はページID）。",
      schema(List("workspaceId"), "workspaceId" -> uuid("ワークスペースID")),
      readOnly = true
    ) {
      (call, a) =>
        needId(a, "workspaceId").withArgs(ws =>
          call(Method.GET, path("api", "workspaces", ws, "pages")).map(_.map(j => tree(j.asArray.toList.flatten)))
        )
    },
    Tool(
      "search",
      "ページを全文検索する（空白区切りで AND）。タイトル・ページID・一致箇所の抜粋・URL を返す。",
      schema(
        List("query"),
        "query" -> str("検索語"),
        "workspaceId" -> uuid("ワークスペースで絞る"),
        "label" -> str("ラベルで絞る"),
        "days" -> int("この日数以内に更新されたページに絞る")
      ),
      readOnly = true
    ) {
      (call, a) =>
        need(a, "query").withArgs(q => {
          val params = List(
            "q" -> Some(q),
            "workspaceId" -> opt(a, "workspaceId"),
            "label" -> opt(a, "label"),
            "days" -> a.get[Int]("days").toOption.map(_.toString)
          )
          val uri = params.foldLeft(path("api", "search")) { case (u, (k, v)) => v.fold(u)(u.withQueryParam(k, _)) }
          call(Method.GET, uri).map(_.map { j =>
            text(Json.fromValues(j.asArray.toList.flatten.map { r =>
              val p = r.hcursor.downField("page").focus.getOrElse(Json.Null)
              Json.obj(
                "pageId" -> s(p, "id").asJson,
                "title" -> s(p, "title").asJson,
                "workspaceId" -> s(p, "workspaceId").asJson,
                "snippet" -> r.hcursor.get[String]("snippet").toOption.map(stripTags).asJson,
                "url" -> pageUrl(s(p, "workspaceId"), s(p, "id")).asJson
              )
            }))
          })
        })
    },
    Tool(
      "get_page",
      "ページの情報と本文（Markdown）を読む。データベースのページなら、項目は get_database で読む。",
      schema(List("pageId"), pageIdP),
      readOnly = true
    ) {
      (call, a) =>
        needId(a, "pageId").withArgs(id =>
          (call(Method.GET, path("api", "pages", id)), call(Method.GET, path("api", "pages", id, "content"))).parMapN {
            (pe, ce) =>
              for {
                p <- pe
                c <- ce
              } yield {
                val pc = p.hcursor
                val labels = pc.get[List[String]]("labels").getOrElse(Nil)
                val meta = List(
                  Some(s"ページID: $id"),
                  Some(s"URL: ${pageUrl(s(p, "workspaceId"), id)}"),
                  Some(s"ワークスペースID: ${s(p, "workspaceId")}"),
                  pc.get[String]("parentId").toOption.map(x => s"親ページID: $x"),
                  Some(s"更新: ${s(p, "updatedAt")}"),
                  Some(s"自分の権限: ${s(p, "role")}"),
                  Option.when(labels.nonEmpty)(s"ラベル: ${labels.mkString(", ")}"),
                  pc.get[String]("status").toOption.map(x => s"状態: $x"),
                  pc.get[String]("ownerName").toOption.map(x => s"所有者: $x"),
                  Option.when(pc.get[Boolean]("database").getOrElse(false))("データベース: はい（get_database で列と項目を読めます）")
                ).flatten
                val body = c.hcursor.downField("content").focus.filterNot(_.isNull).map(markdown.body).getOrElse("")
                s"# ${Titles.display(Option(s(p, "title")))}\n\n${meta.mkString("\n")}\n\n---\n\n$body\n"
              }
          }
        )
    },
    Tool("get_backlinks", "このページへリンクしているページの一覧。", schema(List("pageId"), pageIdP), readOnly = true) {
      (call, a) =>
        needId(a, "pageId").withArgs(id =>
          call(Method.GET, path("api", "pages", id, "backlinks")).map(_.map(j =>
            text(Json.fromValues(j.asArray.toList.flatten.map(p =>
              Json.obj("pageId" -> s(p, "id").asJson, "title" -> s(p, "title").asJson)
            )))
          ))
        )
    },
    Tool(
      "create_page",
      "ページを作る。markdown を渡すと本文にする（見出し・リスト・ToDo・引用・コード・表・太字・リンクなど）。",
      schema(
        List("workspaceId", "title"),
        "workspaceId" -> uuid("ワークスペースID"),
        "title" -> str("タイトル"),
        "parentId" -> uuid("親ページID（省くと最上位）"),
        "markdown" -> str("本文（Markdown）")
      ),
      readOnly = false
    ) {
      (call, a) =>
        (needId(a, "workspaceId"), need(a, "title")).tupled.withArgs((ws, title) => {
          val req =
            Json.obj("title" -> title.asJson, "parentId" -> opt(a, "parentId").asJson, "placement" -> "last".asJson)
          call(Method.POST, path("api", "workspaces", ws, "pages"), Some(req)).flatMap {
            case Left(e) => IO.pure(Left(e))
            case Right(p) =>
              val id = s(p, "id")
              val body = opt(a, "markdown") match {
                case None => IO.pure(Right(Json.Null))
                case Some(md) => call(
                    Method.POST,
                    path("api", "pages", id, "content"),
                    Some(Json.obj("mode" -> "replace".asJson, "markdown" -> md.asJson))
                  )
              }
              body.map(_.map(_ => text(Json.obj("pageId" -> id.asJson, "url" -> pageUrl(ws, id).asJson))))
          }
        })
    },
    Tool(
      "replace_page_content",
      "ページの本文を Markdown ですべて置き換える。置き換える前の本文は版として残る（画面の版の履歴から戻せる）。" +
        "データベース・ホワイトボード・埋め込みなど Markdown で表せないブロックも消えるので、一部だけ変えるなら replace_section を使う。",
      schema(List("pageId", "markdown"), pageIdP, "markdown" -> str("新しい本文（Markdown）")),
      readOnly = false,
      destructive = true
    ) {
      (call, a) =>
        editContent(call, a, "replace")
    },
    Tool(
      "append_to_page",
      "ページの本文の末尾に Markdown を足す（今の本文はそのまま）。",
      schema(List("pageId", "markdown"), pageIdP, "markdown" -> str("足す本文（Markdown）")),
      readOnly = false
    ) {
      (call, a) =>
        editContent(call, a, "append")
    },
    Tool(
      "replace_section",
      "見出しの節（その見出しから、同じか上の階層の次の見出しの手前まで）を Markdown で置き換える。" +
        "markdown には見出しの行も含める（含めなければ見出しも消える）。ほかの部分はそのまま。置き換える前の本文は版として残る。",
      schema(
        List("pageId", "heading", "markdown"),
        pageIdP,
        "heading" -> str("置き換える節の見出しの文字（get_page で見える見出し。# は付けなくてよい）"),
        "markdown" -> str("新しい節（Markdown。見出しの行を含む）")
      ),
      readOnly = false,
      destructive = true
    ) {
      (call, a) =>
        editContent(call, a, "replace_section")
    },
    Tool(
      "update_page",
      "ページのタイトル・アイコン・状態を変える（渡した項目だけ変わる）。本文は replace_page_content などで変える。",
      schema(
        List("pageId"),
        pageIdP,
        "title" -> str("タイトル"),
        "icon" -> str("アイコン（絵文字1つ）"),
        "status" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> List("draft", "review", "published", "deprecated").asJson,
          "description" -> "状態（下書き・レビュー中・公開済み・廃止）".asJson
        )
      ),
      readOnly = false
    ) {
      (call, a) =>
        needId(a, "pageId").withArgs(id => {
          val fields = List("title", "icon", "status").flatMap(k => opt(a, k).map(k -> _.asJson))
          if fields.isEmpty then IO.pure(Left("変える項目（title / icon / status）を指定してください"))
          else call(Method.PATCH, path("api", "pages", id), Some(Json.obj(fields*))).map(_.map(_ => "変更しました"))
        })
    },
    Tool(
      "move_page",
      "ページを移動する（子ページも一緒に動く）。parentId を省くと最上位へ。workspaceId で別のワークスペースへ。",
      schema(
        List("pageId"),
        pageIdP,
        "parentId" -> uuid("新しい親ページID"),
        "workspaceId" -> uuid("移動先のワークスペースID"),
        "afterId" -> uuid("このページの直後に並べる（省くと末尾）")
      ),
      readOnly = false
    ) {
      (call, a) =>
        needId(a, "pageId").withArgs(id => {
          val req = Json.obj(
            "parentId" -> opt(a, "parentId").asJson,
            "workspaceId" -> opt(a, "workspaceId").asJson,
            "afterId" -> opt(a, "afterId").asJson,
            "placement" -> (if opt(a, "afterId").isEmpty then "last".asJson else Json.Null)
          ).dropNullValues
          call(Method.POST, path("api", "pages", id, "move"), Some(req)).map(_.map(_ => "移動しました"))
        })
    },
    Tool(
      "delete_page",
      "ページをゴミ箱へ移す（子ページも）。ゴミ箱から元に戻せる。",
      schema(List("pageId"), pageIdP),
      readOnly = false,
      destructive = true
    ) {
      (call, a) =>
        needId(a, "pageId").withArgs(id => call(Method.DELETE, path("api", "pages", id)).map(_.map(_ => "ゴミ箱へ移しました")))
    },
    Tool(
      "set_labels",
      "ページのラベルを置き換える。",
      schema(List("pageId", "labels"), pageIdP, "labels" -> strs("ラベル（空の配列で全部外す）")),
      readOnly = false
    ) {
      (call, a) =>
        needId(a, "pageId").withArgs(id =>
          call(
            Method.PUT,
            path("api", "pages", id, "labels"),
            Some(Json.obj("labels" -> a.get[List[String]]("labels").getOrElse(Nil).asJson))
          ).asText
        )
    },
    Tool("list_comments", "ページのコメントの一覧（返信は threadId で元のコメントを指す）。", schema(List("pageId"), pageIdP), readOnly = true) {
      (call, a) =>
        needId(a, "pageId").withArgs(id =>
          call(Method.GET, path("api", "pages", id, "comments")).map(_.map(j =>
            text(Json.fromValues(j.asArray.toList.flatten.map(c =>
              Json.obj(
                "id" -> s(c, "id").asJson,
                "threadId" -> c.hcursor.get[String]("threadId").toOption.asJson,
                "author" -> s(c, "authorName").asJson,
                "body" -> s(c, "body").asJson,
                "quote" -> c.hcursor.get[String]("quote").toOption.asJson,
                "createdAt" -> s(c, "createdAt").asJson,
                "resolved" -> c.hcursor.get[String]("resolvedAt").isRight.asJson
              ).dropNullValues
            )))
          ))
        )
    },
    Tool(
      "add_comment",
      "ページにコメントする。threadId を渡すとそのコメントへの返信になる。",
      schema(List("pageId", "body"), pageIdP, "body" -> str("コメントの本文"), "threadId" -> uuid("返信先のコメントID")),
      readOnly = false
    ) {
      (call, a) =>
        (needId(a, "pageId"), need(a, "body")).tupled.withArgs((id, body) =>
          call(
            Method.POST,
            path("api", "pages", id, "comments"),
            Some(Json.obj("body" -> body.asJson, "threadId" -> opt(a, "threadId").asJson).dropNullValues)
          )
            .map(_.map(c => text(Json.obj("commentId" -> s(c, "id").asJson))))
        )
    },
    Tool(
      "get_database",
      "データベースのページの列（ID・名前・種類・選択肢）と項目（ページID・タイトル・列のID → 値）。",
      schema(List("pageId"), pageIdP),
      readOnly = true
    ) {
      (call, a) =>
        needId(a, "pageId").withArgs(id =>
          call(Method.GET, path("api", "pages", id, "database")).map(_.map { j =>
            val c = j.hcursor
            text(Json.obj(
              "columns" -> c.downField("props").focus.getOrElse(Json.arr()),
              "rows" -> Json.fromValues(c.downField("rows").focus.flatMap(_.asArray).toList.flatten.map { r =>
                val p = r.hcursor.downField("page").focus.getOrElse(Json.Null)
                Json.obj(
                  "pageId" -> s(p, "id").asJson,
                  "title" -> s(p, "title").asJson,
                  "values" -> r.hcursor.downField("values").focus.getOrElse(Json.obj())
                )
              }),
              "users" -> c.downField("users").focus.getOrElse(Json.obj())
            ))
          })
        )
    },
    Tool(
      "add_database_rows",
      "データベースに項目を追加する（最大2000件）。values は「列のID → 値」（選択肢は選択肢のID、日付は YYYY-MM-DD）。",
      schema(
        List("pageId", "rows"),
        "pageId" -> uuid("データベースのページID"),
        "rows" -> Json.obj(
          "type" -> "array".asJson,
          "description" -> "追加する項目".asJson,
          "items" -> schema(List("title"), "title" -> str("項目のタイトル"), "values" -> obj("列のID → 値"))
        )
      ),
      readOnly = false
    ) {
      (call, a) =>
        needId(a, "pageId").withArgs(id =>
          call(
            Method.POST,
            path("api", "pages", id, "database", "rows", "bulk"),
            Some(Json.obj("rows" -> a.downField("rows").focus.getOrElse(Json.arr())))
          ).asText
        )
    },
    Tool(
      "update_item_values",
      "データベースの項目（ページ）の値を変える。values は「列のID → 値」（null で消す）。",
      schema(List("pageId", "values"), "pageId" -> uuid("項目のページID"), "values" -> obj("列のID → 値")),
      readOnly = false
    ) {
      (call, a) =>
        needId(a, "pageId").withArgs(id =>
          call(
            Method.PATCH,
            path("api", "pages", id, "props"),
            Some(Json.obj("values" -> a.downField("values").focus.getOrElse(Json.obj())))
          ).map(_.map(_ => "変更しました"))
        )
    },
    Tool(
      "list_my_tasks",
      "自分に割り当てられた ToDo の一覧（ページ・期限つき）。",
      schema(Nil, "includeDone" -> bool("完了したものも含める")),
      readOnly = true
    ) {
      (call, a) =>
        val uri = if a.get[Boolean]("includeDone").getOrElse(false) then
          path("api", "tasks").withQueryParam("done", "true")
        else path("api", "tasks")
        call(Method.GET, uri).asText
    }
  )
  private val byName = tools.map(t => t.name -> t).toMap

  // ------------------------------------------------------------------ JSON-RPC

  private def ok(id: Json, result: Json): Json = Json.obj("jsonrpc" -> "2.0".asJson, "id" -> id, "result" -> result)
  private def fail(id: Json, code: Int, msg: String): Json =
    Json.obj("jsonrpc" -> "2.0".asJson, "id" -> id, "error" -> Json.obj("code" -> code.asJson, "message" -> msg.asJson))
  private def toolResult(r: Either[String, String]): Json =
    Json.obj(
      "content" -> Json.arr(Json.obj("type" -> "text".asJson, "text" -> r.merge.asJson)),
      "isError" -> r.isLeft.asJson
    )

  /** 変更するツールの呼び出しならそのツール */
  private def changes(msg: Json): Option[Tool] = {
    val c = msg.hcursor
    Option.when(c.get[String]("method").contains("tools/call"))(c.downField("params").get[String]("name").getOrElse(""))
      .flatMap(byName.get).filterNot(_.readOnly)
  }

  /** 1件の要求を処理する。通知（id なし）なら None。 */
  private def handle(call: Caller, readOnly: Boolean, msg: Json): IO[Option[Json]] = {
    val c = msg.hcursor
    val id = c.downField("id").focus
    val method = c.get[String]("method").getOrElse("")
    val params = c.downField("params")
    id match {
      case None => IO.pure(None) // 通知（notifications/initialized など）は何もしない
      case Some(id) =>
        method match {
          case "initialize" =>
            val asked = params.get[String]("protocolVersion").toOption
            IO.pure(Some(ok(
              id,
              Json.obj(
                "protocolVersion" -> asked.filter(Versions.contains).getOrElse(Versions.head).asJson,
                "capabilities" -> Json.obj("tools" -> Json.obj("listChanged" -> false.asJson)),
                "serverInfo" -> Json.obj("name" -> "skiwi".asJson, "version" -> Version.asJson),
                "instructions" ->
                  ("skiwi（チーム向けの Wiki）のワークスペース・ページ・コメント・データベースを読み書きするツールです。" +
                    "ページは search か list_workspaces → list_pages で探し、get_page で本文を読みます。本文は Markdown で書き換えられます。" +
                    "権限はこの API トークンの持ち主と同じで、読み取り専用のトークンでは変更できません。").asJson
              )
            )))
          case "ping" => IO.pure(Some(ok(id, Json.obj())))
          case "tools/list" => IO.pure(Some(ok(id, Json.obj("tools" -> tools.map(_.json).asJson))))
          case "tools/call" =>
            val name = params.get[String]("name").getOrElse("")
            byName.get(name) match {
              case None => IO.pure(Some(fail(id, -32602, s"unknown tool: $name")))
              case Some(t) if readOnly && !t.readOnly =>
                IO.pure(Some(ok(id, toolResult(Left("読み取り専用の API トークンでは変更できません")))))
              case Some(t) =>
                val args = params.downField("arguments").focus.filter(_.isObject).getOrElse(Json.obj()).hcursor
                t.run(call, args).handleError(e => Left(s"内部エラー: ${e.getMessage}")).map(r =>
                  Some(ok(id, toolResult(r)))
                )
            }
          case "resources/list" => IO.pure(Some(ok(id, Json.obj("resources" -> Json.arr()))))
          case "prompts/list" => IO.pure(Some(ok(id, Json.obj("prompts" -> Json.arr()))))
          case other => IO.pure(Some(fail(id, -32601, s"method not found: $other")))
        }
    }
  }

  private def unauthorized: IO[Response[IO]] =
    Errors.respond(ErrorCode.McpTokenRequired).map(_.putHeaders(`WWW-Authenticate`(Challenge("Bearer", "skiwi"))))

}
