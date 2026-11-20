<script lang="scala">
import sbirds.web.Dom
import io.circe.Json
import io.circe.syntax.*
import skiwi.web.*
import skiwi.web.model.*
import skiwi.web.parts.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// ワークスペースの自動化ルール（きっかけ → 動作）
case class Props(wid: String, name: String, onClose: Unit => Unit)

// 入力欄の値. 選択の「なし」は ""
case class State(rules: RV[Option[List[Automation]]] = RV(None),
                 hooks: RV[List[Webhook]] = RV(Nil),
                 error: RV[String] = RV(""),
                 busy: RV[Boolean] = RV(false),
                 editingId: RV[Option[String]] = RV(None),
                 open: RV[Boolean] = RV(false),
                 fName: RV[String] = RV(""),
                 // きっかけ
                 tType: RV[String] = RV("status_changed"),
                 tUnder: RV[Option[String]] = RV(None),
                 tTo: RV[String] = RV(""),
                 tLabel: RV[String] = RV(""),
                 tDatabase: RV[String] = RV(""),
                 tProp: RV[String] = RV(""),
                 tValue: RV[String] = RV(""),
                 tOffset: RV[Int] = RV(0),
                 tUnlessProp: RV[String] = RV(""),
                 tUnlessValue: RV[String] = RV(""),
                 // 動作
                 aType: RV[String] = RV("notify"),
                 aTo: RV[String] = RV("owner"),
                 aUsers: RV[List[String]] = RV(Nil),
                 aProp: RV[String] = RV(""),
                 aWebhook: RV[String] = RV(""),
                 aStatus: RV[String] = RV(""),
                 aLabel: RV[String] = RV(""),
                 aMessage: RV[String] = RV(""),
                 userNames: RV[Map[String, String]] = RV(Map.empty),
                 dbProps: RV[List[DbProp]] = RV(Nil),
                 uq: RV[String] = RV(""),
                 ufound: RV[List[DirectoryEntry]] = RV(Nil),
                 userSearch: DebouncedFn[() => Unit] = useDebounce[() => Unit](250)(_()),
                 latest: LatestFn = useLatest())

def initState() = State()

def load(s: State, p: Props): Unit =
  DialogsApi.automations(p.wid).onComplete {
    case Success(r) =>
      s.rules := Some(r)
      DialogsApi.webhooks(p.wid).recover(_ => Nil).foreach(s.hooks := _)
    case Failure(e) => s.error := e.getMessage
  }

def mounted(s: State, props: Props): Unit = {
  load(s, props)
  def loadProps(id: String): Unit = {
    s.dbProps := Nil
    if (id.nonEmpty) DialogsApi.dbProps(id).foreach(ps => if (s.tDatabase.v == id) s.dbProps := ps) // 見られないときは空のまま
  }
  watch(s.tDatabase, false)((_, id) => loadProps(id))
}

val Trigger: List[(String, String)] = List(
  "status_changed" -> "ページの状態が変わったとき", "label_added" -> "ラベルが付いたとき", "page_created" -> "ページが作られたとき",
  "value_changed" -> "データベースの値が変わったとき", "date_reached" -> "データベースの日付になったとき")

val Action: List[(String, String)] = List(
  "notify" -> "通知する（画面内とメール）", "webhook" -> "外部へ送る（Webhook）", "set_status" -> "状態を変える", "add_label" -> "ラベルを付ける")

def statusLabel(st: String): String = Page.StatusLabel.getOrElse(st, st)

def databases: List[Page] = Store.pages.v.filter(_.isDatabase)

def pageTitle(id: Option[String]): String = id.flatMap(Store.byId).map(_.title).filter(_.nonEmpty).getOrElse("（見られないページ）")

def propOf(s: State, id: String): Option[DbProp] = s.dbProps.v.find(_.id == id)

def propType(s: State, id: String): String = propOf(s, id).fold("")(_.`type`)

def options(s: State, id: String): List[DbOption] = propOf(s, id).flatMap(_.options).getOrElse(Nil)

