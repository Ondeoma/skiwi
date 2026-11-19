package skiwi.api

import skiwi.shared.Titles

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import sbirds.auth.Crypto
import skiwi.core.*
import skiwi.db.{AttachmentStore, DocStore, PageShare, ShareStore, ShareUpdate, SharedPage, Store}

import java.time.Instant
import java.util.UUID

/** mode: live（編集中の内容をそのまま公開）| pinned（公開した時点の内容）。pendingChanges: 公開版に未反映の変更がある（pinned のときのみ） */
final case class PublicShareDto(
  token: String,
  includeChildren: Boolean,
  createdAt: Instant,
  createdBy: Option[String],
  expiresAt: Option[Instant],
  hasPassword: Boolean,
  expired: Boolean,
  hideHeader: Boolean = false,
  sidebar: String = "auto",
  mode: String = "live",
  publishedAt: Option[Instant] = None,
  publishedBy: Option[String] = None,
  pendingChanges: Option[Boolean] = None,
  startsAt: Option[Instant] = None,
  notStarted: Boolean = false,
  slug: Option[String] = None,
  indexable: Boolean = false,
  description: Option[String] = None,
  showReleases: Boolean = false) derives Encoder.AsObject
final case class InheritedShareDto(
  pageId: UUID,
  title: String,
  token: String,
  slug: Option[String] = None,
  mode: String = "live") derives Encoder.AsObject

/** available=false はサーバー設定で公開機能が無効。pageSlug: このページのURL名 */
final case class ShareInfoDto(
  available: Boolean,
  share: Option[PublicShareDto],
  inherited: Option[InheritedShareDto],
  pageSlug: Option[String] = None) derives Encoder.AsObject

final case class UnlockReq(
  password: String) derives Decoder
final case class PageSlugDto(
  slug: Option[String]) derives Encoder.AsObject, Decoder
final case class AdminShareDto(
  pageId: UUID,
  title: String,
  icon: Option[String],
  workspaceId: UUID,
  workspaceName: String,
  share: PublicShareDto) derives Encoder.AsObject

final case class GuestPageDto(
  id: UUID,
  parentId: Option[UUID],
  position: String,
  title: String,
  icon: Option[String],
  updatedAt: Instant,
  slug: Option[String] = None) derives Encoder.AsObject

/** sidebar: auto（2ページ以上なら表示）| show | hide。key: URLに使う名前（URL名があればそれ、なければトークン） */
final case class GuestShareDto(
  rootId: UUID,
  pages: List[GuestPageDto],
  hideHeader: Boolean = false,
  sidebar: String = "auto",
  key: String = "",
  releases: Boolean = false) derives Encoder.AsObject

/** 公開の履歴（編集者向け）。note: 変更点のメモ */
final case class ReleaseDto(
  id: UUID,
  publishedAt: Instant,
  publishedBy: Option[String],
  note: Option[String],
  pageCount: Int) derives Encoder.AsObject
final case class ReleasePageDto(
  pageId: UUID,
  parentId: Option[UUID],
  title: String,
  icon: Option[String],
  content: Json,
  text: String) derives Encoder.AsObject
final case class ReleaseContentDto(
  release: ReleaseDto,
  pages: List[ReleasePageDto]) derives Encoder.AsObject
final case class ReleaseNoteReq(
  note: Option[String]) derives Decoder

/** ゲスト向けの更新履歴 */
final case class GuestReleaseDto(
  publishedAt: Instant,
  note: String) derives Encoder.AsObject

/** top / bottom: 本文の上下に入れるHTML（上は外側から、下は内側から） */
/** fullWidth: 全幅で表示するページか（公開版を固定していても、今のページの設定に従う） */
final case class GuestContentDto(
  page: GuestPageDto,
  content: Json,
  styles: List[String] = Nil,
  top: List[String] = Nil,
  bottom: List[String] = Nil,
  fullWidth: Boolean = false) derives Encoder.AsObject
final case class GuestSearchHitDto(
  page: GuestPageDto,
  snippet: Option[String],
  titleHit: Boolean) derives Encoder.AsObject

/** 公開ページのHTMLに埋め込む情報（タイトル・OGP・検索エンジン） */
final case class GuestHead(
  title: String,
  description: Option[String],
  image: Option[String],
  url: String,
  indexable: Boolean)
final case class NotStartedDto(
  error: String,
  startsAt: Instant) derives Encoder.AsObject

