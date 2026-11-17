package skiwi.web.editor

import io.circe.syntax.*
import org.scalajs.dom
import skiwi.web.views.{GuestApi, GuestPage}
import skiwi.web.{Api, ApiError}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.{Failure, Success}

/**
 * 本文の中のページへのリンク（ページリンク・同期ブロックの埋め込み・データベース）が使う、ページの情報.
 *  - このシステムのページの URL（/w/{ワークスペース}/p/{ページ}）の読み取り
 *  - 表示用のページ情報（取得済みのものを共有）と、ゲスト表示（公開ページ・印刷）の状態
 *  - 同期ブロックの埋め込みが読む元ページ（同時の取得は1回にまとめ、定期更新のタイマーも1つにする）
 */
object PageLinks {

  final case class PageUrl(workspaceId: String, pageId: String, blockId: Option[String], anchor: Option[String])

  private val Uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
  private val PathRe = s"(?i)^/w/($Uuid)/p/($Uuid)/?(?:\\?[^#]*)?(?:#(.*))?$$".r
  private val SyncRe = "(?i)^sync-([0-9a-z]{4,40})$".r
  private val BlockRe = "(?i)^block-([0-9a-z]{4,40})$".r

  /** このシステムのページの URL か（同じホストのものだけ）. blockId: 同期ブロック（#sync-ID）、anchor: ブロック（#block-ID） */
  def parse(raw: String): Option[PageUrl] = {
    val s = raw.trim
    if (s.isEmpty || "\\s".r.findFirstIn(s).isDefined) None
    else {
      val path =
        if ("(?i)^https?://.*".r.matches(s))
          scala.util.Try(new dom.URL(s)).toOption.filter(_.host == dom.window.location.host).map(u => u.pathname + u.search + u.hash)
        else Some(s)
      path.flatMap(PathRe.findFirstMatchIn).map { m =>
        val frag = Option(m.group(3)).getOrElse("")
        PageUrl(m.group(1).toLowerCase, m.group(2).toLowerCase,
          SyncRe.findFirstMatchIn(frag).map(_.group(1)), BlockRe.findFirstMatchIn(frag).map(_.group(1)))
      }
    }
  }

  // ---------------------------------------------------------------- 表示の更新の知らせ

  private var listeners: Set[js.Function0[Unit]] = Set.empty
  private def changed(): Unit = listeners.foreach(_())

  /** ページ情報・ゲスト表示の状態が変わったら f を呼ぶ. 戻り値で解除 */
  def onChange(f: js.Function0[Unit]): () => Unit = {
    listeners += f
    () => listeners -= f
  }

  // ---------------------------------------------------------------- 表示用のページ情報

  /** state: loading / ok / denied（削除済み・権限なし） */
  final case class LinkInfo(state: String, title: String = "", icon: Option[String] = None, workspaceId: String = "")

  private var cache: Map[String, LinkInfo] = Map.empty

  def pageInfo(pageId: String): LinkInfo = cache.getOrElse(pageId, {
    val loading = LinkInfo("loading")
    cache += pageId -> loading
    Api.page(pageId, redirectOn401 = false).onComplete {
      case Success(p) => cache += pageId -> LinkInfo("ok", p.title, p.icon, p.workspaceId); changed()
      case Failure(_: ApiError) => cache += pageId -> LinkInfo("denied"); changed()
      case Failure(_) => cache -= pageId // 通信エラーは次に表示するときにやり直す（すぐには知らせない）
    }
    loading
  })

  // ---------------------------------------------------------------- ゲスト表示

  final case class Guest(token: String, pages: Map[String, GuestPage])

  private var guestCtx: Option[Guest] = None

  /** ゲスト表示中は、公開範囲のページだけをリンクとして出す（ログインが必要な API は呼ばない） */
  def guest: Option[Guest] = guestCtx

  /** ゲスト表示（公開ページ・印刷）を始める・終える（None） */
  def setGuest(ctx: Option[(String, List[GuestPage])]): Unit = {
    guestCtx = ctx.map((token, pages) => Guest(token, pages.map(p => p.id -> p).toMap))
    changed()
  }

