<script lang="scala">
import skiwi.web.*
import skiwi.web.parts.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// ゲスト閲覧時に本文の上下へ入れるHTML. kind: page（そのページ. 子ページにも入れるか選べる）/ workspace（全ページ）.
// id: ページまたはワークスペースのID. テンプレートの :id は子のルート要素の属性になるため、.st からは :target-id で渡す
case class Props(kind: String, id: String = "", title: String, onClose: Unit => Unit, targetId: String = "")

case class State(top: RV[String] = RV(""),
                 bottom: RV[String] = RV(""),
                 inherit: RV[Boolean] = RV(true),
                 layers: RV[List[GuestHtmlLayer]] = RV(Nil),
                 original: RV[String] = RV(""),
                 loading: RV[Boolean] = RV(true),
                 saving: RV[Boolean] = RV(false),
                 error: RV[String] = RV(""),
                 preview: RV[Boolean] = RV(false))

def initState() = State()

def idOf(p: Props): String = if (p.targetId.nonEmpty) p.targetId else p.id

def snapshot(s: State): String = List(s.top.v, s.bottom.v, s.inherit.v.toString).mkString("\u0000")

def mounted(s: State, props: Props): Unit =
  DialogsApi.guestHtml(props.kind, idOf(props)).onComplete {
    case Success(r) =>
      s.top := r.top.getOrElse("")
      s.bottom := r.bottom.getOrElse("")
      s.inherit := r.inherit.getOrElse(true)
      s.layers := r.layers.getOrElse(Nil)
      s.original := snapshot(s)
      s.loading := false
    case Failure(e) =>
      s.error := e.getMessage
      s.loading := false
  }

def dirty(s: State): Boolean = !s.loading.v && snapshot(s) != s.original.v

def tooLong(s: State): Boolean = s.top.v.length > 50000 || s.bottom.v.length > 50000

def save(s: State, p: Props): Unit = {
  s.saving := true
  s.error := ""
  DialogsApi.setGuestHtml(p.kind, idOf(p), Dlg.nonBlank(s.top.v), Dlg.nonBlank(s.bottom.v), s.inherit.v).onComplete { r =>
    s.saving := false
    r match {
      case Success(_) => p.onClose(())
      case Failure(e) => s.error := e.getMessage
    }
  }
}

def cancel(s: State, p: Props): Unit =
  if (!dirty(s)) p.onClose(())
  else Dialog.confirm("変更を保存せずに閉じますか？", okLabel = "閉じる").foreach(ok => if (ok) p.onClose(()))

def label(l: GuestHtmlLayer): String =
  if (l.level == "workspace") "🗂 ワークスペース" else s"📄 親ページ「${l.title.getOrElse("（閲覧できないページ）")}」"

def title(p: Props): String = s"🌐 ゲスト向けの表示 ― ${Dlg.untitled(p.title)}"
</script>

