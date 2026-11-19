package skiwi.web

import io.github.ondeoma.scalactive.components.ComponentManager.*
import skiwi.web.model.*

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** 時間のかかる処理（取り込み・書き出し）の進み具合. 画面の右下に出す. */
final case class Progress(label: String, done: Int, total: Int)

/**
 * 画面全体で共有する状態（ログイン中の自分・ワークスペース・ページの一覧）.
 * 以前の pinia の workspace ストア（frontend/src/stores/workspace.ts）に当たる.
 */
object Store {

  val me: RV[Option[Me]] = RV(None)
  val workspaces: RV[List[Workspace]] = RV(Nil)
  val currentId: RV[Option[String]] = RV(None)
  val pages: RV[List[Page]] = RV(Nil)

  /** サイドバーの表示: tree（ページツリー）/ recent / labels / tasks. labelFilter はラベルで絞り込み中のラベル */
  val sidePanel: RV[String] = RV("tree")
  val labelFilter: RV[Option[String]] = RV(None)

  /** お気に入りのページ（すべてのワークスペース. 登録順） */
  val favoritePages: RV[List[FavoritePage]] = RV(Nil)

  /** ページツリーで選んでいるページ（一括操作用. 選んだ順を保つ） */
  val selected: RV[List[String]] = RV(Nil)

  /** 複製の画面を開く対象（WorkspaceView に1つだけ置いたダイアログが表示する） */
  val duplicateTarget: RV[Option[Page]] = RV(None)

  /** 時間のかかる処理の進み具合 */
  val progress: RV[Option[Progress]] = RV(None)

  def isAdmin = me.mapC(_.exists(_.isAdmin))

  def showLabel(l: String): Unit = {
    labelFilter := Some(l)
    sidePanel := "labels"
  }

  def isFavoritePage(id: String): Boolean = favoritePages.v.exists(_.page.id == id)

  def loadFavoritePages(): Future[Unit] = Api.favoritePages().map(favoritePages := _)

  def toggleFavoritePage(id: String): Future[Unit] =
    Api.setPageFavorite(id, !isFavoritePage(id)).flatMap(_ => loadFavoritePages())

  def byId(id: String): Option[Page] = pages.v.find(_.id == id)

  /** id のページの祖先（近い順. includeSelf なら先頭にそのページ自身）. 見られない親（一覧にない）でたどるのをやめる */
  def ancestors(id: String, includeSelf: Boolean = false): List[Page] = {
    val map = pages.v.map(p => p.id -> p).toMap
    val self = map.get(id)
    var cur = if (includeSelf) self else self.flatMap(_.parentId).flatMap(map.get)
    val out = List.newBuilder[Page]
    var n = 0
    // 万一の循環でも止まるように、ページ数を上限にする
    while (cur.isDefined && n <= map.size) {
      out += cur.get
      n += 1
      cur = cur.get.parentId.flatMap(map.get)
    }
    out.result()
  }

  def current: Option[Workspace] = currentId.v.flatMap(id => workspaces.v.find(_.id == id))

  /** 開いているワークスペース（currentId と workspaces のどちらが変わっても追う） */
  val currentC: RV[Option[Workspace]] = RV(None)
  private def syncCurrent(): Unit = currentC := current
  currentId.addWatcher(_ => syncCurrent())
  workspaces.addWatcher(_ => syncCurrent())

  /** 親 → 子（並び順 position. 同じなら元の並び）. orphansToRoot: 一覧にない親の子をルート扱いにする */
  def groupByParent(items: List[Page], orphansToRoot: Boolean = true): Map[Option[String], List[Page]] = {
    val ids = items.map(_.id).toSet
    items.groupBy(p => p.parentId.filter(pid => !orphansToRoot || ids.contains(pid))).view.mapValues(_.sortBy(
      _.position
    )).toMap
  }

  /** ページの木. 閲覧権限のない親が欠けている場合、その子はルート扱いにする */
  def treeOf(items: List[Page]): List[TreeNode] = {
    val kids = groupByParent(items)
    def build(id: Option[String]): List[TreeNode] = kids.getOrElse(id, Nil).map(p => TreeNode(p, build(Some(p.id))))
    build(None)
  }

  def tree = pages.mapC(treeOf)

  def init(): Future[Unit] =
    for {
      m <- Api.me()
      _ = me := Some(m)
      _ = AppNames.set(m.appNames)
      _ = Observe.setUser(m.id)
      ws <- Api.workspaces()
    } yield {
      workspaces := ws
      loadFavoritePages()
      ()
    }

  def select(id: String): Future[Unit] = {
    currentId := Some(id)
    Api.pages(id).map(ps => if (currentId.v.contains(id)) pages := ps)
  }

  def reloadPages(): Future[Unit] = {
    if (favoritePages.v.nonEmpty) loadFavoritePages()
    currentId.v match {
      case Some(id) => Api.pages(id).map(ps => if (currentId.v.contains(id)) pages := ps)
      case None => Future.successful(())
    }
  }

  def reloadWorkspaces(): Future[Unit] = Api.workspaces().map(workspaces := _)

  def createWorkspace(name: String): Future[Workspace] =
    Api.createWorkspace(name).map { w =>
      workspaces := workspaces.v :+ w
      w
    }

  /** お気に入りの登録・解除. サーバーの並び（お気に入りが先頭、それぞれ名前順）で一覧を取り直す */
  def toggleFavorite(id: String): Future[Unit] =
    workspaces.v.find(_.id == id) match {
      case Some(w) =>
        workspaces := workspaces.v.map(x => if (x.id == id) x.copy(favorite = !w.favorite) else x)
        Api.setFavorite(id, !w.favorite).transformWith(r => reloadWorkspaces().flatMap(_ => Future.fromTry(r)))
      case None => Future.successful(())
    }

  def createPage(parentId: Option[String]): Future[Page] = currentId.v match {
    case Some(wid) => Api.createPage(wid, parentId).map { p => pages := pages.v :+ p; p }
    case None => Future.failed(new Exception("no workspace"))
  }

  def patchLocal(id: String, f: Page => Page): Unit = pages := pages.v.map(p => if (p.id == id) f(p) else p)

  def rename(id: String, title: String): Future[Unit] = {
    import io.circe.syntax.*
    patchLocal(id, _.copy(title = title))
    Api.updatePage(id, io.circe.Json.obj("title" -> title.asJson)).map(_ => ())
  }

  def setIcon(id: String, icon: Option[String]): Future[Unit] = {
    import io.circe.syntax.*
    patchLocal(id, _.copy(icon = icon))
    Api.updatePage(id, io.circe.Json.obj("icon" -> icon.asJson)).map(_ => ())
  }

  def remove(id: String): Future[Unit] = Api.deletePage(id).flatMap(_ => reloadPages())

  def renameWorkspace(id: String, name: String): Future[Unit] =
    Api.renameWorkspace(id, name).flatMap(_ => reloadWorkspaces())

  // ---------------------------------------------------------------- 選択（一括操作）

  def toggleSelected(id: String): Unit =
    selected := (if (selected.v.contains(id)) selected.v.filterNot(_ == id) else selected.v :+ id)

  def clearSelection(): Unit = selected := Nil

}
