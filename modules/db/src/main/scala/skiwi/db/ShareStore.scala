package skiwi.db

import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.time.Instant
import java.util.UUID

/**
 * ゲスト向けの公開リンク。
 * mode: live（編集中の内容をそのまま公開）| pinned（公開した時点の内容。page_share_pub）
 * startsAt: 公開の開始日時（それまではゲストは閲覧できない）。slug: わかりやすいURL名。
 */
final case class PageShare(pageId: UUID,
                           token: String,
                           includeChildren: Boolean,
                           createdAt: Instant,
                           createdBy: Option[String],
                           expiresAt: Option[Instant],
                           passwordHash: Option[String],
                           hideHeader: Boolean = false,
                           sidebar: String = "auto",
                           mode: String = "live",
                           publishedAt: Option[Instant] = None,
                           publishedBy: Option[String] = None,
                           startsAt: Option[Instant] = None,
                           slug: Option[String] = None,
                           indexable: Boolean = false,
                           description: Option[String] = None,
                           showReleases: Boolean = false) {
  def expired(now: Instant): Boolean = expiresAt.exists(!_.isAfter(now))
  def notStarted(now: Instant): Boolean = startsAt.exists(_.isAfter(now))
  def pinned: Boolean = mode == "pinned"
}

object PageShare {
  // 項目が多く自動導出できないため、2つの組に分けて読む
  given Read[PageShare] =
    Read[(
      (UUID, String, Boolean, Instant, Option[String], Option[Instant], Option[String], Boolean),
      (
        String,
        String,
        Option[Instant],
        Option[String],
        Option[Instant],
        Option[String],
        Boolean,
        Option[String],
        Boolean
      )
    )]
      .map { case ((a, b, c, d, e, f, g, h), (i, j, k, l, m, n, o, p, q)) =>
        PageShare(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q)
      }
}

/** 公開リンクの設定の変更。None=変更しない、Some(None)=解除。 */
final case class ShareUpdate(includeChildren: Option[Boolean] = None,
                             expiresAt: Option[Option[Instant]] = None,
                             passwordHash: Option[Option[String]] = None,
                             hideHeader: Option[Boolean] = None,
                             sidebar: Option[String] = None,
                             mode: Option[String] = None,
                             startsAt: Option[Option[Instant]] = None,
                             slug: Option[Option[String]] = None,
                             indexable: Option[Boolean] = None,
                             description: Option[Option[String]] = None,
                             showReleases: Option[Boolean] = None)

/** 管理画面用: 公開中のページ。 */
final case class ShareListItem(share: PageShare,
                               title: String,
                               icon: Option[String],
                               workspaceId: UUID,
                               workspaceName: String)

/** 公開範囲に含まれるページ（ゲストに見せる最小限の項目）。 */
final case class SharedPage(id: UUID,
                            parentId: Option[UUID],
                            position: String,
                            title: String,
                            icon: Option[String],
                            updatedAt: Instant,
                            slug: Option[String] = None)

/** 公開の履歴の1件 */
final case class ShareRelease(id: UUID,
                              publishedAt: Instant,
                              publishedBy: Option[String],
                              note: Option[String],
                              pageCount: Int)
final case class ReleasePage(pageId: UUID,
                             parentId: Option[UUID],
                             position: String,
                             title: String,
                             icon: Option[String],
                             content: Option[String],
                             plainText: String)

object ShareStore {

  /** 残す公開の履歴の件数（公開リンクごと） */
  val ReleaseKeep = 100
}

final class ShareStore(xa: Transactor[IO]) extends TxStore(xa) {

  private val shareCols =
    fr"""s.page_id, s.token, s.include_children, s.created_at, u.name, s.expires_at, s.password_hash, s.hide_header, s.sidebar,
         s.mode, s.published_at, pu.name, s.starts_at, s.slug, s.indexable, s.description, s.show_releases"""
  private val shareFrom =
    fr"from page_share s left join app_user u on u.id = s.created_by left join app_user pu on pu.id = s.published_by"
  private val cols = shareCols ++ shareFrom

  def get(pageId: UUID): IO[Option[PageShare]] =
    tx((fr"select" ++ cols ++ fr"where s.page_id = $pageId").query[PageShare].option)

