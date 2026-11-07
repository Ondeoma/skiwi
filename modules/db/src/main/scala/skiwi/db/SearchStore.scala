package skiwi.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import skiwi.core.*

import java.util.UUID

/** fuzzy = 語がそのままは含まれず、表記の近さ（2文字組の一致率）で見つかったもの。 */
final case class SearchHit(page: Page, snippet: Option[String], fuzzy: Boolean, attachment: Option[AttachmentHit] = None)
/** 添付ファイルの中身で見つかったときの、その添付ファイル */
final case class AttachmentHit(id: UUID, filename: String)

/** 検索の絞り込み。label は大文字小文字を区別しない。since 以降に更新されたページ、createdBy が作成したページ。 */
final case class SearchFilter(
    label: Option[String] = None, since: Option[java.time.Instant] = None, createdBy: Option[UUID] = None,
    status: Option[String] = None, owner: Option[UUID] = None
):
  def isEmpty: Boolean = label.isEmpty && since.isEmpty && createdBy.isEmpty && status.isEmpty && owner.isEmpty

/**
 * PGroonga による全文検索。権限による絞り込みは呼び出し側で行う。
 *  1. 通常一致: 各語を（正規化した上で）タイトルか本文に含むページ。ひらがな/カタカナ・全角/半角などの違いは索引の正規化で吸収する。
 *  1'. 添付ファイルの中身での一致: 1. に入らなかったページのうち、本文に貼ってある添付ファイル（PDF・Office など）の文字が各語を含むもの。
 *  2. あいまい一致: 1. に入らなかったページのうち、各語の2文字組の半分以上を含むもの（誤字・表記ゆれ）。一致率の高い順。
 */