/**
 * ゲスト（未ログイン）向けの読み取り専用公開。
 *  - 設定: /api/pages/{id}/share（要Edit）
 *  - 閲覧: /api/public/{トークンまたはURL名}/…（認証なし。公開範囲外は存在を隠して404）
 *  - 公開ページのHTML（/s/…）には、OGP・検索エンジン向けの情報を埋め込む（headFor）
 */
final class ShareRoutes(
  protected val store: Store,
  shares: ShareStore,
  docs: DocStore,
  attachments: AttachmentStore,
  files: AttachmentRoutes,
  protected val sessions: SessionAuth,
  audit: Audit,
  enabled: Boolean = true,
  publicUrl: String = "",
  webhooks: Option[WebhookService] = None) extends PageRouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // ------------------------------------------------------------ 設定（ログインユーザー）
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "share" => getPageShare(req, id)
    case req @ PUT -> Root / "api" / "pages" / UUIDVar(id) / "share" => putPageShare(req, id)
    case req @ POST -> Root / "api" / "pages" / UUIDVar(id) / "share" / "publish" => postPageSharePublish(req, id)
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "share" / "releases" => getPageShareReleases(req, id)
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "share" / "releases" / UUIDVar(rid) =>
      getPageShareRelease(req, id, rid)
    case req @ PATCH -> Root / "api" / "pages" / UUIDVar(id) / "share" / "releases" / UUIDVar(rid) =>
      patchPageShareRelease(req, id, rid)
    case req @ DELETE -> Root / "api" / "pages" / UUIDVar(id) / "share" => deletePageShare(req, id)
    case req @ PUT -> Root / "api" / "pages" / UUIDVar(id) / "guest-slug" => putPageGuestSlug(req, id)
    case req @ GET -> Root / "api" / "admin" / "shares" => getAdminShares(req)
    case req @ POST -> Root / "api" / "public" / key / "unlock" => postPublicUnlock(req, key)
    case req @ GET -> Root / "api" / "public" / key => getPublic(req, key)
    case req @ GET -> Root / "api" / "public" / key / "releases" => getPublicReleases(req, key)
    case req @ GET -> Root / "api" / "public" / key / "pages" / pageKey => getPublicPage(req, key, pageKey)
    case req @ GET -> Root / "api" / "public" / key / "search" => getPublicSearch(req, key)
    case req @ GET -> Root / "api" / "public" / key / "attachments" / UUIDVar(aid) => getPublicAttachment(req, aid, key)
    case GET -> Root / "robots.txt" => getRobotsTxt()
    case GET -> Root / "sitemap.xml" => getSitemapXml()
  }

  private def getPageShare(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withEditor(req, id) {
      (
        user,
        _
      ) =>
        (shares.get(id), shares.coveringAncestor(id), shares.pageSlug(id)).flatMapN {
          (
            own,
            anc,
            slug
          ) =>
            // 親ページを見られない人には、親のタイトルと公開リンク（親の内容も見られる）を伏せる
            anc.traverse(
              (
                s,
                t
              ) =>
                store.pageAccess(s.pageId, user).map {
                  case Some(_) => InheritedShareDto(s.pageId, t, s.token, s.slug, s.mode)
                  case None => InheritedShareDto(s.pageId, "", "", None, s.mode)
                }
            ).flatMap { inherited =>
              own.traverse(fullDto).flatMap(d => Ok(ShareInfoDto(enabled, d, inherited, slug)))
            }
        }
    }

  private def putPageShare(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withEditor(req, id) {
      (
        s,
        p
      ) =>
        if !enabled then err(ErrorCode.GuestShareDisabled)
        else
          body[SetShareReq](req) { b =>
            shares.get(id).flatMap { before =>
              validate(b, before) match {
                case Some(msg) => err(ErrorCode.InvalidInput, msg)
                case None =>
                  b.password.traverse(_.traverse(hashPassword)).flatMap { hash =>
                    val u = ShareUpdate(
                      b.includeChildren,
                      b.expiresAt,
                      hash,
                      b.hideHeader,
                      b.sidebar,
                      b.mode,
                      b.startsAt,
                      b.slug,
                      b.indexable,
                      b.description,
                      b.showReleases
                    )
                    shares.upsert(id, Crypto.randomToken(24), s.user.id, u).flatMap {
                      case Left(_) => err(ErrorCode.ShareSlugTaken)
                      case Right(sh0) =>
                        // 公開版を固定するようにしたときは、その時点の内容を公開する
                        val pin = sh0.pinned && !before.exists(_.pinned)
                        for {
                          sh <- if pin then shares.publish(id, s.user.id).map(_.getOrElse(sh0)) else IO.pure(sh0)
                          _ <- if before.isEmpty then emitPublished(s, id, sh, None) else IO.unit
                          _ <- audit.log(
                            s,
                            if before.isEmpty then "page.public.enable" else "page.public.update",
                            "page",
                            id,
                            Json.obj(
                              "title" -> p.title.asJson,
                              "includeChildren" -> sh.includeChildren.asJson,
                              "expiresAt" -> sh.expiresAt.asJson,
                              "startsAt" -> sh.startsAt.asJson,
                              "password" -> sh.passwordHash.isDefined.asJson,
                              "mode" -> sh.mode.asJson,
                              "slug" -> sh.slug.asJson,
                              "indexable" -> sh.indexable.asJson
                            )
                          )
                          d <- fullDto(sh)
                          r <- Ok(d)
                        } yield r
                    }
                  }
              }
            }
          }
    }

  private def postPageSharePublish(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withEditor(req, id) {
      (
        s,
        p
      ) =>
        decodeBody[Json](req).map(
          _.toOption.flatMap(_.hcursor.get[String]("note").toOption).map(_.trim).filter(_.nonEmpty).map(_.take(2000))
        ).flatMap { note =>
          shares.publish(id, s.user.id, note).flatMap {
            case None => err(ErrorCode.NotPublished)
            case Some(sh) =>
              audit.log(
                s,
                "page.public.publish",
                "page",
                id,
                Json.obj("title" -> p.title.asJson, "note" -> note.asJson)
              ) *>
                emitPublished(s, id, sh, note) *> fullDto(sh).flatMap(Ok(_))
          }
        }
    }

  private def getPageShareReleases(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withEditor(req, id)(
      (
        _,
        _
      ) => shares.releases(id).flatMap(rs => Ok(rs.map(releaseDto)))
    )

  private def getPageShareRelease(
    req: Request[IO],
    id: UUID,
    rid: UUID
  ): IO[Response[IO]] =
    withEditor(req, id) {
      (
        _,
        _
      ) =>
        shares.releasePages(id, rid).flatMap {
          case None => err(ErrorCode.PublishedVersionNotFound)
          case Some((r, ps)) =>
            Ok(ReleaseContentDto(
              releaseDto(r),
              ps.map(x =>
                ReleasePageDto(
                  x.pageId,
                  x.parentId,
                  x.title,
                  x.icon,
                  x.content.fold(Json.Null)(parseOrNull),
                  x.plainText
                )
              )
            ))
        }
    }

  private def patchPageShareRelease(
    req: Request[IO],
    id: UUID,
    rid: UUID
  ): IO[Response[IO]] =
    withEditor(req, id) {
      (
        s,
        _
      ) =>
        body[ReleaseNoteReq](req) { b =>
          val note = b.note.map(_.trim).filter(_.nonEmpty).map(_.take(2000))
          shares.setReleaseNote(id, rid, note).flatMap {
            case false => err(ErrorCode.PublishedVersionNotFound)
            case true => audit.log(
                s,
                "page.public.release_note",
                "page",
                id,
                Json.obj("releaseId" -> rid.asJson, "note" -> note.asJson)
              ) *> NoContent()
          }
        }
    }

  private def deletePageShare(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withEditor(req, id) {
      (
        s,
        p
      ) =>
        shares.delete(id).flatMap { removed =>
          (if removed then audit.log(s, "page.public.disable", "page", id, Json.obj("title" -> p.title.asJson))
           else IO.unit) *> NoContent()
        }
    }

  private def putPageGuestSlug(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withEditor(req, id) {
      (
        s,
        p
      ) =>
        body[PageSlugDto](req) { b =>
          val slug = b.slug.map(_.trim.toLowerCase).filter(_.nonEmpty)
          if slug.exists(x => !validSlug(PageSlug, x)) then
            err(ErrorCode.ShareSlugInvalid)
          else
            shares.setPageSlug(id, slug).flatMap {
              case Left(_) => err(ErrorCode.ShareSlugTaken)
              case Right(_) =>
                audit.log(
                  s,
                  "page.guest_slug",
                  "page",
                  id,
                  Json.obj("title" -> p.title.asJson, "slug" -> slug.asJson)
                ) *> Ok(PageSlugDto(slug))
            }
        }
    }

  private def getAdminShares(
    req: Request[IO]
  ): IO[Response[IO]] =
    admin(req) { _ =>
      IO.realTimeInstant.flatMap { now =>
        shares.listAll.flatMap(xs =>
          Ok(xs.map(x =>
            AdminShareDto(x.share.pageId, x.title, x.icon, x.workspaceId, x.workspaceName, dto(x.share, now))
          ))
        )
      }
    }

  private def postPublicUnlock(
    req: Request[IO],
    key: String
  ): IO[Response[IO]] =
    withShare(req, key, checkPassword = false) { v =>
      val s = v.share
      body[UnlockReq](req) { b =>
        s.passwordHash match {
          case None => NoContent()
          case Some(h) =>
            allowAttempt(s.token, clientAddress(req)).flatMap {
              case false => err(ErrorCode.ShareTooManyAttempts)
              case true =>
                verifyPassword(b.password, h).flatMap {
                  case false => err(ErrorCode.SharePasswordWrong)
                  case true =>
                    NoContent().map(_.addCookie(ResponseCookie(
                      cookieName(s.token),
                      unlockValue(s.token, h, Instant.now().getEpochSecond + UnlockSeconds),
                      maxAge = Some(UnlockSeconds),
                      path = Some(s"/api/public/$key"),
                      httpOnly = true,
                      secure = sessions.cookieSecure,
                      sameSite = Some(SameSite.Lax)
                    )))
                }
            }
        }
      }
    }

  private def getPublic(
    req: Request[IO],
    key: String
  ): IO[Response[IO]] =
    withShare(req, key)(v =>
      Ok(GuestShareDto(
        v.share.pageId,
        v.pages.map(guest),
        v.share.hideHeader,
        v.share.sidebar,
        v.key,
        v.share.pinned && v.share.showReleases
      ))
    )

  private def getPublicReleases(
    req: Request[IO],
    key: String
  ): IO[Response[IO]] =
    withShare(req, key) { v =>
      if !(v.share.pinned && v.share.showReleases) then err(ErrorCode.ItemNotFound)
      else
        shares.releases(v.share.pageId).flatMap(rs =>
          Ok(rs.flatMap(r => r.note.map(n => GuestReleaseDto(r.publishedAt, n))))
        )
    }

  private def getPublicPage(
    req: Request[IO],
    key: String,
    pageKey: String
  ): IO[Response[IO]] =
    withShare(req, key) { v =>
      v.find(pageKey) match {
        case None => err(ErrorCode.ItemNotFound)
        case Some(p) =>
          sbirds.observe.Metrics.count("skiwi.public.views") *>
            (v.content(p.id), store.cssLayers(p.id), store.guestHtmlLayers(p.id), store.isFullWidth(p.id)).flatMapN {
              (
                content,
                css,
                html,
                wide
              ) =>
                Ok(GuestContentDto(
                  guest(p),
                  forGuest(key, v.pages.map(_.id).toSet)(content),
                  css.map(_.css),
                  top = html.flatMap(_.top),
                  bottom = html.reverse.flatMap(_.bottom),
                  fullWidth = wide
                ))
            }
      }
    }

  private def getPublicSearch(
    req: Request[IO],
    key: String
  ): IO[Response[IO]] =
    withShare(req, key) { v =>
      val q = req.uri.query.params.getOrElse("q", "").take(200)
      v.texts.flatMap { texts =>
        val docs = v.pages.map(p => GuestSearch.Doc(p.id, p.title, texts.getOrElse(p.id, "")))
        val byId = v.pages.map(p => p.id -> p).toMap
        Ok(GuestSearch.search(q, docs).map(h => GuestSearchHitDto(guest(byId(h.id)), h.snippet, h.titleHit)))
      }
    }

  private def getPublicAttachment(
    req: Request[IO],
    aid: UUID,
    key: String
  ): IO[Response[IO]] =
    withShare(req, key) { v =>
      attachments.get(aid).flatMap {
        case Some(a) if v.pages.exists(_.id == a.pageId) => files.serve(a, "private, no-store")
        case _ => err(ErrorCode.ItemNotFound)
      }
    }

  private def getRobotsTxt(): IO[Response[IO]] = {
    val body =
      if enabled then s"User-agent: *\nAllow: /s/\nDisallow: /\nSitemap: $base/sitemap.xml\n"
      else "User-agent: *\nDisallow: /\n"
    text(body, MediaType.text.plain)
  }

  private def getSitemapXml(): IO[Response[IO]] =
    (IO.realTime.map(_.toMillis), if enabled then shares.indexable else IO.pure(Nil)).flatMapN {
      (
        now,
        xs
      ) =>
        val key = xs.hashCode
        sitemapCache.get.flatMap {
          case Some((at, k, body)) if k == key && now - at < 600000 => text(body, MediaType.application.xml)
          case _ => buildSitemap(xs).flatTap(b => sitemapCache.set(Some((now, key, b)))).flatMap(text(
              _,
              MediaType.application.xml
            ))
        }
    }

  private val base = publicUrl.stripSuffix("/")

  private def dto(
    s: PageShare,
    now: Instant,
    pending: Option[Boolean] = None
  ) =
    PublicShareDto(
      s.token,
      s.includeChildren,
      s.createdAt,
      s.createdBy,
      s.expiresAt,
      s.passwordHash.isDefined,
      s.expired(now),
      s.hideHeader,
      s.sidebar,
      s.mode,
      s.publishedAt,
      s.publishedBy,
      pending,
      s.startsAt,
      s.notStarted(now),
      s.slug,
      s.indexable,
      s.description,
      s.showReleases
    )

  private def fullDto(
    s: PageShare
  ): IO[PublicShareDto] =
    (if s.pinned then shares.hasUnpublished(s).map(Some(_)) else IO.pure(None)).map(p => dto(s, Instant.now(), p))

  // --- パスワード（PBKDF2-HMAC-SHA256）。解錠済みのしるしはリンクごとのCookie ---------------------
  private val iterations = 120000
  private def pbkdf2(
    pw: String,
    salt: Array[Byte],
    iter: Int
  ): Array[Byte] = {
    val spec = new javax.crypto.spec.PBEKeySpec(pw.toCharArray, salt, iter, 256)
    javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded
  }
  private val b64 = java.util.Base64.getUrlEncoder.withoutPadding()
  private val b64d = java.util.Base64.getUrlDecoder
  private def hashPassword(
    pw: String
  ): IO[String] = IO.blocking {
    val salt = new Array[Byte](16)
    new java.security.SecureRandom().nextBytes(salt)
    s"pbkdf2$$$iterations$$${b64.encodeToString(salt)}$$${b64.encodeToString(pbkdf2(pw, salt, iterations))}"
  }
  private def verifyPassword(
    pw: String,
    stored: String
  ): IO[Boolean] = IO.blocking {
    stored.split('$') match {
      case Array("pbkdf2", it, salt, hash) =>
        java.security.MessageDigest.isEqual(pbkdf2(pw, b64d.decode(salt), it.toInt), b64d.decode(hash))
      case _ => false
    }
  }
  private def cookieName(
    token: String
  ) = s"skiwi_guest_${token.take(10)}"

  /** 解錠の Cookie の値は「期限（エポック秒）.ハッシュ」。期限を過ぎるか、パスワードを変えると無効になる。 */
  private val UnlockSeconds = 86400L
  private def unlockValue(
    token: String,
    hash: String,
    exp: Long
  ) = s"$exp." + b64.encodeToString(Crypto.sha256(s"$token:$hash:$exp"))
  private def unlocked(
    req: Request[IO],
    s: PageShare
  ): Boolean =
    s.passwordHash.forall(h =>
      req.cookies.exists(c =>
        c.name == cookieName(s.token) &&
          (c.content.split('.') match {
            case Array(e, _) => e.toLongOption.exists(exp =>
                exp > Instant.now().getEpochSecond && Crypto.constantTimeEquals(c.content, unlockValue(s.token, h, exp))
              )
            case _ => false
          })
      )
    )

  /**
   * 総当たり対策: 10分間に、リンク×接続元ごとに10回、リンクごとに合わせて100回まで.
   * 接続元ごとに数えるので、第三者が間違ったパスワードを送り続けても、ほかの人は解錠できる
   */
  private val attempts = Ref.unsafe[IO, Map[String, (Int, Instant)]](Map.empty)
  private def allowAttempt(
    token: String,
    client: String
  ): IO[Boolean] = IO.realTimeInstant.flatMap { now =>
    attempts.modify { m0 =>
      val m = m0.filter(_._2._2.isAfter(now.minusSeconds(600)))
      def count(
        k: String
      ) = m.get(k).fold(0)(_._1)
      def bump(
        mm: Map[String, (Int, Instant)],
        k: String
      ) = mm + (k -> (count(k) + 1, m.get(k).fold(now)(_._2)))
      val (perClient, perLink) = (s"$token|$client", token)
      if count(perClient) >= 10 || count(perLink) >= 100 then (m, false)
      else (bump(bump(m, perClient), perLink), true)
    }
  }
  private def emitPublished(
    s: AuthSession,
    id: UUID,
    sh: PageShare,
    note: Option[String]
  ): IO[Unit] =
    webhooks.traverse_(_.emit(
      "page.published",
      id,
      s.user.name,
      Json.obj("note" -> note.asJson, "publicUrl" -> s"$base/s/${sh.slug.getOrElse(sh.token)}".asJson)
    ))
  private def releaseDto(
    r: skiwi.db.ShareRelease
  ) = ReleaseDto(r.id, r.publishedAt, r.publishedBy, r.note, r.pageCount)
  private def guest(
    p: SharedPage
  ) = GuestPageDto(p.id, p.parentId, p.position, p.title, p.icon, p.updatedAt, p.slug)

  /** ゲスト向け応答: 検索エンジンに載せない（APIの応答そのもの）・キャッシュさせない。 */
  private def guestHeaders(
    r: Response[IO]
  ): Response[IO] =
    r.putHeaders(
      Header.Raw(CIString("X-Robots-Tag"), "noindex, nofollow"),
      Header.Raw(CIString("Cache-Control"), "no-store")
    )

  private val notFound = err(ErrorCode.ItemNotFound).map(guestHeaders)

  /** ゲストに見せる版: 公開版を固定しているならその内容（現在の公開範囲にあるページだけ）、なければ現在の内容。 */
  /** pages: ゲストに見せるページ（固定（pinned）なら公開版のタイトルなど） */
  private final case class GuestView(
    share: PageShare,
    pages: List[SharedPage]) {
    def find(
      key: String
    ): Option[SharedPage] =
      uuidOpt(key).flatMap(id => pages.find(_.id == id))
        .orElse(pages.find(_.slug.contains(key.toLowerCase)))

    /** ゲストに見せる本文（JSON）。固定（pinned）なら公開版、そうでなければ最新の本文 */
    private def contents(
      ids: List[UUID]
    ): IO[Map[UUID, String]] = if share.pinned then shares.pubContents(share, ids) else docs.currentContents(ids)
    def content(
      id: UUID
    ): IO[Json] = contents(List(id)).map(_.get(id).fold(Json.Null)(parseOrNull))
    private def savedTexts: IO[Map[UUID, String]] = {
      val ids = pages.map(_.id)
      if share.pinned then shares.pubPlainTexts(share, ids) else docs.plainTexts(ids)
    }

    /**
     * ゲストに見せる平文（検索用）。保存済みの平文はエディタが作るので、ページへのリンクのタイトル（公開範囲外のページのものを含む）が入る。
     * そこで本文の JSON から作り直す（ページへのリンク・メンションは含めない）。重いので、保存済みの平文が変わらない間は覚えておく
     */
    def texts: IO[Map[UUID, String]] = savedTexts.flatMap { saved =>
      guestTextCache.get.flatMap { cache =>
        val keys = pages.map(p => p.id -> (p.id, saved.getOrElse(p.id, "").hashCode))
        val missing = keys.filterNot(
          (
            _,
            k
          ) => cache.contains(k)
        )
        // 覚えていないページの本文は1回の問い合わせでまとめて読む
        contents(missing.map(_._1)).flatMap { cs =>
          val fresh = missing.map(
            (
              id,
              k
            ) => k -> guestText(cs.get(id).fold(Json.Null)(parseOrNull))
          )
          IO.whenA(fresh.nonEmpty)(guestTextCache.update(m => (if m.size > 20000 then Map.empty else m) ++ fresh))
            .as {
              val all = cache ++ fresh;
              keys.map(
                (
                  id,
                  k
                ) => id -> all(k)
              ).toMap
            }
        }
      }
    }
    def key: String = share.slug.getOrElse(share.token)
  }

  private val guestTextCache = Ref.unsafe[IO, Map[(UUID, Int), String]](Map.empty)
  private def guestText(
    content: Json
  ): String = if content.isNull then "" else MarkdownParse.plainText(Vector(content))

  private def view(
    s: PageShare,
    live: List[SharedPage]
  ): IO[GuestView] =
    if s.pinned then shares.pubPages(s, live).map(GuestView(s, _))
    else IO.pure(GuestView(s, live))

  /** 公開リンクを確認する。期限切れは 410、公開前は 403、パスワード未入力は 401（ゲスト画面が案内を出す）。 */
  private def withShare(
    req: Request[IO],
    key: String,
    checkPassword: Boolean = true
  )(
    f: GuestView => IO[Response[IO]]
  ): IO[Response[IO]] =
    if !enabled || key.length > 100 then notFound
    else
      (shares.resolve(key), IO.realTimeInstant).flatMapN {
        case (None, _) => notFound
        case (Some((s, _)), now) if s.expired(now) => err(ErrorCode.ShareExpired).map(guestHeaders)
        case (Some((s, _)), now) if s.notStarted(now) =>
          Response[IO](Status.Forbidden).withEntity(NotStartedDto(
            "not_started",
            s.startsAt.get
          )).pure[IO].map(guestHeaders)
        case (Some((s, _)), _) if checkPassword && !unlocked(req, s) =>
          err(ErrorCode.SharePasswordRequired).map(guestHeaders)
        case (Some((s, ps)), _) => view(s, ps).flatMap(f).map(guestHeaders)
      }

  /**
   * ゲストに渡す本文を整える。
   *  - 添付ファイルのURLを公開リンク経由のものに置き換える
   *  - 公開範囲外のページへのリンクは、タイトルを伏せる
   */
  private def forGuest(
    key: String,
    visible: Set[UUID]
  )(
    j: Json
  ): Json = {
    val prefix = "/api/attachments/"
    def attrs(
      o: Json,
      linkHidden: Boolean
    ): Json = o.mapObject { obj =>
      val replaced = obj.mapValues(v =>
        v.asString match {
          case Some(s) if s.startsWith(prefix) =>
            Json.fromString(s"/api/public/$key/attachments/${s.drop(prefix.length)}")
          case Some(s) if s.contains("\"" + prefix) =>
            // ホワイトボードのスナップショット（JSON文字列）の中の画像
            Json.fromString(s.replace("\"" + prefix, s"\"/api/public/$key/attachments/"))
          case _ => v
        }
      )
      if linkHidden then replaced.add("title", Json.Null) else replaced
    }
    def walk(
      j: Json
    ): Json =
      j.arrayOrObject(
        j,
        arr => Json.fromValues(arr.map(walk)),
        obj => {
          val isPageLink = obj("type").flatMap(_.asString).contains("pageLink")
          val hidden = isPageLink && !obj("attrs").flatMap(_.hcursor.get[String]("pageId").toOption)
            .flatMap(uuidOpt).exists(visible)
          Json.fromJsonObject(obj.toList.foldLeft(obj) { case (acc, (k, v)) =>
            k match {
              case "attrs" if v.isObject => acc.add(k, attrs(v, hidden))
              case "content" | "marks" => acc.add(k, walk(v))
              case _ => acc
            }
          })
        }
      )
    walk(j)
  }

  // --- 入力の確認 ------------------------------------------------------------------------
  private val ShareSlug = "^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$".r
  private val PageSlug = "^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$".r
  private val UuidLike = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$".r
  private def validSlug(
    r: scala.util.matching.Regex,
    s: String
  ) = r.matches(s) && !UuidLike.matches(s)

  private def validate(
    b: SetShareReq,
    before: Option[PageShare]
  ): Option[String] = {
    val starts = b.startsAt.getOrElse(before.flatMap(_.startsAt))
    val expires = b.expiresAt.getOrElse(before.flatMap(_.expiresAt))
    if b.password.flatten.exists(pw => pw.length < 4 || pw.length > 200) then Some("パスワードは4〜200文字にしてください")
    else if b.sidebar.exists(v => !Set("auto", "show", "hide")(v)) then Some("sidebar は auto・show・hide のどれかにしてください")
    else if b.mode.exists(v => !Set("live", "pinned")(v)) then Some("mode は live か pinned にしてください")
    else if b.slug.flatten.exists(s => !validSlug(ShareSlug, s)) then
      Some("URL名は半角の英小文字・数字・ハイフン（-）で入力してください（公開リンクは3〜64文字）")
    else if b.description.flatten.exists(_.length > 300) then Some("説明文は300文字までです")
    else if (starts, expires).tupled.exists(
        (
          a,
          e
        ) => !a.isBefore(e)
      )
    then Some("公開の開始日時は、終了日時より前にしてください")
    else None
  }

  // --- 公開ページのHTML（OGP・検索エンジン） ------------------------------------------------

  private def firstImage(
    j: Json
  ): Option[String] =
    j.asObject.flatMap { o =>
      val own = Option.when(
        o("type").flatMap(_.asString).contains("image")
      )(o("attrs").flatMap(_.hcursor.get[String]("src").toOption)).flatten
      own.orElse(o("content").flatMap(_.asArray).flatMap(_.view.flatMap(firstImage).headOption))
    }.orElse(j.asArray.flatMap(_.view.flatMap(firstImage).headOption))

  private def summary(
    text: String
  ): Option[String] = {
    val t = text.replaceAll("\\s+", " ").trim
    Option.when(t.nonEmpty)(if t.length > 120 then t.take(119) + "…" else t)
  }

  /** /s/{key}/{pageKey} のHTMLに入れる情報。None は公開されていない・パスワード付きなど（内容を出さず、検索エンジンにも載せない）。 */
  def headFor(
    key: String,
    pageKey: Option[String]
  ): IO[Option[GuestHead]] =
    if !enabled || key.length > 100 then IO.pure(None)
    else
      (shares.resolve(key), IO.realTimeInstant).flatMapN {
        case (Some((s, live)), now) if !s.expired(now) && !s.notStarted(now) && s.passwordHash.isEmpty =>
          view(s, live).flatMap { v =>
            pageKey.fold(v.pages.headOption)(v.find) match {
              case None => IO.pure(None)
              case Some(p) =>
                v.content(p.id).map { content =>
                  val isRoot = p.id == s.pageId
                  // 説明文は本文の JSON から作る（公開範囲外のページへのリンクのタイトルを出さない）
                  val desc = (if isRoot then s.description else None).orElse(summary(guestText(content)))
                  val img =
                    firstImage(forGuest(v.key, Set.empty)(content)).filter(_.startsWith("/api/public/")).map(base + _)
                  val url = s"$base/s/${v.key}" + (if isRoot then "" else s"/${p.slug.getOrElse(p.id.toString)}")
                  Some(GuestHead(Titles.display(p.title), desc, img, url, s.indexable))
                }
            }
          }
        case _ => IO.pure(None)
      }

  private def attr(
    s: String
  ) = Html.escape(s)

  /** index.html に埋め込む（タイトル・OGP・検索エンジン向けの指定）。 */
  def renderHead(
    html: String,
    h: Option[GuestHead]
  ): String = {
    val tags = h match {
      case None => """<meta name="robots" content="noindex, nofollow">"""
      case Some(x) =>
        List(
          Option.when(!x.indexable)("""<meta name="robots" content="noindex, nofollow">"""),
          x.description.map(d => s"""<meta name="description" content="${attr(d)}">"""),
          Some(s"""<link rel="canonical" href="${attr(x.url)}">"""),
          Some(s"""<meta property="og:type" content="article">"""),
          Some(s"""<meta property="og:title" content="${attr(x.title)}">"""),
          Some(s"""<meta property="og:url" content="${attr(x.url)}">"""),
          x.description.map(d => s"""<meta property="og:description" content="${attr(d)}">"""),
          x.image.map(i => s"""<meta property="og:image" content="${attr(i)}">"""),
          Some(s"""<meta name="twitter:card" content="${
              if x.image.isDefined then "summary_large_image" else "summary"
            }">""")
        ).flatten.mkString("\n    ")
    }
    val title = h.fold("skiwi")(x => s"${attr(x.title)} - skiwi")
    val withTitle =
      html.replaceFirst("<title>[^<]*</title>", java.util.regex.Matcher.quoteReplacement(s"<title>$title</title>"))
    withTitle.replaceFirst("</head>", java.util.regex.Matcher.quoteReplacement(s"    $tags\n  </head>"))
  }

  private def text(
    body: String,
    mt: MediaType
  ): IO[Response[IO]] =
    IO.pure(Response[IO](Status.Ok).withEntity(body)(using EntityEncoder.stringEncoder).withContentType(`Content-Type`(
      mt,
      Charset.`UTF-8`
    )))

  private def xml(
    s: String
  ) = Html.escape(s)

  /**
   * sitemap.xml は公開リンクごとに公開範囲をたどるので、10分覚えておく（ログインなしで呼べるため）。
   * 載せる公開リンク（設定を含む）が変わったら作り直す。(作った時刻, 公開リンクの一覧のハッシュ, 中身)
   */
  private val sitemapCache = Ref.unsafe[IO, Option[(Long, Int, String)]](None)

  private def buildSitemap(
    xs: List[PageShare]
  ): IO[String] =
    IO.pure(xs).flatMap { xs =>
      xs.flatTraverse { s =>
        shares.resolve(s.token).flatMap {
          case None => IO.pure(Nil)
          case Some((sh, live)) =>
            view(sh, live).map { v =>
              v.pages.map { p =>
                val path = if p.id == sh.pageId then "" else s"/${p.slug.getOrElse(p.id.toString)}"
                s"  <url><loc>${xml(s"$base/s/${v.key}$path")}</loc><lastmod>${p.updatedAt}</lastmod></url>"
              }
            }
        }
      }
    }.map { urls =>
      s"""<?xml version="1.0" encoding="UTF-8"?>
         |<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
         |${urls.mkString("\n")}
         |</urlset>
         |""".stripMargin
    }
}