  /** 公開する（既に公開中なら設定だけ変える。リンクは変わらない）。 */
  def upsert(pageId: UUID, token: String, by: UUID, u: ShareUpdate): IO[Either[String, PageShare]] = {
    val inc = u.includeChildren.getOrElse(true)
    val sets = List(
      u.includeChildren.map(v => fr"include_children = $v"),
      u.expiresAt.map(v => fr"expires_at = $v"),
      u.passwordHash.map(v => fr"password_hash = $v"),
      u.hideHeader.map(v => fr"hide_header = $v"),
      u.sidebar.map(v => fr"sidebar = $v"),
      u.mode.map(v => fr"mode = $v"),
      u.startsAt.map(v => fr"starts_at = $v"),
      u.slug.map(v => fr"slug = $v"),
      u.indexable.map(v => fr"indexable = $v"),
      u.description.map(v => fr"description = $v"),
      u.showReleases.map(v => fr"show_releases = $v")
    ).flatten
    val setAll =
      (if sets.isEmpty then List(fr"include_children = page_share.include_children") else sets).intercalate(fr",")
    val ins =
      fr"""insert into page_share (page_id, token, include_children, created_by, expires_at, password_hash, hide_header, sidebar,
                                   mode, starts_at, slug, indexable, description, show_releases)
           values ($pageId, $token, $inc, $by, ${u.expiresAt.flatten}, ${u.passwordHash.flatten}, ${u.hideHeader.getOrElse(
          false
        )},
                   ${u.sidebar.getOrElse("auto")}, ${u.mode.getOrElse(
          "live"
        )}, ${u.startsAt.flatten}, ${u.slug.flatten},
                   ${u.indexable.getOrElse(false)}, ${u.description.flatten}, ${u.showReleases.getOrElse(false)})
           on conflict (page_id) do update set""" ++ setAll
    tx(ins.update.run *> (fr"select" ++ cols ++ fr"where s.page_id = $pageId").query[PageShare].unique)
      .map(Right(_))
      .recover { case e: java.sql.SQLException if e.getSQLState == "23505" => Left("slug_taken") }
  }

  /** 公開中のページの一覧（管理者用。削除済みのページ・ワークスペースは除く）。 */
  def listAll: IO[List[ShareListItem]] = tx(
    (fr"select" ++ shareCols ++ fr", p.title, p.icon, w.id, w.name" ++ shareFrom ++
      fr"""join page p on p.id = s.page_id join workspace w on w.id = p.workspace_id
           where p.deleted_at is null and w.deleted_at is null order by s.created_at desc""").query[
      ShareListItem
    ].to[List]
  )

  def delete(pageId: UUID): IO[Boolean] = tx(sql"delete from page_share where page_id = $pageId".update.run.map(_ > 0))

  /** ワークスペース内で公開中のページ（ツリーの印用）。 */
  def sharedIn(wsId: UUID): IO[Set[UUID]] = tx(
    sql"""select s.page_id from page_share s join page p on p.id = s.page_id
          where p.workspace_id = $wsId and p.deleted_at is null""".query[UUID].to[List].map(_.toSet)
  )

  /** このページを含む、祖先の公開リンク（子ページも公開する設定のもの）。一番近い祖先を返す。 */
  def coveringAncestor(pageId: UUID): IO[Option[(PageShare, String)]] = tx {
    sql"""with recursive anc as (
            select p.parent_id as id, p.inherit_acl as passes, 1 as depth from page p where p.id = $pageId
            union all
            select p.parent_id, p.inherit_acl and a.passes, a.depth + 1 from page p join anc a on p.id = a.id
            where a.id is not null
          )
          select a.id, a.depth, a.passes from anc a where a.id is not null order by a.depth"""
      .query[(UUID, Int, Boolean)].to[List]
      .flatMap { ancestors =>
        // passes: 対象ページから祖先の直下まで、権限の継承が途切れていない
        val candidates = ancestors.filter(_._3).map(_._1)
        candidates.collectFirstSomeM { id =>
          (fr"select" ++ cols ++ fr"where s.page_id = $id and s.include_children").query[PageShare].option.flatMap {
            case None => none[(PageShare, String)].pure[ConnectionIO]
            case Some(s) =>
              sql"select title from page where id = $id and deleted_at is null".query[String].option.map(_.map(s -> _))
          }
        }
      }
  }

