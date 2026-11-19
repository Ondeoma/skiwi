package skiwi.core

import java.time.Instant
import java.util.UUID

/** UUID として読めれば Some（読めなければ None）。 */
def uuidOpt(
  s: String
): Option[UUID] = scala.util.Try(UUID.fromString(s)).toOption

/** ページ／ワークスペースに対する権限。閲覧(View) < 編集(Edit)。 */
enum Role {
  case View, Edit
}

object Role {
  given Ordering[Role] = Ordering.by(_.ordinal)

  def parse(
    s: String
  ): Option[Role] = s.toLowerCase match {
    case "view" => Some(View)
    case "edit" => Some(Edit)
    case _ => None
  }

  extension (
    r: Role
  ) {
    def asString: String = r match {
      case View => "view"
      case Edit => "edit"
    }
    def >=(
      o: Role
    ): Boolean = r.ordinal >= o.ordinal
    def <(
      o: Role
    ): Boolean = r.ordinal < o.ordinal
  }
}

enum PrincipalType {
  case User, Group
}

object PrincipalType {
  def parse(
    s: String
  ): Option[PrincipalType] = s.toLowerCase match {
    case "user" => Some(User)
    case "group" => Some(Group)
    case _ => None
  }

  extension (
    p: PrincipalType
  ) {
    def asString: String = p match {
      case User => "user"
      case Group => "group"
    }
  }
}

final case class Principal(
  tpe: PrincipalType,
  id: UUID)
final case class AclEntry(
  principal: Principal,
  role: Role)

/** ページ1階層分のACL。inherit=false の場合、上位（親・ワークスペース）の権限を継承しない。 */
final case class AclNode(
  inherit: Boolean,
  entries: List[AclEntry])

final case class User(
  id: UUID,
  sub: String,
  email: Option[String],
  name: String)
final case class Group(
  id: UUID,
  externalId: String,
  name: String)
final case class Workspace(
  id: UUID,
  name: String,
  defaultRole: Option[Role])

final case class Page(
  id: UUID,
  workspaceId: UUID,
  parentId: Option[UUID],
  position: String,
  title: String,
  icon: Option[String],
  createdAt: Instant,
  updatedAt: Instant)

/** 認証手段。APIトークンの read スコープは閲覧のみ（編集系リクエストは拒否）。 */
enum AuthVia {
  case Session
  case Token(
    id: UUID,
    readOnly: Boolean)
}

/** 認証済みセッション。isAdmin はIdPの管理者グループ（ADMIN_GROUPS）所属で決まる（全ページ編集可）。 */
final case class AuthSession(
  user: User,
  groups: List[Group],
  isAdmin: Boolean,
  via: AuthVia = AuthVia.Session) {
  def principals: Set[Principal] =
    groups.map(g => Principal(PrincipalType.Group, g.id)).toSet + Principal(PrincipalType.User, user.id)
  def readOnly: Boolean = via match {
    case AuthVia.Token(_, ro) => ro
    case AuthVia.Session => false
  }
  def viaString: String = via match {
    case AuthVia.Session => "session"
    case _: AuthVia.Token => "token"
  }
}

/** 兄弟ページ間の挿入位置。 */
enum Placement {
  case First, Last
  case After(
    id: UUID)
}