def valueProps(s: State): List[DbProp] =
  s.dbProps.v.filter(p => Set("select", "checkbox", "text", "number", "multi_select", "person", "date").contains(p.`type`))

def dateProps(s: State): List[DbProp] = s.dbProps.v.filter(_.`type` == "date")

def condProps(s: State): List[DbProp] = s.dbProps.v.filter(p => p.`type` == "select" || p.`type` == "checkbox")

def personProps(s: State): List[DbProp] = s.dbProps.v.filter(_.`type` == "person")

def startNew(s: State): Unit = {
  s.editingId := None
  s.fName := ""
  fill(s, AutoTrigger("status_changed", to = Some("review")), AutoAction("notify", to = Some("owner")))
  s.open := true
}

def startEdit(s: State, r: Automation): Unit = {
  s.editingId := Some(r.id)
  s.fName := r.name
  fill(s, r.trigger, r.action)
  s.open := true
}

private def fill(s: State, t: AutoTrigger, a: AutoAction): Unit = {
  s.tType := t.`type`
  s.tUnder := t.under
  s.tTo := t.to.getOrElse("")
  s.tLabel := t.label.getOrElse("")
  s.tDatabase := t.database.getOrElse("")
  s.tProp := t.prop.getOrElse("")
  s.tValue := t.value.getOrElse("")
  s.tOffset := t.offset.getOrElse(0)
  s.tUnlessProp := t.unlessProp.getOrElse("")
  s.tUnlessValue := t.unlessValue.getOrElse("")
  s.aType := a.`type`
  s.aTo := a.to.getOrElse("owner")
  s.aUsers := a.users.getOrElse(Nil)
  s.aProp := a.prop.getOrElse("")
  s.aWebhook := a.webhook.getOrElse("")
  s.aStatus := a.status.getOrElse("")
  s.aLabel := a.label.getOrElse("")
  s.aMessage := a.message.getOrElse("")
}

def pickUnder(s: State): Unit = Host.pickPage().foreach(_.foreach(p => s.tUnder := Some(p.id)))

def onUserSearch(s: State, value: String): Unit =
  s.userSearch(() => s.latest(DialogsApi.users(value)).foreach(s.ufound := _))

def addUser(s: State, u: DirectoryEntry): Unit = {
  s.userNames := s.userNames.v + (u.id -> u.name)
  if (!s.aUsers.v.contains(u.id)) s.aUsers := s.aUsers.v :+ u.id
  s.uq := ""
  s.ufound := Nil
}

private def opt(v: String): Option[String] = Some(v).filter(_.nonEmpty)

/** 送る内容（きっかけの種類で使わない項目は消す） */
def clean(s: State): (Json, Json) = {
  val tt = s.tType.v
  val dbTrigger = tt == "value_changed" || tt == "date_reached"
  val unless = tt == "date_reached" && s.tUnlessProp.v.nonEmpty && s.tUnlessValue.v.nonEmpty
  val trig = Api.obj(
    "type" -> tt.asJson,
    "under" -> s.tUnder.v.asJson,
    "to" -> (if (tt == "status_changed") opt(s.tTo.v) else None).asJson,
    "label" -> (if (tt == "label_added") Some(s.tLabel.v.trim) else None).asJson,
    "database" -> (if (dbTrigger) opt(s.tDatabase.v) else None).asJson,
    "prop" -> (if (dbTrigger) opt(s.tProp.v) else None).asJson,
    "value" -> (if (tt == "value_changed") opt(s.tValue.v) else None).asJson,
    "offset" -> (if (tt == "date_reached") Some(s.tOffset.v) else None).asJson,
    "unlessProp" -> (if (unless) Some(s.tUnlessProp.v) else None).asJson,
    "unlessValue" -> (if (unless) Some(s.tUnlessValue.v) else None).asJson)
  val at = s.aType.v
  val act = Api.obj(
    "type" -> at.asJson,
    "message" -> Dlg.nonBlank(s.aMessage.v).asJson,
    "to" -> (if (at == "notify") Some(s.aTo.v) else None).asJson,
    "users" -> (if (at == "notify" && s.aTo.v == "users") Some(s.aUsers.v) else None).asJson,
    "prop" -> (if (at == "notify" && s.aTo.v == "prop") opt(s.aProp.v) else None).asJson,
    "webhook" -> (if (at == "webhook") opt(s.aWebhook.v) else None).asJson,
    "label" -> (if (at == "add_label") Some(s.aLabel.v.trim) else None).asJson)
  // 状態を変える: 「なし」は null で送る
  (trig, if (at == "set_status") act.mapObject(_.add("status", opt(s.aStatus.v).asJson)) else act)
}

