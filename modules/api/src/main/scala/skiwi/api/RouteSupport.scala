package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import skiwi.core.*
import skiwi.db.{Store, StoreError}

import java.util.{Base64, UUID}

/** JSON として読めなければ null。 */
def parseOrNull(s: String): Json = io.circe.parser.parse(s).getOrElse(Json.Null)

/** Base64 の Yjs 状態（空や読めないものは None）。 */
def decodeYdoc(b64: String): Option[Array[Byte]] =
  Either.catchNonFatal(Base64.getDecoder.decode(b64)).toOption.filter(_.nonEmpty)

/** 戻り先は同じサイトの相対パスだけ（外部サイトへのリダイレクトに使わせない）。省略は "/"、不正なものは None。 */
def safeReturnTo(raw: Option[String]): Option[String] = raw.map(_.trim).filter(_.nonEmpty) match
  case None => Some("/")
  case Some(p) =>
    Option.when(p.startsWith("/") && !p.startsWith("//") && !p.contains('\\') && !p.exists(_ < ' ') && p.length <= 2000)(p)

/** 1分あたりの回数をキーごとに制限する。上限内なら記録して true。 */
def perMinute[K](rate: Ref[IO, Map[K, List[Long]]], key: K, limit: Int): IO[Boolean] =
  IO.realTime.map(_.toMillis).flatMap { now =>
    rate.modify { m =>
      val recent = m.getOrElse(key, Nil).filter(_ > now - 60000)
      if recent.size >= limit then (m.updated(key, recent), false) else (m.updated(key, now :: recent), true)
    }
  }

/** 親ページ（なければワークスペース直下）に子を作れない理由。 */
enum EditUnderError:
  case Forbidden, ParentNotFound, WorkspaceNotFound

/** *Routes に共通の補助（エラー応答・認証・リクエスト本文）。 */
trait RouteSupport extends Http4sDsl[IO]:
  protected def sessions: SessionAuth

  protected def err(status: Status, msg: String): IO[Response[IO]] =
    Response[IO](status).withEntity(ErrorDto(msg)).pure[IO]

  protected def fromStore(e: StoreError): IO[Response[IO]] = e match
    case _: StoreError.NotFound => err(Status.NotFound, e.message)
    case _: StoreError.Invalid  => err(Status.BadRequest, e.message)

  /** ログインしていなければ 401。 */
  protected def authed(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    sessions.fromRequest(req).flatMap(_.fold(err(Status.Unauthorized, "unauthorized"))(f))

  /** 管理者のみ（それ以外は 403）。 */
  protected def admin(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => if s.isAdmin then f(s) else err(Status.Forbidden, "admin only"))

  /** リクエスト本文を読む（読めなければ 400）。 */
  protected def body[A: Decoder](req: Request[IO])(f: A => IO[Response[IO]]): IO[Response[IO]] =
    req.as[A].attempt.flatMap {
      case Left(_)  => err(Status.BadRequest, "invalid body")
      case Right(b) => f(b)
    }

/** ページ・ワークスペースの権限を確認する補助。 */
trait PageRouteSupport extends RouteSupport:
  protected def store: Store

  /** 権限のないページは存在を隠すため 404、閲覧可だが need に足りなければ 403。 */
  protected def checkPage(s: AuthSession, id: UUID, need: Role)(f: (Page, Role) => IO[Response[IO]]): IO[Response[IO]] =
    store.pageAccess(id, s).flatMap {
      case None                    => err(Status.NotFound, "page not found")
      case Some((_, r)) if r < need => err(Status.Forbidden, "insufficient permission")
      case Some((p, r))            => f(p, r)
    }

  /** ページに対する権限を確認してから処理する。 */
  protected def withPage(req: Request[IO], id: UUID, need: Role)(f: (AuthSession, Page, Role) => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => checkPage(s, id, need)((p, r) => f(s, p, r)))

  /** 権限のないワークスペースは 404、閲覧可だが need に足りなければ 403。 */
  protected def checkWs(s: AuthSession, id: UUID, need: Role)(f: (Workspace, Role) => IO[Response[IO]]): IO[Response[IO]] =
    store.workspaceRole(id, s).flatMap {
      case None                     => err(Status.NotFound, "workspace not found")
      case Some((_, r)) if r < need => err(Status.Forbidden, "insufficient permission")
      case Some((ws, r))            => f(ws, r)
    }

  /** ワークスペース直下に対する権限を確認してから処理する。 */
  protected def withWs(req: Request[IO], id: UUID, need: Role)(f: (AuthSession, Workspace, Role) => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => checkWs(s, id, need)((ws, r) => f(s, ws, r)))

  /** ワークスペースに属する設定（Webhook・自動化ルールなど）を読み、そのワークスペースの編集権限を確認してから処理する（なければ 404）。 */
  protected def withWsItem[A](req: Request[IO], find: IO[Option[A]], what: String)(wsOf: A => UUID)(f: (AuthSession, Workspace, A) => IO[Response[IO]]): IO[Response[IO]] =
    authed(req) { s =>
      find.flatMap {
        case None    => err(Status.NotFound, s"$what not found")
        case Some(a) => checkWs(s, wsOf(a), Role.Edit)((ws, _) => f(s, ws, a))
      }
    }

  /** ワークスペースが見える（ページ単位の共有だけの場合も含む）ときだけ処理する（見えなければ 404）。 */
  protected def withVisibleWs(req: Request[IO], id: UUID)(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => store.workspaceVisible(id, s).ifM(f(s), err(Status.NotFound, "workspace not found")))

  /** 親ページ（なければワークスペース直下）に対する編集権限を確認する。 */
  protected def canEditUnder(s: AuthSession, wsId: UUID, parent: Option[UUID]): IO[Either[EditUnderError, Unit]] =
    parent match
      case Some(pid) =>
        store.pageAccess(pid, s).map {
          case Some((p, r)) if p.workspaceId == wsId && r >= Role.Edit => Right(())
          case Some((p, _)) if p.workspaceId == wsId                   => Left(EditUnderError.Forbidden)
          case _                                                       => Left(EditUnderError.ParentNotFound)
        }
      case None =>
        store.workspaceRole(wsId, s).map {
          case Some((_, r)) if r >= Role.Edit => Right(())
          case Some(_)                        => Left(EditUnderError.Forbidden)
          case None                           => Left(EditUnderError.WorkspaceNotFound)
        }

  /** canEditUnder が通れば f、通らなければ 403 / 404。 */
  protected def editUnder(s: AuthSession, wsId: UUID, parent: Option[UUID])(f: => IO[Response[IO]]): IO[Response[IO]] =
    canEditUnder(s, wsId, parent).flatMap {
      case Right(_)                               => f
      case Left(EditUnderError.Forbidden)         => err(Status.Forbidden, "insufficient permission")
      case Left(EditUnderError.ParentNotFound)    => err(Status.NotFound, "parent page not found")
      case Left(EditUnderError.WorkspaceNotFound) => err(Status.NotFound, "workspace not found")
    }
