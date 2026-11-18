package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.core.*
import skiwi.db.Store

import java.util.UUID

/** level: workspace | page。title は閲覧できるページのときだけ。own=そのページ自身 */
final case class CssLayerDto(level: String, pageId: Option[UUID], title: Option[String], css: String, inherit: Boolean, own: Boolean) derives Encoder.AsObject
final case class PageStylesDto(layers: List[CssLayerDto], css: Option[String], inherit: Boolean) derives Encoder.AsObject
final case class SetPageCssReq(css: Option[String], inherit: Boolean) derives Decoder
final case class WorkspaceCssDto(css: Option[String]) derives Encoder.AsObject, Decoder
final case class GuestHtmlLayerDto(level: String, title: Option[String], top: Option[String], bottom: Option[String]) derives Encoder.AsObject
final case class PageGuestHtmlDto(top: Option[String], bottom: Option[String], inherit: Boolean, layers: List[GuestHtmlLayerDto] = Nil) derives Encoder.AsObject
final case class SetGuestHtmlReq(top: Option[String], bottom: Option[String], inherit: Option[Boolean]) derives Decoder

/**
 * カスタムCSS（ワークスペース・ページ）。設定は編集者のみ、取得は閲覧できる人。
 * 表示エリアの外に効かないよう、範囲の限定と危険な記述（外部URL・@import・position: fixed）の除去は表示側で行う。
 */
final class CssRoutes(protected val store: Store, protected val sessions: SessionAuth, audit: Audit) extends PageRouteSupport:

  val MaxLength = 20000

  private def clean(css: Option[String]) = css.map(_.trim).filter(_.nonEmpty)

  /** 適用するCSSの層（祖先のタイトルは閲覧できるときだけ） */
  def layers(s: Option[AuthSession], pageId: UUID): IO[List[CssLayerDto]] =
    store.cssLayers(pageId).flatMap { ls =>
      titles(s, ls.filterNot(_.depth.contains(0)).flatMap(_.pageId)).map { visible =>
        ls.map { l =>
          l.pageId match
            case None => CssLayerDto("workspace", None, None, l.css, true, own = false)
            case Some(pid) =>
              val own = l.depth.contains(0)
              CssLayerDto("page", Some(pid), if own then l.title else visible.get(pid), l.css, l.inherit, own)
        }
      }
    }

  /** 閲覧できる祖先のタイトル（ログインしていなければなし） */
  private def titles(s: Option[AuthSession], ids: List[UUID]): IO[Map[UUID, String]] =
    s.fold(IO.pure(Map.empty[UUID, String]))(ss => store.pagesAccess(ids, ss).map(_.view.mapValues(_._1.title).toMap))

  val MaxHtml = 50000
  private def tooLong(b: SetGuestHtmlReq) = b.top.exists(_.length > MaxHtml) || b.bottom.exists(_.length > MaxHtml)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // ------------------------------------------------------------ ゲスト向けHTML（編集者のみ。表示はゲスト閲覧時だけ）
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "guest-html" =>
      withEditor(req, id) { (s, _) =>
        (store.pageGuestHtml(id), store.guestHtmlLayers(id).map(_.filterNot(_.depth.contains(0)))).flatMapN { (own, ls) =>
          titles(Some(s), ls.flatMap(_.pageId)).flatMap { visible =>
            val outer = ls.map(l => GuestHtmlLayerDto(if l.pageId.isEmpty then "workspace" else "page", l.pageId.flatMap(visible.get), l.top, l.bottom))
            val (t, b, inh) = own.getOrElse((None, None, true))
            Ok(PageGuestHtmlDto(t, b, inh, outer))
          }
        }
      }

    case req @ PUT -> Root / "api" / "pages" / UUIDVar(id) / "guest-html" =>
      withEditor(req, id) { (s, p) =>
        body[SetGuestHtmlReq](req) {
          case b if tooLong(b) => err(Status.BadRequest, s"HTML は${MaxHtml}文字までです")
          case b =>
            store.setPageGuestHtml(id, clean(b.top), clean(b.bottom), b.inherit.getOrElse(true)) *>
              audit.log(s, "page.guest_html", "page", id, Json.obj("title" -> p.title.asJson,
                "top" -> clean(b.top).fold(0)(_.length).asJson, "bottom" -> clean(b.bottom).fold(0)(_.length).asJson)) *> NoContent()
        }
      }

    case req @ GET -> Root / "api" / "workspaces" / UUIDVar(id) / "guest-html" =>
      withWs(req, id, Role.Edit)((_, _, _) => store.workspaceGuestHtml(id).flatMap(h => Ok(PageGuestHtmlDto(h.flatMap(_._1), h.flatMap(_._2), true))))

    case req @ PUT -> Root / "api" / "workspaces" / UUIDVar(id) / "guest-html" =>
      withWs(req, id, Role.Edit) { (s, w, _) =>
        body[SetGuestHtmlReq](req) {
          case b if tooLong(b) => err(Status.BadRequest, s"HTML は${MaxHtml}文字までです")
          case b =>
            store.setWorkspaceGuestHtml(id, clean(b.top), clean(b.bottom)) *>
              audit.log(s, "workspace.guest_html", "workspace", id, Json.obj("name" -> w.name.asJson)) *> NoContent()
        }
      }

    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "styles" =>
      withPage(req, id, Role.View) { (s, _, _) =>
        (layers(Some(s), id), store.pageCss(id)).flatMapN((ls, own) => Ok(PageStylesDto(ls, own.flatMap(_._1), own.forall(_._2))))
      }

    case req @ PUT -> Root / "api" / "pages" / UUIDVar(id) / "css" =>
      withEditor(req, id) { (s, p) =>
        body[SetPageCssReq](req) {
          case b if b.css.exists(_.length > MaxLength) => err(Status.BadRequest, s"CSS は${MaxLength}文字までです")
          case b =>
            store.setPageCss(id, clean(b.css), b.inherit) *>
              audit.log(s, "page.css", "page", id, Json.obj("title" -> p.title.asJson, "length" -> clean(b.css).fold(0)(_.length).asJson, "inherit" -> b.inherit.asJson)) *>
              NoContent()
        }
      }

    case req @ GET -> Root / "api" / "workspaces" / UUIDVar(id) / "css" =>
      withVisibleWs(req, id)(_ => store.workspaceCss(id).flatMap(c => Ok(WorkspaceCssDto(c))))

    case req @ PUT -> Root / "api" / "workspaces" / UUIDVar(id) / "css" =>
      withWs(req, id, Role.Edit) { (s, w, _) =>
        body[WorkspaceCssDto](req) {
          case b if b.css.exists(_.length > MaxLength) => err(Status.BadRequest, s"CSS は${MaxLength}文字までです")
          case b =>
            store.setWorkspaceCss(id, clean(b.css)) *>
              audit.log(s, "workspace.css", "workspace", id, Json.obj("name" -> w.name.asJson, "length" -> clean(b.css).fold(0)(_.length).asJson)) *>
              NoContent()
        }
      }
  }
