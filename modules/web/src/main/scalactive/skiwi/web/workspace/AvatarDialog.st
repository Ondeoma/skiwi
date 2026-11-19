<script lang="scala">
import skiwi.web.AppNames
import org.scalajs.dom
import skiwi.web.{Api, Backdrop, Store}
import skiwi.web.parts.Avatar

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 自分のアイコンを設定する. 選んだ画像は真ん中を正方形に切り抜いて縮め、保存するまでは見本だけを出す.
case class Props(onClose: Unit => Unit)

case class State(picked: RV[Option[dom.Blob]] = RV(None),
                 preview: RV[Option[String]] = RV(None),
                 error: RV[String] = RV(""),
                 busy: RV[Boolean] = RV(false),
                 fileEl: ElementRef[dom.HTMLInputElement] = ElementRef())

def initState() = State()

def setPreview(s: State, b: Option[dom.Blob]): Unit = {
  s.preview.v.foreach(dom.URL.revokeObjectURL)
  s.picked := b
  s.preview := b.map(dom.URL.createObjectURL)
}

def unmounted(s: State): Unit = setPreview(s, None)

def onPick(s: State, e: dom.Event): Unit = {
  val input = e.target.asInstanceOf[dom.HTMLInputElement]
  val f = Option(input.files).flatMap(fs => Option(fs.item(0)))
  input.value = ""
  f.foreach { file =>
    s.error := ""
    AvatarImage.squareImage(file)
      .map(b => setPreview(s, Some(b)))
      .recover { case er => s.error := er.getMessage }
  }
}

def run(s: State, p: Props)(op: => Future[Option[String]]): Unit = {
  s.busy := true
  s.error := ""
  op.map { url =>
    Store.me := Store.me.v.map(_.copy(avatarUrl = url))
    p.onClose(())
  }.recover { case e => s.error := e.getMessage }.andThen(_ => s.busy := false)
}

def save(s: State, p: Props): Unit = s.picked.v.foreach(b => run(s, p)(Api.setAvatar(b)))

def remove(s: State, p: Props): Unit = run(s, p)(Api.deleteAvatar().map(_ => None))

def onKey(p: Props, e: dom.Event): Unit = if (e.asInstanceOf[dom.KeyboardEvent].key == "Escape") p.onClose(())

def meName = Store.me.v.map(_.name).getOrElse("")

def shownUrl(s: State): Option[String] = s.preview.v.orElse(Store.me.v.flatMap(_.avatarUrl))
</script>

<template>
  <div class="backdrop" @mousedown="Backdrop.down(event)" @mouseup="Backdrop.up(event)" @click="Backdrop.click(event)(props.onClose(()))" @keydown="onKey(props, event)">
    <form class="dialog" role="dialog" aria-label="アイコンの設定" data-testid="avatar-dialog" @submit.prevent="save(s, props)">
      <h2>🖼 アイコンの設定</h2>
      <div class="body">
        <Avatar :name="meName" :url="shownUrl(s)" :size="96" testid="avatar-preview" />
        <div class="side">
          <button type="button" data-testid="avatar-pick" @click="s.fileEl.foreach(_.click())">画像を選ぶ…</button>
          <input ref="s.fileEl" type="file" accept="image/*" hidden data-testid="avatar-file" @change="onPick(s, event)">
          <p class="note">真ん中を正方形に切り抜いて、小さくしてから保存します。アイコンは同じ {{ AppNames.self }} を使うほかの人にも見えます。</p>
          <button v-if="Store.me.v.exists(_.avatarUrl.isDefined) && s.picked.v.isEmpty" type="button" class="link danger" :disabled="s.busy.v" data-testid="avatar-remove" @click="remove(s, props)">アイコンを外す</button>
        </div>
      </div>
      <p v-if="s.error.v.nonEmpty" class="error" data-testid="avatar-error">{{ s.error.v }}</p>
      <div class="actions">
        <button type="button" @click="props.onClose(())">キャンセル</button>
        <button type="submit" class="primary" :disabled="s.busy.v || s.picked.v.isEmpty" data-testid="avatar-save">{{ if (s.busy.v) "保存中…" else "保存" }}</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.backdrop { position: fixed; inset: 0; background: rgba(0,0,0,.35); display: flex; align-items: flex-start; justify-content: center; padding-top: 10vh; z-index: 90; }
.dialog { background: var(--bg); border: 1px solid var(--line); border-radius: 10px; width: min(440px, 92vw); padding: 1rem 1.2rem; box-shadow: 0 20px 50px rgba(0,0,0,.25); }
h2 { font-size: 1.05rem; margin: 0 0 .8rem; }
.body { display: flex; gap: 1rem; align-items: flex-start; }
.side { display: flex; flex-direction: column; align-items: flex-start; gap: .4rem; min-width: 0; }
.note { margin: 0; font-size: .8rem; color: var(--muted); }
.link { border: none; background: none; padding: 0; font-size: .82rem; color: var(--muted); }
.link.danger:hover { color: var(--danger); }
.error { font-size: .85rem; }
.actions { display: flex; justify-content: flex-end; gap: .5rem; margin-top: 1rem; }
.primary:disabled { opacity: .6; }
</style>
