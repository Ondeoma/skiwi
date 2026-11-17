<script lang="scala">
import org.scalajs.dom
import skiwi.web.JsModule

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

// 読み取り専用の本文（JS の editor.renderStatic で描く）. content: Tiptap の JSON（JS の値）
case class Props(content: js.Any, testid: String = "")

case class State(el: ElementRef[dom.HTMLElement] = ElementRef(),
                 handle: RV[Option[js.Dynamic]] = RV(None),
                 gone: RV[Boolean] = RV(false))

def initState() = State()

def mounted(s: State, props: Props): Unit =
  JsModule.load("editor").foreach { m =>
    if (!s.gone.v) s.el.foreach { el =>
      val opts = if (props.testid.nonEmpty) js.Dynamic.literal(testid = props.testid) else js.Dynamic.literal()
      s.handle := Some(m.renderStatic(el, props.content, opts))
    }
  }

def unmounted(s: State): Unit = {
  s.gone := true
  s.handle.v.foreach(_.destroy())
  s.handle := None
}
</script>

<template>
  <div ref="s.el"></div>
</template>
