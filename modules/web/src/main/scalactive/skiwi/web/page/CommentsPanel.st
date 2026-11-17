<script lang="scala">
import org.scalajs.dom
import skiwi.web.{Dialog, Ui}
import skiwi.web.parts.Avatar

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

// コメントの一覧と投稿. draft は本文の選択範囲・ホワイトボードへの新しいコメント.
// editor: 本文のエディタ（印を付ける・外す. まだなければ None）/ focus: 開いたときに目立たせるスレッド（コメントの ID・印の anchor）
case class Props(pageId: String,
                 comments: Reactive[List[PageComment]],
                 canEdit: Reactive[Boolean],
                 editor: () => Option[EditorHandle],
                 draft: Reactive[Option[CommentDraft]],
                 focus: Reactive[Option[String]],
                 onClose: Unit => Unit,
                 onReload: Unit => Unit,
                 onClearDraft: Unit => Unit)

case class Thread(root: PageComment, replies: List[PageComment])

case class State(tab: RV[String] = RV("open"),
                 busy: RV[Boolean] = RV(false),
                 newBody: RV[String] = RV(""),
                 replies: scala.collection.mutable.Map[String, RV[String]] = scala.collection.mutable.Map.empty,
                 editing: RV[Option[String]] = RV(None),
                 editBody: RV[String] = RV(""),
                 listEl: ElementRef[dom.HTMLElement] = ElementRef())

def initState() = State()

def threadsOf(cs: List[PageComment]): List[Thread] =
  cs.filter(_.threadId.isEmpty)
    .map(root => Thread(root, cs.filter(_.threadId.contains(root.id))))
    // 先頭が削除され返信もないスレッドは出さない
    .filterNot(t => t.root.deleted && t.replies.forall(_.deleted))
    .reverse

def threads(props: Props) = props.comments.mapC(threadsOf)

def shown(s: State, props: Props) = computed {
  val open = s.tab.v == "open"
  threadsOf(props.comments.v).filter(t => open == t.root.resolvedAt.isEmpty)
}

def openCount(props: Props) = props.comments.mapC(cs => threadsOf(cs).count(_.root.resolvedAt.isEmpty))

def resolvedCount(props: Props) = props.comments.mapC(cs => threadsOf(cs).count(_.root.resolvedAt.isDefined))

def replyRv(s: State, id: String): RV[String] = s.replies.getOrElseUpdate(id, RV(""))

def guard(s: State)(f: => Future[Any]): Unit = Dialog.guarded(Some(s.busy))(f)

def newAnchorId(): String = {
  val c = js.Dynamic.global.crypto
  val id = if (!js.isUndefined(c.randomUUID)) c.randomUUID().asInstanceOf[String] else s"${js.Date.now()}${js.Math.random()}"
  id.replace("-", "").take(20)
}

def post(s: State, props: Props): Unit = {
  val body = s.newBody.v.trim
  if (body.nonEmpty) {
    val d = props.draft.v
    guard(s) {
      var anchor: Option[String] = None
      var markRange: Option[(Int, Int, String)] = None
      d match {
        case Some(CommentDraft("text", Some(from), Some(to), _, _)) if props.canEdit.v && props.editor().isDefined =>
          // 編集できる人は本文に印を付ける（閲覧のみの人は引用だけ残す）
          val id = newAnchorId()
          anchor = Some(s"text:$id")
          markRange = Some((from, to, id))
        case Some(CommentDraft("wb", _, _, _, Some(board))) => anchor = Some(s"wb:$board")
        case _ => ()
      }
      PageApi.createComment(props.pageId, body, anchor = anchor, quote = d.flatMap(_.quote)).map { _ =>
        markRange.foreach { (from, to, id) => props.editor().filterNot(_.isDestroyed).foreach(_.setCommentMark(from, to, id)) }
        s.newBody := ""
        s.tab := "open"
        props.onClearDraft(())
        props.onReload(())
      }
    }
  }
}

