package skiwi.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** 接続時に送る文書の構成要素。状態断片（スナップショット）とupdateログ。どちらもYjsのupdateとして適用できる。 */
final case class DocParts(snapshots: List[(UUID, Array[Byte])], updates: List[(Long, Array[Byte])])

/** 圧縮の結果。 */
/** newMentions: 本文に新しく書かれたメンション（通知の対象） */
final case class CompactionResult(removedUpdates: Int,
                                  removedSnapshots: Int,
                                  versioned: Boolean,
                                  snapshotId: UUID,
                                  newMentions: Set[UUID] = Set.empty)

/**
 * 同時編集ドキュメント(Yjs)の永続化。updateログ＋圧縮スナップショット。
 *
 * 圧縮の安全性（複数ノード対応）: 圧縮では「圧縮したクライアントに届けたと分かっているもの（updateのseqとスナップショットのID）」
 * だけを削除し、そのクライアントの全状態を新しいスナップショットとして追加する。Yjsのupdateは冪等なので、
 * 消し残しや重複があっても内容は失われない。同じページが一時的に複数ノードで開かれていても安全。
 */
final class DocStore(xa: Transactor[IO]) extends TxStore(xa) {

  /** 同じページの書き込みをノードをまたいで直列化する */
  private def lockPageC(pageId: UUID): ConnectionIO[Option[UUID]] =
    sql"select id from page where id = $pageId for update".query[UUID].option

  def appendUpdate(pageId: UUID, data: Array[Byte]): IO[Long] =
    tx(sql"insert into page_updates (page_id, data) values ($pageId, $data) returning seq".query[Long].unique)

  /** 他ノードから通知されたupdateを取得する（圧縮で消えていれば None。その内容はスナップショットに含まれている）。 */
  def getUpdate(pageId: UUID, seq: Long): IO[Option[Array[Byte]]] =
    tx(sql"select data from page_updates where page_id = $pageId and seq = $seq".query[Array[Byte]].option)

  /** 全ての状態断片とupdateログ（seq昇順）。 */
  def load(pageId: UUID): IO[DocParts] = tx {
    for {
      snaps <- sql"""select id, ydoc from page_snapshot where page_id = $pageId and kind in ('compact', 'part')
                     order by created_at""".query[(UUID, Array[Byte])].to[List]
      ups <-
        sql"select seq, data from page_updates where page_id = $pageId order by seq".query[(Long, Array[Byte])].to[List]
    } yield DocParts(snaps, ups)
  }

  /**
   * 圧縮: deliveredSeqs / deliveredSnapshots（圧縮したクライアントが受け取り済みのもの）だけを削除し、
   * そのクライアントの全状態を最新の本文(kind='compact')として保存する。既存の最新本文のうち削除対象でないものは 'part' に格下げする。
   * 直近の版が versionEvery より古く本文が変わっていれば、同じ内容を版履歴にも残す。
   */
  def saveCompaction(pageId: UUID,
                     ydoc: Array[Byte],
                     content: Json,
                     plainText: String,
                     deliveredSeqs: Set[Long],
                     deliveredSnapshots: Set[UUID],
                     by: UUID,
                     authors: Set[UUID] = Set.empty,
                     versionEvery: FiniteDuration = 10.minutes): IO[CompactionResult] = tx {
    val cutoff = Instant.now().minusMillis(versionEvery.toMillis)
    val authorsArr = (authors + by).toArray
    val seqs = deliveredSeqs.toArray
    val snaps = deliveredSnapshots.toArray
    val contentJson = content.noSpaces
    for {
      // 同じページの圧縮をノードをまたいで直列化する
      _ <- lockPageC(pageId)
      ds <-
        sql"delete from page_snapshot where page_id = $pageId and kind in ('compact', 'part') and id = any($snaps)".update.run
      _ <- sql"update page_snapshot set kind = 'part' where page_id = $pageId and kind = 'compact'".update.run
      id <- sql"""insert into page_snapshot (page_id, kind, ydoc, content, plain_text, created_by)
                  values ($pageId, 'compact', $ydoc, $contentJson::jsonb, $plainText, $by) returning id""".query[
        UUID
      ].unique
      n <- sql"delete from page_updates where page_id = $pageId and seq = any($seqs)".update.run
      added <- refreshDerived(pageId, content)
      // 版履歴: 最新版と本文が同じなら作らない
      last <- sql"""select created_at, plain_text, content::text from page_snapshot
                    where page_id = $pageId and kind = 'version' order by created_at desc limit 1"""
        .query[(Instant, String, String)].option
      due = last match {
        case None => true
        case Some((at, txt, json)) => at.isBefore(cutoff) &&
          !(txt == plainText && io.circe.parser.parse(json).contains(content))
      }
      _ <- if due then insertVersionC(pageId, ydoc, contentJson, plainText, by, authorsArr, None).void
      else ().pure[ConnectionIO]
    } yield CompactionResult(n, ds, due, id, added.toSet)
  }

