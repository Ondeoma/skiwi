<script lang="scala">
import skiwi.web.AppNames
import skiwi.web.Nav
import io.circe.Json
import io.circe.parser.parse
import org.scalajs.dom
import skiwi.web.Dialog

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.{Failure, Success}

// API の説明書（/openapi.json を読んで表示する）。「試す」はログイン中のこの画面の権限で実際に呼ぶ

case class Param(name: String, in: String, required: Boolean, description: String, schema: Option[Json])

case class Entry(key: String, method: String, path: String, op: Json)

case class TryResult(status: Int, text: String)

case class State(doc: RV[Option[Json]] = RV(None),
                 error: RV[String] = RV(""),
                 q: RV[String] = RV(""),
                 openKey: RV[Option[String]] = RV(None),
                 inputs: RV[Map[String, String]] = RV(Map.empty),
                 body: RV[String] = RV(""),
                 result: RV[Option[TryResult]] = RV(None),
                 busy: RV[Boolean] = RV(false))

def initState() = State()

def mounted(s: State): Unit = {
  dom.fetch("/openapi.json").toFuture.flatMap(_.text().toFuture).onComplete {
    case Success(t) => parse(t).fold(e => s.error := e.getMessage, j => s.doc := Some(j))
    case Failure(e) => s.error := e.getMessage
  }
}

// ---------------------------------------------------------------- JSON の読み取り

def str(j: Json, k: String): Option[String] = j.hcursor.get[String](k).toOption

def field(j: Json, k: String): Option[Json] = j.hcursor.downField(k).focus

def fields(j: Option[Json]): List[(String, Json)] = j.flatMap(_.asObject).map(_.toList).getOrElse(Nil)

def items(j: Option[Json]): List[Json] = j.flatMap(_.asArray).map(_.toList).getOrElse(Nil)

def docOf(s: State): Json = s.doc.v.getOrElse(Json.Null)

def info(s: State, k: String): String = field(docOf(s), "info").flatMap(str(_, k)).getOrElse("")

def jsonSchema(content: Option[Json]): Option[Json] = content.flatMap(field(_, "application/json")).flatMap(field(_, "schema"))

def resolve(doc: Json, s: Option[Json]): Option[Json] = s.flatMap { x =>
  str(x, "$ref") match {
    case Some(r) => doc.hcursor.downField("components").downField("schemas").downField(r.split('/').last).focus
    case None => Some(x)
  }
}

def summary(e: Entry): String = str(e.op, "summary").getOrElse("")

def description(e: Entry): String = str(e.op, "description").getOrElse("")

def tags(op: Json): List[String] = items(field(op, "tags")).flatMap(_.asString)

def params(e: Entry): List[Param] = items(field(e.op, "parameters")).map { p =>
  Param(str(p, "name").getOrElse(""), str(p, "in").getOrElse(""), p.hcursor.get[Boolean]("required").getOrElse(false),
    str(p, "description").getOrElse(""), field(p, "schema"))
}

def requestSchema(e: Entry): Option[Option[Json]] = field(e.op, "requestBody").map(b => jsonSchema(field(b, "content")))

/** 応答（本文のあるもの）: ステータスとスキーマ */
def responses(e: Entry): List[(String, Option[Json])] =
  fields(field(e.op, "responses")).collect { case (st, r) if field(r, "content").isDefined => (st, jsonSchema(field(r, "content"))) }

def entries(s: State): List[Entry] = {
  val all = fields(field(docOf(s), "paths")).flatMap((path, ms) => fields(Some(ms)).map((m, op) => Entry(s"$m $path", m, path, op)))
  val k = s.q.v.trim.toLowerCase
  if (k.isEmpty) all
  else all.filter(e => s"${e.method} ${e.path} ${summary(e)} ${tags(e.op).mkString(" ")}".toLowerCase.contains(k))
}

def groups(s: State): List[(String, List[Entry])] = {
  val es = entries(s)
  items(field(docOf(s), "tags")).flatMap(str(_, "name")).map(t => (t, es.filter(e => tags(e.op).contains(t)))).filter(_._2.nonEmpty)
}

