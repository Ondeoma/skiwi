package skiwi.api

import cats.effect.*
import io.circe.Encoder
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import skiwi.core.*
import skiwi.db.{SearchFilter, SearchStore, Store, TextNorm}

import java.util.UUID

final case class AttachmentHitDto(id: UUID, filename: String, url: String) derives Encoder.AsObject
/** attachment: 添付ファイルの中身で見つかったとき、その添付ファイル（snippet もその中身から） */
final case class SearchResultDto(page: PageDto, snippet: Option[String], fuzzy: Boolean, attachment: Option[AttachmentHitDto] = None) derives Encoder.AsObject
final case class WorkspaceHitDto(id: UUID, name: String, role: Role, fuzzy: Boolean) derives Encoder.AsObject

/**
 * GET /api/search?q=...[&workspaceId=...]  — 閲覧可能なページだけを返す（workspaceId なしなら全ワークスペース）。
 * GET /api/search/workspaces?q=...         — 見えるワークスペースを名前で検索する。
 * どちらも、ひらがな/カタカナ・全角/半角などの違いを無視し、表記の近いもの（fuzzy=true）も後ろに含める。
 */
final class SearchRoutes(store: Store, search: SearchStore, protected val sessions: SessionAuth) extends RouteSupport:

  private object Q  extends QueryParamDecoderMatcher[String]("q")
  private object Ws extends OptionalQueryParamDecoderMatcher[String]("workspaceId")
  private object Label extends OptionalQueryParamDecoderMatcher[String]("label")
  private object Days  extends OptionalQueryParamDecoderMatcher[Int]("days")
  private object Mine  extends OptionalQueryParamDecoderMatcher[Boolean]("mine")
  private object St    extends OptionalQueryParamDecoderMatcher[String]("status")
  private object Owner extends OptionalQueryParamDecoderMatcher[String]("owner")

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // 絞り込み: label=ラベル、days=この日数以内に更新、mine=true で自分が作成したページ。q は空でもよい（絞り込みだけで一覧）
    case req @ GET -> Root / "api" / "search" :? Q(q) +& Ws(ws) +& Label(label) +& Days(days) +& Mine(mine) +& St(status) +& Owner(owner) =>
      authed(req) { s =>
        val wsId = ws.flatMap(uuidOpt)
        for
          now  <- IO.realTimeInstant
          filter = SearchFilter(
            label.map(_.trim).filter(_.nonEmpty).map(_.take(40)),
            days.filter(_ > 0).map(d => now.minusSeconds(d.toLong * 86400)),
            Option.when(mine.contains(true))(s.user.id),
            status.filter(st => st == "none" || UpdatePageReq.Statuses(st)),
            Option.when(owner.contains("me"))(s.user.id)
          )
          hits <- search.search(q.take(200), wsId, filter = filter)
          // 見つかったページの権限をまとめて解決して絞り込む
          roles <- store.pagesAccess(hits.map(_.page.id), s)
          dtos = hits.flatMap { h =>
            roles.get(h.page.id).map { (_, r) =>
              val p = h.page
              SearchResultDto(PageDto.of(p, r), h.snippet, h.fuzzy,
                h.attachment.map(a => AttachmentHitDto(a.id, a.filename, s"/api/attachments/${a.id}")))
            }
          }.take(50)
          resp <- Ok(dtos)
        yield resp
      }

    case req @ GET -> Root / "api" / "search" / "workspaces" :? Q(q) =>
      authed(req) { s =>
        val words = TextNorm.words(q.take(200))
        if words.isEmpty then Ok(List.empty[WorkspaceHitDto])
        else
          for
            ws   <- store.listWorkspaces(s)
            norm <- search.normalize(words ++ ws.map(_._1.name))
            (nWords, nNames) = norm.splitAt(words.size)
            hits = ws.zip(nNames).flatMap { case ((w, r), name) =>
              val score = nWords.map(TextNorm.ratio(_, name)).min
              Option.when(score >= TextNorm.FuzzyThreshold)((WorkspaceHitDto(w.id, w.name, r, score < 1.0), score))
            }
            resp <- Ok(hits.sortBy((h, sc) => (-sc, h.name)).map(_._1).take(20))
          yield resp
      }
  }