def label(list: List[(String, String)], k: String): String = list.toMap.getOrElse(k, k)

def save(s: State, p: Props): Unit = {
  s.error := ""
  s.busy := true
  val (trig, act) = clean(s)
  val name = Dlg.nonBlank(s.fName.v).getOrElse(s"${label(Trigger, s.tType.v)} → ${label(Action, s.aType.v)}")
  val body = Json.obj("name" -> name.asJson, "trigger" -> trig, "action" -> act)
  val f = s.editingId.v.fold(DialogsApi.createAutomation(p.wid, body))(id => DialogsApi.updateAutomation(id, body))
  f.onComplete { r =>
    s.busy := false
    r match {
      case Success(_) =>
        s.open := false
        load(s, p)
      case Failure(e) => s.error := message(e)
    }
  }
}

def message(e: Throwable): String = {
  val m = e.getMessage
  if (m == "通知先を指定してください") "通知する相手を選んでください"
  else if (m.startsWith("trigger needs a database")) "データベースと列を選んでください"
  else if (m == "きっかけのラベルを指定してください") "ラベルを入力してください"
  else if (m == "アクションが正しくありません") "動作の設定が足りません"
  else m
}

def toggle(s: State, p: Props, r: Automation): Unit =
  DialogsApi.updateAutomation(r.id, Json.obj("enabled" -> (!r.enabled).asJson)).foreach(_ => load(s, p))

def remove(s: State, p: Props, r: Automation): Unit =
  Dialog.confirm(s"ルール「${r.name}」を削除しますか？", okLabel = "削除", danger = true).foreach { ok =>
    if (ok) DialogsApi.removeAutomation(r.id).foreach(_ => load(s, p))
  }

/** ルールの説明 */
def describe(s: State, r: Automation): String = {
  val x = r.trigger
  val y = r.action
  var when = label(Trigger, x.`type`)
  if (x.`type` == "status_changed") x.to.foreach(t => when = s"状態が「${statusLabel(t)}」になったとき")
  if (x.`type` == "label_added") when = s"ラベル「${x.label.getOrElse("")}」が付いたとき"
  if (x.`type` == "value_changed") when = s"「${pageTitle(x.database)}」の値が変わったとき"
  if (x.`type` == "date_reached") {
    val off = x.offset.getOrElse(0)
    when = s"「${pageTitle(x.database)}」の日付の${if (off == 0) "当日" else if (off < 0) s"${-off}日前" else s"${off}日後"}"
  }
  x.under.foreach(u => when += s"（「${pageTitle(Some(u))}」の下）")
  val to = y.to match {
    case Some("owner") => "所有者"
    case Some("watchers") => "ウォッチしている人"
    case Some("prop") => "項目のユーザー"
    case _ => s"${y.users.fold(0)(_.size)}人"
  }
  val what = y.`type` match {
    case "notify" => s"${to}に通知"
    case "webhook" => s"「${s.hooks.v.find(h => y.webhook.contains(h.id)).fold("Webhook")(_.name)}」へ送る"
    case "set_status" => s"状態を「${y.status.fold("なし")(statusLabel)}」に"
    case _ => s"ラベル「${y.label.getOrElse("")}」を付ける"
  }
  s"$when → $what"
}

def lastRun(r: Automation): String = r.lastRunAt.fold("まだ動いていません")(at => s"最後に動いた日時: ${Ui.date(at)}（${r.runCount}回）")

