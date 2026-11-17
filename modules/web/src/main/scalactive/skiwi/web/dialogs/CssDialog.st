<script lang="scala">
import org.scalajs.dom
import skiwi.web.*
import skiwi.web.parts.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// カスタムCSSの編集. kind: page（そのページ. 子ページにも適用するか選べる）/ workspace（ワークスペースの全ページ）.
// 入力中は onPreview で親に渡して、その場で見た目を確かめられる（None: プレビューをやめる）.
// id: ページまたはワークスペースのID. テンプレートの :id は子のルート要素の属性になるため、.st からは :target-id で渡す
case class Props(kind: String, id: String = "", title: String, onClose: Unit => Unit, onSaved: Unit => Unit = _ => (),
                 onPreview: Option[String] => Unit = _ => (), targetId: String = "")

case class State(css: RV[String] = RV(""),
                 inherit: RV[Boolean] = RV(true),
                 inherited: RV[List[CssLayer]] = RV(Nil),
                 original: RV[(String, Boolean)] = RV(("", true)),
                 loading: RV[Boolean] = RV(true),
                 saving: RV[Boolean] = RV(false),
                 error: RV[String] = RV(""))

def initState() = State()

def idOf(p: Props): String = if (p.targetId.nonEmpty) p.targetId else p.id

def mounted(s: State, props: Props): Unit = {
  val id = idOf(props)
  val load =
    if (props.kind == "page") DialogsApi.pageStyles(id).map { st =>
      s.css := st.css.getOrElse("")
      s.inherit := st.inherit
      s.inherited := st.layers.filterNot(_.own)
    }
    else DialogsApi.workspaceCss(id).map(w => s.css := w.css.getOrElse(""))
  load.onComplete { r =>
    r match {
      case Success(_) => s.original := ((s.css.v, s.inherit.v))
      case Failure(e) => s.error := e.getMessage
    }
    s.loading := false
  }
  // 入力中の見た目をその場で反映（ページのとき）
  watch(s.css, false)((_, v) => if (!s.loading.v) props.onPreview(Some(v)))
}

/** 適用されない記述の警告（scopeCss の warnings） */
def warnings(s: State): List[String] = CustomCss.scopeCss(s.css.v).warnings

def dirty(s: State): Boolean = s.css.v != s.original.v._1 || s.inherit.v != s.original.v._2

def save(s: State, p: Props): Unit = {
  s.saving := true
  s.error := ""
  val v = Dlg.nonBlank(s.css.v)
  val f = if (p.kind == "page") DialogsApi.setPageCss(idOf(p), v, s.inherit.v) else DialogsApi.setWorkspaceCss(idOf(p), v)
  f.onComplete { r =>
    s.saving := false
    r match {
      case Success(_) =>
        dom.window.dispatchEvent(new dom.Event("skiwi:css-changed"))
        p.onSaved(())
        p.onClose(())
      case Failure(e) => s.error := e.getMessage
    }
  }
}

def cancel(s: State, p: Props): Unit = {
  def close(): Unit = { p.onPreview(None); p.onClose(()) }
  if (!dirty(s)) close()
  else Dialog.confirm("変更を保存せずに閉じますか？", okLabel = "閉じる").foreach(ok => if (ok) close())
}

def label(l: CssLayer): String =
  if (l.level == "workspace") "🗂 ワークスペース" else s"📄 親ページ「${l.title.getOrElse("（閲覧できないページ）")}」"

def example(p: Props): String =
  if (p.kind == "page") "/* 例: 見出しの色と本文の行間 */\nh1, h2 { color: #1d4ed8; }\np { line-height: 1.9; }\n.ProseMirror table td { font-size: 0.9em; }"
  else "/* 例: このワークスペースの全ページ */\nh1 { border-bottom: 2px solid #e5e7eb; padding-bottom: .2em; }"

def title(p: Props): String =
  s"🎨 ${if (p.kind == "page") "このページのスタイル" else "ワークスペースのスタイル"} ― ${Dlg.untitled(p.title)}"

def intro(p: Props): String =
  if (p.kind == "page") "このページの本文とタイトルの見た目を CSS で変えられます。" else "このワークスペースの全ページの本文とタイトルに適用します。"
</script>

<template>
  <Modal label="カスタムCSS" :title="title(props)" testid="css-dialog" :width="520" :max-height="86" :top="6" :side="true" :small-title="true" close-title="閉じる" @close="cancel(s, props)">
    <p class="note">
      {{ intro(props) }}
      効くのはページの表示エリアの中だけです（サイドバーなどには効きません）。外部のURL・@import・position: fixed は使えません。
    </p>
    <p v-if="s.loading.v" class="muted">読み込み中…</p>
    <template v-else>
      <details v-if="s.inherited.v.nonEmpty" class="inherited">
        <summary>上位から適用されているスタイル（{{ s.inherited.v.size }}件）</summary>
        <div v-for="l in s.inherited.v.zipWithIndex" :key="l._2" class="layer">
          <div class="layer-title">{{ label(l._1) }}</div>
          <pre>{{ l._1.css }}</pre>
        </div>
        <p class="note">このページのスタイルは、これらの後に適用されます（同じ指定ならこちらが優先）。</p>
      </details>
      <textarea v-model="s.css" class="code" spellcheck="false" :placeholder="example(props)" data-testid="css-input"></textarea>
      <ul v-if="warnings(s).nonEmpty" class="warn" data-testid="css-warnings">
        <li v-for="w in warnings(s)" :key="w">⚠ {{ w }}（この部分は適用されません）</li>
      </ul>
      <label v-if='props.kind == "page"' class="row">
        <input v-model="s.inherit" type="checkbox" data-testid="css-inherit">
        子ページ（その下の階層すべて）にも適用する
      </label>
    </template>
    <p v-if="s.error.v.nonEmpty" class="error">{{ s.error }}</p>
    <div class="actions">
      <span class="muted small">{{ Dlg.num(s.css.v.length) }} / 20,000 文字</span>
      <button @click="cancel(s, props)">キャンセル</button>
      <button class="primary" :disabled="s.loading.v || s.saving.v || s.css.v.length > 20000" data-testid="css-save" @click="save(s, props)">保存</button>
    </div>
  </Modal>
</template>

<style scoped>
.note { margin: .4rem 0; }
.code { width: 100%; box-sizing: border-box; min-height: 260px; resize: vertical; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .82rem; line-height: 1.5; padding: .5rem .6rem; border: 1px solid var(--line); border-radius: 8px; background: var(--side); color: inherit; tab-size: 2; }
.code:focus { outline: none; border-color: var(--accent); }
.inherited { margin: .4rem 0; font-size: .82rem; }
.inherited summary { cursor: pointer; color: var(--muted); }
.layer { margin: .3rem 0; }
.layer-title { font-size: .75rem; color: var(--muted); }
.layer pre { margin: .1rem 0; padding: .3rem .5rem; background: var(--hover); border-radius: 6px; font-size: .75rem; max-height: 120px; overflow: auto; white-space: pre-wrap; }
.warn { margin: .3rem 0; padding: 0 0 0 1rem; font-size: .78rem; color: #b45309; }
.row { display: flex; align-items: center; gap: .4rem; margin: .5rem 0; font-size: .88rem; }
.actions { display: flex; align-items: center; justify-content: flex-end; gap: .5rem; margin-top: .6rem; }
.actions .small { margin-right: auto; font-size: .75rem; }
.primary:disabled { opacity: .5; }
.error { font-size: .85rem; }
</style>
