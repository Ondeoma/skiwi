package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import skiwi.auth.Crypto
import skiwi.core.*
import skiwi.db.{AttachmentStore, DocStore, PageShare, PubPage, ShareStore, ShareUpdate, SharedPage, Store}

import java.time.Instant
import java.util.UUID

/** mode: live（編集中の内容をそのまま公開）| pinned（公開した時点の内容）。pendingChanges: 公開版に未反映の変更がある（pinned のときのみ） */
final case class PublicShareDto(
    token: String, includeChildren: Boolean, createdAt: Instant, createdBy: Option[String],
    expiresAt: Option[Instant], hasPassword: Boolean, expired: Boolean, hideHeader: Boolean = false, sidebar: String = "auto",
    mode: String = "live", publishedAt: Option[Instant] = None, publishedBy: Option[String] = None, pendingChanges: Option[Boolean] = None,
    startsAt: Option[Instant] = None, notStarted: Boolean = false,
    slug: Option[String] = None, indexable: Boolean = false, description: Option[String] = None,
    showReleases: Boolean = false
) derives Encoder.AsObject
final case class InheritedShareDto(pageId: UUID, title: String, token: String, slug: Option[String] = None, mode: String = "live") derives Encoder.AsObject
/** available=false はサーバー設定で公開機能が無効。pageSlug: このページのURL名 */
final case class ShareInfoDto(available: Boolean, share: Option[PublicShareDto], inherited: Option[InheritedShareDto], pageSlug: Option[String] = None) derives Encoder.AsObject
/** expiresAt / startsAt / password / slug / description: 項目なし=変更しない、null（空文字も）=解除、値=設定。 */
final case class SetShareReq(
    includeChildren: Option[Boolean], expiresAt: Option[Option[Instant]], password: Option[Option[String]],
    hideHeader: Option[Boolean] = None, sidebar: Option[String] = None,
    mode: Option[String] = None, startsAt: Option[Option[Instant]] = None, slug: Option[Option[String]] = None,
    indexable: Option[Boolean] = None, description: Option[Option[String]] = None, showReleases: Option[Boolean] = None
)
object SetShareReq:
  private def tri[A: Decoder](c: io.circe.HCursor, k: String): Decoder.Result[Option[Option[A]]] =
    c.downField(k).success.fold(Right(None))(f => f.as[Option[A]].map(Some(_)))
  private def triText(c: io.circe.HCursor, k: String) = tri[String](c, k).map(_.map(_.map(_.trim).filter(_.nonEmpty)))
  given Decoder[SetShareReq] = Decoder.instance { c =>
    for
      inc <- c.get[Option[Boolean]]("includeChildren")
      exp <- tri[Instant](c, "expiresAt")
      pw  <- tri[String](c, "password")
      hh  <- c.get[Option[Boolean]]("hideHeader")
      sb  <- c.get[Option[String]]("sidebar")
      md  <- c.get[Option[String]]("mode")
      st  <- tri[Instant](c, "startsAt")
      sl  <- triText(c, "slug")
      ix  <- c.get[Option[Boolean]]("indexable")
      ds  <- triText(c, "description")
      sr  <- c.get[Option[Boolean]]("showReleases")
    yield SetShareReq(inc, exp, pw.map(_.filter(_.nonEmpty)), hh, sb, md, st, sl.map(_.map(_.toLowerCase)), ix, ds, sr)
  }
final case class UnlockReq(password: String) derives Decoder
final case class PageSlugDto(slug: Option[String]) derives Encoder.AsObject, Decoder
final case class AdminShareDto(
    pageId: UUID, title: String, icon: Option[String], workspaceId: UUID, workspaceName: String,
    share: PublicShareDto
) derives Encoder.AsObject

