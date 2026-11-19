package skiwi.api

import io.circe.*
import skiwi.core.*

import java.time.Instant
import java.util.UUID

// フロントエンド(Scala.js)と共有する DTO（modules/shared）
export skiwi.shared.{
  AgentDto, AgentKeyDto, AuditDto, CreateAgentReq, CreateTokenReq, CreatedTokenDto, ErrorDto, TokenDto, UpdateAgentReq
}

given Encoder[Role] = Encoder.encodeString.contramap(_.asString)
given Decoder[Role] = Decoder.decodeString.emap(s => Role.parse(s).toRight(s"invalid role: $s"))
given Encoder[PrincipalType] = Encoder.encodeString.contramap(_.asString)
given Decoder[PrincipalType] =
  Decoder.decodeString.emap(s => PrincipalType.parse(s).toRight(s"invalid principal type: $s"))

/** avatarUrl: アイコンがあれば、その URL（更新日時の版付き） */
/** 「項目なし（変えない）・null（消す）・値」の3通りを読む。項目がなければ None、null なら Some(None) */
def tri[A: Decoder](c: io.circe.HCursor, k: String): Decoder.Result[Option[Option[A]]] =
  c.downField(k).success.fold(Right(None): Decoder.Result[Option[Option[A]]])(f => f.as[Option[A]].map(Some(_)))

/** tri の文字列版（前後の空白を除き、空なら null と同じ） */
def triText(c: io.circe.HCursor, k: String): Decoder.Result[Option[Option[String]]] =
  tri[String](c, k).map(_.map(_.map(_.trim).filter(_.nonEmpty)))

final case class MeDto(
  id: UUID,
  name: String,
  email: Option[String],
  groups: List[String],
  isAdmin: Boolean,
  avatarUrl: Option[String],
  /** sbirds のアプリの表示名（slug → 名前. 自身は "_self". Cookie のセッションのときだけ） */
  appNames: Map[String, String] = Map.empty) derives Encoder.AsObject

final case class WorkspaceDto(id: UUID, name: String, role: Role, favorite: Boolean = false) derives Encoder.AsObject
final case class FavoriteDto(favorite: Boolean) derives Encoder.AsObject, Decoder
final case class CreateWorkspaceReq(name: String) derives Decoder

final case class PageDto(
  id: UUID,
  workspaceId: UUID,
  parentId: Option[UUID],
  position: String,
  title: String,
  icon: Option[String],
  role: Role,
  updatedAt: Instant,
  /** ゲスト向けに公開中（このページ自身に公開リンクがある） */
  published: Boolean = false,
  /** ラベル（ページ一覧でのみ返す） */
  labels: List[String] = Nil,
  ownerId: Option[UUID] = None,
  ownerName: Option[String] = None,
  /** draft | review | published | deprecated */
  status: Option[String] = None,
  /** データベース（子ページを表・ボード・カレンダーで見る）のページ */
  database: Boolean = false,
  /** 保護中（編集できる人が限られている） */
  locked: Boolean = false,
  /** 全幅で表示する */
  fullWidth: Boolean = false) derives Encoder.AsObject {

  /** 所有者・状態を付ける */
  def withMeta(m: Option[skiwi.db.PageMeta]): PageDto =
    m.fold(this)(x => copy(ownerId = x.ownerId, ownerName = x.ownerName, status = x.status))
}
object PageDto {
  def of(p: Page, r: Role): PageDto =
    PageDto(p.id, p.workspaceId, p.parentId, p.position, p.title, p.icon, r, p.updatedAt)
}

/** placement: "first" | "last"(既定) 、または afterId 指定。 */
final case class CreatePageReq(parentId: Option[UUID],
                               afterId: Option[UUID],
                               placement: Option[String],
                               title: Option[String],
                               icon: Option[String]) derives Decoder

/** workspaceId を付けると別のワークスペースへ移す（子孫ごと） */
final case class MovePageReq(parentId: Option[UUID],
                             afterId: Option[UUID],
                             placement: Option[String],
                             workspaceId: Option[UUID] = None) derives Decoder

/** icon / ownerId / status: 項目なし=変更しない、null（icon は空文字も）=外す、値=設定（自動導出だと null が「変更しない」になるため手書き）。 */
final case class UpdatePageReq(title: Option[String],
                               icon: Option[Option[String]],
                               ownerId: Option[Option[UUID]] = None,
                               status: Option[Option[String]] = None,
                               fullWidth: Option[Boolean] = None)
object UpdatePageReq {
  val Statuses = Set("draft", "review", "published", "deprecated")
  given Decoder[UpdatePageReq] = Decoder.instance { c =>
    for {
      title <- c.get[Option[String]]("title")
      icon <- triText(c, "icon")
      owner <- tri[UUID](c, "ownerId")
      status <- tri[String](c, "status")
      fw <- c.get[Option[Boolean]]("fullWidth")
    } yield UpdatePageReq(title, icon, owner, status, fw)
  }
}

/** expiresAt: 権限の期限（過ぎると自動で外れる） */
final case class AclEntryDto(principalType: PrincipalType,
                             principalId: UUID,
                             role: Role,
                             expiresAt: Option[Instant] = None) derives Encoder.AsObject, Decoder
final case class AclDto(inherit: Boolean, entries: List[AclEntryDto]) derives Encoder.AsObject, Decoder
final case class WorkspaceAclDto(defaultRole: Option[Role], entries: List[AclEntryDto]) derives Decoder

// ---------------------------------------------------------------- フェーズ4

/** ACL表示用（名前付き）。 */
final case class AclEntryViewDto(principalType: PrincipalType,
                                 principalId: UUID,
                                 role: Role,
                                 name: String,
                                 detail: Option[String],
                                 expiresAt: Option[Instant] = None) derives Encoder.AsObject
final case class AclViewDto(inherit: Boolean, entries: List[AclEntryViewDto]) derives Encoder.AsObject
final case class WorkspaceAclViewDto(defaultRole: Option[Role], entries: List[AclEntryViewDto]) derives Encoder.AsObject

final case class DirectoryDto(principalType: PrincipalType, id: UUID, name: String, detail: Option[String])
  derives Encoder.AsObject

final case class WatchDto(watching: Boolean) derives Encoder.AsObject, Decoder

/**
 * ウォッチの状態。edits / comments / subtree はこのページの設定（ウォッチしていなければ false）。
 * inherited: 「子ページも含める」でウォッチしている祖先（このページに設定がなければ、その設定で通知が届く）
 */
final case class WatchInheritedDto(pageId: UUID, title: String, edits: Boolean, comments: Boolean)
  derives Encoder.AsObject
final case class WatchStateDto(watching: Boolean,
                               edits: Boolean,
                               comments: Boolean,
                               subtree: Boolean,
                               inherited: Option[WatchInheritedDto]) derives Encoder.AsObject

/** watching だけ（従来）か、edits / comments / subtree を指定する */
final case class SetWatchReq(watching: Option[Boolean],
                             edits: Option[Boolean],
                             comments: Option[Boolean],
                             subtree: Option[Boolean]) derives Decoder

final case class VersionDto(id: UUID,
                            createdAt: Instant,
                            label: Option[String],
                            createdBy: Option[String],
                            authors: List[String]) derives Encoder.AsObject
final case class VersionContentDto(version: VersionDto, content: Json, text: String) derives Encoder.AsObject
final case class PageContentDto(content: Json, text: String, updatedAt: Instant) derives Encoder.AsObject

/** クライアントが送る現在の文書状態。ydoc は Yjs の状態(base64)。 */
final case class DocStateReq(ydoc: String, content: Json, text: String, label: Option[String]) derives Decoder
