package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.core.*
import skiwi.db.{Comment, CommentStore, LinkExtract, OpsStore, Store}

import java.time.Instant
import java.util.UUID

final case class CommentDto(
    id: UUID, threadId: Option[UUID], anchor: Option[String], quote: Option[String], body: String,
    authorId: Option[UUID], authorName: Option[String], createdAt: Instant, updatedAt: Instant,
    resolvedAt: Option[Instant], resolvedBy: Option[String], deleted: Boolean, canEdit: Boolean, canDelete: Boolean
) derives Encoder.AsObject
final case class NewCommentReq(body: String, threadId: Option[UUID], anchor: Option[String], quote: Option[String]) derives Decoder
final case class EditCommentReq(body: String) derives Decoder
final case class ResolveReq(resolved: Boolean) derives Decoder

final case class AudienceEntryDto(userId: UUID, name: String, email: Option[String], role: Role, reason: String) derives Encoder.AsObject

/**
 * コメント（閲覧できる人は誰でも書ける）と、ページを閲覧できる人の一覧。
 *  - 編集・削除は投稿者（削除はページの編集者も可）。解決は投稿者とページの編集者。
 *  - 本文の @[名前](ユーザーID) はメンションとして通知する。返信はスレッドの参加者とウォッチしている人に通知する。
 */
