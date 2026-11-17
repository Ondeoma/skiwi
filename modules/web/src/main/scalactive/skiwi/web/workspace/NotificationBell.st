<script lang="scala">
import org.scalajs.dom
import skiwi.shared.NoticeText
import skiwi.web.parts.PushToggle
import skiwi.web.{Nav, Ui}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 画面内の通知（ウォッチ中のページの更新・共有された）. 1分ごとに確認する.
case class State(unread: RV[Int] = RV(0),
                 items: RV[List[Notif]] = RV(Nil),
                 open: RV[Boolean] = RV(false),
                 decided: RV[Map[Long, String]] = RV(Map.empty),
                 root: ElementRef[dom.HTMLElement] = ElementRef(),
                 ls: Listeners = new Listeners)

def initState() = State()

def refresh(s: State): Unit =
  WorkspaceApi.notifications().foreach { r =>
    s.unread := r.unread
    s.items := r.items
  } // 一時的な失敗は次回

def mounted(s: State): Unit = {
  refresh(s)
  // 裏に回っているタブでは取りに行かない（表に戻ったときに visibilitychange で取り直す）
  s.ls.timer(60000)(if (dom.document.visibilityState == "visible") refresh(s))
  s.ls.on[dom.Event](dom.document, "visibilitychange")(_ => if (dom.document.visibilityState == "visible") refresh(s))
  s.ls.on[dom.Event](dom.window, WsCommand.Event)(e => if (WsCommand.name(e) == "notifications") toggle(s))
  // 外側をクリックしたら閉じる
  s.ls.on[dom.MouseEvent](dom.document, "mousedown") { e =>
    if (s.open.v && !s.root.get.exists(_.contains(e.target.asInstanceOf[dom.Node]))) s.open := false
  }
}

def unmounted(s: State): Unit = s.ls.clear()

def toggle(s: State): Unit = {
  s.open := !s.open.v
  if (s.open.v) refresh(s)
}

// 見出し・文・開く場所はプッシュ通知と同じ（skiwi.shared.NoticeText）
def heading(n: Notif): String = NoticeText.heading(n.kind, n.pageId.isDefined, n.pageIcon, n.pageTitle, n.workspaceName)

def text(n: Notif): String = NoticeText.text(n.kind, n.actors, n.role, n.excerpt, n.due)

def markRead(s: State, n: Notif): Future[Unit] =
  if (n.read) Future.successful(())
  else WorkspaceApi.markRead(Some(List(n.id))).recover { case _ => s.unread.v }.map { u =>
    s.unread := u
    s.items := s.items.v.map(x => if (x.id == n.id) x.copy(read = true) else x)
  }

def go(s: State, n: Notif): Unit = {
  s.open := false
  markRead(s, n).foreach(_ => NoticeText.link(n.kind, n.pageId, n.workspaceId, n.threadId).foreach(Nav.go))
}

// 権限の申請への返事（通知から直接）
def decide(s: State, n: Notif, action: String): Unit = n.requestId.foreach { rid =>
  WorkspaceApi.decide(rid, action).map { r =>
    val msg = r.status match {
      case "approved" => s"✅ ${if (r.role.contains("edit")) "編集" else "閲覧"}を許可しました"
      case "denied" => "却下しました"
      case _ => "処理済みです"
    }
    s.decided := s.decided.v + (n.id -> msg)
    markRead(s, n)
  }.recover { case e => s.decided := s.decided.v + (n.id -> s"失敗しました: ${e.getMessage}") }
}

def readAll(s: State): Unit =
  WorkspaceApi.markRead().foreach { u =>
    s.unread := u
    s.items := s.items.v.map(_.copy(read = true))
  }

def bellTitle(s: State): String = if (s.unread.v > 0) s"未読の通知 ${s.unread.v} 件" else "通知"

def roleLabel(n: Notif): String = NoticeText.roleLabel(n.role)
</script>

