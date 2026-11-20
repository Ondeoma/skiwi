<script lang="scala">
import sbirds.web.Dom
import skiwi.web.{Dialog, Ui}
import skiwi.web.parts.{HelpLink, Modal}

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// データベースのフォーム: 回答がこのデータベースの項目になる。URL を知っている人が回答できる（ログインを求めることもできる）
// dbProps: データベースの列
case class Props(pageId: String, dbProps: List[DbProp], onClose: Unit => Unit = _ => ())

/** 編集中の質問（入力中に行を作り直さないよう、値は RV で持つ） */
final case class FieldRow(prop: String, label: RV[String], required: RV[Boolean], help: Option[String])

case class State(forms: RV[Option[List[DbFormDef]]] = RV(None),
                 error: RV[String] = RV(""),
                 editing: RV[Option[String]] = RV(None),
                 open: RV[Boolean] = RV(false),
                 title: RV[String] = RV(""),
                 description: RV[String] = RV(""),
                 fields: RV[List[FieldRow]] = RV(Nil),
                 requireLogin: RV[Boolean] = RV(false),
                 respondentProp: RV[String] = RV(""),
                 enabled: RV[Boolean] = RV(true),
                 copied: RV[Option[String]] = RV(None))

def initState() = State()

def load(s: State, props: Props): Unit =
  FormApi.list(props.pageId).onComplete {
    case Success(fs) => s.forms := Some(fs)
    case Failure(e) => s.error := e.getMessage
  }

def mounted(s: State, props: Props): Unit = load(s, props)

val Answerable: Set[String] = Set("text", "number", "select", "multi_select", "date", "checkbox", "url")

def answerable(props: Props): List[DbProp] = props.dbProps.filter(p => Answerable(p.tpe))

def personProps(props: Props): List[DbProp] = props.dbProps.filter(_.tpe == "person")

def row(prop: String, label: String = "", required: Boolean = false, help: Option[String] = None): FieldRow =
  FieldRow(prop, RV(label), RV(required), help)

// --- 作成・編集 ---
def startNew(s: State, props: Props): Unit = {
  s.editing := None
  s.title := "お問い合わせ"
  s.description := ""
  s.fields := row("title", "件名", required = true) :: answerable(props).take(4).map(p => row(p.id))
  s.requireLogin := false
  s.respondentProp := ""
  s.enabled := true
  s.open := true
}

def startEdit(s: State, f: DbFormDef): Unit = {
  s.editing := Some(f.id)
  s.title := f.title
  s.description := f.description.getOrElse("")
  s.fields := f.fields.map(x => row(x.prop, x.label.getOrElse(""), x.required, x.help))
  s.requireLogin := f.requireLogin
  s.respondentProp := f.respondentProp.getOrElse("")
  s.enabled := f.enabled
  s.open := true
}

def has(s: State, id: String): Boolean = s.fields.v.exists(_.prop == id)

def toggleField(s: State, id: String, on: Boolean): Unit =
  s.fields := (if (on) s.fields.v :+ row(id) else s.fields.v.filterNot(_.prop == id))

def move(s: State, i: Int, d: Int): Unit = {
  val fs = s.fields.v
  val j = i + d
  if (j >= 0 && j < fs.size) s.fields := fs.updated(i, fs(j)).updated(j, fs(i))
}

def nameOf(props: Props, id: String): String =
  if (id == "title") "名前（項目の名前）" else props.dbProps.find(_.id == id).fold(id)(_.name)

def iconOf(props: Props, id: String): String =
  if (id == "title") "≡" else Db.icon(props.dbProps.find(_.id == id).fold("text")(_.tpe))

def save(s: State, props: Props): Unit = {
  s.error := ""
  val body = SaveForm(
    title = s.title.v.trim,
    description = Some(s.description.v.trim).filter(_.nonEmpty),
    fields = s.fields.v.map(f => FormField(f.prop, Some(f.label.v).filter(_.nonEmpty), f.required.v, f.help)),
    requireLogin = s.requireLogin.v,
    respondentProp = if (s.requireLogin.v) Some(s.respondentProp.v).filter(_.nonEmpty) else None,
    enabled = s.enabled.v)
  val f = s.editing.v match {
    case Some(id) => FormApi.update(id, body)
    case None => FormApi.create(props.pageId, body)
  }
  f.onComplete {
    case Success(_) =>
      s.open := false
      load(s, props)
    case Failure(e) => s.error := e.getMessage
  }
}

def remove(s: State, props: Props, f: DbFormDef): Unit =
  Dialog.confirm(s"フォーム「${f.title}」を削除しますか？（集まった回答＝項目は残ります）", okLabel = "削除", danger = true).foreach { ok =>
    if (ok) FormApi.remove(f.id).onComplete(_ => load(s, props))
  }

def copy(s: State, f: DbFormDef): Unit = Ui.copy(f.url, s.copied, f.id)

def copyLabel(s: State, f: DbFormDef): String = if (s.copied.v.contains(f.id)) "コピーしました" else "コピー"

def forms(s: State): List[DbFormDef] = s.forms.v.getOrElse(Nil)

def usage(f: DbFormDef): String = s"${f.useCount}件の回答" + f.lastAt.fold("")(t => s"・最後の回答 ${Ui.date(t)}")

def itemClass(f: DbFormDef): String = if (f.enabled) "item" else "item off"

def addable(s: State, props: Props): List[DbProp] = answerable(props).filterNot(p => has(s, p.id))
</script>