def today(): String = new js.Date().toISOString()

/** スキーマから例を作る */
def example(doc: Json, s0: Option[Json], depth: Int = 0): Json = resolve(doc, s0) match {
  case None => Json.Null
  case Some(_) if depth > 4 => Json.Null
  case Some(s) =>
    items(field(s, "enum")).headOption.getOrElse {
      str(s, "type") match {
        case Some("object") => Json.fromFields(fields(field(s, "properties")).map((k, v) => k -> example(doc, Some(v), depth + 1)))
        case Some("array") => Json.arr(example(doc, field(s, "items"), depth + 1))
        case Some("integer") | Some("number") => Json.fromInt(0)
        case Some("boolean") => Json.False
        case Some("string") =>
          Json.fromString(str(s, "format") match {
            case Some("uuid") => "00000000-0000-0000-0000-000000000000"
            case Some("date-time") => today()
            case Some("date") => today().take(10)
            case _ => ""
          })
        case _ => Json.Null
      }
    }
}

/** JSON.stringify(v, null, 2) と同じ書式 */
def pretty(j: Json): String = js.JSON.stringify(js.JSON.parse(j.noSpaces), null: js.Array[js.Any], 2)

def schemaText(doc: Json, s0: Option[Json], indent: String = ""): String = resolve(doc, s0) match {
  case None => ""
  case Some(s) =>
    val tpe = str(s, "type")
    if (tpe.contains("object") && field(s, "properties").isDefined)
      "{\n" + fields(field(s, "properties")).map { (k, v) =>
        val r = resolve(doc, Some(v))
        val en = items(r.flatMap(field(_, "enum"))).flatMap(_.asString)
        val rt = r.flatMap(str(_, "type"))
        val t =
          if (en.nonEmpty) en.map(x => s"\"$x\"").mkString(" | ")
          else if (rt.contains("array")) resolve(doc, r.flatMap(field(_, "items"))).flatMap(str(_, "type")).getOrElse("object") + "[]"
          else rt.getOrElse("object")
        val nullable = if (r.exists(_.hcursor.get[Boolean]("nullable").getOrElse(false))) " | null" else ""
        val desc = str(v, "description").orElse(r.flatMap(str(_, "description"))).filter(_.nonEmpty).fold("")(d => s"  // $d")
        s"$indent  $k: $t$nullable$desc"
      }.mkString("\n") + s"\n$indent}"
    else if (tpe.contains("array")) s"[${schemaText(doc, field(s, "items"), indent)}]"
    else tpe.getOrElse("object")
}

def schemaType(s: State, p: Param): String = resolve(docOf(s), p.schema).flatMap(str(_, "type")).getOrElse("")

// ---------------------------------------------------------------- 試す

def isOpen(s: State, e: Entry): Boolean = s.openKey.v.contains(e.key)

def epCls(s: State, e: Entry): String = if (isOpen(s, e)) "ep open" else "ep"

def toggle(s: State, e: Entry): Unit =
  if (s.openKey.v.contains(e.key)) s.openKey := None
  else {
    s.openKey := Some(e.key)
    s.inputs := Map.empty
    s.result := None
    s.body := requestSchema(e).flatten.fold("")(b => pretty(example(docOf(s), Some(b))))
  }

def setInput(s: State, name: String, ev: dom.Event): Unit =
  s.inputs := s.inputs.v.updated(name, ev.target.asInstanceOf[dom.HTMLInputElement].value)

