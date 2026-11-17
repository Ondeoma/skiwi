<script lang="scala">
import io.circe.*
import org.scalajs.dom
import skiwi.web.{JsModule, Store}

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

// ページの本文のエディタ（旧 components/Editor.vue）. 中身は JS の editor モジュールの create が el に描く.
// 状態表示・書式のメニュー・ブロックのつまみ・表の操作も JS 側が描く.
// onHandle: 作ったエディタ（片付けたら None）/ onRole: サーバーが通知する権限（view・edit・none）/
// onComment: 本文を選んで「💬」/ onCommentClick: 本文のコメントの印を押した（印の ID）/ onPresence: このページを開いているほかの人
case class Props(pageId: String,
                 wid: String,
                 editable: Reactive[Boolean],
                 onHandle: Option[EditorHandle] => Unit = _ => (),
                 onRole: String => Unit = _ => (),
                 onComment: CommentDraft => Unit = _ => (),
                 onCommentClick: String => Unit = _ => (),
                 onPresence: List[Person] => Unit = _ => ())

case class State(el: ElementRef[dom.HTMLElement] = ElementRef(),
                 handle: RV[Option[EditorHandle]] = RV(None),
                 gone: RV[Boolean] = RV(false))

def initState() = State()

private def str(o: js.Dynamic, key: String): Option[String] = {
  val v = o.selectDynamic(key)
  if (js.isUndefined(v) || v == null) None else Some(v.toString)
}

def mounted(s: State, props: Props): Unit = {
  val loading = JsModule.load("editor")
  loading.failed.foreach(e => dom.console.error("editor:", e.getMessage))
  loading.foreach { m =>
    if (!s.gone.v) s.el.foreach { el =>
      val me = Store.me.v
      val opts = js.Dynamic.literal(
        pageId = props.pageId,
        wid = props.wid,
        editable = props.editable.v,
        userName = me.map(_.name).getOrElse("anonymous"),
        userAvatar = me.flatMap(_.avatarUrl).orNull,
        onRole = ((r: String) => props.onRole(r)): js.Function1[String, Unit],
        onComment = ((d: js.Dynamic) =>
          props.onComment(CommentDraft("text", d.from.asInstanceOf[js.UndefOr[Int]].toOption, d.to.asInstanceOf[js.UndefOr[Int]].toOption,
            str(d, "quote")))): js.Function1[js.Dynamic, Unit],
        onCommentClick = ((id: String) => props.onCommentClick(id)): js.Function1[String, Unit],
        onPresence = ((ps: js.Any) =>
          props.onPresence(EditorHandle.toJson(ps).as[List[Person]].getOrElse(Nil))): js.Function1[js.Any, Unit],
      )
      val h = new EditorHandle(m.create(el, opts))
      s.handle := Some(h)
      props.onHandle(Some(h))
    }
  }
  watch(props.editable, false)((_, b) => s.handle.v.foreach(_.setEditable(b)))
}

def unmounted(s: State, props: Props): Unit = {
  s.gone := true
  s.handle.v.foreach(_.destroy())
  s.handle := None
  props.onHandle(None)
}
</script>

<template>
  <div ref="s.el" class="page-editor"></div>
</template>