def reply(s: State, props: Props, t: Thread): Unit = {
  val r = replyRv(s, t.root.id)
  val body = r.v.trim
  if (body.nonEmpty) guard(s) {
    PageApi.createComment(props.pageId, body, threadId = Some(t.root.id)).map { _ =>
      r := ""
      props.onReload(())
    }
  }
}

def setResolved(s: State, props: Props, t: Thread, v: Boolean): Unit =
  guard(s)(PageApi.resolveComment(t.root.id, v).map(_ => props.onReload(())))

def startEdit(s: State, c: PageComment): Unit = {
  s.editing := Some(c.id)
  s.editBody := c.body
}

def saveEdit(s: State, props: Props, c: PageComment): Unit = guard(s) {
  val b = s.editBody.v.trim
  if (b.isEmpty) Future.successful(())
  else PageApi.editComment(c.id, b).map { _ =>
    s.editing := None
    props.onReload(())
  }
}

def remove(s: State, props: Props, c: PageComment, t: Thread): Unit = {
  val isRoot = c.id == t.root.id
  Dialog.confirm(if (isRoot) "このコメントを削除しますか？（返信は残ります）" else "この返信を削除しますか？", okLabel = "削除", danger = true).foreach { ok =>
    if (ok) guard(s) {
      PageApi.removeComment(c.id).map { _ =>
        // スレッドごとなくなる場合は本文の印も外す
        t.root.anchor.filter(_.startsWith("text:")).foreach { a =>
          if (isRoot && t.replies.forall(_.deleted) && props.canEdit.v)
            props.editor().filterNot(_.isDestroyed).foreach(_.unsetCommentMark(a.drop(5)))
        }
        props.onReload(())
      }
    }
  }
}

def esc(v: String): String = js.Dynamic.global.CSS.escape(v).asInstanceOf[String]

def markSelector(a: String): String =
  if (a.startsWith("text:")) s".ProseMirror [data-comment=\"${esc(a.drop(5))}\"]" else s".ProseMirror [data-whiteboard-id=\"${esc(a.drop(3))}\"]"

/** スレッドの対象（本文の範囲・ホワイトボード）へ移動して目立たせる */
def reveal(t: Thread): Unit = t.root.anchor.foreach { a =>
  Option(dom.document.querySelector(markSelector(a))).foreach { el =>
    el.asInstanceOf[js.Dynamic].scrollIntoView(js.Dynamic.literal(block = "center", behavior = "smooth"))
    val marks = if (a.startsWith("text:")) dom.document.querySelectorAll(markSelector(a)).toList else List(el)
    marks.foreach(_.classList.add("flash"))
    dom.window.setTimeout(() => marks.foreach(_.classList.remove("flash")), 1200)
  }
}

def anchorMissing(t: Thread): Boolean =
  t.root.anchor.exists(a => a.startsWith("text:") && dom.document.querySelector(markSelector(a)) == null)

def mounted(s: State, props: Props): Unit = {
  // 通知などから開いたスレッドへスクロール
  val key = computed { (props.focus.v, props.comments.v.size) }
  watch(key, true) { (_, k) =>
    k._1.foreach { id =>
      threadsOf(props.comments.v).find(x => x.root.id == id || x.root.anchor.contains(id) || x.replies.exists(_.id == id)).foreach { t =>
        s.tab := (if (t.root.resolvedAt.isDefined) "resolved" else "open")
        dom.window.setTimeout(() => {
          s.listEl.foreach { list =>
            Option(list.querySelector(s"[data-thread=\"${t.root.id}\"]")).foreach { el =>
              el.asInstanceOf[js.Dynamic].scrollIntoView(js.Dynamic.literal(block = "center"))
              el.classList.add("focus")
              dom.window.setTimeout(() => el.classList.remove("focus"), 1500)
            }
          }
          reveal(t)
        }, 0)
      }
    }
  }
}

def tabCls(s: State, t: String) = s.tab.mapC(x => if (x == t) "on" else "")