def tryIt(s: State, e: Entry): Unit = {
  val ok =
    if (e.method == "get") Future.successful(true)
    else Dialog.confirm(s"${e.method.toUpperCase} ${e.path} を実行しますか？\nあなたの権限で、実際にデータが変わります。", okLabel = "実行する")
  ok.foreach { yes =>
    if (yes) {
      var url = e.path
      val query = new dom.URLSearchParams()
      params(e).foreach { p =>
        val v = s.inputs.v.getOrElse(p.name, "")
        if (p.in == "path") url = url.replace(s"{${p.name}}", js.URIUtils.encodeURIComponent(v))
        else if (v.nonEmpty) query.set(p.name, v)
      }
      if (query.toString.nonEmpty) url += s"?$query"
      val init = new dom.RequestInit {}
      init.method = e.method.toUpperCase.asInstanceOf[dom.HttpMethod]
      init.credentials = dom.RequestCredentials.`same-origin`
      if (s.body.v.nonEmpty && e.method != "get") {
        init.headers = js.Dictionary("Content-Type" -> "application/json")
        init.body = s.body.v
      }
      s.busy := true
      dom.fetch(url, init).toFuture.flatMap(res => res.text().toFuture.map(t => (res.status, t))).onComplete {
        case Success((st, t)) =>
          val shown = parse(t).toOption.fold(t)(pretty)
          s.result := Some(TryResult(st, shown.take(20000)))
          s.busy := false
        case Failure(err) =>
          s.result := Some(TryResult(0, err.getMessage))
          s.busy := false
      }
    }
  }
}

def curl(s: State, e: Entry): String = {
  val b = requestSchema(e).fold("")(sc => s" \\\n  -H 'Content-Type: application/json' \\\n  -d '${example(docOf(s), sc).noSpaces}'")
  val noAuth = field(e.op, "security").flatMap(_.asArray).exists(_.isEmpty)
  val auth = if (noAuth) "" else " \\\n  -H 'Authorization: Bearer <APIトークン>'"
  s"curl -X ${e.method.toUpperCase} '${dom.window.location.origin}${e.path}'$auth$b"
}

def resultOk(r: TryResult): Boolean = r.status >= 200 && r.status < 300
</script>

<template>
  <div class="docs" data-testid="api-docs">
    <header>
      <a href="/" @click='Nav.link(event, "/")' class="back">← {{ AppNames.self }} に戻る</a>
      <h1>📘 API の説明書</h1>
      <p class="links"><a href="/openapi.json" target="_blank" rel="noopener">openapi.json</a>（OpenAPI 3.0。各種ツールに読み込めます）・<a href="/settings/tokens" @click='Nav.link(event, "/settings/tokens")'>APIトークンを発行</a></p>
    </header>
    <p v-if="s.error.v.nonEmpty" class="error">{{ s.error }}</p>
    <template v-else-if="s.doc.v.isDefined">
      <pre class="intro">{{ info(s, "description") }}</pre>
      <input v-model="s.q" type="search" class="filter" placeholder="🔍 絞り込み（例: database、コメント）" data-testid="api-filter">
      <section v-for="g in groups(s)" :key="g._1">
        <h2>{{ g._1 }}</h2>
        <div v-for="e in g._2" :key="e.key" :class="epCls(s, e)" data-testid="api-endpoint">
          <button class="ep-head" @click="toggle(s, e)">
            <span :class='"m " + e.method'>{{ e.method.toUpperCase }}</span>
            <code>{{ e.path }}</code>
            <span class="sum">{{ summary(e) }}</span>
          </button>
          <div v-if="isOpen(s, e)" class="ep-body">
            <p v-if="description(e).nonEmpty" class="desc">{{ description(e) }}</p>
            <table v-if="params(e).nonEmpty" class="params">
              <tr v-for="p in params(e)" :key="p.name">
                <td><code>{{ p.name }}</code><span v-if="p.required" class="req">*</span><div class="muted small">{{ if (p.in == "path") "パス" else "クエリ" }}・{{ schemaType(s, p) }}</div></td>
                <td>{{ p.description }}</td>
                <td><input :placeholder="p.name" data-testid="api-param" @input="setInput(s, p.name, event)"></td>
              </tr>
            </table>
            <template v-for="b in requestSchema(e).toList">
              <h4>送る内容（JSON）</h4>
              <pre class="schema">{{ schemaText(docOf(s), b) }}</pre>
              <textarea v-model="s.body" rows="6" spellcheck="false" data-testid="api-body"></textarea>
            </template>
            <template v-for="r in responses(e)" :key="r._1">
              <h4>応答 {{ r._1 }}</h4>
              <pre class="schema">{{ schemaText(docOf(s), r._2) }}</pre>
            </template>
            <h4>curl</h4>
            <pre class="schema">{{ curl(s, e) }}</pre>
            <div class="try">
              <button class="primary" :disabled="s.busy" data-testid="api-try" @click="tryIt(s, e)">試す（ログイン中の権限で実行）</button>
            </div>
            <div v-for="r in s.result.v.toList" class="result" data-testid="api-result">
              <b :class='if (resultOk(r)) "ok" else ""'>HTTP {{ r.status }}</b>
              <pre>{{ r.text }}</pre>
            </div>
          </div>
        </div>
      </section>
    </template>
    <p v-else class="muted">読み込み中…</p>
  </div>
