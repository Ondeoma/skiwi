package skiwi.web

import io.circe.syntax.*
import io.github.ondeoma.scalactive.components.ComponentManager.*
import io.github.ondeoma.scalactive.routes.Router
import org.scalajs.dom
import skiwi.web.model.*

import scala.concurrent.{Future, Promise}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** 画面の移動 */
object Nav {

  /** 画面を移動する（履歴に積む） */
  def go(path: String): Unit = Router.go(path)

  /** 画面を移動する（履歴を置き換える） */
  def replace(path: String): Unit = {
    dom.window.history.replaceState(js.Object(), "", path)
    Router.href := dom.window.location.href
  }

  /**
   * URL だけを書き換え、画面は描き直さない（クエリ文字列の変更など）.
   * RouterComponent はクエリ文字列が変わると画面を作り直すため、表示の状態を URL に残すだけのときはこれを使う.
   */
  def replaceQuiet(path: String): Unit = dom.window.history.replaceState(js.Object(), "", path)

  /** 今の URL のクエリ文字列の値 */
  def query(name: String): Option[String] = Option(new dom.URL(dom.window.location.href).searchParams.get(name))

  def pageHref(workspaceId: String, pageId: String): String = s"/w/$workspaceId/p/$pageId"

  /** 修飾キーなしの左クリックか（Ctrl/⌘クリックなどはブラウザに任せて新しいタブで開く） */
  def plainClick(e: dom.Event): Boolean = e match {
    case m: dom.MouseEvent => !m.defaultPrevented && m.button == 0 && !m.ctrlKey && !m.metaKey && !m.shiftKey && !m.altKey
    case _ => true
  }

  /**
   * 自前の <a :href> のクリックで画面を移動する（RouterLink の a にはスコープ付き CSS が届かないため）.
   * 例: <a :href="to" @click="Nav.link(event, to)">
   */
  def link(e: dom.Event, to: String): Unit =
    if (plainClick(e)) {
      e.preventDefault()
      go(to)
    }

}

/**
 * Scalactive の部品を JS（エディタのノードビューなど）から描画するための登録.
 * mount(el, params) は描画して、片付ける関数を返す.
 */
object Embeds {

  type Mount = (dom.Element, js.Dictionary[js.Any]) => (() => Unit)

  private var registry: Map[String, Mount] = Map.empty

  def register(name: String)(m: Mount): Unit = registry += name -> m

  def open(name: String, el: dom.Element, params: js.Dictionary[js.Any]): () => Unit = registry.get(name) match {
    case Some(m) => m(el, params)
    case None =>
      dom.console.error(s"mountView: unknown view $name")
      () => ()
  }

  /** 部品を el に描画し、片付ける関数を返す */
  def render(el: dom.Element)(cmp: => GenComponentF): () => Unit =
    mount(el, cmp) match {
      case Right(h) => () => h.unmount()
      case Left(e) =>
        dom.console.error(s"mount: $e")
        () => ()
    }

}

/**
 * JS（frontend/src/ui/host.ts の SkiwiHost）へ、画面の移動・ダイアログ・ページの一覧などを渡す（window.__skiwiHost）.
 * エディタ（Tiptap）などの JS のままの部分は、これを通して Scalactive の画面とやり取りする.
 */
object Host {

  /** ページ選択モーダル（parts/PagePicker.st）の要求 */
  val pagePicker: RV[Option[Promise[Option[Page]]]] = RV(None)

  /** ページを選ぶ（取り消しは None）. 開いていたものは取り消し扱いで閉じる */
  def pickPage(): Future[Option[Page]] = {
    pagePicker.v.foreach(_.trySuccess(None))
    val p = Promise[Option[Page]]()
    pagePicker := Some(p)
    p.future.andThen(_ => if (pagePicker.v.contains(p)) pagePicker := None)
  }

