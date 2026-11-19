package skiwi.db

import doobie.*
import skiwi.core.*

object Codecs {
  given Meta[Role] = Meta[String].timap(s =>
    Role.parse(s).getOrElse(throw IllegalStateException(s"invalid role in db: $s"))
  )(_.asString)

  given Meta[PrincipalType] = Meta[String].timap(s =>
    PrincipalType.parse(s).getOrElse(throw IllegalStateException(s"invalid principal type in db: $s"))
  )(_.asString)
}