  /** 本文から作る付随の記録（被リンク・担当者のいる ToDo・メンション）を作り直す。新しくメンションされた人を返す。 */
  private def refreshDerived(pageId: UUID, contentJson: Json): ConnectionIO[List[UUID]] = {
    // 被リンク: 本文中のリンク先を記録し直す（自分自身へのリンクは除く）
    val targets = (LinkExtract.targets(contentJson) - pageId).toArray
    val mentioned = LinkExtract.mentions(contentJson).toArray
    // ToDo は（項目, 担当者）ごとの行にして、まとめて入れる。期限なしは空文字
    val tasks = LinkExtract.tasks(contentJson).flatMap(t => t.assignees.toList.map(t -> _))
    val tSeq = tasks.map(_._1.seq).toArray
    val tText = tasks.map(_._1.text).toArray
    val tChecked = tasks.map(_._1.checked.toString).toArray // boolean[] は bit[] として送られるため文字で渡す
    val tAssignee = tasks.map(_._2).toArray
    val tDue = tasks.map(_._1.due.fold("")(_.toString)).toArray
    for {
      _ <- sql"delete from page_link where source_id = $pageId and not (target_id = any($targets))".update.run
      _ <- sql"""insert into page_link (source_id, target_id) select $pageId, t from unnest($targets::uuid[]) t
                 on conflict do nothing""".update.run
      // ToDo: 担当者のいる項目を記録し直す（存在するユーザーのみ）
      _ <- sql"delete from page_task where page_id = $pageId".update.run
      _ <- sql"""insert into page_task (page_id, seq, text, checked, assignee_id, due)
                 select $pageId, t.seq, t.text, t.checked::boolean, u.id, nullif(t.due, '')::date
                 from unnest($tSeq::int[], $tText::text[], $tChecked::text[], $tAssignee::uuid[], $tDue::text[]) as t(seq, text, checked, assignee, due)
                 join app_user u on u.id = t.assignee""".update.run
      // メンション: 本文にいる人を記録し直し、新しく増えた人を返す（存在するユーザーのみ）
      _ <- sql"delete from page_mention where page_id = $pageId and not (user_id = any($mentioned))".update.run
      added <-
        sql"""insert into page_mention (page_id, user_id) select $pageId, u.id from app_user u where u.id = any($mentioned)
                     on conflict do nothing returning user_id""".query[UUID].to[List]
    } yield added
  }

  /**
   * サーバーで本文を変えたとき（API・MCP）: 読み出し・検索に使う本文を新しくする。Yjs の update 自体は更新ログに入れてある前提。
   * 圧縮済みの本文があればその本文だけを差し替え（Yjs の状態は次の圧縮で揃う）、なければ update を圧縮済みの本文として入れる。
   * before があれば、変える前の本文（JSON・平文・ラベル）を同じトランザクションで版として残す。
   */
  def saveServerEdit(pageId: UUID,
                     update: Array[Byte],
                     content: Json,
                     plainText: String,
                     by: UUID,
                     before: Option[(Json, String, String)]): IO[Unit] = tx {
    val json = content.noSpaces
    for {
      _ <- lockPageC(pageId)
      _ <- before.traverse_((j, t, label) =>
        insertVersionC(pageId, Array.emptyByteArray, j.noSpaces, t, by, Array(by), Some(label))
      )
      n <- sql"""update page_snapshot set content = $json::jsonb, plain_text = $plainText
                 where page_id = $pageId and kind = 'compact'""".update.run
      _ <- if n > 0 then ().pure[ConnectionIO]
      else sql"""insert into page_snapshot (page_id, kind, ydoc, content, plain_text, created_by)
                      values ($pageId, 'compact', $update, $json::jsonb, $plainText, $by)""".update.run.void
      _ <- refreshDerived(pageId, content)
    } yield ()
  }