final case class GuestPageDto(id: UUID, parentId: Option[UUID], position: String, title: String, icon: Option[String], updatedAt: Instant, slug: Option[String] = None) derives Encoder.AsObject
/** sidebar: auto（2ページ以上なら表示）| show | hide。key: URLに使う名前（URL名があればそれ、なければトークン） */
final case class GuestShareDto(rootId: UUID, pages: List[GuestPageDto], hideHeader: Boolean = false, sidebar: String = "auto", key: String = "", releases: Boolean = false) derives Encoder.AsObject
/** 公開の履歴（編集者向け）。note: 変更点のメモ */
final case class ReleaseDto(id: UUID, publishedAt: Instant, publishedBy: Option[String], note: Option[String], pageCount: Int) derives Encoder.AsObject
final case class ReleasePageDto(pageId: UUID, parentId: Option[UUID], title: String, icon: Option[String], content: Json, text: String) derives Encoder.AsObject
final case class ReleaseContentDto(release: ReleaseDto, pages: List[ReleasePageDto]) derives Encoder.AsObject
final case class ReleaseNoteReq(note: Option[String]) derives Decoder
/** ゲスト向けの更新履歴 */
final case class GuestReleaseDto(publishedAt: Instant, note: String) derives Encoder.AsObject
/** top / bottom: 本文の上下に入れるHTML（上は外側から、下は内側から） */
/** fullWidth: 全幅で表示するページか（公開版を固定していても、今のページの設定に従う） */
final case class GuestContentDto(page: GuestPageDto, content: Json, styles: List[String] = Nil, top: List[String] = Nil, bottom: List[String] = Nil,
    fullWidth: Boolean = false) derives Encoder.AsObject
final case class GuestSearchHitDto(page: GuestPageDto, snippet: Option[String], titleHit: Boolean) derives Encoder.AsObject
/** 公開ページのHTMLに埋め込む情報（タイトル・OGP・検索エンジン） */
final case class GuestHead(title: String, description: Option[String], image: Option[String], url: String, indexable: Boolean)
final case class NotStartedDto(error: String, startsAt: Instant) derives Encoder.AsObject

