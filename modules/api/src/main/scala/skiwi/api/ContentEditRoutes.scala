package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.core.*
import skiwi.db.*
import skiwi.ydoc.YEdit

import java.util.UUID

/** 本文の書き換え方。 */
enum ContentEditMode:
  case replace, append, replace_section
object ContentEditMode:
  given Decoder[ContentEditMode] = Decoder[String].emap(s => values.find(_.toString == s).toRight(s"mode は ${values.mkString(" / ")} のどれかです"))

final case class ContentEditReq(mode: ContentEditMode, markdown: String, heading: Option[String]) derives Decoder

/** 本文を Markdown で書き換える（API・MCP 用）。サーバーで Yjs の状態を読み、変えるブロックだけの update を作って、開いている人にもそのまま届ける。
  *  - replace: 本文をすべて置き換える
  *  - append: 末尾に足す
  *  - replace_section: 見出し（heading の文字と一致するもの）から、同じか上の階層の次の見出しの手前までを置き換える
  * 置き換える前の本文は版として残す（版の履歴から戻せる）。
  * inject: update を保存して配る（Collab.injectUpdate） */
final class ContentEditRoutes(
    protected val store: Store,
    docs: DocStore,
    ops: OpsStore,
    protected val sessions: SessionAuth,
    audit: Audit,
    inject: (UUID, Array[Byte]) => IO[Long]
) extends PageRouteSupport:

  private val MaxMarkdown = 256 * 1024
  private val MaxBlocks   = 5000


  private def doc(blocks: Vector[Json]): Json = Json.obj("type" -> "doc".asJson, "content" -> Json.fromValues(blocks))

  /** 見出しの節の範囲（最上位のブロックの番号。until は含まない）。 */
  private def section(blocks: Vector[Json], heading: String): Either[String, (Int, Int)] =
    // (見出しの階層, 文字)。見出しでなければ None
    val heads = blocks.map { b =>
      val c = b.hcursor
      Option.when(c.get[String]("type").contains("heading"))(
        (c.downField("attrs").get[Int]("level").getOrElse(1), MarkdownParse.plainText(Vector(b)).trim))
    }
    val want = heading.trim.dropWhile(_ == '#').trim
    heads.indexWhere(_.exists(_._2 == want)) match
      case -1 =>
        val hs = heads.flatten.map(_._2).take(50)
        Left(s"見出し「$want」が見つかりません。ある見出し: ${if hs.isEmpty then "（なし）" else hs.mkString("、")}")
      case i =>
        val lv  = heads(i).get._1
        val end = heads.indexWhere(_.exists(_._1 <= lv), i + 1)
        Right((i, if end < 0 then blocks.size else end))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" / "pages" / UUIDVar(id) / "content" =>
      withPage(req, id, Role.Edit) { (s, page, _) =>
        req.as[ContentEditReq].attempt.flatMap {
          case Left(e) => err(Status.BadRequest, s"invalid body: ${e.getMessage}")
          case Right(b) if b.markdown.length > MaxMarkdown => err(Status.PayloadTooLarge, "markdown is too large")
          case Right(b) if b.mode == ContentEditMode.replace_section && b.heading.forall(_.trim.isEmpty) =>
            err(Status.BadRequest, "replace_section には heading（見出しの文字）が要ります")
          case Right(b) =>
            val newBlocks = MarkdownParse.blocks(b.markdown)
            if newBlocks.size > MaxBlocks then err(Status.PayloadTooLarge, "too many blocks") else edit(s, page, b, newBlocks)
        }
      }
  }

  private def edit(s: AuthSession, page: Page, b: ContentEditReq, newBlocks: Vector[Json]): IO[Response[IO]] =
    docs.load(page.id).flatMap { parts =>
      // Yjs の状態を読む（CPU を使うので、計算用のスレッドを長く占めないよう blocking で）
      IO.blocking(YEdit.load(parts.snapshots.map(_._2) ++ parts.updates.map(_._2))).attempt
    }.flatMap {
      case Left(e) => err(Status.InternalServerError, s"本文を読めませんでした: ${e.getMessage}")
      case Right(state) =>
        val old = state.json
        val range: Either[String, (Int, Int)] = b.mode match
          case ContentEditMode.replace         => Right((0, old.size))
          case ContentEditMode.append          => Right((old.size, old.size))
          case ContentEditMode.replace_section => section(old, b.heading.getOrElse(""))
        range match
          case Left(m) => err(Status.NotFound, m)
          case Right((from, until)) =>
            val update  = YEdit.replace(state, from, until, newBlocks.flatMap(_.asObject))
            val blocks  = old.patch(from, newBlocks, until - from)
            val summary = Json.obj("mode" -> b.mode.toString.asJson, "removed" -> (until - from).asJson, "added" -> newBlocks.size.asJson)
            // 消すものがあれば、変える前の本文を版として残す
            val before = Option.when(until > from)((doc(old), MarkdownParse.plainText(old), "API で本文を変える前"))
            for
              _ <- inject(page.id, update)
              _ <- docs.saveServerEdit(page.id, update, doc(blocks), MarkdownParse.plainText(blocks), s.user.id, before)
              _ <- skiwi.observe.Metrics.count("skiwi.pages.updated", "via" -> "api")
              _ <- (ops.enqueuePageEdited(page.id, Set(s.user.id), 300, 1800),
                    audit.log(s, "page.edit", "page", page.id, summary.deepMerge(Json.obj("title" -> page.title.asJson, "from" -> from.asJson)))).parTupled
              r <- Ok(summary.deepMerge(Json.obj("pageId" -> page.id.asJson, "blocks" -> blocks.size.asJson)))
            yield r
    }