<template>
  <div ref="s.root" class="bell-wrap">
    <button class="bell" :title="bellTitle(s)" data-testid="bell" @click="toggle(s)">
      🔔<span class="lbl">通知</span><span v-if="s.unread.v > 0" class="count" data-testid="bell-count">{{ if (s.unread.v > 99) "99+" else s.unread.v.toString }}</span>
    </button>
    <div v-if="s.open.v" class="panel" role="dialog" aria-label="通知" data-testid="notifications">
      <header>
        <b>通知</b>
        <button v-if="s.unread.v > 0" class="link" @click="readAll(s)">すべて既読にする</button>
      </header>
      <p v-if="s.items.v.isEmpty" class="empty">通知はありません。ページの「🔕 ウォッチ」を押すと、更新がここに届きます。</p>
      <ul v-else>
        <li v-for="n in s.items.v" :key="n.id" :class='Map("unread" -> !n.read)' data-testid="notification" @click="go(s, n)">
          <div class="t">{{ heading(n) }}</div>
          <div class="d">{{ text(n) }}</div>
          <div v-if='n.kind == "access_request" && n.requestId.isDefined' class="req" @click="event.stopPropagation()">
            <span v-if="s.decided.v.contains(n.id)" class="m">{{ s.decided.v.getOrElse(n.id, "") }}</span>
            <template v-else>
              <button data-testid="notif-approve" @click='decide(s, n, "approve")'>{{ roleLabel(n) }}を許可</button>
              <button class="deny" data-testid="notif-deny" @click='decide(s, n, "deny")'>却下</button>
            </template>
          </div>
          <div class="m">{{ Ui.date(n.createdAt) }}<template v-if="n.pageId.isDefined && n.workspaceName.isDefined"> ・ {{ n.workspaceName.getOrElse("") }}</template></div>
        </li>
      </ul>
      <footer><PushToggle /></footer>
    </div>
  </div>
</template>

<style scoped>
.bell-wrap { position: relative; }
.bell { border: none; position: relative; border-radius: 6px; padding: 2px 7px; color: var(--muted); font-size: .9rem; display: inline-flex; align-items: center; gap: 3px; }
.bell:hover { background: var(--hover); color: var(--fg); }
.bell .lbl { font-size: .72rem; margin-left: 3px; }
.count { position: absolute; top: -4px; right: -6px; min-width: 16px; height: 16px; padding: 0 3px; border-radius: 8px; background: var(--danger); color: #fff; font-size: 10px; line-height: 16px; text-align: center; }
.panel { position: absolute; left: 0; top: calc(100% + 6px); z-index: 70; width: min(340px, calc(100vw - 24px)); max-height: 70vh; overflow: auto; background: var(--bg); border: 1px solid var(--line); border-radius: 10px; box-shadow: 0 12px 32px rgba(0,0,0,.25); }
header { display: flex; justify-content: space-between; align-items: center; padding: .5rem .7rem; border-bottom: 1px solid var(--line); position: sticky; top: 0; background: var(--bg); }
.link { border: none; color: var(--accent); font-size: .78rem; }
ul { list-style: none; margin: 0; padding: 4px; }
li { padding: .45rem .55rem; border-radius: 6px; cursor: pointer; border-left: 3px solid transparent; }
li:hover { background: var(--hover); }
li.unread { border-left-color: var(--accent); }
li.unread .t { font-weight: 700; }
.t { font-size: .88rem; }
.d { font-size: .8rem; }
.m { font-size: .72rem; color: var(--muted); }
.req { display: flex; gap: .4rem; align-items: center; margin: .3rem 0 .1rem; }
.req button { font-size: .75rem; padding: 1px 8px; }
.req .deny { color: var(--danger); }
.empty { padding: 1rem; font-size: .82rem; color: var(--muted); }
footer { padding: .45rem .7rem; border-top: 1px solid var(--line); }
</style>
