package skiwi.web.model

import io.circe.*

// 複数の画面（ページ・設定ダイアログ・管理）で使う API の型

/** status: none（申請していない）/ pending（承認待ち）/ has_access（すでに見られる）/ approved / denied */
final case class AccessStatus(status: String, role: Option[String] = None) derives Codec.AsObject

final case class LockEditor(id: String, name: String) derives Codec.AsObject

/** canEdit: 保護中でも編集できる / canChange: 保護の設定・解除ができる */
final case class PageLockState(locked: Boolean, lockedAt: Option[String] = None, lockedBy: Option[String] = None,
                               reason: Option[String] = None, editors: List[LockEditor] = Nil, canEdit: Boolean = false,
                               canChange: Boolean = false) derives Codec.AsObject

final case class CssLayer(level: String, pageId: Option[String] = None, title: Option[String] = None, css: String = "",
                          inherit: Boolean = false, own: Boolean = false) derives Codec.AsObject

final case class PageStyles(layers: List[CssLayer] = Nil, css: Option[String] = None, inherit: Boolean = false) derives Codec.AsObject

/**
 * sidebar: auto / show / hide. mode: live（編集中の内容をそのまま）/ pinned（「公開」を押した時点の内容）.
 * pendingChanges: pinned のとき、公開版に反映していない変更がある. notStarted: まだ公開前
 */
final case class PublicShare(token: String, includeChildren: Boolean, createdAt: String, createdBy: Option[String],
                             expiresAt: Option[String], hasPassword: Boolean, expired: Boolean, hideHeader: Boolean,
                             sidebar: String, mode: String, publishedAt: Option[String], publishedBy: Option[String],
                             pendingChanges: Option[Boolean], startsAt: Option[String], notStarted: Boolean,
                             slug: Option[String], indexable: Boolean, description: Option[String], showReleases: Boolean)
  derives Codec.AsObject

object PublicShare {

  /** 表示が片付けられる途中で読まれたときの値 */
  val empty: PublicShare = PublicShare("", true, "", None, None, false, false, false, "auto", "live", None, None, None, None,
    false, None, false, None, false)

}
