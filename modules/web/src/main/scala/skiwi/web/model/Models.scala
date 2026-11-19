package skiwi.web.model

import skiwi.shared.Titles

import io.circe.*

// 画面全体で使う API の型. 画面ごとの型は、その画面の XxxApi に置く.

/** avatarUrl: アイコンを設定していれば、その URL / appNames: sbirds のアプリの表示名（slug → 名前. 自身は "_self"） */
final case class Me(id: String,
                    name: String,
                    email: Option[String] = None,
                    groups: List[String] = Nil,
                    isAdmin: Boolean = false,
                    avatarUrl: Option[String] = None,
                    appNames: Map[String, String] = Map.empty) derives Codec.AsObject

/** role: view / edit */
final case class Workspace(id: String, name: String, role: String, favorite: Boolean = false) derives Codec.AsObject

/**
 * ページ（一覧・取得の結果）.
 * published: ゲスト向けに公開中 / labels: ラベル（ページ一覧でのみ入る） / status: draft・review・published・deprecated
 * database: データベースのページ / locked: 保護中 / fullWidth: 全幅で表示する
 */
final case class Page(id: String,
                      workspaceId: String,
                      parentId: Option[String],
                      position: String,
                      title: String,
                      icon: Option[String],
                      role: String,
                      updatedAt: String,
                      published: Option[Boolean] = None,
                      labels: Option[List[String]] = None,
                      ownerId: Option[String] = None,
                      ownerName: Option[String] = None,
                      status: Option[String] = None,
                      database: Option[Boolean] = None,
                      locked: Option[Boolean] = None,
                      fullWidth: Option[Boolean] = None) derives Codec.AsObject {

  def isDatabase: Boolean = database.contains(true)

  def isLocked: Boolean = locked.contains(true)

  def isPublished: Boolean = published.contains(true)

  def canEdit: Boolean = role == "edit"

  def labelList: List[String] = labels.getOrElse(Nil)

  /** 表示名（無題なら「無題」） */
  def displayTitle: String = Titles.display(title)

  def iconOr: String = icon.getOrElse("📄")

}

object Page {

  val StatusLabel: Map[String, String] =
    Map("draft" -> "下書き", "review" -> "レビュー中", "published" -> "公開済み", "deprecated" -> "廃止")

  val Statuses: List[String] = List("draft", "review", "published", "deprecated")

}

final case class FavoritePage(page: Page, workspaceName: String) derives Codec.AsObject

/** principalType: user / group */
final case class DirectoryEntry(principalType: String, id: String, name: String, detail: Option[String] = None)
  derives Codec.AsObject

/** ページツリーの節（Store.tree） */
final case class TreeNode(page: Page, children: List[TreeNode])
