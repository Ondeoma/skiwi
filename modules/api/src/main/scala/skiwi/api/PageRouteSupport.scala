package skiwi.api

import cats.effect.*
import org.http4s.*
import skiwi.core.*
import skiwi.db.Store

import java.util.UUID

/** ページ・ワークスペースの権限を確認する補助。 */
trait PageRouteSupport extends RouteSupport {
  protected def store: Store

  /** 権限のないページは存在を隠すため 404、閲覧可だが need に足りなければ 403。 */
  protected def checkPage(s: AuthSession, id: UUID, need: Role)(f: (Page, Role) => IO[Response[IO]]): IO[Response[IO]] =
    store.pageAccess(id, s).flatMap {
      case None => err(ErrorCode.PageNotFound)
      case Some((_, r)) if r < need => err(ErrorCode.PermissionDenied)
      case Some((p, r)) => f(p, r)
    }

  /** ページの編集者だけ（それ以外は 403。見られなければ 404） */
  protected def withEditor(req: Request[IO], id: UUID)(f: (AuthSession, Page) => IO[Response[IO]]): IO[Response[IO]] =
    withPage(req, id, Role.Edit)((s, p, _) => f(s, p))

  /** ページに対する権限を確認してから処理する。 */
  protected def withPage(req: Request[IO],
                         id: UUID,
                         need: Role)(f: (AuthSession, Page, Role) => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s =>
      checkPage(s, id, need)((p, r) => f(s, p, r))
    )

  /** 権限のないワークスペースは 404、閲覧可だが need に足りなければ 403。 */
  protected def checkWs(s: AuthSession,
                        id: UUID,
                        need: Role)(f: (Workspace, Role) => IO[Response[IO]]): IO[Response[IO]] =
    store.workspaceRole(id, s).flatMap {
      case None => err(ErrorCode.WorkspaceNotFound)
      case Some((_, r)) if r < need => err(ErrorCode.PermissionDenied)
      case Some((ws, r)) => f(ws, r)
    }

  /** ワークスペース直下に対する権限を確認してから処理する。 */
  protected def withWs(req: Request[IO],
                       id: UUID,
                       need: Role)(f: (AuthSession, Workspace, Role) => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s =>
      checkWs(s, id, need)((ws, r) => f(s, ws, r))
    )

  /** ワークスペースに属する設定（Webhook・自動化ルールなど）を読み、そのワークスペースの編集権限を確認してから処理する（なければ 404）。 */
  protected def withWsItem[A](
    req: Request[IO],
    find: IO[Option[A]],
    what: String)(wsOf: A => UUID)(f: (AuthSession, Workspace, A) => IO[Response[IO]]): IO[Response[IO]] =
    authed(req) { s =>
      find.flatMap {
        case None => err(ErrorCode.ItemNotFound, s"${what}が見つかりません")
        case Some(a) => checkWs(s, wsOf(a), Role.Edit)((ws, _) => f(s, ws, a))
      }
    }

  /** ワークスペースが見える（ページ単位の共有だけの場合も含む）ときだけ処理する（見えなければ 404）。 */
  protected def withVisibleWs(req: Request[IO], id: UUID)(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => store.workspaceVisible(id, s).ifM(f(s), err(ErrorCode.WorkspaceNotFound)))

  /** 親ページ（なければワークスペース直下）に対する編集権限を確認する。 */
  protected def canEditUnder(s: AuthSession, wsId: UUID, parent: Option[UUID]): IO[Either[EditUnderError, Unit]] =
    parent match {
      case Some(pid) =>
        store.pageAccess(pid, s).map {
          case Some((p, r)) if p.workspaceId == wsId && r >= Role.Edit => Right(())
          case Some((p, _)) if p.workspaceId == wsId => Left(EditUnderError.Forbidden)
          case _ => Left(EditUnderError.ParentNotFound)
        }
      case None =>
        store.workspaceRole(wsId, s).map {
          case Some((_, r)) if r >= Role.Edit => Right(())
          case Some(_) => Left(EditUnderError.Forbidden)
          case None => Left(EditUnderError.WorkspaceNotFound)
        }
    }

  /**
   * いっしょに消える子孫のうち、この人が編集できない（見られないものを含む）ページの数。
   * 親の編集権限だけで、個別に権限を絞った子や保護中の子まで消させないために使う。
   */
  protected def uneditableDescendants(s: AuthSession, id: UUID, inTrash: Boolean): IO[Int] =
    store.subtreeIds(id, inTrash).flatMap { ids =>
      if ids.isEmpty then IO.pure(0)
      else
        (if inTrash then store.pagesAccessAny(ids, s) else store.pagesAccess(ids, s))
          .map(acc => ids.count(i => !acc.get(i).exists(_._2 >= Role.Edit)))
    }
  protected def descendantsBlocked(n: Int): String = s"編集できない子ページ（${n}件）があるため削除できません"

  /** canEditUnder が通れば f、通らなければ 403 / 404。 */
  protected def editUnder(s: AuthSession, wsId: UUID, parent: Option[UUID])(f: => IO[Response[IO]]): IO[Response[IO]] =
    canEditUnder(s, wsId, parent).flatMap {
      case Right(_) => f
      case Left(EditUnderError.Forbidden) => err(ErrorCode.PermissionDenied)
      case Left(EditUnderError.ParentNotFound) => err(ErrorCode.ParentPageNotFound)
      case Left(EditUnderError.WorkspaceNotFound) => err(ErrorCode.WorkspaceNotFound)
    }
}