<template>
  <Modal label="ゲスト向けの表示" :title="title(props)" testid="guest-html-dialog" :max-height="88" :top="6" :z="65" :dim="0.35" :small-title="true" close-title="閉じる" @close="cancel(s, props)">
    <p class="note">
      ゲスト向けの公開ページで、本文の上と下に入れるHTMLです（ログインしている人の画面には出ません）。
      {{ if (props.kind == "workspace") "このワークスペースの全ページに入ります。" else "" }}
      HTMLは隔離された枠（iframe）の中で表示するため、スクリプトも使えますが、skiwi の画面やログイン情報には触れられません。リンクはページ全体で開きます。
    </p>
    <p v-if="s.loading.v" class="muted">読み込み中…</p>
    <template v-else>
      <details v-if="s.layers.v.nonEmpty" class="inherited">
        <summary>上位から入るHTML（{{ s.layers.v.size }}件）</summary>
        <div v-for="l in s.layers.v.zipWithIndex" :key="l._2" class="layer">
          <div class="layer-title">{{ label(l._1) }}</div>
          <pre v-if="l._1.top.exists(_.nonEmpty)">上: {{ l._1.top.getOrElse("") }}</pre>
          <pre v-if="l._1.bottom.exists(_.nonEmpty)">下: {{ l._1.bottom.getOrElse("") }}</pre>
        </div>
        <p class="note">本文の上は「ワークスペース → 親 → このページ」、下は「このページ → 親 → ワークスペース」の順に並びます。</p>
      </details>
      <label class="lbl">本文の上</label>
      <textarea v-model="s.top" class="code" spellcheck="false" placeholder="例: &lt;div style=&quot;padding:12px;background:#eef2ff&quot;&gt;お知らせ: …&lt;/div&gt;" data-testid="guest-html-top"></textarea>
      <label class="lbl">本文の下</label>
      <textarea v-model="s.bottom" class="code" spellcheck="false" placeholder="例: &lt;p&gt;© 2026 Example Inc. &lt;a href=&quot;https://example.com&quot;&gt;お問い合わせ&lt;/a&gt;&lt;/p&gt;" data-testid="guest-html-bottom"></textarea>
      <label v-if='props.kind == "page"' class="row">
        <input v-model="s.inherit" type="checkbox" data-testid="guest-html-inherit">
        子ページ（その下の階層すべて）にも入れる
      </label>
      <button class="link" @click="s.preview := !s.preview.v">{{ if (s.preview.v) "▾ プレビューを閉じる" else "▸ プレビュー" }}</button>
      <div v-if="s.preview.v" class="preview">
        <SandboxedHtml v-if="s.top.v.trim.nonEmpty" :html="s.top.v" />
        <div class="body-dummy">（ページの本文）</div>
        <SandboxedHtml v-if="s.bottom.v.trim.nonEmpty" :html="s.bottom.v" />
      </div>
    </template>
    <p v-if="s.error.v.nonEmpty" class="error">{{ s.error }}</p>
    <div class="actions">
      <span class="muted small">それぞれ 50,000 文字まで</span>
      <button @click="cancel(s, props)">キャンセル</button>
      <button class="primary" :disabled="s.loading.v || s.saving.v || tooLong(s)" data-testid="guest-html-save" @click="save(s, props)">保存</button>
    </div>
  </Modal>
</template>

<style scoped>
.note { margin: .4rem 0; }
.lbl { display: block; font-size: .8rem; font-weight: 600; margin: .5rem 0 .2rem; }
.code { width: 100%; box-sizing: border-box; min-height: 110px; resize: vertical; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .8rem; line-height: 1.5; padding: .5rem .6rem; border: 1px solid var(--line); border-radius: 8px; background: var(--side); color: inherit; }
.code:focus { outline: none; border-color: var(--accent); }
.inherited { margin: .4rem 0; font-size: .82rem; }
.inherited summary { cursor: pointer; color: var(--muted); }
.layer-title { font-size: .75rem; color: var(--muted); margin-top: .3rem; }
.layer pre { margin: .1rem 0; padding: .3rem .5rem; background: var(--hover); border-radius: 6px; font-size: .72rem; max-height: 90px; overflow: auto; white-space: pre-wrap; }
.row { display: flex; align-items: center; gap: .4rem; margin: .5rem 0; font-size: .88rem; }
.link { border: none; background: none; color: var(--accent); padding: 0; font-size: .8rem; }
.preview { margin-top: .4rem; border: 1px dashed var(--line); border-radius: 8px; padding: .5rem; }
.body-dummy { text-align: center; color: var(--muted); font-size: .8rem; padding: .8rem 0; border-top: 1px dashed var(--line); border-bottom: 1px dashed var(--line); margin: .3rem 0; }
.actions { display: flex; align-items: center; justify-content: flex-end; gap: .5rem; margin-top: .7rem; }
.actions .small { margin-right: auto; font-size: .75rem; }
.primary:disabled { opacity: .5; }
.error { font-size: .85rem; }
</style>