def draftLabel(props: Props) = props.draft.mapC {
  case Some(d) if d.kind == "wb" => "🧩 ホワイトボードへのコメント"
  case Some(d) => s"「${d.quote.getOrElse("")}」へのコメント"
  case None => ""
}

def hasDraft(props: Props) = props.draft.mapC(_.exists(_.kind != "page"))

def autofocus(props: Props) = props.draft.mapC(_.isDefined)

def emptyText(s: State) =
  s.tab.mapC(t => if (t == "open") "未解決のコメントはありません。本文を選択して「💬」からもコメントできます。" else "解決済みのコメントはありません。")
</script>

<template>
  <aside class="comments" aria-label="コメント" data-testid="comments">
    <header>
      <strong>💬 コメント</strong>
      <div class="tabs">
        <button :class='tabCls(s, "open")' @click='s.tab := "open"'>未解決（{{ openCount(props) }}）</button>
        <button :class='tabCls(s, "resolved")' @click='s.tab := "resolved"'>解決済み（{{ resolvedCount(props) }}）</button>
      </div>
      <button class="x" title="閉じる" @click="props.onClose(())">✕</button>
    </header>

    <div class="compose">
      <div v-if="hasDraft(props)" class="quote">
        <span>{{ draftLabel(props) }}</span>
        <button class="link" @click="props.onClearDraft(())">ページ全体へのコメントにする</button>
      </div>
      <MentionInput :model="s.newBody" :autofocus="autofocus(props)" :onSubmit="_ => post(s, props)" />
      <div class="row">
        <small class="muted">⌘/Ctrl+Enter で送信</small>
        <button class="primary" :disabled="s.busy.v || s.newBody.v.trim.isEmpty" data-testid="comment-post" @click="post(s, props)">コメントする</button>
      </div>
    </div>

    <div ref="s.listEl" class="list">
      <p v-if="shown(s, props).v.isEmpty" class="muted empty">{{ emptyText(s) }}</p>
      <article v-for="t in shown(s, props)" :key="t.root.id" class="thread" :data-thread="t.root.id" data-testid="thread">
        <button v-if="t.root.anchor.isDefined" class="anchor" :class='if (anchorMissing(t)) "missing" else ""' @click="reveal(t)">
          <template v-if='t.root.anchor.exists(_.startsWith("wb:"))'>🧩 ホワイトボード</template>
          <template v-else>「{{ t.root.quote.getOrElse("") }}」<span v-if="anchorMissing(t)">（本文から削除された箇所）</span></template>
        </button>
        <div v-else-if="t.root.quote.isDefined" class="anchor static">「{{ t.root.quote.getOrElse("") }}」</div>
        <div v-for="c in t.root :: t.replies" :key="c.id" class="c" :class='if (c.id != t.root.id) "reply" else ""'>
          <div class="meta">
            <Avatar :name='c.authorName.getOrElse("")' :url="c.authorAvatarUrl" :size="20" />
            <b>{{ c.authorName.getOrElse("（不明）") }}</b>
            <span class="muted">{{ Ui.date(c.createdAt) }}{{ if (c.updatedAt != c.createdAt && !c.deleted) "（編集済み）" else "" }}</span>
            <span class="acts">
              <button v-if="c.canEdit && !s.editing.v.contains(c.id)" class="link" @click="startEdit(s, c)">編集</button>
              <button v-if="c.canDelete" class="link danger" @click="remove(s, props, c, t)">削除</button>
            </span>
          </div>
          <p v-if="c.deleted" class="muted deleted">（削除されたコメント）</p>
          <template v-else-if="s.editing.v.contains(c.id)">
            <MentionInput :model="s.editBody" :onSubmit="_ => saveEdit(s, props, c)" :onCancel="_ => s.editing := None" />
            <div class="row"><button class="link" @click="s.editing := None">キャンセル</button><button class="primary sm" :disabled="s.busy" @click="saveEdit(s, props, c)">保存</button></div>
          </template>
          <CommentBody v-else :body="c.body" />
        </div>
        <div v-if="t.root.resolvedAt.isDefined" class="resolved muted">✓ {{ t.root.resolvedBy.getOrElse("") }} が解決（{{ t.root.resolvedAt.map(Ui.date).getOrElse("") }}）</div>
        <div class="thread-foot">
          <MentionInput v-if="t.root.resolvedAt.isEmpty" :model="replyRv(s, t.root.id)" placeholder="返信…" :onSubmit="_ => reply(s, props, t)" />
          <div class="row">
            <button v-if="t.root.canDelete || props.canEdit.v" class="link" data-testid="thread-resolve" @click="setResolved(s, props, t, t.root.resolvedAt.isEmpty)">
              {{ if (t.root.resolvedAt.isDefined) "↺ 未解決に戻す" else "✓ 解決" }}
            </button>
            <button v-if="t.root.resolvedAt.isEmpty" class="primary sm" :disabled="s.busy.v || replyRv(s, t.root.id).v.trim.isEmpty" @click="reply(s, props, t)">返信</button>
          </div>
        </div>
      </article>
    </div>
  </aside>