<template>
  <Modal label="フォーム" testid="forms" :width="620" title="📝 フォーム" :onClose="_ => props.onClose(())">
    <p class="help"><HelpLink to="database#フォーム" /></p>
    <p class="note">回答はこのデータベースの項目になります（フォームを作った人の権限で作ります）。URL を知っている人は誰でも回答できます。ログインを求めると、回答者をユーザーの列に記録できます。</p>
    <p v-if="s.error.v.nonEmpty" class="error" data-testid="form-error">{{ s.error }}</p>
    <ul v-if="s.forms.v.isDefined &amp;&amp; !s.open.v" class="list">
      <li v-if="forms(s).isEmpty" class="muted">まだフォームがありません。</li>
      <li v-for="f in forms(s)" :key="f.id" :class="itemClass(f)" data-testid="form-item">
        <div class="h"><b>{{ f.title }}</b><span v-if="!f.enabled" class="tag">停止中</span><span v-if="f.requireLogin" class="tag">ログインが必要</span>
          <span class="spacer"></span><button class="link" @click="startEdit(s, f)">編集</button><button class="link danger" @click="remove(s, props, f)">削除</button></div>
        <div class="url"><a :href="f.url" target="_blank" rel="noopener" data-testid="form-url">{{ f.url }}</a> <button class="link" @click="copy(s, f)">{{ copyLabel(s, f) }}</button></div>
        <div class="muted small">{{ usage(f) }}</div>
      </li>
    </ul>
    <form v-if="s.open" class="form" data-testid="form-edit" @submit.prevent="save(s, props)">
      <label class="col"><span>フォームの名前</span><input v-model="s.title" maxlength="200" required data-testid="form-title"></label>
      <label class="col"><span>説明（回答者に表示）</span><textarea v-model="s.description" rows="2" maxlength="4000"></textarea></label>
      <div class="col">
        <span>質問（上から順に表示）</span>
        <div v-for="fi in s.fields.v.zipWithIndex" :key="fi._1.prop" class="field" data-testid="form-field">
          <span class="ic">{{ iconOf(props, fi._1.prop) }}</span>
          <input v-model="fi._1.label" :placeholder="nameOf(props, fi._1.prop)" class="lbl" maxlength="200">
          <label class="req"><input v-model="fi._1.required" type="checkbox"> 必須</label>
          <button type="button" class="ghost" @click="move(s, fi._2, -1)">↑</button><button type="button" class="ghost" @click="move(s, fi._2, 1)">↓</button>
          <button type="button" class="ghost" title="外す" @click="toggleField(s, fi._1.prop, false)">×</button>
        </div>
        <div class="add">
          追加:
          <button v-if='!has(s, "title")' type="button" class="link" @click='toggleField(s, "title", true)'>≡ 名前</button>
          <button v-for="p in addable(s, props)" :key="p.id" type="button" class="link" :data-testid='"form-add-" + p.name' @click="toggleField(s, p.id, true)">{{ Db.icon(p.tpe) }} {{ p.name }}</button>
        </div>
        <span class="muted small">回答できる列: テキスト・数値・選択・複数選択・日付・チェック・URL</span>
      </div>
      <label class="row"><input v-model="s.requireLogin" type="checkbox" data-testid="form-login"> 回答にはログインが必要</label>
      <label v-if="s.requireLogin.v &amp;&amp; personProps(props).nonEmpty" class="row">回答者を記録する列:
        <select @change="s.respondentProp := Dom.value(event)">
          <option value="" :selected="s.respondentProp.v.isEmpty">（記録しない）</option>
          <option v-for="p in personProps(props)" :key="p.id" :value="p.id" :selected="s.respondentProp.v == p.id">{{ p.name }}</option>
        </select>
      </label>
      <label v-if="s.editing.v.isDefined" class="row"><input v-model="s.enabled" type="checkbox"> 回答を受け付ける</label>
      <div class="acts"><button type="button" @click="s.open := false">やめる</button><button type="submit" class="primary" data-testid="form-save">{{ if (s.editing.v.isDefined) "保存" else "作成" }}</button></div>
    </form>
    <button v-else class="addbtn" data-testid="form-add" @click="startNew(s, props)">＋ フォームを作る</button>
  </Modal>
</template>

<style scoped>
.help { margin: .2rem 0 0; }
.note { line-height: 1.55; }
.list { list-style: none; padding: 0; margin: .4rem 0; }
.item { border: 1px solid var(--line); border-radius: 8px; padding: .5rem .7rem; margin-bottom: .45rem; font-size: .85rem; }
.item.off { opacity: .6; }
.h { display: flex; align-items: center; gap: .4rem; }
.tag { font-size: .7rem; border: 1px solid var(--line); border-radius: 4px; padding: 0 5px; color: var(--muted); }
.spacer { flex: 1; }
.url { margin: .2rem 0; word-break: break-all; font-size: .8rem; }
.url a { color: var(--accent); }
.link { border: none; background: none; color: var(--accent); padding: 0 .3rem; font-size: .8rem; }
.danger:hover { color: var(--danger); }
.form { display: flex; flex-direction: column; gap: .6rem; font-size: .85rem; }
.col { display: flex; flex-direction: column; gap: .3rem; }
.col > span { font-size: .78rem; color: var(--muted); }
.row { display: flex; align-items: center; gap: .4rem; }
input:not([type=checkbox]), textarea, select { font: inherit; padding: .3rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; }
.field { display: flex; align-items: center; gap: .35rem; }
.field .ic { width: 1.4rem; text-align: center; color: var(--muted); }
.field .lbl { flex: 1; min-width: 0; }
.req { font-size: .78rem; white-space: nowrap; display: flex; align-items: center; gap: .2rem; }
.ghost { border: none; background: none; color: var(--muted); padding: 0 4px; }
.add { display: flex; flex-wrap: wrap; align-items: center; gap: .2rem; font-size: .78rem; color: var(--muted); }
.acts { display: flex; justify-content: flex-end; gap: .4rem; }
.addbtn { margin-top: .3rem; }
.error { font-size: .82rem; }
</style>
