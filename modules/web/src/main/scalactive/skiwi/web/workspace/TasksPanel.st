<script lang="scala">
import skiwi.shared.Titles
import skiwi.web.Nav
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

// 自分に割り当てられたToDo（すべてのワークスペース）. ToDo の項目で @メンションされると担当になる.
case class Props(wid: String)

case class State(showDone: RV[Boolean] = RV(false), tasks: RV[Option[List[TaskItem]]] = RV(None))

def initState() = State()

final case class TaskGroup(key: String, label: String, items: List[TaskItem])

def load(s: State): Unit = {
  s.tasks := None
  WorkspaceApi.myTasks(s.showDone.v).map(Some(_)).recover { case _ => Some(Nil) }.foreach(s.tasks := _)
}

def mounted(s: State): Unit = {
  load(s)
  watch(s.showDone)((_, _) => load(s))
}

/** 今日から offsetDays 日後（YYYY-MM-DD. editor/DueDate.ts の today と同じ） */
def today(offsetDays: Int = 0): String = {
  val d = new js.Date()
  d.setDate(d.getDate() + offsetDays)
  f"${d.getFullYear().toInt}%04d-${d.getMonth().toInt + 1}%02d-${d.getDate().toInt}%02d"
}

def groups(s: State): List[TaskGroup] = {
  val t0 = today()
  val t1 = today(1)
  val keyOf = (t: TaskItem) =>
    if (t.checked) "done" else t.due match {
      case None => "none"
      case Some(d) if d < t0 => "overdue"
      case Some(d) if d <= t1 => "today"
      case _ => "later"
    }
  val ts = s.tasks.v.getOrElse(Nil)
  List("overdue" -> "⚠ 期限切れ", "today" -> "今日・明日", "later" -> "これから", "none" -> "期限なし", "done" -> "完了")
    .map { case (k, l) => TaskGroup(k, l, ts.filter(t => keyOf(t) == k)) }
    .filter(_.items.nonEmpty)
}

def fmt(d: String): String = d.split('-').toList match {
  case _ :: m :: dd :: _ => s"${m.toInt}/${dd.toInt}"
  case _ => d
}

def href(t: TaskItem): String = Nav.pageHref(t.workspaceId, t.pageId)
</script>

<template>
  <div class="tasks" data-testid="tasks-panel">
    <label class="opt"><input v-model="s.showDone" type="checkbox"> 完了も表示</label>
    <p v-if="s.tasks.v.isEmpty" class="muted">読み込み中…</p>
    <p v-else-if="s.tasks.v.exists(_.isEmpty)" class="muted">担当のToDoはありません。ToDoリストの項目で @名前 を書くと、その人の担当になります（「📅」で期限）。</p>
    <section v-for="g in groups(s)" :key="g.key">
      <p :class='"head " + g.key'>{{ g.label }}（{{ g.items.size }}）</p>
      <a v-for="t in g.items" :key='s"${t.pageId}:${t.seq}"' :href="href(t)"
         :class='Map("item" -> true, "checked" -> t.checked, "router-link-active" -> WsRoute.isActive(href(t)))' data-testid="task-item"
         @click="Nav.link(event, href(t))">
        <div class="t">{{ if (t.checked) "☑" else "☐" }} {{ if (t.text.isEmpty) "（内容なし）" else t.text }}</div>
        <div class="m">
          <span v-if="t.due.isDefined" :class='"due " + g.key'>📅 {{ fmt(t.due.getOrElse("")) }}</span>
          {{ t.pageIcon.getOrElse("📄") }} {{ Titles.display(t.pageTitle) }}<template v-if="t.workspaceId != props.wid"> ・ 🗂 {{ t.workspaceName }}</template>
        </div>
      </a>
    </section>
  </div>
</template>

<style scoped>
.tasks { padding: 0 .4rem; }
.opt { display: flex; align-items: center; gap: .3rem; font-size: .75rem; color: var(--muted); margin: 0 .3rem .3rem; }
.head { font-size: .75rem; font-weight: 600; color: var(--muted); margin: .6rem .3rem .2rem; }
.head.overdue { color: var(--danger); }
.item { display: block; padding: .35rem .5rem; border-radius: 6px; color: inherit; text-decoration: none; }
.item:hover, .item.router-link-active { background: var(--hover); }
.item.checked .t { text-decoration: line-through; color: var(--muted); }
.t { font-size: .88rem; overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.m { font-size: .72rem; color: var(--muted); }
.due { margin-right: .3rem; }
.due.overdue { color: var(--danger); font-weight: 600; }
.due.today { color: #d97706; font-weight: 600; }
.muted { font-size: .8rem; padding: 0 .4rem; }
</style>