final class SearchStore(xa: Transactor[IO]):
  import TextNorm.*

  /** 入力を空白で分割し、各語をGroongaのクエリ構文でフレーズとしてエスケープしてAND結合する。
    * 例: `日本 "語"` → `"日本" "\"語\""`
    */
  def toGroongaQuery(input: String): Option[String] =
    val ws = words(input)
    if ws.isEmpty then None else Some(ws.map(phrase).mkString(" "))

  /** PGroonga で正規化（索引と同じ規則）。 */
  def normalize(xs: List[String]): IO[List[String]] =
    if xs.isEmpty then IO.pure(Nil)
    else
      val arr = xs.toArray
      sql"select pgroonga_normalize(w, $Normalizer) from unnest($arr::text[]) with ordinality u(w, i) order by i"
        .query[String].to[List].transact(xa)

  // 全件走査になっても索引と同じ正規化で照合されるよう、index_name を明示する
  private def onTitle(q: String) = fr"p.title &@~ pgroonga_condition($q, index_name => 'page_title_pgroonga')"
  /** タイトルか本文が q に一致するページ。OR を1つの条件に書くと全件を1行ずつ照合するので、索引を使える2つの問い合わせの和にする */
  private def matching(q: String) =
    fr"p.id in (select t.id from page t where t.title &@~ pgroonga_condition($q, index_name => 'page_title_pgroonga')" ++
      fr"union select x.page_id from page_snapshot x where x.kind = 'compact' and x.plain_text &@~ pgroonga_condition($q, index_name => 'page_snapshot_text_pgroonga'))"
  private val pageCols = fr"p.id, p.workspace_id, p.parent_id, p.position, p.title, p.icon, p.created_at, p.updated_at"
  private val fromPages = fr"from page p left join page_snapshot s on s.page_id = p.id and s.kind = 'compact' where p.deleted_at is null"

  private def filterCond(f: SearchFilter): Fragment =
    f.label.fold(fr"")(l => fr"and exists (select 1 from page_label l where l.page_id = p.id and lower(l.label) = lower($l))") ++
      f.since.fold(fr"")(t => fr"and p.updated_at >= $t") ++
      f.createdBy.fold(fr"")(u => fr"and p.created_by = $u") ++
      f.status.fold(fr"")(st => if st == "none" then fr"and p.status is null" else fr"and p.status = $st") ++
      f.owner.fold(fr"")(u => fr"and p.owner_id = $u")

  def search(input: String, workspaceId: Option[UUID], limit: Int = 200, filter: SearchFilter = SearchFilter()): IO[List[SearchHit]] =
    val wsCond0 = workspaceId.fold(fr"")(id => fr"and p.workspace_id = $id") ++ filterCond(filter)
    toGroongaQuery(input) match
      // 語がなく絞り込みだけ: 条件に合うページを新しい順に
      case None if !filter.isEmpty =>
        (fr"select" ++ pageCols ++ fr", null::text" ++ fromPages ++ wsCond0 ++ fr"order by p.updated_at desc limit $limit")
          .query[(Page, Option[String])].to[List].transact(xa).map(_.map((p, sn) => SearchHit(p, sn, fuzzy = false)))
      case None => IO.pure(Nil)
      case Some(q) =>
        val wsCond = wsCond0
        val exact =
          (fr"select" ++ pageCols ++ fr", (pgroonga_snippet_html(s.plain_text, pgroonga_query_extract_keywords($q), 160))[1]" ++
            fromPages ++ wsCond ++ fr"and" ++ matching(q) ++
            fr"order by (" ++ onTitle(q) ++ fr") desc, p.updated_at desc limit $limit")
            .query[(Page, Option[String])].to[List].transact(xa)
        for
          ex <- exact
          at <- inAttachments(q, wsCond, ex.map(_._1.id).toSet, limit - ex.size)
          fz <- fuzzy(words(input), wsCond, ex.map(_._1.id).toSet ++ at.map(_.page.id), limit - ex.size - at.size)
        yield ex.map((p, sn) => SearchHit(p, sn, fuzzy = false)) ++ at ++ fz

  /** 添付ファイルの中身で探す。いまも本文に貼ってある（本文に添付ファイルの ID がある）ものだけ。1ページにつき最新の1件 */
  private def inAttachments(q: String, wsCond: Fragment, exclude: Set[UUID], limit: Int): IO[List[SearchHit]] =
    if limit <= 0 then IO.pure(Nil)
    else
      (fr"select distinct on (p.id)" ++ pageCols ++
        fr", a.id, a.filename, (pgroonga_snippet_html(t.body, pgroonga_query_extract_keywords($q), 160))[1]" ++
        fr"from attachment_text t join attachment a on a.id = t.attachment_id join page p on p.id = a.page_id" ++
        fr"join page_snapshot s on s.page_id = p.id and s.kind = 'compact'" ++
        fr"where p.deleted_at is null and t.status = 'ok'" ++ wsCond ++
        fr"and t.body &@~ pgroonga_condition($q, index_name => 'attachment_text_pgroonga')" ++
        fr"and strpos(s.content::text, a.id::text) > 0" ++
        fr"order by p.id, a.created_at desc limit 1000")
        .query[(Page, UUID, String, Option[String])].to[List].transact(xa)
        .map(_.filterNot(r => exclude(r._1.id)).sortBy(r => -r._1.updatedAt.toEpochMilli).take(limit)
          .map((p, aid, name, sn) => SearchHit(p, sn, fuzzy = false, Some(AttachmentHit(aid, name)))))

  private def fuzzy(raw: List[String], wsCond: Fragment, exclude: Set[UUID], limit: Int): IO[List[SearchHit]] =
    if limit <= 0 then IO.pure(Nil)
    else normalize(raw).flatMap { norm =>
      // 2文字組を持たない（1文字の）語しかない場合は、あいまい一致の意味がないので行わない
      if !norm.exists(bigrams(_).size >= 2) then IO.pure(Nil)
      else
        val grams = norm.flatMap(bigrams).distinct
        val orQ = grams.map(phrase).mkString(" OR ")
        // 強調表示用: 元の表記の2文字組（スニペット関数は既定の正規化で照合するため）と正規化後の組の両方
        val keywords = (raw.flatMap(bigrams) ++ grams).distinct.toArray
        (fr"select" ++ pageCols ++ fr", (pgroonga_snippet_html(s.plain_text, $keywords::text[], 160))[1]," ++
          fr"pgroonga_normalize(p.title, $Normalizer), pgroonga_normalize(coalesce(s.plain_text, ''), $Normalizer)" ++
          fromPages ++ wsCond ++ fr"and" ++ matching(orQ) ++ fr"limit 500")
          .query[(Page, Option[String], String, String)].to[List].transact(xa)
          .map { rows =>
            rows.iterator
              .filterNot(r => exclude(r._1.id))
              .map { (p, sn, nTitle, nText) =>
                // 各語の一致率の最小値（すべての語がある程度一致すること）。タイトルでの一致を優先
                val inTitle = norm.map(ratio(_, nTitle)).min
                val inAll   = norm.map(w => ratio(w, nTitle) max ratio(w, nText)).min
                (p, sn, inTitle, inAll)
              }
              .filter(_._4 >= FuzzyThreshold)
              .toList
              .sortBy((p, _, t, a) => (-t, -a, -p.updatedAt.toEpochMilli))
              .take(limit)
              .map((p, sn, _, _) => SearchHit(p, sn, fuzzy = true))
          }
    }