  private def scopeC(s: PageShare): ConnectionIO[List[SharedPage]] = {
    val root = s.pageId
    val q =
      if s.includeChildren then
        sql"""with recursive sub as (
                select id, 0 as depth from page where id = $root and deleted_at is null
                union all
                select p.id, sub.depth + 1 from page p join sub on p.parent_id = sub.id
                where p.deleted_at is null and p.inherit_acl
              )
              select p.id, p.parent_id, p.position, p.title, p.icon,
                     greatest(p.updated_at, coalesce((select c.created_at from page_snapshot c where c.page_id = p.id and c.kind = 'compact'), p.updated_at)),
                     p.guest_slug
              from page p join sub on sub.id = p.id order by sub.depth, p.position collate "C", p.id"""
      else
        sql"""select p.id, p.parent_id, p.position, p.title, p.icon,
                     greatest(p.updated_at, coalesce((select c.created_at from page_snapshot c where c.page_id = p.id and c.kind = 'compact'), p.updated_at)),
                     p.guest_slug
              from page p where p.id = $root and p.deleted_at is null"""
    q.query[SharedPage].to[List]
  }

  /** リンク（トークンまたはURL名）が有効なら、公開範囲のページ一覧（先頭が公開したページ）。現在の内容による。 */
  def resolve(key: String): IO[Option[(PageShare, List[SharedPage])]] = tx {
    (fr"select" ++ cols ++ fr"where s.token = $key or s.slug = ${key.toLowerCase}").query[PageShare].to[List].map(
      _.headOption
    ).flatMap {
      case None => none[(PageShare, List[SharedPage])].pure[ConnectionIO]
      case Some(s) =>
        scopeC(s).map(ps => if ps.headOption.exists(_.id == s.pageId) then Some((s, ps)) else None)
    }
  }

  /** 公開範囲（現在の内容）。公開版の作成・未公開の変更の確認用。 */
  def scope(s: PageShare): IO[List[SharedPage]] = tx(scopeC(s))

  /** 公開版を作り直す（公開範囲の各ページの、現在の本文を保存する）。公開の履歴（変更点のメモと内容）も残す。 */
  def publish(pageId: UUID, by: UUID, note: Option[String] = None): IO[Option[PageShare]] = tx {
    (fr"select" ++ cols ++ fr"where s.page_id = $pageId for update of s").query[PageShare].option.flatMap {
      case None => none[PageShare].pure[ConnectionIO]
      case Some(s) =>
        for {
          ps <- scopeC(s)
          ids = ps.map(_.id).toArray
          _ <- sql"delete from page_share_pub where share_page_id = $pageId".update.run
          _ <-
            sql"""insert into page_share_pub (share_page_id, page_id, parent_id, position, title, icon, content, plain_text, updated_at)
                      select $pageId, p.id, p.parent_id, p.position, p.title, p.icon, c.content, coalesce(c.plain_text, ''),
                             greatest(p.updated_at, coalesce(c.created_at, p.updated_at))
                      from page p left join page_snapshot c on c.page_id = p.id and c.kind = 'compact'
                      where p.id = any($ids)""".update.run
          _ <- sql"update page_share set published_at = now(), published_by = $by where page_id = $pageId".update.run
          rid <- sql"""insert into page_share_release (share_page_id, published_by, note, page_count)
                        values ($pageId, $by, $note, ${ps.size}) returning id""".query[UUID].unique
          _ <-
            sql"""insert into page_share_release_page (release_id, page_id, parent_id, position, title, icon, content, plain_text)
                      select $rid, page_id, parent_id, position, title, icon, content, plain_text
                      from page_share_pub where share_page_id = $pageId""".update.run
          // 古い履歴は新しいものから ReleaseKeep 件だけ残す
          _ <- sql"""delete from page_share_release where share_page_id = $pageId and id not in (
                        select id from page_share_release where share_page_id = $pageId order by published_at desc limit ${ShareStore.ReleaseKeep})""".update.run
          r <- (fr"select" ++ cols ++ fr"where s.page_id = $pageId").query[PageShare].unique
        } yield Some(r)
    }
  }

  /** 公開の履歴（新しい順） */
  def releases(sharePageId: UUID): IO[List[ShareRelease]] = tx(
    sql"""select r.id, r.published_at, u.name, r.note, r.page_count from page_share_release r
          left join app_user u on u.id = r.published_by where r.share_page_id = $sharePageId
          order by r.published_at desc""".query[ShareRelease].to[List]
  )

