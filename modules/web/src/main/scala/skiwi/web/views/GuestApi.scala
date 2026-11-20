package skiwi.web.views

import skiwi.shared.Titles

import io.circe.*
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.web.{Api, ApiError}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

// ゲスト向けの公開ページ・PDF（印刷）の画面が使う API.
// ゲスト用はログイン画面に飛ばさない（redirectOn401 = false）.

/** 公開範囲のページ. slug: ページのURL名 */
final case class GuestPage(id: String,
                           parentId: Option[String] = None,
                           position: String = "",
                           title: String = "",
                           icon: Option[String] = None,
                           updatedAt: String = "",
                           slug: Option[String] = None) derives Codec.AsObject {

  def displayTitle: String = Titles.display(title)

  def iconOr: String = icon.getOrElse("📄")

}

/** sidebar: auto（2ページ以上なら表示）| show | hide. releases: 更新履歴を出す */
final case class GuestTree(rootId: String,
                           pages: List[GuestPage],
                           hideHeader: Option[Boolean] = None,
                           sidebar: Option[String] = None,
                           key: Option[String] = None,
                           releases: Option[Boolean] = None) derives Codec.AsObject

/** snippet: 一致箇所を <mark> で囲んだHTML（ほかはエスケープ済み） */
final case class GuestSearchHit(page: GuestPage, snippet: Option[String] = None, titleHit: Boolean = false)
  derives Codec.AsObject

/** styles: カスタムCSS. top・bottom: 本文の上下に出すHTML. fullWidth: 全幅で表示する */
final case class GuestPageContent(page: GuestPage,
                                  content: Json = Json.Null,
                                  styles: Option[List[String]] = None,
                                  top: Option[List[String]] = None,
                                  bottom: Option[List[String]] = None,
                                  fullWidth: Option[Boolean] = None) derives Codec.AsObject

final case class GuestRelease(publishedAt: String, note: String = "") derives Codec.AsObject

final case class PageContent(content: Json = Json.Null, text: String = "") derives Codec.AsObject

object GuestApi {

  private def base(token: String): String = s"/api/public/${Api.enc(token)}"

  def tree(token: String): Future[GuestTree] = Api.get[GuestTree](base(token), redirectOn401 = false)

  /** 更新履歴（公開日時と変更点のメモ） */
  def releases(token: String): Future[List[GuestRelease]] =
    Api.get[List[GuestRelease]](s"${base(token)}/releases", redirectOn401 = false)

  /** 公開範囲のページの検索 */
  def search(token: String, q: String): Future[List[GuestSearchHit]] =
    Api.get[List[GuestSearchHit]](s"${base(token)}/search?q=${Api.enc(q)}", redirectOn401 = false)

  def page(token: String, pageId: String): Future[GuestPageContent] =
    Api.get[GuestPageContent](s"${base(token)}/pages/${Api.enc(pageId)}", redirectOn401 = false)

  /** 正しければ true. 回数超過などは例外 */
  def unlock(token: String, password: String): Future[Boolean] =
    Api.request(
      dom.HttpMethod.POST,
      s"${base(token)}/unlock",
      Some(Json.obj("password" -> password.asJson)),
      redirectOn401 = false
    )
      .map(_ => true)
      .recover {
        case e: ApiError if e.status == 403 => false
        case e: ApiError if e.status == 429 => throw ApiError(429, "試行回数が多すぎます。しばらく待ってからお試しください")
      }

  /** ページの本文（ログインユーザー. PDF 用） */
  def pageContent(id: String): Future[PageContent] =
    Api.get[PageContent](s"/api/pages/${Api.seg(id)}/content", redirectOn401 = false)

  /** ゲスト向けのページの URL（URL 名があればそれ、なければ ID） */
  def guestHref(token: String, p: GuestPage): String = s"/s/$token/${p.slug.filter(_.nonEmpty).getOrElse(p.id)}"

  /**
   * 親 → 子（並び順 position. thenId なら同じ並び順は ID で、でなければ元の並び）.
   * orphansToRoot: 一覧にない親（公開範囲の外）の子をルート扱いにする
   */
  def groupByParent(items: List[GuestPage],
                    orphansToRoot: Boolean = true,
                    thenId: Boolean = false): Map[Option[String], List[GuestPage]] = {
    val ids = items.map(_.id).toSet
    items.groupBy(p => p.parentId.filter(pid => !orphansToRoot || ids.contains(pid))).view
      .mapValues(xs => if (thenId) xs.sortBy(p => (p.position, p.id)) else xs.sortBy(_.position)).toMap
  }

  /** JSON を JS の値に（エディタへ渡す本文など） */
  def toJs(j: Json): js.Any = if (j.isNull) "" else js.JSON.parse(j.noSpaces)

}
