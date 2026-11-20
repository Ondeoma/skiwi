<script lang="scala">
import skiwi.web.AppNames
import io.circe.Json
import org.scalajs.dom
import skiwi.web.Api
import skiwi.web.ApiError

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// smohua（フォーム）で集めた回答を、ページの中に表示する（エディタの「フォームの回答（smohua）」のブロックの中身. docs/smohua-integration.md）.
// 見ている本人が「smohua と連携」で許可した権限で、skiwi のサーバーを通して読む（GET /api/integrations/smohua/items/{ID}/summary・responses）.
case class Props(formId: String)

// smohua の応答（GET /api/forms/{ID}/summary・responses）から、表示に使うものだけを取り出したもの
final case class Count(label: String, count: Int)
final case class Question(id: String, label: String, answered: Int, counts: List[Count], average: Option[Double], texts: List[String])
final case class Summary(title: String, status: String, total: Int, questions: List[Question])
final case class Answer(seq: Long, who: String, at: String, cells: List[String])

/** phase: loading / off（連携が設定されていない）/ connect（自分が連携していない）/ ok / error */
case class State(phase: RV[String] = RV("loading"), summary: RV[Option[Summary]] = RV(None), answers: RV[Option[List[Answer]]] = RV(None),
                 tab: RV[String] = RV("summary"), error: RV[String] = RV(""))

def initState() = State()

private val smohua = Integrations.smohua

private def detail(props: Props, kind: String) =
  Api.call(dom.HttpMethod.GET, s"/api/integrations/smohua/items/${Api.seg(props.formId)}/${Api.seg(kind)}", redirectOn401 = false).map(Api.as[Json])

/** 回答の値の表示（文字・数・複数の選択） */
def text(j: Json): String =
  j.asString.orElse(j.asNumber.map(n => n.toLong.map(_.toString).getOrElse(n.toDouble.toString)))
    .orElse(j.asArray.map(_.flatMap(_.asString).mkString("、"))).getOrElse("")

def parseSummary(j: Json): Summary = {
  val c = j.hcursor
  Summary(c.get[String]("title").getOrElse(""), c.get[String]("status").getOrElse(""), c.get[Int]("total").getOrElse(0),
    c.get[List[Json]]("questions").getOrElse(Nil).map { q =>
      val qc = q.hcursor
      Question(qc.get[String]("id").getOrElse(""), qc.get[String]("label").getOrElse(""), qc.get[Int]("answered").getOrElse(0),
        qc.get[List[Json]]("counts").getOrElse(Nil).map(x => Count(x.hcursor.get[String]("label").getOrElse(""), x.hcursor.get[Int]("count").getOrElse(0))),
        qc.get[Double]("average").toOption, qc.get[List[String]]("texts").getOrElse(Nil))
    })
}

def parseAnswers(j: Json, qs: List[Question]): List[Answer] =
  j.hcursor.get[List[Json]]("responses").getOrElse(Nil).map { r =>
    val c = r.hcursor
    val as = c.downField("answers")
    Answer(c.get[Long]("seq").getOrElse(0L), c.downField("respondent").get[String]("name").getOrElse("匿名"),
      c.get[String]("createdAt").toOption.map(t => new scala.scalajs.js.Date(t).asInstanceOf[scala.scalajs.js.Dynamic].toLocaleString("ja-JP").asInstanceOf[String]).getOrElse(""),
      qs.map(q => as.downField(q.id).focus.map(text).getOrElse("")))
  }

def fail(s: State, e: Throwable): Unit = {
  s.error := (e match {
    case ApiError(404, _) => "フォームが見つからないか、見る権限がありません"
    case other               => other.getMessage
  })
  s.phase := "error"
}

def load(s: State, props: Props): Unit = {
  s.answers := None
  smohua.load().foreach { _ =>
    val st = smohua.status
    if (!st.exists(_.enabled)) s.phase := "off"
    else if (!st.exists(_.connected)) s.phase := "connect"
    else detail(props, "summary").onComplete {
      case Success(d) if d.hcursor.get[Boolean]("connected").contains(true) =>
        s.summary := d.hcursor.downField("data").focus.map(parseSummary)
        s.phase := "ok"
        if (s.tab.v == "list") loadAnswers(s, props)
      // 相手側で連携が切れた
      case Success(_) => smohua.reload(); s.phase := "connect"
      case Failure(e) => fail(s, e)
    }
  }
}

def loadAnswers(s: State, props: Props): Unit = detail(props, "responses").onComplete {
  case Success(d) => s.answers := Some(d.hcursor.downField("data").focus.map(parseAnswers(_, s.summary.v.map(_.questions).getOrElse(Nil))).getOrElse(Nil))
  case Failure(e) => fail(s, e)
}

def mounted(s: State, props: Props): Unit = load(s, props)

def setTab(s: State, props: Props, t: String): Unit = {
  s.tab := t
  if (t == "list" && s.answers.v.isEmpty) loadAnswers(s, props)
}

/** 連携の後に戻ってくる場所（今の画面） */
def connectUrl(): String = {
  val u = new dom.URL(dom.window.location.href)
  Integrations.connectUrl("smohua", u.pathname + u.search + u.hash)
}

def openUrl(props: Props): String = smohua.url.fold("#")(u => s"$u/forms/${props.formId}/responses")
def statusLabel(st: String): String = st match {
  case "open"   => "受付中"
  case "closed" => "締め切り"
  case "draft"  => "下書き"
  case other    => other
}
def is(s: State, p: String) = s.phase.mapC(_ == p)
def isTab(s: State, t: String) = computed { s.phase.v == "ok" && s.tab.v == t }
def tabCls(s: State, t: String) = s.tab.mapC(x => if (x == t) "on" else "")
def summaries(s: State) = s.summary.mapC(_.toList)
def questions(s: State) = s.summary.mapC(_.map(_.questions).getOrElse(Nil))
def rows(s: State) = s.answers.mapC(_.getOrElse(Nil))
/** 棒の長さ（いちばん多い選択肢を 100% にする） */
def width(q: Question, c: Count): String = s"width: ${c.count * 100 / math.max(1, q.counts.map(_.count).max)}%"
</script>

