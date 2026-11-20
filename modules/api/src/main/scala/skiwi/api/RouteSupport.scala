package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.core.*
import sbirds.http.Errors
import sbirds.shared.AppError

import java.util.Base64

/** JSON として読めなければ null。 */
def parseOrNull(s: String): Json = io.circe.parser.parse(s).getOrElse(Json.Null)

/** Base64 の Yjs 状態（空や読めないものは None）。 */
def decodeYdoc(b64: String): Option[Array[Byte]] =
  Either.catchNonFatal(Base64.getDecoder.decode(b64)).toOption.filter(_.nonEmpty)

/** 本文（JSON）の最初の画像の src（公開ページの OGP・データベースのギャラリーの表紙に使う）。 */
def firstImageSrc(j: Json): Option[String] =
  j.asObject.flatMap { o =>
    val own = Option.when(
      o("type").flatMap(_.asString).contains("image")
    )(o("attrs").flatMap(_.hcursor.get[String]("src").toOption)).flatten
    own.orElse(o("content").flatMap(_.asArray).flatMap(_.view.flatMap(firstImageSrc).headOption))
  }.orElse(j.asArray.flatMap(_.view.flatMap(firstImageSrc).headOption))

/** 戻り先は同じサイトの相対パスだけ（外部サイトへのリダイレクトに使わせない）。省略は "/"、不正なものは None。 */
def safeReturnTo(raw: Option[String]): Option[String] = raw.map(_.trim).filter(_.nonEmpty) match {
  case None => Some("/")
  case Some(p) =>
    Option.when(p.startsWith("/") && !p.startsWith("//") && !p.contains('\\') && !p.exists(_ < ' ') &&
      p.length <= 2000 && Uri.fromString(p).isRight)(p)
}

/** 接続元のアドレス。ロードバランサー（HAProxy の option forwardfor）が X-Forwarded-For の最後に付けたもの、なければ TCP の接続元 */
def clientAddress(req: Request[IO]): String =
  req.headers.get(org.typelevel.ci.CIString("X-Forwarded-For")).flatMap(_.toList.lastOption)
    .flatMap(_.value.split(',').lastOption).map(_.trim).filter(_.nonEmpty)
    .orElse(req.remoteAddr.map(_.toString)).getOrElse("-")

/** 名前（前後の空白を除いて1〜max文字）。正しくなければ None */
def validName(raw: String, max: Int = 100): Option[String] = Some(raw.trim).filter(n => n.nonEmpty && n.length <= max)

/** ページのタイトルの長さの上限（これを超えた分は切り捨てる） */
val MaxPageTitle = 500

/** ページのアイコン（絵文字）の長さの上限 */
val MaxPageIcon = 32

/** ワークスペースの名前の長さの上限 */
val MaxWorkspaceName = 200

/** リクエスト本文の JSON の入れ子の上限（本文の JSON はふつう数十段） */
val MaxJsonDepth = 256

/** JSON の入れ子が limit 段を超えるか（再帰せずに数える） */
def jsonDepthOver(root: Json, limit: Int): Boolean = {
  val stack = scala.collection.mutable.Stack((root, 1))
  var over = false
  while !over && stack.nonEmpty do {
    val (j, d) = stack.pop()
    if d > limit then over = true
    else {
      j.asArray.foreach(_.foreach(c => stack.push((c, d + 1))))
      j.asObject.foreach(_.values.foreach(c => stack.push((c, d + 1))))
    }
  }
  over
}

/** 1分あたりの回数をキーごとに制限する。上限内なら記録して true。 */
def perMinute[K](rate: Ref[IO, Map[K, List[Long]]], key: K, limit: Int): IO[Boolean] =
  IO.realTime.map(_.toMillis).flatMap { now =>
    rate.modify { m =>
      val recent = m.getOrElse(key, Nil).filter(_ > now - 60000)
      if recent.size >= limit then (m.updated(key, recent), false) else (m.updated(key, now :: recent), true)
    }
  }

/** 親ページ（なければワークスペース直下）に子を作れない理由。 */
enum EditUnderError {
  case Forbidden, ParentNotFound, WorkspaceNotFound
}

/**
 * *Routes に共通の補助（エラー応答・認証・リクエスト本文）。
 */
trait RouteSupport extends sbirds.http.RouteHelpers {
  protected def sessions: SessionAuth

  protected def bodyInvalid: sbirds.shared.ErrorCode = ErrorCode.RequestBodyInvalid

  /** エラーの応答（コード付きでログに出る）. detail を省くとコードの既定の文言. */
  protected def err(code: ErrorCode, detail: String | Null = null): IO[Response[IO]] =
    Errors.respond(AppError(code, Option(detail)))

  protected def fail(e: AppError): IO[Response[IO]] = Errors.respond(e)

  /** ログインしていなければ 401。操作したユーザーを、トレース（スパンの属性）とこの中のログに載せる。 */
  protected def authed(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    sessions.fromRequest(req).flatMap(_.fold(err(ErrorCode.NotLoggedIn))(s =>
      sbirds.observe.Telemetry.withUser(s.user.id.toString, s.user.name)(f(s))
    ))

  /** 管理者のみ（それ以外は 403）。 */
  protected def admin(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => if s.isAdmin then f(s) else err(ErrorCode.AdminOnly))

  /** リクエスト本文を読む（読めなければ 400）。 */
  override protected def body[A: Decoder](req: Request[IO])(f: A => IO[Response[IO]]): IO[Response[IO]] =
    decodeBody[A](req).flatMap(_.fold(err(ErrorCode.RequestBodyInvalid, _), f))

  /** リクエスト本文を読む。読めなければ Left(理由)。大きすぎる本文（Security.bodyLimit の AppError）はそのまま投げる（全体のエラー処理で 413 になる） */
  protected def decodeBody[A: Decoder](req: Request[IO]): IO[Either[String, A]] =
    req.as[Json].attempt.flatMap {
      case Left(e: AppError) => IO.raiseError(e)
      case Left(_) => IO.pure(Left("リクエストの本文が正しくありません"))
      // 深すぎる入れ子は、保存時の書き出し（再帰）でスタックを使い切るので断る
      case Right(j) if jsonDepthOver(j, MaxJsonDepth) => IO.pure(Left("リクエストの本文の入れ子が深すぎます"))
      case Right(j) => IO.pure(j.as[A].left.map(e => s"リクエストの本文が正しくありません: ${e.getMessage}"))
    }
}
