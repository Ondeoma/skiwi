package skiwi.api

import io.circe.Decoder

import java.time.Instant

/** expiresAt / startsAt / password / slug / description: 項目なし=変更しない、null（空文字も）=解除、値=設定。 */
final case class SetShareReq(includeChildren: Option[Boolean],
                             expiresAt: Option[Option[Instant]],
                             password: Option[Option[String]],
                             hideHeader: Option[Boolean] = None,
                             sidebar: Option[String] = None,
                             mode: Option[String] = None,
                             startsAt: Option[Option[Instant]] = None,
                             slug: Option[Option[String]] = None,
                             indexable: Option[Boolean] = None,
                             description: Option[Option[String]] = None,
                             showReleases: Option[Boolean] = None)

object SetShareReq {
  given Decoder[SetShareReq] = Decoder.instance { c =>
    for {
      inc <- c.get[Option[Boolean]]("includeChildren")
      exp <- tri[Instant](c, "expiresAt")
      pw <- tri[String](c, "password")
      hh <- c.get[Option[Boolean]]("hideHeader")
      sb <- c.get[Option[String]]("sidebar")
      md <- c.get[Option[String]]("mode")
      st <- tri[Instant](c, "startsAt")
      sl <- triText(c, "slug")
      ix <- c.get[Option[Boolean]]("indexable")
      ds <- triText(c, "description")
      sr <- c.get[Option[Boolean]]("showReleases")
    } yield SetShareReq(
      inc,
      exp,
      pw.map(_.filter(_.nonEmpty)),
      hh,
      sb,
      md,
      st,
      sl.map(_.map(_.toLowerCase)),
      ix,
      ds,
      sr
    )
  }
}