<template>
  <div class="smohua-form" data-testid="smohua-form">
    <p v-if='is(s, "loading")' class="muted note">📋 フォームの回答を読み込んでいます…</p>
    <p v-if='is(s, "off")' class="muted note">📋 フォームの回答（{{ AppNames.of("smohua") }} との連携が設定されていません。管理者に確認してください）</p>
    <p v-if='is(s, "connect")' class="note">📋 フォームの回答を表示するには、<a :href="connectUrl()" data-testid="smohua-form-connect">smohua と連携</a>してください。</p>
    <p v-if='is(s, "error")' class="note">📋 フォームの回答を表示できません: {{ s.error }} <button type="button" @click="load(s, props)">再読み込み</button></p>
    <template v-if='is(s, "ok")'>
      <div v-for="m in summaries(s)" class="head">
        <span class="title">📋 {{ m.title }}</span>
        <span class="badge">{{ statusLabel(m.status) }}</span>
        <span class="muted">{{ m.total }} 件の回答</span>
        <span class="grow"></span>
        <span class="seg">
          <button type="button" :class='tabCls(s, "summary")' @click='setTab(s, props, "summary")'>集計</button>
          <button type="button" :class='tabCls(s, "list")' @click='setTab(s, props, "list")'>一覧</button>
        </span>
        <button type="button" title="読み直す" @click="load(s, props)">↻</button>
        <a :href="openUrl(props)" target="_blank" rel="noopener">{{ AppNames.of("smohua") }} で開く</a>
      </div>
    </template>
    <div v-if='isTab(s, "summary")' class="body">
      <div v-for="q in questions(s)" :key="q.id" class="q">
        <div class="q-head"><b>{{ q.label }}</b> <span class="muted">{{ q.answered }} 件</span><span v-for="a in q.average.toList" class="muted">・平均 {{ a }}</span></div>
        <div v-for="c in q.counts" class="bar-row">
          <span class="bar-label">{{ c.label }}</span>
          <span class="bar-track"><span class="bar-fill" :style="width(q, c)"></span></span>
          <span class="bar-num">{{ c.count }}</span>
        </div>
        <ul v-if="q.texts.nonEmpty" class="texts"><li v-for="t in q.texts.take(5)">{{ t }}</li></ul>
      </div>
      <p v-if="questions(s).mapC(_.isEmpty)" class="muted">質問はありません</p>
    </div>
    <div v-if='isTab(s, "list")' class="body table">
      <p v-if="s.answers.mapC(_.isEmpty)" class="muted">読み込んでいます…</p>
      <table v-else>
        <thead><tr><th>番号</th><th>回答日時</th><th>回答者</th><th v-for="q in questions(s)">{{ q.label }}</th></tr></thead>
        <tbody>
          <tr v-for="r in rows(s)" :key="r.seq">
            <td>#{{ r.seq }}</td><td class="nowrap">{{ r.at }}</td><td class="nowrap">{{ r.who }}</td><td v-for="c in r.cells">{{ c }}</td>
          </tr>
        </tbody>
      </table>
      <p class="muted small">新しいものから50件までを表示しています。すべての回答は {{ AppNames.of("smohua") }} で見られます。</p>
    </div>
  </div>
</template>

<style scoped>
.smohua-form { border: 1px solid var(--line); border-radius: 8px; padding: .6rem .8rem; font-size: .9rem; }
.note { margin: 0; }
.head { display: flex; align-items: center; gap: .5rem; flex-wrap: wrap; }
.title { font-weight: 600; overflow-wrap: anywhere; }
.badge { font-size: .72rem; padding: .05rem .5rem; border-radius: 999px; background: var(--hover); white-space: nowrap; }
.grow { flex: 1; }
.seg button { border-radius: 0; font-size: .8rem; }
.seg button:first-child { border-radius: 6px 0 0 6px; }
.seg button:last-child { border-radius: 0 6px 6px 0; border-left: none; }
.seg button.on { background: var(--accent); border-color: var(--accent); color: #fff; }
.body { margin-top: .6rem; border-top: 1px solid var(--line); padding-top: .5rem; }
.q { margin-bottom: .8rem; }
.q-head { margin-bottom: .2rem; }
.bar-row { display: grid; grid-template-columns: minmax(5rem, 12rem) 1fr 3rem; gap: .5rem; align-items: center; padding: .1rem 0; }
.bar-label { overflow-wrap: anywhere; }
.bar-track { background: var(--hover); border-radius: 4px; height: .9rem; overflow: hidden; }
.bar-fill { display: block; height: 100%; background: var(--accent); border-radius: 4px; min-width: 2px; }
.bar-num { text-align: right; }
.texts { margin: .2rem 0 0; padding-left: 1.2rem; }
.texts li { white-space: pre-wrap; overflow-wrap: anywhere; }
.table { overflow-x: auto; }
table { border-collapse: collapse; width: 100%; font-size: .85rem; }
th, td { text-align: left; padding: .3rem .5rem; border-bottom: 1px solid var(--line); vertical-align: top; }
th { color: var(--muted); font-weight: 600; white-space: nowrap; }
td { max-width: 16rem; overflow-wrap: anywhere; }
.nowrap { white-space: nowrap; }
.small { font-size: .78rem; }
</style>