  private def insertVersionC(pageId: UUID,
                             ydoc: Array[Byte],
                             json: String,
                             text: String,
                             by: UUID,
                             authors: Array[UUID],
                             label: Option[String]) =
    sql"""insert into page_snapshot (page_id, kind, ydoc, content, plain_text, created_by, authors, label)
          values ($pageId, 'version', $ydoc, $json::jsonb, $text, $by, $authors, $label)
          returning id""".query[UUID].unique

  /** 手動で版を保存する（復元前の退避、ラベル付き保存）。 */
  def createVersion(pageId: UUID,
                    ydoc: Array[Byte],
                    json: String,
                    text: String,
                    by: UUID,
                    label: Option[String]): IO[UUID] =
    tx(insertVersionC(pageId, ydoc, json, text, by, Array(by), label))

  /** 版の一覧・取得で共通の列（VersionMeta の順） */
  private val versionCols =
    fr"""s.id, s.created_at, s.label, cu.name,
          coalesce((select array_agg(u.name order by u.name) from app_user u where u.id = any(s.authors)), '{}')"""
  private val versionFrom = fr"from page_snapshot s left join app_user cu on cu.id = s.created_by"

  def listVersions(pageId: UUID, limit: Int = 200): IO[List[VersionMeta]] = tx((fr"select" ++ versionCols ++
    versionFrom ++ fr"where s.page_id = $pageId and s.kind = 'version' order by s.created_at desc limit $limit").query[(
    UUID,
    Instant,
    Option[String],
    Option[String],
    Array[String]
  )].to[List].map(_.map(r => VersionMeta(r._1, r._2, r._3, r._4, r._5.toList))))

  /** 版の本文(ProseMirror JSON)と平文。 */
  def getVersion(pageId: UUID, versionId: UUID): IO[Option[(VersionMeta, String, String)]] = tx((fr"select" ++
    versionCols ++ fr", s.content::text, s.plain_text" ++ versionFrom ++
    fr"where s.page_id = $pageId and s.id = $versionId and s.kind = 'version'").query[(
    UUID,
    Instant,
    Option[String],
    Option[String],
    Array[String],
    String,
    String
  )].option.map(_.map(r => (VersionMeta(r._1, r._2, r._3, r._4, r._5.toList), r._6, r._7))))

  /** 現在の本文（圧縮済みの分）。API向け。 */
  def currentContent(pageId: UUID): IO[Option[(String, String, Instant)]] = tx(
    sql"""select content::text, plain_text, created_at from page_snapshot
          where page_id = $pageId and kind = 'compact'""".query[(String, String, Instant)].option
  )

  /** 複数のページの最新の本文（JSON）。本文のないページは含めない */
  def currentContents(ids: List[UUID]): IO[Map[UUID, String]] = {
    val arr = ids.toArray
    if ids.isEmpty then IO.pure(Map.empty)
    else
      tx(sql"select page_id, content::text from page_snapshot where page_id = any($arr) and kind = 'compact'".query[(
        UUID,
        String
      )].to[List].map(_.toMap))
  }

  /** 複数のページの最新の本文の平文 */
  def plainTexts(ids: List[UUID]): IO[Map[UUID, String]] = {
    val arr = ids.toArray
    if ids.isEmpty then IO.pure(Map.empty)
    else
      tx(sql"select page_id, plain_text from page_snapshot where page_id = any($arr) and kind = 'compact'".query[(
        UUID,
        String
      )].to[List].map(_.toMap))
  }

  /** 本文がまだ何も保存されていないか（取り込み先の確認用）。 */
  def isEmpty(pageId: UUID): IO[Boolean] = tx(
    sql"""select not exists(select 1 from page_updates where page_id = $pageId)
             and not exists(select 1 from page_snapshot where page_id = $pageId and kind in ('compact', 'part'))""".query[
      Boolean
    ].unique
  )

  /** このページへリンクしているページ（削除済みを除く）。権限での絞り込みは呼び出し側で行う。 */
  def backlinks(pageId: UUID): IO[List[UUID]] = tx(
    sql"""select l.source_id from page_link l join page p on p.id = l.source_id
          where l.target_id = $pageId and p.deleted_at is null order by p.updated_at desc""".query[UUID].to[List]
  )

  /** 検索・確認用: 最新の平文テキスト（圧縮済みの分のみ）。 */
  def plainText(pageId: UUID): IO[Option[String]] =
    tx(sql"select plain_text from page_snapshot where page_id = $pageId and kind = 'compact'".query[String].option)
}

final case class VersionMeta(id: UUID,
                             createdAt: Instant,
                             label: Option[String],
                             createdBy: Option[String],
                             authors: List[String])