</template>

<style scoped>
.docs { max-width: 960px; margin: 0 auto; padding: 1.2rem 1.5rem 4rem; }
.back { font-size: .85rem; color: var(--muted); text-decoration: none; }
h1 { font-size: 1.6rem; margin: .4rem 0; }
h2 { font-size: 1.05rem; margin: 1.6rem 0 .5rem; padding-bottom: .2rem; border-bottom: 1px solid var(--line); }
h4 { font-size: .8rem; color: var(--muted); margin: .8rem 0 .3rem; }
.links { font-size: .85rem; color: var(--muted); }
.links a { color: var(--accent); }
.intro { white-space: pre-wrap; font: inherit; font-size: .85rem; background: var(--side); border: 1px solid var(--line); border-radius: 8px; padding: .6rem .8rem; }
.filter { width: 100%; font: inherit; padding: .4rem .7rem; border: 1px solid var(--line); border-radius: 8px; background: var(--bg); color: inherit; margin-top: .6rem; }
.ep { border: 1px solid var(--line); border-radius: 8px; margin-bottom: .4rem; overflow: hidden; }
.ep-head { width: 100%; display: flex; align-items: center; gap: .6rem; border: none; border-radius: 0; padding: .45rem .6rem; text-align: left; background: none; }
.ep-head:hover, .ep.open .ep-head { background: var(--hover); }
.m { font-size: .7rem; font-weight: 700; min-width: 3.6rem; text-align: center; border-radius: 4px; padding: 1px 0; color: #fff; }
.m.get { background: #2563eb; } .m.post { background: #16a34a; } .m.put { background: #d97706; } .m.patch { background: #9333ea; } .m.delete { background: var(--danger); }
code { font-size: .85rem; }
.sum { color: var(--muted); font-size: .85rem; margin-left: auto; text-align: right; }
.ep-body { padding: .4rem .8rem .8rem; font-size: .85rem; }
.desc { color: var(--muted); }
.params { width: 100%; border-collapse: collapse; }
.params td { border-bottom: 1px solid var(--line); padding: .3rem; vertical-align: top; }
.params input { font: inherit; font-size: .82rem; padding: .2rem .4rem; border: 1px solid var(--line); border-radius: 5px; background: var(--bg); color: inherit; width: 100%; min-width: 10rem; }
.req { color: var(--danger); margin-left: 2px; }
.schema, .result pre { background: var(--side); border: 1px solid var(--line); border-radius: 6px; padding: .5rem; font-size: .78rem; white-space: pre-wrap; word-break: break-all; margin: 0; }
textarea { width: 100%; box-sizing: border-box; font-family: ui-monospace, monospace; font-size: .8rem; padding: .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; margin-top: .4rem; }
.try { margin-top: .6rem; }
.result { margin-top: .6rem; }
.result b { color: var(--danger); } .result b.ok { color: #16a34a; }
.result pre { max-height: 24rem; overflow: auto; margin-top: .3rem; }
.small { font-size: .72rem; }
</style>