def target(s: State): String = s.tUnder.v.fold("ワークスペースのすべてのページ")(u => s"「${pageTitle(Some(u))}」とその下のページ")

def offsets: List[(Int, String)] =
  List(-7, -3, -2, -1).map(n => n -> s"${-n}日前") ++ List(0 -> "当日") ++ List(1, 2, 3, 7).map(n => n -> s"${n}日後")

def userName(s: State, id: String): String = s.userNames.v.getOrElse(id, id.take(8))

def title(p: Props): String = s"⚙ 自動化ルール — ${p.name}"
</script>

<template>
  <Modal label="自動化ルール" :title="title(props)" testid="automations" :width="680" :max-height="86" :top="6" @close="props.onClose(())">
    <template #head><HelpLink to="automation#自動化ルール" /></template>
    <p class="note">「きっかけ」が起きたら「動作」を自動で行います。例: 状態がレビュー中になったら所有者に通知／期限の前日に担当者に通知／ラベル「社外」が付いたら Slack に送る。動作で変えたページで、ほかのルールが続けて動くことはありません。</p>
    <p v-if="s.error.v.nonEmpty" class="error" data-testid="automation-error">{{ s.error }}</p>

    <ul v-if="s.rules.v.isDefined" class="list">
      <li v-if="s.rules.v.exists(_.isEmpty) && !s.open.v" class="muted">まだルールがありません。</li>
      <li v-for="r in s.rules.v.getOrElse(Nil)" :key="r.id" :class='List("rule", "off" -> !r.enabled)' data-testid="automation-item">
        <div class="r-head">
          <label class="en"><input type="checkbox" :checked="r.enabled" data-testid="automation-enabled" @change="toggle(s, props, r)"></label>
          <b>{{ r.name }}</b>
          <span class="spacer"></span>
          <button class="link" @click="startEdit(s, r)">編集</button>
          <button class="link danger" @click="remove(s, props, r)">削除</button>
        </div>
        <div class="desc">{{ describe(s, r) }}</div>
        <div class="muted small">
          {{ lastRun(r) }}
          <span v-if="r.lastError.isDefined" class="error"> ⚠ {{ r.lastError.getOrElse("") }}</span>
        </div>
      </li>
    </ul>

    <form v-if="s.open.v" class="form" data-testid="automation-form" @submit.prevent="save(s, props)">
      <label class="col"><span>名前</span><input v-model="s.fName" maxlength="100" placeholder="例: レビュー依頼を所有者に知らせる" data-testid="automation-name"></label>

      <fieldset>
        <legend>きっかけ</legend>
        <select v-model="s.tType" data-testid="automation-trigger">
          <option v-for="k in Trigger" :key="k._1" :value="k._1" :selected="s.tType.v == k._1">{{ k._2 }}</option>
        </select>
        <div v-if='s.tType.v == "status_changed"' class="row">
          状態が
          <select v-model="s.tTo" data-testid="automation-to-status">
            <option value="" :selected="s.tTo.v.isEmpty">（どれでも）</option>
            <option v-for="st in Page.Statuses" :key="st" :value="st" :selected="s.tTo.v == st">{{ statusLabel(st) }}</option>
          </select>
          になったとき
        </div>
        <div v-if='s.tType.v == "label_added"' class="row">ラベル <input v-model="s.tLabel" placeholder="例: 社外" data-testid="automation-label"> が付いたとき</div>
        <template v-if='s.tType.v == "value_changed" || s.tType.v == "date_reached"'>
          <div class="row">
            データベース
            <select v-model="s.tDatabase" data-testid="automation-db">
              <option value="" disabled :selected="s.tDatabase.v.isEmpty">（選ぶ）</option>
              <option v-for="d in databases" :key="d.id" :value="d.id" :selected="s.tDatabase.v == d.id">📊 {{ Dlg.untitled(d.title) }}</option>
            </select>
          </div>
          <div v-if='s.tType.v == "value_changed"' class="row">
            列
            <select v-model="s.tProp" data-testid="automation-prop">
              <option v-for="pp in valueProps(s)" :key="pp.id" :value="pp.id" :selected="s.tProp.v == pp.id">{{ pp.name }}</option>
            </select>
            が
            <select v-if='propType(s, s.tProp.v) == "select" || propType(s, s.tProp.v) == "multi_select"' v-model="s.tValue" data-testid="automation-value">
              <option value="" :selected="s.tValue.v.isEmpty">（どれでも）</option>
              <option v-for="o in options(s, s.tProp.v)" :key="o.id" :value="o.id" :selected="s.tValue.v == o.id">{{ o.name }}</option>
            </select>
            <select v-else-if='propType(s, s.tProp.v) == "checkbox"' v-model="s.tValue">
              <option value="">（どちらでも）</option><option value="true">✓ あり</option><option value="false">なし</option>
            </select>
            <input v-else v-model="s.tValue" placeholder="（空欄: どの値でも）">
            になったとき
          </div>
          <template v-else>
            <div class="row">
              日付の列
              <select v-model="s.tProp" data-testid="automation-date-prop">
                <option v-for="pp in dateProps(s)" :key="pp.id" :value="pp.id" :selected="s.tProp.v == pp.id">{{ pp.name }}</option>
              </select>
              の
              <select v-model="s.tOffset" data-testid="automation-offset">
                <option v-for="n in offsets" :key="n._1" :value="n._1" :selected="s.tOffset.v == n._1">{{ n._2 }}</option>
              </select>
              （1時間ごとに確認）
            </div>
            <div class="row">
              ただし
              <select v-model="s.tUnlessProp">
                <option value="" :selected="s.tUnlessProp.v.isEmpty">（条件なし）</option>
                <option v-for="pp in condProps(s)" :key="pp.id" :value="pp.id" :selected="s.tUnlessProp.v == pp.id">{{ pp.name }}</option>
              </select>
              <template v-if="s.tUnlessProp.v.nonEmpty">
                が
                <select v-if='propType(s, s.tUnlessProp.v) == "select"' v-model="s.tUnlessValue">
                  <option v-for="o in options(s, s.tUnlessProp.v)" :key="o.id" :value="o.id" :selected="s.tUnlessValue.v == o.id">{{ o.name }}</option>
                </select>
                <select v-else v-model="s.tUnlessValue"><option value="true">✓ あり</option><option value="false">なし</option></select>
                の項目は除く
              </template>
            </div>
          </template>
        </template>
        <div class="row">
          対象: {{ target(s) }}
          <button type="button" class="link" @click="pickUnder(s)">ページを選ぶ</button>
          <button v-if="s.tUnder.v.isDefined" type="button" class="link" @click="s.tUnder := None">すべてに戻す</button>
        </div>
      </fieldset>

      <fieldset>
        <legend>動作</legend>
        <select v-model="s.aType" data-testid="automation-action">
          <option v-for="k in Action" :key="k._1" :value="k._1" :selected="s.aType.v == k._1">{{ k._2 }}</option>
        </select>
        <template v-if='s.aType.v == "notify"'>
          <div class="row">
            通知する相手
            <select v-model="s.aTo" data-testid="automation-notify-to">
              <option value="owner">ページの所有者</option>
              <option value="watchers">ページをウォッチしている人</option>
              <option value="users">指定した人</option>
              <option value="prop" :disabled="personProps(s).isEmpty">データベースのユーザーの列（例: 担当者）</option>
            </select>
            <select v-if='s.aTo.v == "prop"' v-model="s.aProp" data-testid="automation-notify-prop">
              <option v-for="pp in personProps(s)" :key="pp.id" :value="pp.id" :selected="s.aProp.v == pp.id">{{ pp.name }}</option>
            </select>
          </div>
          <div v-if='s.aTo.v == "users"' class="col">
            <ul class="chips">
              <li v-for="u in s.aUsers" :key="u">👤 {{ userName(s, u) }}<button type="button" class="x" @click="s.aUsers := s.aUsers.v.filterNot(_ == u)">×</button></li>
            </ul>
            <input v-model="s.uq" placeholder="名前・メールで検索して追加" data-testid="automation-user-search" @input="onUserSearch(s, Dom.value(event))">
            <ul v-if="s.ufound.v.nonEmpty" class="found"><li v-for="u in s.ufound" :key="u.id"><button type="button" data-testid="automation-user-add" @click="addUser(s, u)">👤 {{ u.name }}</button></li></ul>
          </div>
        </template>
        <div v-if='s.aType.v == "webhook"' class="row">
          送り先
          <select v-model="s.aWebhook" data-testid="automation-webhook">
            <option v-for="h in s.hooks" :key="h.id" :value="h.id" :selected="s.aWebhook.v == h.id">{{ h.name }}</option>
          </select>
          <span v-if="s.hooks.v.isEmpty" class="muted">（先に「外部への通知」で送り先を追加してください）</span>
        </div>
        <div v-if='s.aType.v == "set_status"' class="row">
          状態を
          <select v-model="s.aStatus"><option value="" :selected="s.aStatus.v.isEmpty">（なし）</option><option v-for="st in Page.Statuses" :key="st" :value="st" :selected="s.aStatus.v == st">{{ statusLabel(st) }}</option></select>
          にする
        </div>
        <div v-if='s.aType.v == "add_label"' class="row">ラベル <input v-model="s.aLabel" placeholder="例: 要確認"> を付ける</div>
        <label v-if='s.aType.v == "notify" || s.aType.v == "webhook"' class="col">
          <span>文面（任意。{title}=ページ名、{actor}=操作した人、{date}=日付、{status}=状態）</span>
          <input v-model="s.aMessage" maxlength="1000" placeholder="例: 「{title}」のレビューをお願いします" data-testid="automation-message">
        </label>
      </fieldset>
      <div class="f-acts">
        <button type="button" @click="s.open := false">やめる</button>
        <button type="submit" class="primary" :disabled="s.busy.v" data-testid="automation-save">{{ if (s.editingId.v.isDefined) "保存" else "追加" }}</button>
      </div>
    </form>
    <button v-else class="add" data-testid="automation-add" @click="startNew(s)">＋ ルールを追加</button>
  </Modal>
