<script lang="scala">
import skiwi.shared.Titles
import io.circe.*
import io.circe.syntax.*
import skiwi.web.{Choice, Dialog, Host, Nav, Store}
import skiwi.web.model.*
import skiwi.web.parts.HelpLink

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// ページツリーで選んだページの一括操作（サイドメニューの下に出る）
case class Props(wid: String)

case class State(busy: RV[Boolean] = RV(false))

def initState() = State()

def pages: List[Page] = Store.selected.v.flatMap(Store.byId)

def editable: List[Page] = pages.filter(_.canEdit)

def titles(n: Int = 3): String = {
  val ps = pages
  ps.take(n).map(p => s"「${p.displayTitle}」").mkString + (if (ps.size > n) s" ほか${ps.size - n}件" else "")
}

/** 開いているページ（またはその祖先）が移動・削除された */
def openAffected(ids: List[String]): Boolean =
  WsRoute.currentPid.exists(pid => Store.ancestors(pid, includeSelf = true).exists(p => ids.contains(p.id)))

def reason(e: String): String =
  if (e == "権限がありません") "編集権限がありません"
  else e

/** action: 一括操作. moveTo: 移動先のワークスペース（別のワークスペースへ移すとき） */
def run(s: State, p: Props, action: String, fields: JsonObject, label: String, moveTo: Option[String] = None): Future[Unit] = {
  s.busy := true
  val ids = Store.selected.v
  val affected = (action == "move" || action == "delete") && openAffected(ids)
  val pid = WsRoute.currentPid
  WorkspaceApi.bulk(ids, fields.add("action", action.asJson)).flatMap { r =>
    Store.reloadPages().flatMap { _ =>
      val after =
        if (r.failed.nonEmpty) {
          val lines = r.failed.take(10).map(f => s"・${f.title.fold("（見つからないページ）")(t => s"「${Titles.display(t)}」")}: ${reason(f.error)}").mkString("\n")
          Dialog.alert(s"$label: ${r.done.size + r.skipped.size} 件を処理しました。次の ${r.failed.size} 件はできませんでした。\n\n$lines")
            .map(_ => Store.selected := r.failed.map(_.id).filter(id => Store.byId(id).isDefined))
        } else {
          // ラベル・所有者・状態のあとは、続けて操作できるよう選択を残す
          if (action == "move" || action == "delete") Store.clearSelection()
          Future.successful(())
        }
      after.map { _ =>
        if (affected && action == "delete") Nav.go(s"/w/${p.wid}")
        else if (affected && action == "move") moveTo.filter(_ != p.wid).foreach(w => pid.foreach(id => Nav.go(s"/w/$w/p/$id")))
      }
    }
  }.recoverWith { case e => Dialog.alert(s"${label}できませんでした: ${e.getMessage}") }
    .andThen(_ => s.busy := false)
}

def move(s: State, p: Props): Unit = {
  val others = Store.workspaces.v.filter(w => w.role == "edit" && w.id != p.wid)
    .map(w => Choice(s"↪ 「${w.name}」の直下へ", s"ws:${w.id}", Some("別のワークスペースへ移す（移動先の権限が適用されます）")))
  Dialog.choose(s"${titles()} をどこへ移動しますか？（子ページも一緒に移動します）",
    List(Choice("📄 ページの下へ…", "page", Some("ページを選んで、その子ページにする")), Choice("🗂 このワークスペースの直下へ", "root")) ++ others
  ).foreach {
    case None => ()
    case Some("root") => run(s, p, "move", JsonObject("parentId" -> Json.Null), "移動")
    case Some(w) if w.startsWith("ws:") =>
      val to = w.drop(3)
      run(s, p, "move", JsonObject("parentId" -> Json.Null, "workspaceId" -> to.asJson), "移動", Some(to))
    case Some(_) =>
      Host.pickPage().foreach(_.foreach { t =>
        val ws = Option.when(t.workspaceId != p.wid)(t.workspaceId)
        run(s, p, "move", JsonObject.fromIterable(List("parentId" -> t.id.asJson) ++ ws.map(w => "workspaceId" -> w.asJson)), "移動", ws)
      })
  }
}

