package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Encoder
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import skiwi.db.OpsStore

import java.time.Instant
import java.util.UUID

final case class AvatarDto(avatarUrl: Option[String]) derives Encoder.AsObject

/** ユーザーのアイコン。画像はブラウザで正方形に縮めてから送る（PNG・JPEG・WebP、200KB まで）。
  *  - `GET /api/users/{id}/avatar`: ログイン中のユーザーなら誰のものでも見られる（`?v=` 付きは長くキャッシュする）
  *  - `PUT /api/me/avatar`（本文が画像そのもの）・`DELETE /api/me/avatar`: 自分のアイコンを変える・消す */
final class AvatarRoutes(ops: OpsStore, protected val sessions: SessionAuth, maxBytes: Int = AvatarRoutes.MaxBytes) extends RouteSupport:
  import AvatarRoutes.*

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ GET -> Root / "api" / "users" / UUIDVar(id) / "avatar" =>
      authed(req) { _ =>
        val versioned = req.uri.query.params.contains("v")
        ops.avatar(id).flatMap {
          // 自分で登録したアイコンがなければ IdP のアイコンへ
          case None => ops.pictures(List(id)).flatMap(_.get(id) match
              case Some(pic) => Response[IO](Status.Found).putHeaders(Header.Raw(CIString("Location"), pic),
                  Header.Raw(CIString("Cache-Control"), "private, max-age=300")).pure[IO]
              case None => err(Status.NotFound, "no avatar").map(_.putHeaders(Header.Raw(CIString("Cache-Control"), "private, max-age=300"))))
          case Some((ct, data, _)) =>
            Response[IO](Status.Ok).withBodyStream(fs2.Stream.chunk(fs2.Chunk.array(data))).putHeaders(
              Header.Raw(CIString("Content-Length"), data.length.toString),
              Header.Raw(CIString("Content-Type"), ct),
              Header.Raw(CIString("X-Content-Type-Options"), "nosniff"),
              Header.Raw(CIString("Content-Security-Policy"), "sandbox; default-src 'none'"),
              Header.Raw(CIString("Cache-Control"), if versioned then "private, max-age=31536000, immutable" else "private, max-age=300")
            ).pure[IO]
        }
      }

    case req @ PUT -> Root / "api" / "me" / "avatar" =>
      authed(req) { s =>
        val ct = req.headers.get[`Content-Type`].map(c => s"${c.mediaType.mainType}/${c.mediaType.subType}")
        ct.filter(Types.contains) match
          case None => err(Status.UnsupportedMediaType, "PNG・JPEG・WebP の画像を送ってください")
          case Some(t) =>
            if req.contentLength.exists(_ > maxBytes) then err(Status.PayloadTooLarge, "画像が大きすぎます")
            else req.body.take(maxBytes.toLong + 1).compile.to(Array).flatMap { data =>
              if data.length > maxBytes then err(Status.PayloadTooLarge, "画像が大きすぎます")
              else if !looksLike(t, data) then err(Status.BadRequest, "画像として読めません")
              else ops.saveAvatar(s.user.id, t, data).flatMap(v => Ok(AvatarDto(Some(url(s.user.id, v)))))
            }
      }

    case req @ DELETE -> Root / "api" / "me" / "avatar" =>
      authed(req)(s => ops.deleteAvatar(s.user.id) *> Ok(AvatarDto(None)))
  }

object AvatarRoutes:
  val MaxBytes = 200 * 1024
  private val Types = Set("image/png", "image/jpeg", "image/webp")

  def url(userId: UUID, version: Instant): String = s"/api/users/$userId/avatar?v=${version.toEpochMilli}"

  /** 表示するアイコンの URL（自分で登録したもの → IdP のもの）. どちらもない人は含まない */
  def urls(ops: OpsStore, userIds: List[UUID]): IO[Map[UUID, String]] =
    (ops.avatarVersions(userIds), ops.pictures(userIds)).mapN((vs, pics) => pics ++ vs.map((id, v) => id -> url(id, v)))

  /** 先頭のバイトが、名乗った種類の画像のものか */
  private[api] def looksLike(contentType: String, d: Array[Byte]): Boolean =
    def at(i: Int, bs: Int*) = d.length >= i + bs.length && bs.indices.forall(k => (d(i + k) & 0xff) == bs(k))
    contentType match
      case "image/png"  => at(0, 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a)
      case "image/jpeg" => at(0, 0xff, 0xd8, 0xff)
      case "image/webp" => at(0, 'R', 'I', 'F', 'F') && at(8, 'W', 'E', 'B', 'P')
      case _            => false