/** ゲスト（未ログイン）向けの読み取り専用公開。
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
    webhooks: Option[WebhookService] = None
) extends PageRouteSupport:

  private val base = publicUrl.stripSuffix("/")

  private def withEditor(req: Request[IO], id: UUID)(f: (AuthSession, Page) => IO[Response[IO]]): IO[Response[IO]] =
    withPage(req, id, Role.Edit)((s, p, _) => f(s, p))

  private def dto(s: PageShare, now: Instant, pending: Option[Boolean] = None) =
    PublicShareDto(s.token, s.includeChildren, s.createdAt, s.createdBy, s.expiresAt, s.passwordHash.isDefined, s.expired(now),
      s.hideHeader, s.sidebar, s.mode, s.publishedAt, s.publishedBy, pending, s.startsAt, s.notStarted(now), s.slug, s.indexable, s.description, s.showReleases)

  private def fullDto(s: PageShare): IO[PublicShareDto] =
    (if s.pinned then shares.hasUnpublished(s).map(Some(_)) else IO.pure(None)).map(p => dto(s, Instant.now(), p))

  // --- パスワード（PBKDF2-HMAC-SHA256）。解錠済みのしるしはリンクごとのCookie ---------------------
  private val iterations = 120000
  private def pbkdf2(pw: String, salt: Array[Byte], iter: Int): Array[Byte] =
    val spec = new javax.crypto.spec.PBEKeySpec(pw.toCharArray, salt, iter, 256)
    javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded
  private val b64 = java.util.Base64.getUrlEncoder.withoutPadding()
  private val b64d = java.util.Base64.getUrlDecoder
  private def hashPassword(pw: String): IO[String] = IO.blocking {
    val salt = new Array[Byte](16)
    new java.security.SecureRandom().nextBytes(salt)
    s"pbkdf2$$$iterations$$${b64.encodeToString(salt)}$$${b64.encodeToString(pbkdf2(pw, salt, iterations))}"
  }
  private def verifyPassword(pw: String, stored: String): IO[Boolean] = IO.blocking {
    stored.split('$') match
      case Array("pbkdf2", it, salt, hash) =>
        java.security.MessageDigest.isEqual(pbkdf2(pw, b64d.decode(salt), it.toInt), b64d.decode(hash))
      case _ => false
  }
  private def cookieName(token: String) = s"skiwi_guest_${token.take(10)}"
  /** パスワードを変えると以前の解錠は無効になる。 */
  private def unlockValue(token: String, hash: String) = b64.encodeToString(Crypto.sha256(s"$token:$hash"))
  private def unlocked(req: Request[IO], s: PageShare): Boolean =
    s.passwordHash.forall(h => req.cookies.exists(c => c.name == cookieName(s.token) && c.content == unlockValue(s.token, h)))

  /** 総当たり対策: リンクごとに10分間で10回まで。 */
  private val attempts = Ref.unsafe[IO, Map[String, (Int, Instant)]](Map.empty)
  private def allowAttempt(token: String): IO[Boolean] = IO.realTimeInstant.flatMap { now =>
    attempts.modify { m =>
      val (n, since) = m.get(token).filter(_._2.isAfter(now.minusSeconds(600))).getOrElse((0, now))
      (m.filter(_._2._2.isAfter(now.minusSeconds(600))) + (token -> (n + 1, since)), n < 10)
    }
  }
  private def emitPublished(s: AuthSession, id: UUID, sh: PageShare, note: Option[String]): IO[Unit] =
    webhooks.traverse_(_.emit("page.published", id, s.user.name,
      Json.obj("note" -> note.asJson, "publicUrl" -> s"$base/s/${sh.slug.getOrElse(sh.token)}".asJson)))
  private def releaseDto(r: skiwi.db.ShareRelease) = ReleaseDto(r.id, r.publishedAt, r.publishedBy, r.note, r.pageCount)
  private def guest(p: SharedPage) = GuestPageDto(p.id, p.parentId, p.position, p.title, p.icon, p.updatedAt, p.slug)

  /** ゲスト向け応答: 検索エンジンに載せない（APIの応答そのもの）・キャッシュさせない。 */
  private def guestHeaders(r: Response[IO]): Response[IO] =
    r.putHeaders(Header.Raw(CIString("X-Robots-Tag"), "noindex, nofollow"), Header.Raw(CIString("Cache-Control"), "no-store"))

  private val notFound = err(Status.NotFound, "not found").map(guestHeaders)

  /** ゲストに見せる版: 公開版を固定しているならその内容（現在の公開範囲にあるページだけ）、なければ現在の内容。 */
  private final case class GuestView(share: PageShare, pages: List[SharedPage], pub: Option[Map[UUID, PubPage]]):
    def find(key: String): Option[SharedPage] =
      uuidOpt(key).flatMap(id => pages.find(_.id == id))
        .orElse(pages.find(_.slug.contains(key.toLowerCase)))
    def content(id: UUID): IO[Json] = pub match
      case Some(m) => IO.pure(m.get(id).flatMap(_.content).fold(Json.Null)(parseOrNull))
      case None    => docs.currentContent(id).map(_.fold(Json.Null)((json, _, _) => parseOrNull(json)))
    def texts: IO[Map[UUID, String]] = pub match
      case Some(m) => IO.pure(m.view.mapValues(_.plainText).toMap)
      case None    => shares.plainTexts(pages.map(_.id))
    def key: String = share.slug.getOrElse(share.token)

  private def view(s: PageShare, live: List[SharedPage]): IO[GuestView] =
    if s.pinned then shares.pubPages(s, live).map(pp => GuestView(s, pp.map(_.page), Some(pp.map(p => p.page.id -> p).toMap)))
    else IO.pure(GuestView(s, live, None))

  /** 公開リンクを確認する。期限切れは 410、公開前は 403、パスワード未入力は 401（ゲスト画面が案内を出す）。 */
  private def withShare(req: Request[IO], key: String, checkPassword: Boolean = true)(f: GuestView => IO[Response[IO]]): IO[Response[IO]] =
    if !enabled || key.length > 100 then notFound
    else (shares.resolve(key), IO.realTimeInstant).flatMapN {
      case (None, _)                                        => notFound
      case (Some((s, _)), now) if s.expired(now)            => err(Status.Gone, "expired").map(guestHeaders)
      case (Some((s, _)), now) if s.notStarted(now)         =>
        Response[IO](Status.Forbidden).withEntity(NotStartedDto("not_started", s.startsAt.get)).pure[IO].map(guestHeaders)
      case (Some((s, _)), _) if checkPassword && !unlocked(req, s) => err(Status.Unauthorized, "password_required").map(guestHeaders)
      case (Some((s, ps)), _)                               => view(s, ps).flatMap(f).map(guestHeaders)
    }

  /** ゲストに渡す本文を整える。
    *  - 添付ファイルのURLを公開リンク経由のものに置き換える
    *  - 公開範囲外のページへのリンクは、タイトルを伏せる */
  private def forGuest(key: String, visible: Set[UUID])(j: Json): Json =
    val prefix = "/api/attachments/"
    def attrs(o: Json, linkHidden: Boolean): Json = o.mapObject { obj =>
      val replaced = obj.mapValues(v => v.asString match
        case Some(s) if s.startsWith(prefix) => Json.fromString(s"/api/public/$key/attachments/${s.drop(prefix.length)}")
        case Some(s) if s.contains("\"" + prefix) =>
          // ホワイトボードのスナップショット（JSON文字列）の中の画像
          Json.fromString(s.replace("\"" + prefix, s"\"/api/public/$key/attachments/"))
        case _                               => v)
      if linkHidden then replaced.add("title", Json.Null) else replaced
    }
    def walk(j: Json): Json =
      j.arrayOrObject(
        j,
        arr => Json.fromValues(arr.map(walk)),
        obj => {
          val isPageLink = obj("type").flatMap(_.asString).contains("pageLink")
          val hidden = isPageLink && !obj("attrs").flatMap(_.hcursor.get[String]("pageId").toOption)
            .flatMap(uuidOpt).exists(visible)
          Json.fromJsonObject(obj.toList.foldLeft(obj) { case (acc, (k, v)) =>
            k match
              case "attrs" if v.isObject => acc.add(k, attrs(v, hidden))
              case "content" | "marks"   => acc.add(k, walk(v))
              case _                     => acc
          })
        }
      )
    walk(j)

  // --- 入力の確認 ------------------------------------------------------------------------
  private val ShareSlug = "^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$".r
  private val PageSlug  = "^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$".r
  private val UuidLike  = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$".r
  private def validSlug(r: scala.util.matching.Regex, s: String) = r.matches(s) && !UuidLike.matches(s)

  private def validate(b: SetShareReq, before: Option[PageShare]): Option[String] =
    val starts = b.startsAt.getOrElse(before.flatMap(_.startsAt))
    val expires = b.expiresAt.getOrElse(before.flatMap(_.expiresAt))
    if b.password.flatten.exists(pw => pw.length < 4 || pw.length > 200) then Some("password must be 4-200 characters")
    else if b.sidebar.exists(v => !Set("auto", "show", "hide")(v)) then Some("sidebar must be auto, show or hide")
    else if b.mode.exists(v => !Set("live", "pinned")(v)) then Some("mode must be live or pinned")
    else if b.slug.flatten.exists(s => !validSlug(ShareSlug, s)) then Some("invalid slug")
    else if b.description.flatten.exists(_.length > 300) then Some("description is too long")
    else if (starts, expires).tupled.exists((a, e) => !a.isBefore(e)) then Some("startsAt must be before expiresAt")
    else None

  // --- 公開ページのHTML（OGP・検索エンジン） ------------------------------------------------

  private def firstImage(j: Json): Option[String] =
    j.asObject.flatMap { o =>
      val own = Option.when(o("type").flatMap(_.asString).contains("image"))(o("attrs").flatMap(_.hcursor.get[String]("src").toOption)).flatten
      own.orElse(o("content").flatMap(_.asArray).flatMap(_.view.flatMap(firstImage).headOption))
    }.orElse(j.asArray.flatMap(_.view.flatMap(firstImage).headOption))

  private def summary(text: String): Option[String] =
    val t = text.replaceAll("\\s+", " ").trim
    Option.when(t.nonEmpty)(if t.length > 120 then t.take(119) + "…" else t)

  /** /s/{key}/{pageKey} のHTMLに入れる情報。None は公開されていない・パスワード付きなど（内容を出さず、検索エンジンにも載せない）。 */
  def headFor(key: String, pageKey: Option[String]): IO[Option[GuestHead]] =
    if !enabled || key.length > 100 then IO.pure(None)
    else (shares.resolve(key), IO.realTimeInstant).flatMapN {
      case (Some((s, live)), now) if !s.expired(now) && !s.notStarted(now) && s.passwordHash.isEmpty =>
        view(s, live).flatMap { v =>
          pageKey.fold(v.pages.headOption)(v.find) match
            case None => IO.pure(None)
            case Some(p) =>
              (v.content(p.id), v.texts.map(_.get(p.id))).mapN { (content, text) =>
                val isRoot = p.id == s.pageId
                val desc = (if isRoot then s.description else None).orElse(text.flatMap(summary))
                val img = firstImage(forGuest(v.key, Set.empty)(content)).filter(_.startsWith("/api/public/")).map(base + _)
                val url = s"$base/s/${v.key}" + (if isRoot then "" else s"/${p.slug.getOrElse(p.id.toString)}")
                Some(GuestHead(if p.title.isBlank then "無題" else p.title, desc, img, url, s.indexable))
              }
        }
      case _ => IO.pure(None)
    }

  private def attr(s: String) = Html.escape(s)

  /** index.html に埋め込む（タイトル・OGP・検索エンジン向けの指定）。 */
  def renderHead(html: String, h: Option[GuestHead]): String =
    val tags = h match
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
          Some(s"""<meta name="twitter:card" content="${if x.image.isDefined then "summary_large_image" else "summary"}">""")
        ).flatten.mkString("\n    ")
    val title = h.fold("skiwi")(x => s"${attr(x.title)} - skiwi")
    val withTitle = html.replaceFirst("<title>[^<]*</title>", java.util.regex.Matcher.quoteReplacement(s"<title>$title</title>"))
    withTitle.replaceFirst("</head>", java.util.regex.Matcher.quoteReplacement(s"    $tags\n  </head>"))

  private def text(body: String, mt: MediaType): IO[Response[IO]] =
    IO.pure(Response[IO](Status.Ok).withEntity(body)(using EntityEncoder.stringEncoder[IO]).withContentType(`Content-Type`(mt, Charset.`UTF-8`)))

  private def xml(s: String) = Html.escape(s)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // ------------------------------------------------------------ 設定（ログインユーザー）
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "share" =>
      withEditor(req, id) { (_, _) =>
        (shares.get(id), shares.coveringAncestor(id), shares.pageSlug(id)).flatMapN { (own, anc, slug) =>
          own.traverse(fullDto).flatMap { d =>
            Ok(ShareInfoDto(enabled, d, anc.map((s, t) => InheritedShareDto(s.pageId, t, s.token, s.slug, s.mode)), slug))
          }
        }
      }

    case req @ PUT -> Root / "api" / "pages" / UUIDVar(id) / "share" =>
      withEditor(req, id) { (s, p) =>
        if !enabled then err(Status.Forbidden, "public sharing is disabled")
        else
          body[SetShareReq](req) { b =>
            shares.get(id).flatMap { before =>
              validate(b, before) match
                case Some(msg) => err(Status.BadRequest, msg)
                case None =>
                  b.password.traverse(_.traverse(hashPassword)).flatMap { hash =>
                    val u = ShareUpdate(b.includeChildren, b.expiresAt, hash, b.hideHeader, b.sidebar, b.mode, b.startsAt, b.slug, b.indexable, b.description, b.showReleases)
                    shares.upsert(id, Crypto.randomToken(24), s.user.id, u).flatMap {
                      case Left(_) => err(Status.Conflict, "slug_taken")
                      case Right(sh0) =>
                        // 公開版を固定するようにしたときは、その時点の内容を公開する
                        val pin = sh0.pinned && !before.exists(_.pinned)
                        for
                          sh <- if pin then shares.publish(id, s.user.id).map(_.getOrElse(sh0)) else IO.pure(sh0)
                          _  <- if before.isEmpty then emitPublished(s, id, sh, None) else IO.unit
                          _  <- audit.log(s, if before.isEmpty then "page.public.enable" else "page.public.update", "page", id,
                                  Json.obj("title" -> p.title.asJson, "includeChildren" -> sh.includeChildren.asJson,
                                    "expiresAt" -> sh.expiresAt.asJson, "startsAt" -> sh.startsAt.asJson, "password" -> sh.passwordHash.isDefined.asJson,
                                    "mode" -> sh.mode.asJson, "slug" -> sh.slug.asJson, "indexable" -> sh.indexable.asJson))
                          d  <- fullDto(sh)
                          r  <- Ok(d)
                        yield r
                    }
                  }
            }
          }
      }

    // 公開版を作り直す（現在の内容を公開する）
    // 本文 {note}（変更点のメモ。任意）
    case req @ POST -> Root / "api" / "pages" / UUIDVar(id) / "share" / "publish" =>
      withEditor(req, id) { (s, p) =>
        req.as[Json].attempt.map(_.toOption.flatMap(_.hcursor.get[String]("note").toOption).map(_.trim).filter(_.nonEmpty).map(_.take(2000))).flatMap { note =>
          shares.publish(id, s.user.id, note).flatMap {
            case None => err(Status.NotFound, "not shared")
            case Some(sh) =>
              audit.log(s, "page.public.publish", "page", id, Json.obj("title" -> p.title.asJson, "note" -> note.asJson)) *>
                emitPublished(s, id, sh, note) *> fullDto(sh).flatMap(Ok(_))
          }
        }
      }

    // 公開の履歴（変更点のメモ・公開した内容）
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "share" / "releases" =>
      withEditor(req, id)((_, _) => shares.releases(id).flatMap(rs => Ok(rs.map(releaseDto))))

    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "share" / "releases" / UUIDVar(rid) =>
      withEditor(req, id) { (_, _) =>
        shares.releasePages(id, rid).flatMap {
          case None => err(Status.NotFound, "release not found")
          case Some((r, ps)) =>
            Ok(ReleaseContentDto(releaseDto(r), ps.map(x => ReleasePageDto(x.pageId, x.parentId, x.title, x.icon,
              x.content.fold(Json.Null)(parseOrNull), x.plainText))))
        }
      }

    case req @ PATCH -> Root / "api" / "pages" / UUIDVar(id) / "share" / "releases" / UUIDVar(rid) =>
      withEditor(req, id) { (s, _) =>
        body[ReleaseNoteReq](req) { b =>
          val note = b.note.map(_.trim).filter(_.nonEmpty).map(_.take(2000))
          shares.setReleaseNote(id, rid, note).flatMap {
            case false => err(Status.NotFound, "release not found")
            case true  => audit.log(s, "page.public.release_note", "page", id, Json.obj("releaseId" -> rid.asJson, "note" -> note.asJson)) *> NoContent()
          }
        }
      }

    case req @ DELETE -> Root / "api" / "pages" / UUIDVar(id) / "share" =>
      withEditor(req, id) { (s, p) =>
        shares.delete(id).flatMap { removed =>
          (if removed then audit.log(s, "page.public.disable", "page", id, Json.obj("title" -> p.title.asJson)) else IO.unit) *> NoContent()
        }
      }

    // このページのURL名（公開ページのURLに使う）
    case req @ PUT -> Root / "api" / "pages" / UUIDVar(id) / "guest-slug" =>
      withEditor(req, id) { (s, p) =>
        body[PageSlugDto](req) { b =>
          val slug = b.slug.map(_.trim.toLowerCase).filter(_.nonEmpty)
          if slug.exists(x => !validSlug(PageSlug, x)) then err(Status.BadRequest, "invalid slug")
          else
            shares.setPageSlug(id, slug).flatMap {
              case Left(_) => err(Status.Conflict, "slug_taken")
              case Right(_) =>
                audit.log(s, "page.guest_slug", "page", id, Json.obj("title" -> p.title.asJson, "slug" -> slug.asJson)) *> Ok(PageSlugDto(slug))
            }
        }
      }

    // 管理者: 公開中のページの一覧
    case req @ GET -> Root / "api" / "admin" / "shares" =>
      admin(req) { _ =>
        IO.realTimeInstant.flatMap { now =>
          shares.listAll.flatMap(xs => Ok(xs.map(x => AdminShareDto(x.share.pageId, x.title, x.icon, x.workspaceId, x.workspaceName, dto(x.share, now)))))
        }
      }

    // ------------------------------------------------------------ 閲覧（ゲスト・認証なし）
    case req @ POST -> Root / "api" / "public" / key / "unlock" =>
      withShare(req, key, checkPassword = false) { v =>
        val s = v.share
        body[UnlockReq](req) { b =>
          s.passwordHash match
            case None => NoContent()
            case Some(h) =>
              allowAttempt(s.token).flatMap {
                case false => err(Status.TooManyRequests, "too many attempts")
                case true =>
                  verifyPassword(b.password, h).flatMap {
                    case false => err(Status.Forbidden, "wrong password")
                    case true =>
                      NoContent().map(_.addCookie(ResponseCookie(
                        cookieName(s.token), unlockValue(s.token, h), maxAge = Some(86400L), path = Some(s"/api/public/$key"),
                        httpOnly = true, secure = req.isSecure.getOrElse(false), sameSite = Some(SameSite.Lax))))
                  }
              }
        }
      }

    case req @ GET -> Root / "api" / "public" / key =>
      withShare(req, key)(v => Ok(GuestShareDto(v.share.pageId, v.pages.map(guest), v.share.hideHeader, v.share.sidebar, v.key, v.share.pinned && v.share.showReleases)))

    // 更新履歴（公開版を固定し、表示する設定のときだけ。メモのあるものだけ）
    case req @ GET -> Root / "api" / "public" / key / "releases" =>
      withShare(req, key) { v =>
        if !(v.share.pinned && v.share.showReleases) then err(Status.NotFound, "not found")
        else shares.releases(v.share.pageId).flatMap(rs => Ok(rs.flatMap(r => r.note.map(n => GuestReleaseDto(r.publishedAt, n)))))
      }

    case req @ GET -> Root / "api" / "public" / key / "pages" / pageKey =>
      withShare(req, key) { v =>
        v.find(pageKey) match
          case None => err(Status.NotFound, "not found")
          case Some(p) =>
            (v.content(p.id), store.cssLayers(p.id), store.guestHtmlLayers(p.id), store.isFullWidth(p.id)).flatMapN { (content, css, html, wide) =>
              Ok(GuestContentDto(guest(p), forGuest(key, v.pages.map(_.id).toSet)(content), css.map(_.css),
                top = html.flatMap(_.top), bottom = html.reverse.flatMap(_.bottom), fullWidth = wide))
            }
      }

    // 公開範囲のページの検索（タイトル・本文）
    case req @ GET -> Root / "api" / "public" / key / "search" =>
      withShare(req, key) { v =>
        val q = req.uri.query.params.getOrElse("q", "").take(200)
        v.texts.flatMap { texts =>
          val docs = v.pages.map(p => GuestSearch.Doc(p.id, p.title, texts.getOrElse(p.id, "")))
          val byId = v.pages.map(p => p.id -> p).toMap
          Ok(GuestSearch.search(q, docs).map(h => GuestSearchHitDto(guest(byId(h.id)), h.snippet, h.titleHit)))
        }
      }

    case req @ GET -> Root / "api" / "public" / key / "attachments" / UUIDVar(aid) =>
      withShare(req, key) { v =>
        attachments.get(aid).flatMap {
          case Some(a) if v.pages.exists(_.id == a.pageId) => files.serve(a, "private, no-store")
          case _                                           => err(Status.NotFound, "not found")
        }
      }

    // ------------------------------------------------------------ 検索エンジン向け
    case GET -> Root / "robots.txt" =>
      val body =
        if enabled then s"User-agent: *\nAllow: /s/\nDisallow: /\nSitemap: $base/sitemap.xml\n"
        else "User-agent: *\nDisallow: /\n"
      text(body, MediaType.text.plain)

    case GET -> Root / "sitemap.xml" =>
      (if enabled then shares.indexable else IO.pure(Nil)).flatMap { xs =>
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
      }.flatMap { urls =>
        val body = s"""<?xml version="1.0" encoding="UTF-8"?>
                      |<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                      |${urls.mkString("\n")}
                      |</urlset>
                      |""".stripMargin
        text(body, MediaType.application.xml)
      }
  }
