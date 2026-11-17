<script lang="scala">
import io.circe.Json
import io.circe.syntax.*
import org.scalajs.dom

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success, Try}

// フォームの回答画面（/f/:token。ログイン不要。「ログインが必要」のフォームはログインを案内する）
case class Props(token: String)

/** 質問と入力中の値（text: 文字の入力・選択 / check: チェック / multi: 複数選択）・エラー */
final case class Q(f: PublicField, text: RV[String], check: RV[Boolean], multi: RV[List[String]], err: RV[String])

case class State(form: RV[Option[PublicForm]] = RV(None),
                 qs: RV[List[Q]] = RV(Nil),
                 error: RV[String] = RV(""),
                 busy: RV[Boolean] = RV(false),
                 done: RV[Boolean] = RV(false))

def initState() = State()

def fresh(fs: List[PublicField]): List[Q] = fs.map(f => Q(f, RV(""), RV(false), RV(Nil), RV("")))

def mounted(s: State, props: Props): Unit =
  FormApi.publicGet(props.token).onComplete {
    case Success(f) =>
      s.form := Some(f)
      dom.document.title = s"${f.title} - フォーム"
      s.qs := fresh(f.fields)
    case Failure(e) => s.error := e.getMessage
  }

def toggleMulti(q: Q, id: String, on: Boolean): Unit = q.multi := (if (on) q.multi.v :+ id else q.multi.v.filterNot(_ == id))

private val UrlRe = "(?i)^(https?://|mailto:).*".r

def submit(s: State, props: Props): Unit = if (s.form.v.isDefined) {
  val out = Map.newBuilder[String, Json]
  var bad = false
  s.qs.v.foreach { q =>
    q.err := ""
    val t = q.f.`type`
    val v: Option[Json] = t match {
      case "checkbox" => Some(q.check.v).filter(identity).map(Json.fromBoolean)
      case "multi_select" => Some(q.multi.v).filter(_.nonEmpty).map(_.asJson)
      case _ => Some(q.text.v.trim).filter(_.nonEmpty).map(Json.fromString)
    }
    v match {
      case None => if (q.f.required) { q.err := "入力してください"; bad = true }
      case Some(j) if t == "number" =>
        val n = Val.jsNumber(q.text.v.trim.replace(",", ""))
        if (n.isNaN || n.isInfinite) { q.err := "数値を入力してください"; bad = true }
        else out += q.f.prop -> Json.fromDoubleOrNull(n)
      case Some(_) if t == "url" && !UrlRe.matches(q.text.v.trim.takeWhile(_ != '\n')) =>
        q.err := "https:// で始まる URL を入力してください"
        bad = true
      case Some(j) => out += q.f.prop -> j
    }
  }
  if (!bad) {
    s.busy := true
    s.error := ""
    FormApi.publicSubmit(props.token, out.result()).onComplete { r =>
      s.busy := false
      r match {
        case Success(_) => s.done := true
        case Failure(e) => s.error := e.getMessage
      }
    }
  }
}

/** ログインしたら、このフォームに戻る（App が受け取る） */
def rememberReturn(props: Props): Unit = Try(dom.window.sessionStorage.setItem("skiwi.returnTo", s"/f/${props.token}"))

def again(s: State): Unit = {
  s.done := false
  s.qs := fresh(s.form.v.fold(List.empty[PublicField])(_.fields))
}

def needLogin(f: PublicForm): Boolean = f.requireLogin && !f.loggedIn

def testid(q: Q): String = s"form-input-${q.f.label}"
</script>