  // ---------------------------------------------------------------- 同期ブロックの元ページ

  final case class SyncedSource(title: String, content: io.circe.Json)

  private val Ttl = 1000.0
  private var recent: Map[String, (Double, Future[Option[SyncedSource]])] = Map.empty

  private def load(pid: String, guestToken: Option[String]): Future[Option[SyncedSource]] = guestToken match {
    case Some(t) => GuestApi.page(t, pid).map(r => Some(SyncedSource(r.page.title, r.content)))
    case None =>
      // 取れなければ「権限なし」として表示する（ログイン画面には飛ばさない）
      Api.okOrNone(Api.page(pid, redirectOn401 = false)).zip(Api.okOrNone(GuestApi.pageContent(pid))).map {
        case (Some(meta), Some(c)) => Some(SyncedSource(meta.title, c.content))
        case _ => None
      }
  }

  /** 元ページのタイトルと保存済みの本文（見られなければ None） */
  def syncedSource(pid: String, guestToken: Option[String]): Future[Option[SyncedSource]] = {
    val now = js.Date.now()
    recent = recent.filter((_, v) => now - v._1 < Ttl)
    val key = s"${guestToken.getOrElse("")}:$pid"
    recent.get(key).map(_._2).getOrElse {
      val p = load(pid, guestToken)
      recent += key -> (now, p)
      p.failed.foreach(_ => recent -= key)
      p
    }
  }

  private var subscribers: Set[js.Function0[Unit]] = Set.empty
  private var timer: Option[Int] = None
  private val onVisible: js.Function1[dom.Event, Unit] = _ => if (dom.document.visibilityState == "visible") subscribers.foreach(_())

  /** 30秒ごと・画面に戻ったときに refresh を呼ぶ. 戻り値で止める */
  def onSyncedRefresh(refresh: js.Function0[Unit]): () => Unit = {
    subscribers += refresh
    if (subscribers.size == 1) {
      timer = Some(dom.window.setInterval(() => onVisible(null), 30000))
      dom.document.addEventListener("visibilitychange", onVisible)
    }
    () => if (subscribers.contains(refresh)) {
      subscribers -= refresh
      if (subscribers.isEmpty) {
        timer.foreach(dom.window.clearInterval)
        timer = None
        dom.document.removeEventListener("visibilitychange", onVisible)
      }
    }
  }

  /** 本文（ProseMirror JSON）の中の同期ブロック（ID・中の文字・ノード）. 埋め込む範囲を選ぶ・表示するときに使う */
  def syncedBlocks(doc: js.Any): js.Array[js.Any] = {
    val out = js.Array[js.Any]()
    def children(n: js.Dynamic): List[js.Dynamic] =
      if (js.isUndefined(n.content) || n.content == null) Nil else n.content.asInstanceOf[js.Array[js.Dynamic]].toList
    def textOf(n: js.Dynamic): String =
      (if (js.isUndefined(n.text) || n.text == null) "" else n.text.toString) + children(n).map(textOf).map(" " + _).mkString
    def walk(n: js.Dynamic): Unit =
      if (!js.isUndefined(n) && n != null && js.typeOf(n) == "object") {
        val attrs = n.attrs
        if (n.`type`.asInstanceOf[Any] == "syncedBlock" && !js.isUndefined(attrs) && attrs != null && !js.isUndefined(attrs.id) && attrs.id != null && attrs.id.toString.nonEmpty)
          out.push(js.Dynamic.literal(id = attrs.id, text = textOf(n).replaceAll("\\s+", " ").trim, node = n))
        children(n).foreach(walk)
      }
    walk(doc.asInstanceOf[js.Dynamic])
    out
  }

  /** JS（エディタ）へ渡す形 */
  def guestToJs: js.Any = guestCtx.fold[js.Any](null) { g =>
    val m = new js.Map[String, js.Any]()
    g.pages.foreach((id, p) => m(id) = GuestApi.toJs(p.asJson))
    js.Dynamic.literal(token = g.token, pages = m)
  }

}