def remove(s: State, p: Props): Unit =
  Dialog.confirm(s"${titles()}（${pages.size}件）とその子ページをゴミ箱に移しますか？\nゴミ箱から元に戻せます。", okLabel = "ゴミ箱に移す", danger = true)
    .foreach(ok => if (ok) run(s, p, "delete", JsonObject.empty, "削除"))

def labels(s: State, p: Props): Unit =
  Dialog.choose(s"${pages.size}件のページのラベル", List(Choice("🏷 ラベルを付ける", "add"), Choice("✂ ラベルを外す", "remove"))).foreach(_.foreach { mode =>
    val existing = pages.flatMap(_.labelList).distinct
    val msg =
      if (mode == "add") "付けるラベル（「,」区切りで複数可）"
      else s"外すラベル（「,」区切りで複数可）${if (existing.nonEmpty) s"\n付いているラベル: ${existing.mkString(", ")}" else ""}"
    Dialog.prompt(msg, "", okLabel = if (mode == "add") "付ける" else "外す").foreach { v =>
      val ls = v.toList.flatMap(_.split("[,、，]").toList.map(_.trim).filter(_.nonEmpty))
      if (ls.nonEmpty) run(s, p, "labels", JsonObject((if (mode == "add") "addLabels" else "removeLabels") -> ls.asJson), "ラベルの変更")
    }
  })

def owner(s: State, p: Props): Unit =
  Dialog.pickUser("所有者（名前・メールで検索。空欄で外す）", "所有者を選んでください", allowEmpty = true).foreach(_.foreach { u =>
    run(s, p, "owner", JsonObject("ownerId" -> u.map(_.id).asJson), "所有者の変更")
  })

def status(s: State, p: Props): Unit =
  Dialog.choose(s"${pages.size}件のページの状態", Page.Statuses.map(k => Choice(Page.StatusLabel(k), k)) :+ Choice("（状態なし）", "")).foreach(_.foreach { v =>
    run(s, p, "status", JsonObject("status" -> Option(v).filter(_.nonEmpty).asJson), "状態の変更")
  })
</script>

<template>
  <div v-if="Store.selected.v.nonEmpty" class="bulk" data-testid="bulk-bar">
    <div class="head">
      <span><b data-testid="bulk-count">{{ Store.selected.v.size }}</b> ページを選択中</span>
      <span><HelpLink to="pages#まとめて操作する" /> <button class="link" data-testid="bulk-clear" @click="Store.clearSelection()">選択を解除</button></span>
    </div>
    <p v-if="editable.size < pages.size" class="note">編集できないページ {{ pages.size - editable.size }} 件は変更されません</p>
    <div class="acts">
      <button :disabled="s.busy.v" data-testid="bulk-move" @click="move(s, props)">↪ 移動</button>
      <button :disabled="s.busy.v" data-testid="bulk-labels" @click="labels(s, props)">🏷 ラベル</button>
      <button :disabled="s.busy.v" data-testid="bulk-owner" @click="owner(s, props)">👤 所有者</button>
      <button :disabled="s.busy.v" data-testid="bulk-status" @click="status(s, props)">📌 状態</button>
      <button :disabled="s.busy.v" class="danger" data-testid="bulk-delete" @click="remove(s, props)">🗑 削除</button>
    </div>
    <p class="note">⌘/Ctrl＋クリックで選択を追加・解除、Shift＋クリックで範囲を選べます</p>
  </div>
</template>

<style scoped>
.bulk { border-top: 1px solid var(--line); padding: .5rem .6rem; background: var(--bg); font-size: .82rem; display: flex; flex-direction: column; gap: .35rem; }
.head { display: flex; justify-content: space-between; align-items: center; }
.link { border: none; color: var(--accent); padding: 0; font-size: .78rem; }
.acts { display: flex; flex-wrap: wrap; gap: 4px; }
.acts button { font-size: .78rem; padding: 2px 8px; }
.acts .danger:hover { color: var(--danger); border-color: var(--danger); }
.note { margin: 0; font-size: .7rem; }
</style>