  /** 公開の履歴の1件の内容（ページごとのタイトル・本文） */
  def releasePages(sharePageId: UUID, releaseId: UUID): IO[Option[(ShareRelease, List[ReleasePage])]] = tx {
    sql"""select r.id, r.published_at, u.name, r.note, r.page_count from page_share_release r
          left join app_user u on u.id = r.published_by where r.share_page_id = $sharePageId and r.id = $releaseId"""
      .query[ShareRelease].option.flatMap {
        case None => none[(ShareRelease, List[ReleasePage])].pure[ConnectionIO]
        case Some(r) =>
          sql"""select page_id, parent_id, position, title, icon, content::text, plain_text
                from page_share_release_page where release_id = $releaseId order by position collate "C", page_id"""
            .query[ReleasePage].to[List].map(ps => Some((r, ps)))
      }
  }

  def setReleaseNote(sharePageId: UUID, releaseId: UUID, note: Option[String]): IO[Boolean] = tx(
    sql"update page_share_release set note = $note where id = $releaseId and share_page_id = $sharePageId".update.run.map(
      _ > 0
    )
  )

  /** 公開版のページ（現在の公開範囲にあるものだけ。並びは現在の公開範囲の順）。ページのURL名は現在のもの。 */
  /** 公開版のページの一覧（固定した時点のタイトルなど。本文は guestContents で必要な分だけ読む）。いまの公開範囲 live にあるものだけ */
  def pubPages(s: PageShare, live: List[SharedPage]): IO[List[SharedPage]] = tx {
    sql"""select page_id, parent_id, position, title, icon, updated_at
          from page_share_pub where share_page_id = ${s.pageId}"""
      .query[(UUID, Option[UUID], String, String, Option[String], Instant)].to[List]
      .map { rows =>
        val byId = rows.map(r => r._1 -> r).toMap
        live.flatMap(l => byId.get(l.id).map(r => SharedPage(r._1, r._2, r._3, r._4, r._5, r._6, l.slug)))
      }
  }

  /** 公開版の本文（JSON）。本文のないページは含めない */
  def pubContents(s: PageShare, ids: List[UUID]): IO[Map[UUID, String]] = {
    val arr = ids.toArray
    if ids.isEmpty then IO.pure(Map.empty)
    else
      tx(sql"""select page_id, content::text from page_share_pub
                  where share_page_id = ${s.pageId} and page_id = any($arr) and content is not null""".query[(
        UUID,
        String
      )].to[List].map(_.toMap))
  }

  /** 公開版の本文の平文 */
  def pubPlainTexts(s: PageShare, ids: List[UUID]): IO[Map[UUID, String]] = {
    val arr = ids.toArray
    if ids.isEmpty then IO.pure(Map.empty)
    else
      tx(sql"select page_id, plain_text from page_share_pub where share_page_id = ${s.pageId} and page_id = any($arr)".query[(
        UUID,
        String
      )].to[List].map(_.toMap))
  }

  /** 公開版に未反映の変更があるか（ページの追加・削除、タイトル・アイコン・本文の変更）。 */
  def hasUnpublished(s: PageShare): IO[Boolean] =
    s.publishedAt match {
      case None => IO.pure(true)
      case Some(at) =>
        tx(scopeC(s)).flatMap { live =>
          tx(sql"select page_id from page_share_pub where share_page_id = ${s.pageId}".query[UUID].to[List]).map {
            pub =>
              live.map(_.id).toSet != pub.toSet || live.exists(_.updatedAt.isAfter(at))
          }
        }
    }

  /** 検索エンジンに載せる公開リンク（期限内・公開開始済み・パスワードなし）。 */
  def indexable: IO[List[PageShare]] = tx(
    (fr"select" ++ cols ++ fr"""join page p on p.id = s.page_id join workspace w on w.id = p.workspace_id
       where s.indexable and s.password_hash is null and p.deleted_at is null and w.deleted_at is null
         and""" ++ SqlParts.notExpired("s.expires_at") ++ fr"and (s.starts_at is null or s.starts_at <= now())").query[
      PageShare
    ].to[List]
  )

  // ---------------------------------------------------------------- ページのURL名

  def pageSlug(pageId: UUID): IO[Option[String]] =
    tx(sql"select guest_slug from page where id = $pageId".query[Option[String]].option.map(_.flatten))

  /** Left("slug_taken"): 同じワークスペースで使用中 */
  def setPageSlug(pageId: UUID, slug: Option[String]): IO[Either[String, Unit]] =
    tx(sql"update page set guest_slug = $slug where id = $pageId".update.run).as(Right(()))
      .recover { case e: java.sql.SQLException if e.getSQLState == "23505" => Left("slug_taken") }
}