<template>
  <div class="formpage" data-testid="form-page">
    <div class="card">
      <p v-if="s.error.v.nonEmpty &amp;&amp; s.form.v.isEmpty" class="error" data-testid="form-error">{{ s.error }}</p>
      <template v-else-if="s.form.v.isDefined">
        <template v-for="f in s.form.v.toList">
          <h1>{{ f.title }}</h1>
          <p v-if="f.description.exists(_.nonEmpty)" class="desc">{{ f.description.getOrElse("") }}</p>
          <div v-if="needLogin(f)" class="login">
            このフォームに回答するにはログインが必要です。<a href="/auth/login" @click="rememberReturn(props)">ログインする</a>
          </div>
          <div v-else-if="s.done" class="done" data-testid="form-done">
            <p>✅ 回答を送信しました。ありがとうございました。</p>
            <button @click="again(s)">もう一度回答する</button>
          </div>
          <form v-else @submit.prevent="submit(s, props)">
            <p v-if="f.loggedIn &amp;&amp; f.userName.isDefined" class="muted small">{{ f.userName.getOrElse("") }} として回答します</p>
            <div v-for="q in s.qs" :key="q.f.prop" class="q" data-testid="form-q">
              <label class="ql">{{ q.f.label }}<span v-if="q.f.required" class="reqm">*</span></label>
              <p v-if="q.f.help.exists(_.nonEmpty)" class="help">{{ q.f.help.getOrElse("") }}</p>
              <textarea v-if='q.f.`type` == "text"' v-model="q.text" rows="3" :data-testid="testid(q)"></textarea>
              <input v-else-if='q.f.`type` == "title"' v-model="q.text" maxlength="500" :data-testid="testid(q)">
              <input v-else-if='q.f.`type` == "number"' v-model="q.text" inputmode="decimal" :data-testid="testid(q)">
              <input v-else-if='q.f.`type` == "date"' v-model="q.text" type="date" :data-testid="testid(q)">
              <input v-else-if='q.f.`type` == "url"' v-model="q.text" type="url" placeholder="https://" :data-testid="testid(q)">
              <label v-else-if='q.f.`type` == "checkbox"' class="chk"><input v-model="q.check" type="checkbox"> はい</label>
              <select v-else-if='q.f.`type` == "select"' :data-testid="testid(q)" @change="q.text := DbDom.value(event)">
                <option value="" :selected="q.text.v.isEmpty">（選んでください）</option>
                <option v-for="o in q.f.options" :key="o.id" :value="o.id" :selected="q.text.v == o.id">{{ o.name }}</option>
              </select>
              <div v-else-if='q.f.`type` == "multi_select"' class="multi">
                <label v-for="o in q.f.options" :key="o.id" class="chk"><input type="checkbox" :checked="q.multi.v.contains(o.id)" @change="toggleMulti(q, o.id, DbDom.checked(event))"> {{ o.name }}</label>
              </div>
              <p v-if="q.err.v.nonEmpty" class="error small">{{ q.err }}</p>
            </div>
            <p v-if="s.error.v.nonEmpty" class="error">{{ s.error }}</p>
            <button type="submit" class="primary" :disabled="s.busy.v" data-testid="form-submit">送信</button>
          </form>
        </template>
      </template>
      <p v-else class="muted">読み込み中…</p>
    </div>
  </div>
</template>

<style scoped>
.formpage { min-height: 100vh; background: var(--side); padding: 2rem 1rem; box-sizing: border-box; }
.card { max-width: 640px; margin: 0 auto; background: var(--bg); border: 1px solid var(--line); border-radius: 12px; padding: 1.5rem 1.8rem 2rem; }
h1 { font-size: 1.5rem; margin: 0 0 .4rem; }
.desc { white-space: pre-wrap; color: var(--muted); line-height: 1.7; }
.q { margin: 1.1rem 0; display: flex; flex-direction: column; gap: .3rem; }
.ql { font-weight: 600; }
.reqm { color: var(--danger); margin-left: .2rem; }
.help { margin: 0; font-size: .8rem; color: var(--muted); }
input:not([type=checkbox]), textarea, select { font: inherit; padding: .45rem .6rem; border: 1px solid var(--line); border-radius: 8px; background: var(--bg); color: inherit; }
.chk { display: flex; align-items: center; gap: .35rem; }
.multi { display: flex; flex-wrap: wrap; gap: .3rem 1rem; }
.primary { padding: .45rem 1.6rem; font-size: 1rem; margin-top: .5rem; }
.done { padding: 1rem 0; }
.login { padding: 1rem; background: var(--side); border-radius: 8px; }
.login a { color: var(--accent); }
.small { font-size: .8rem; }
</style>