  /**
   * エディタのデータベースブロック: 既存のデータベースを選ぶか、pageId のページの子ページとして新しく作る.
   * 戻り値はデータベースのページの ID（取り消しは None）
   */
  def pickDatabase(pageId: String): Future[Option[String]] = {
    val New = "__new__"
    val dbs = Store.pages.v.filter(_.isDatabase)
    Dialog.choose("ページの中に表示するデータベース",
      Choice("＋ 新しいデータベースを作る", New, Some("このページの子ページとして作ります")) :: dbs.map(p => Choice(s"📊 ${p.displayTitle}", p.id)))
      .flatMap {
        case Some(New) =>
          Store.byId(pageId) match {
            case None => Future.successful(None)
            case Some(here) =>
              Dialog.prompt("データベースの名前", "新しいデータベース", okLabel = "作成").map(_.map(_.trim).filter(_.nonEmpty)).flatMap {
                case None => Future.successful(None)
                case Some(name) =>
                  (for {
                    p <- Api.createPage(here.workspaceId, Some(here.id), name)
                    _ <- skiwi.web.database.DbApi.createDefault(p.id)
                    _ <- Store.reloadPages()
                  } yield Some(p.id)).recoverWith { case e => Dialog.alertError("作れませんでした", e).map(_ => None) }
              }
          }
        case other => Future.successful(other)
      }
  }

  /** 画面を移動します（Router.go） */
  def navigate(path: String): Unit = Nav.go(path)

  /** 画面内の確認ダイアログ */
  def confirm(message: String, okLabel: String = "OK", danger: Boolean = false): Future[Boolean] =
    Dialog.confirm(message, okLabel, danger)

  private def toJs(j: io.circe.Json): js.Any = js.JSON.parse(j.noSpaces)

  private def str(o: js.Dynamic, key: String, default: String): String =
    if (js.isUndefined(o) || o == null) default
    else o.selectDynamic(key).asInstanceOf[js.UndefOr[String]].filter(_ != null).getOrElse(default)

  private def bool(o: js.Dynamic, key: String): Boolean =
    !js.isUndefined(o) && o != null && o.selectDynamic(key).asInstanceOf[js.UndefOr[Boolean]].getOrElse(false)

  def exposeToJs(): Unit = {
    val h = js.Dynamic.literal(
      navigate = (path: String) => Nav.go(path),
      replace = (path: String) => Nav.replace(path),
      confirm = (message: String, opts: js.Dynamic) =>
        Dialog.confirm(message, str(opts, "okLabel", "OK"), bool(opts, "danger")).toJSPromise,
      prompt = (message: String, value: String, opts: js.Dynamic) =>
        Dialog.prompt(message, Option(value).getOrElse(""), str(opts, "okLabel", "OK"), str(opts, "placeholder", ""), bool(opts, "multiline"))
          .map(_.orNull).toJSPromise,
      choose = (message: String, options: js.Array[js.Dynamic]) =>
        Dialog.choose(message, options.toList.map(o => Choice(str(o, "label", ""), str(o, "value", ""), Option(str(o, "detail", null)))))
          .map(_.orNull).toJSPromise,
      alert = (message: String) => Dialog.alert(message).toJSPromise,
      me = () => Store.me.v.fold[js.Any](null)(m => toJs(m.asJson)),
      workspaces = () => toJs(Store.workspaces.v.asJson),
      pages = () => toJs(Store.pages.v.asJson),
      currentWorkspaceId = () => Store.currentId.v.orNull,
      reloadPages = () => Store.reloadPages().toJSPromise,
      onPagesChange = (f: js.Function0[Unit]) => {
        val w = Store.pages.addWatcher(_ => f())
        (() => w.abort()): js.Function0[Unit]
      },
      mountView = (name: String, el: dom.Element, params: js.Dictionary[js.Any]) =>
        (Embeds.open(name, el, Option(params).getOrElse(js.Dictionary())): js.Function0[Unit]),
      pickDatabase = (pageId: String) => pickDatabase(pageId).map(_.orNull).toJSPromise,
      pickPage = () =>
        pickPage().map(_.fold[js.Any](null)(p => js.Dynamic.literal(id = p.id, workspaceId = p.workspaceId, title = p.title))).toJSPromise,
      // 外部サービス（skea・skawau）との連携の状態と項目（workspace.Integrations. エディタの装飾が使う）
      integration = (name: String) => workspace.Integrations.jsApi(name),
    )
    dom.window.asInstanceOf[js.Dynamic].__skiwiHost = h
  }

}