</template>

<style scoped>
.x { padding: 0 4px; }
.note { line-height: 1.55; }
.list { list-style: none; padding: 0; margin: .4rem 0; }
.rule { border: 1px solid var(--line); border-radius: 8px; padding: .45rem .7rem; margin-bottom: .45rem; font-size: .85rem; }
.rule.off { opacity: .55; }
.r-head { display: flex; align-items: center; gap: .4rem; }
.desc { margin: .15rem 0; }
.spacer { flex: 1; }
.link { border: none; background: none; color: var(--accent); padding: 0 .2rem; font-size: .8rem; }
.danger:hover { color: var(--danger); }
.form { display: flex; flex-direction: column; gap: .6rem; border: 1px solid var(--line); border-radius: 8px; padding: .6rem .7rem; font-size: .85rem; }
fieldset { border: 1px solid var(--line); border-radius: 8px; padding: .4rem .6rem .6rem; display: flex; flex-direction: column; gap: .4rem; }
legend { font-size: .78rem; color: var(--muted); padding: 0 .3rem; }
.row { display: flex; align-items: center; gap: .35rem; flex-wrap: wrap; }
.col { display: flex; flex-direction: column; gap: .25rem; }
.col span { font-size: .78rem; color: var(--muted); }
select, input { font: inherit; font-size: .84rem; padding: .2rem .4rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; }
.chips { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: .3rem; }
.chips li { background: var(--hover); border-radius: 12px; padding: 1px 4px 1px 8px; font-size: .8rem; }
.found { list-style: none; margin: 0; padding: 0; border: 1px solid var(--line); border-radius: 6px; }
.found button { width: 100%; text-align: left; border: none; padding: .25rem .5rem; }
.f-acts { display: flex; justify-content: flex-end; gap: .4rem; }
.add { margin-top: .3rem; }
.error { font-size: .82rem; }
</style>