</template>

<style scoped>
.comments { position: fixed; top: 44px; right: 0; bottom: 0; width: min(380px, 100vw); z-index: 45; background: var(--bg); border-left: 1px solid var(--line); box-shadow: -10px 0 30px rgba(0,0,0,.15); display: flex; flex-direction: column; }
header { display: flex; align-items: center; gap: .5rem; padding: .6rem .8rem; border-bottom: 1px solid var(--line); }
.tabs { display: flex; gap: 2px; flex: 1; justify-content: flex-end; }
.tabs button { font-size: .72rem; padding: 1px 6px; color: var(--muted); }
.tabs button.on { color: var(--fg); border-color: var(--accent); background: var(--hover); }
.x { border: none; color: var(--muted); }
.compose { padding: .6rem .8rem; border-bottom: 1px solid var(--line); }
.quote { display: flex; flex-direction: column; gap: 2px; font-size: .78rem; background: rgba(234,179,8,.12); border-left: 3px solid rgba(234,179,8,.7); padding: .3rem .5rem; border-radius: 4px; margin-bottom: .4rem; }
.row { display: flex; justify-content: space-between; align-items: center; gap: .4rem; margin-top: .3rem; }
.primary { font-size: .82rem; }
.primary:disabled { opacity: .5; }
.primary.sm { font-size: .75rem; padding: 1px 10px; }
.link { border: none; background: none; color: var(--accent); padding: 0; font-size: .75rem; }
.link.danger:hover { color: var(--danger); }
.list { flex: 1; overflow: auto; padding: .4rem .8rem 2rem; }
.empty { font-size: .82rem; padding: 1rem 0; }
.thread { border: 1px solid var(--line); border-radius: 9px; padding: .5rem .6rem; margin: .5rem 0; transition: box-shadow .3s; }
.thread.focus { box-shadow: 0 0 0 2px var(--accent); }
.anchor { display: block; width: 100%; text-align: left; border: none; border-left: 3px solid rgba(234,179,8,.7); background: rgba(234,179,8,.10); font-size: .78rem; padding: .2rem .45rem; border-radius: 3px; margin-bottom: .35rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.anchor.missing { opacity: .6; border-left-color: var(--line); }
.anchor.static { cursor: default; }
.c { padding: .25rem 0; }
.c.reply { margin-left: .8rem; padding-left: .5rem; border-left: 2px solid var(--line); }
.meta { display: flex; align-items: center; gap: .4rem; font-size: .78rem; }
.meta .acts { margin-left: auto; display: flex; gap: .4rem; }
.deleted { font-size: .8rem; font-style: italic; }
.resolved { font-size: .75rem; margin-top: .3rem; }
.thread-foot { margin-top: .4rem; }
</style>