final class CommentRoutes(protected val store: Store, comments: CommentStore, ops: OpsStore, protected val sessions: SessionAuth, audit: Audit, mentions: Mentions, webhooks: Option[WebhookService] = None)
    extends PageRouteSupport:

  /** コメントとそのページへの権限を確認する */
  private def withComment(req: Request[IO], id: UUID)(f: (AuthSession, Comment, Page, Role) => IO[Response[IO]]) =
    comments.get(id).flatMap {
      case None    => err(Status.NotFound, "comment not found")
      case Some(c) => withPage(req, c.pageId, Role.View)((s, p, r) => f(s, c, p, r))
    }

  private def dto(s: AuthSession, r: Role)(c: Comment) =
    val mine = c.authorId.contains(s.user.id)
    CommentDto(c.id, c.threadId, c.anchor, c.quote, if c.deletedAt.isDefined then "" else c.body, c.authorId, c.authorName,
      c.createdAt, c.updatedAt, c.resolvedAt, c.resolvedBy, c.deletedAt.isDefined,
      canEdit = mine && c.deletedAt.isEmpty, canDelete = (mine || r == Role.Edit) && c.deletedAt.isEmpty)

  private val validAnchor = """^(text|wb):[0-9A-Za-z-]{1,64}$""".r

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "comments" =>
      withPage(req, id, Role.View)((s, _, r) => comments.list(id).flatMap(cs => Ok(cs.map(dto(s, r)))))

    case req @ POST -> Root / "api" / "pages" / UUIDVar(id) / "comments" =>
      withPage(req, id, Role.View) { (s, _, r) =>
        body[NewCommentReq](req) {
          case b if b.body.trim.isEmpty || b.body.length > 10000 => err(Status.BadRequest, "body must be 1-10000 characters")
          case b if b.anchor.exists(a => validAnchor.findFirstIn(a).isEmpty) => err(Status.BadRequest, "invalid anchor")
          case b =>
            // 返信先は同じページのスレッドの先頭に限る
            b.threadId.flatTraverse(comments.get).flatMap { parent =>
              if b.threadId.isDefined && !parent.exists(c => c.pageId == id && c.isRoot) then err(Status.BadRequest, "invalid thread")
              else
                val root = parent.map(_.id)
                for
                  c        <- comments.create(id, root, if root.isDefined then None else b.anchor, if root.isDefined then None else b.quote.map(_.take(500)), b.body.trim, s.user.id)
                  excerpt   = Mentions.excerpt(c.body)
                  mentioned = LinkExtract.commentMentions(c.body) - s.user.id
                  _        <- mentions.notify("mention", id, mentioned, s.user.name, excerpt, Some(c.root))
                  others   <- (root.fold(IO.pure(Set.empty[UUID]))(comments.participants), ops.watchers(id)).mapN(_ ++ _)
                  _        <- mentions.notify("comment", id, others -- mentioned - s.user.id, s.user.name, excerpt, Some(c.root))
                  _        <- webhooks.traverse_(_.emit("comment.created", id, s.user.name, Json.obj("excerpt" -> excerpt.asJson, "threadId" -> c.root.toString.asJson)))
                  resp     <- Created(dto(s, r)(c))
                yield resp
            }
        }
      }

    case req @ PATCH -> Root / "api" / "comments" / UUIDVar(id) =>
      withComment(req, id) { (s, c, _, r) =>
        body[EditCommentReq](req) {
          case _ if !c.authorId.contains(s.user.id) || c.deletedAt.isDefined => err(Status.Forbidden, "only the author can edit")
          case b if b.body.trim.isEmpty || b.body.length > 10000 => err(Status.BadRequest, "body must be 1-10000 characters")
          case b =>
            // 編集で新しく加わったメンションだけ通知する
            val added = LinkExtract.commentMentions(b.body) -- LinkExtract.commentMentions(c.body) - s.user.id
            comments.edit(id, b.body.trim) *> mentions.notify("mention", c.pageId, added, s.user.name, Mentions.excerpt(b.body), Some(c.root)) *>
              comments.get(id).flatMap(n => Ok(dto(s, r)(n.get)))
        }
      }

    case req @ DELETE -> Root / "api" / "comments" / UUIDVar(id) =>
      withComment(req, id) { (s, c, _, r) =>
        if !(c.authorId.contains(s.user.id) || r == Role.Edit) then err(Status.Forbidden, "insufficient permission")
        else comments.delete(id) *>
          audit.log(s, "comment.delete", "page", c.pageId, Json.obj("commentId" -> id.asJson, "author" -> c.authorName.asJson)) *> NoContent()
      }

    case req @ POST -> Root / "api" / "comments" / UUIDVar(id) / "resolve" =>
      withComment(req, id) { (s, c, _, r) =>
        body[ResolveReq](req) {
          case _ if !c.isRoot => err(Status.BadRequest, "only a thread can be resolved")
          case _ if !(c.authorId.contains(s.user.id) || r == Role.Edit) => err(Status.Forbidden, "insufficient permission")
          case b => comments.resolve(id, Option.when(b.resolved)(s.user.id)) *> comments.get(id).flatMap(n => Ok(dto(s, r)(n.get)))
        }
      }

    // ------------------------------------------------------------ 閲覧できる人（要Edit。グループの所属が分かるため）
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "audience" =>
      withPage(req, id, Role.Edit) { (_, _, _) =>
        store.audience(id, sessions.adminGroups).flatMap {
          case None => err(Status.NotFound, "page not found")
          case Some(a) =>
            val principalIds = a.entries.flatMap(_.source).collect {
              case Acl.Source.PageEntry(_, pr)    => pr.id
              case Acl.Source.WorkspaceEntry(pr) => pr.id
            }.distinct
            ops.principalNames(principalIds).flatMap { names =>
              def who(pr: Principal) = pr.tpe match
                case PrincipalType.Group => s"グループ「${names.get(pr.id).fold("(不明)")(_.name)}」"
                case PrincipalType.User  => "個別の共有"
              def title(d: Int) = a.chainTitles.lift(d).filter(_.nonEmpty).getOrElse("無題")
              val dtos = a.entries.map { e =>
                val reason = e.source match
                  case None                                => "管理者"
                  case Some(Acl.Source.PageEntry(0, pr))   => s"このページの共有設定（${who(pr)}）"
                  case Some(Acl.Source.PageEntry(d, pr))   => s"親ページ「${title(d)}」の共有設定（${who(pr)}）"
                  case Some(Acl.Source.WorkspaceEntry(pr)) => s"ワークスペースの共有設定（${who(pr)}）"
                  case Some(Acl.Source.WorkspaceDefault)   => "ワークスペースの既定（全員）"
                AudienceEntryDto(e.userId, e.name, e.email, e.role, reason)
              }
              Ok(dtos.sortBy(d => (if d.role == Role.Edit then 0 else 1, d.name)))
            }
        }
      }
  }
