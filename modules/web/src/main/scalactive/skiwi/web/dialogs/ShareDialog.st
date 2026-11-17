<script lang="scala">
import skiwi.web.*
import skiwi.web.model.*
import skiwi.web.parts.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.{Failure, Success}

// ページまたはワークスペースの共有設定（kind: page / workspace）. ユーザー・グループ単位で閲覧/編集を付与する.
// id: ページまたはワークスペースのID. テンプレートの :id は子のルート要素の属性になるため、.st からは :target-id で渡す
case class Props(kind: String, id: String = "", title: String, onClose: Unit => Unit, targetId: String = "")

/** 一覧の1行（役割・期限はその場で変える） */
case class Entry(principalType: String, principalId: String, name: Option[String], detail: Option[String],
                 role: RV[String], expiresAt: RV[Option[String]]) {

  def toAcl: AclEntry = AclEntry(principalType, principalId, role.v, name, detail, expiresAt.v)

}

object Entry {

  def of(e: AclEntry): Entry = Entry(e.principalType, e.principalId, e.name, e.detail, RV(e.role), RV(e.expiresAt))

}

case class State(loading: RV[Boolean] = RV(true),
                 saving: RV[Boolean] = RV(false),
                 error: RV[String] = RV(""),
                 inherit: RV[Boolean] = RV(true),
                 defaultRole: RV[String] = RV(""),
                 entries: RV[List[Entry]] = RV(Nil),
                 query: RV[String] = RV(""),
                 found: RV[List[DirectoryEntry]] = RV(Nil),
                 search: Debounced = new Debounced(250),
                 // 閲覧・編集できる人（権限の継承・グループを展開した結果. 保存済みの設定で計算する）
                 audience: RV[Option[List[AudienceEntry]]] = RV(None),
                 audienceOpen: RV[Boolean] = RV(false),
                 audienceQ: RV[String] = RV(""),
                 // 閲覧権限の申請（ページのみ）. reqDays: 許可する権限の期限（日数. 0=なし）
                 requests: RV[List[AccessRequest]] = RV(Nil),
                 reqDays: RV[Int] = RV(0),
                 // ゲスト向けの公開リンク（ページのみ. 設定は PublicShareSection がその場で反映する）
                 pub: RV[Option[ShareInfo]] = RV(None))

def initState() = State()

def idOf(p: Props): String = if (p.targetId.nonEmpty) p.targetId else p.id

def mounted(s: State, props: Props): Unit = {
  val id = idOf(props)
  val load =
    if (props.kind == "page") {
      DialogsApi.share(id).foreach(x => s.pub := Some(x))
      DialogsApi.accessRequests(id).foreach(s.requests := _)
      DialogsApi.pageAcl(id).map { a =>
        s.inherit := a.inherit
        s.entries := a.entries.map(Entry.of)
      }
    } else DialogsApi.workspaceAcl(id).map { a =>
      s.defaultRole := a.defaultRole.getOrElse("")
      s.entries := a.entries.map(Entry.of)
    }
  load.onComplete { r =>
    r.failed.foreach(e => s.error := e.getMessage)
    s.loading := false
  }
}

def toggleAudience(s: State, p: Props): Unit = {
  s.audienceOpen := !s.audienceOpen.v
  if (s.audienceOpen.v && s.audience.v.isEmpty) DialogsApi.audience(idOf(p)).onComplete {
    case Success(a) => s.audience := Some(a)
    case Failure(e) => s.error := e.getMessage
  }
}

def audienceShown(s: State): List[AudienceEntry] = {
  val q = s.audienceQ.v.trim.toLowerCase
  val xs = s.audience.v.getOrElse(Nil)
  if (q.isEmpty) xs
  else xs.filter(a => a.name.toLowerCase.contains(q) || a.email.getOrElse("").toLowerCase.contains(q) || a.reason.toLowerCase.contains(q))
}

def editors(s: State): Int = s.audience.v.getOrElse(Nil).count(_.role == "edit")

def audienceHead(s: State): String = s.audience.v.fold("")(a => s"（${a.size}人・うち編集 ${editors(s)}人）")

/** action: approve / deny */
def decide(s: State, r: AccessRequest, action: String, role: Option[String]): Unit = {
  s.error := ""
  val until = if (action == "approve" && s.reqDays.v > 0) Some(Dlg.daysLater(s.reqDays.v)) else None
  DialogsApi.decide(r.id, action, role, until).onComplete {
    case Failure(e) => s.error := e.getMessage
    case Success(res) =>
      s.requests := s.requests.v.filterNot(_.id == r.id)
      // 承認した人を一覧にも反映する（保存前の編集は残す）
      if (action == "approve") res.role.foreach { role =>
        s.entries.v.find(x => x.principalType == "user" && x.principalId == r.userId) match {
          case Some(e) =>
            e.role := role
            if (until.isDefined && e.expiresAt.v.isDefined) e.expiresAt := until
          case None =>
            s.entries := s.entries.v :+ Entry("user", r.userId, Some(r.name), r.email, RV(role), RV(until))
        }
        s.audience := None
      }
  }
}

def onSearch(s: State, value: String): Unit = {
  val q = value.trim
  if (q.isEmpty) { s.search.cancel(); s.found := Nil }
  else s.search.run { latest =>
    Api.directory(q).foreach { r =>
      if (latest()) s.found := r.filterNot(d => s.entries.v.exists(_.principalId == d.id))
    }
  }
}

def add(s: State, d: DirectoryEntry): Unit = {
  s.entries := s.entries.v :+ Entry(d.principalType, d.id, Some(d.name), d.detail, RV("view"), RV(None))
  s.query := ""
  s.found := Nil
}

def remove(s: State, e: Entry): Unit = s.entries := s.entries.v.filterNot(_ eq e)

def save(s: State, p: Props): Unit = {
  s.saving := true
  s.error := ""
  val es = s.entries.v.map(_.toAcl)
  val f =
    if (p.kind == "page") DialogsApi.setPageAcl(idOf(p), s.inherit.v, es)
    else DialogsApi.setWorkspaceAcl(idOf(p), Dlg.nonBlank(s.defaultRole.v), es)
  f.onComplete { r =>
    s.saving := false
    r match {
      case Success(_) => p.onClose(())
      case Failure(e) => s.error := e.getMessage
    }
  }
}

def icon(principalType: String): String = if (principalType == "group") "👥" else "👤"

def roleLabel(role: String): String = if (role == "edit") "編集" else "閲覧"

def today: String = Dlg.ymd(new js.Date())

def showPublic(s: State, p: Props): Boolean = p.kind == "page" && s.pub.v.exists(_.available)

def pubShared(s: State): Boolean = s.pub.v.exists(_.share.isDefined)

def pubInherited(s: State): Option[InheritedShare] = s.pub.v.flatMap(_.inherited)
</script>

<template>
  <div class="backdrop" @click.self="props.onClose(())">
    <div class="dialog" role="dialog" aria-label="共有">
      <h2>共有 ― {{ Dlg.untitled(props.title) }} <HelpLink to="sharing" /></h2>
      <p v-if="s.loading.v" class="muted">読み込み中…</p>
      <template v-else>
        <section v-if="s.requests.v.nonEmpty" class="requests" data-testid="access-requests">
          <h3>✋ 権限の申請（{{ s.requests.v.size }}件）</h3>
          <div v-for="r in s.requests" :key="r.id" class="req" data-testid="access-request-item">
            <div class="req-head">
              <span>👤 {{ r.name }} <span class="muted">{{ r.email.getOrElse("") }}</span></span>
              <span class="muted">{{ roleLabel(r.role) }}を申請・{{ Ui.date(r.createdAt) }}</span>
            </div>
            <p v-if="r.message.exists(_.nonEmpty)" class="req-msg">{{ r.message.getOrElse("") }}</p>
            <div class="req-actions">
              <button data-testid="req-approve-view" @click='decide(s, r, "approve", Some("view"))'>閲覧を許可</button>
              <button data-testid="req-approve-edit" @click='decide(s, r, "approve", Some("edit"))'>編集を許可</button>
              <button class="link danger" data-testid="req-deny" @click='decide(s, r, "deny", None)'>却下</button>
            </div>
          </div>
          <label class="row">
            許可する権限の期限:
            <select v-model="s.reqDays" data-testid="req-expiry">
              <option value="0">なし（無期限）</option>
              <option value="7">1週間</option>
              <option value="30">1か月</option>
              <option value="90">3か月</option>
            </select>
          </label>
          <p class="note">許可すると、このページの個別の共有設定に追加します（すぐに反映されます）。期限を過ぎると自動で外れます。</p>
        </section>
        <label v-if='props.kind == "page"' class="row">
          <input v-model="s.inherit" type="checkbox">
          親ページ・ワークスペースの権限を引き継ぐ
        </label>
        <p v-if='props.kind == "page" && !s.inherit.v' class="note">引き継がない場合、下の一覧の人・グループだけがアクセスできます。</p>
        <label v-if='props.kind == "workspace"' class="row">
          全員（ログインできる人）の既定:
          <select v-model="s.defaultRole">
            <option value="">アクセス不可</option>
            <option value="view">閲覧</option>
            <option value="edit">編集</option>
          </select>
        </label>

        <div class="search">
          <input v-model="s.query" placeholder="ユーザー名・メール・グループ名で追加…" @input="onSearch(s, Dlg.value(event))">
          <ul v-if="s.found.v.nonEmpty" class="found">
            <li v-for="d in s.found" :key="d.id" @click="add(s, d)">
              {{ icon(d.principalType) }} {{ d.name }} <span class="muted">{{ d.detail.getOrElse("") }}</span>
            </li>
          </ul>
        </div>

        <table class="entries">
          <tr v-for="e in s.entries" :key="e.principalId">
            <td>{{ icon(e.principalType) }} {{ e.name.getOrElse(e.principalId) }} <span class="muted">{{ e.detail.getOrElse("") }}</span></td>
            <td>
              <select v-model="e.role">
                <option value="view">閲覧</option>
                <option value="edit">編集</option>
              </select>
            </td>
            <td class="exp">
              <input
                type="date"
                :value="Dlg.dateOf(e.expiresAt.v)"
                :min="today"
                title="この日の終わりに権限が外れます（空欄で無期限）"
                data-testid="acl-expiry"
                @change="e.expiresAt := Dlg.endOf(Dlg.value(event))"
              >
              <span v-if="e.expiresAt.v.isEmpty" class="muted small">無期限</span>
            </td>
            <td><button title="外す" @click="remove(s, e)">✕</button></td>
          </tr>
          <tr v-if="s.entries.v.isEmpty"><td class="muted" colspan="4">個別の共有設定はありません</td></tr>
        </table>
        <p class="note">新しく共有した相手には、メールで通知します。</p>

        <section v-if='props.kind == "page"' class="audience" data-testid="audience">
          <button class="aud-head" @click="toggleAudience(s, props)">{{ if (s.audienceOpen.v) "▾" else "▸" }} 👀 このページを閲覧・編集できる人{{ audienceHead(s) }}</button>
          <template v-if="s.audienceOpen.v">
            <p class="note">保存済みの設定での結果です（親ページ・ワークスペースからの継承とグループを展開しています）。</p>
            <p v-if="s.audience.v.isEmpty" class="muted">読み込み中…</p>
            <template v-else>
              <input v-if="s.audience.v.exists(_.size > 8)" v-model="s.audienceQ" class="aud-q" placeholder="名前・メール・理由で絞り込み">
              <ul class="aud-list">
                <li v-for="a in audienceShown(s)" :key="a.userId" data-testid="audience-item">
                  <span class="aud-name">👤 {{ a.name }} <span class="muted">{{ a.email.getOrElse("") }}</span></span>
                  <span :class='List("aud-role", a.role)'>{{ roleLabel(a.role) }}</span>
                  <span class="aud-why">{{ a.reason }}</span>
                </li>
              </ul>
              <p v-if="pubShared(s)" class="note">🌐 このほか、ゲスト向けの公開リンクを知っている人は、ログインせずに閲覧できます。</p>
              <p v-else-if="pubInherited(s).isDefined" class="note">🌐 このほか、親ページ「{{ Dlg.untitled(pubInherited(s).flatMap(_.title)) }}」の公開リンクを知っている人は、ログインせずに閲覧できます。</p>
            </template>
          </template>
        </section>

        <PublicShareSection v-if="showPublic(s, props)" :pub="s.pub" :page-id="idOf(props)" :title="props.title" :on-error="m => s.error := m" />
      </template>
      <p v-if="s.error.v.nonEmpty" class="error">{{ s.error }}</p>
      <div class="actions">
        <button @click="props.onClose(())">キャンセル</button>
        <button class="primary" :disabled="s.loading.v || s.saving.v" @click="save(s, props)">保存</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.backdrop { position: fixed; inset: 0; background: rgba(0,0,0,.35); display: flex; align-items: flex-start; justify-content: center; padding-top: 6vh; z-index: 50; }
.dialog { background: var(--bg); border: 1px solid var(--line); border-radius: 10px; width: min(560px, 92vw); max-height: 85vh; overflow: auto; padding: 1rem 1.2rem; box-shadow: 0 20px 50px rgba(0,0,0,.25); }
h2 { font-size: 1.1rem; margin: 0 0 .8rem; }
.row { display: flex; gap: .5rem; align-items: center; margin: .4rem 0; }
.note { font-size: .8rem; margin: .2rem 0 .6rem; }
.search { position: relative; margin: .6rem 0; }
.search input { width: 100%; padding: .35rem .6rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); }
.found { position: absolute; left: 0; right: 0; top: 100%; margin: 2px 0 0; padding: 4px; list-style: none; background: var(--bg); border: 1px solid var(--line); border-radius: 6px; max-height: 220px; overflow: auto; z-index: 2; box-shadow: 0 8px 20px rgba(0,0,0,.15); }
.found li { padding: .3rem .5rem; border-radius: 4px; cursor: pointer; }
.found li:hover { background: var(--hover); }
.entries { width: 100%; border-collapse: collapse; }
.entries td { padding: .3rem .2rem; border-bottom: 1px solid var(--line); }
.entries td:nth-child(2), .entries td:nth-child(3), .entries td:nth-child(4) { width: 1%; white-space: nowrap; }
.entries .exp input { font: inherit; font-size: .78rem; padding: 0 2px; border: 1px solid var(--line); border-radius: 4px; background: var(--bg); color: inherit; }
select { font: inherit; padding: .1rem .3rem; background: var(--bg); color: inherit; border: 1px solid var(--line); border-radius: 4px; }
.audience { margin-top: .6rem; }
.aud-head { border: none; padding: 0; font-size: .88rem; font-weight: 600; }
.aud-q { width: 100%; padding: .25rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); margin-bottom: .3rem; }
.aud-list { list-style: none; margin: 0; padding: 0; max-height: 220px; overflow: auto; border: 1px solid var(--line); border-radius: 8px; }
.aud-list li { display: grid; grid-template-columns: 1fr auto; gap: 0 .5rem; padding: .3rem .5rem; border-bottom: 1px solid var(--line); font-size: .84rem; }
.aud-list li:last-child { border-bottom: none; }
.aud-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.aud-role { font-size: .72rem; border-radius: 4px; padding: 0 6px; align-self: center; }
.aud-role.edit { background: rgba(37,99,235,.15); color: var(--accent); }
.aud-role.view { background: var(--hover); color: var(--muted); }
.aud-why { grid-column: 1 / -1; font-size: .74rem; color: var(--muted); }
button.link { border: none; background: none; color: var(--accent); padding: 0 .2rem; font-size: .85rem; }
.actions { display: flex; justify-content: flex-end; gap: .5rem; margin-top: 1rem; }
.small { font-size: .78rem; }
.requests { margin-bottom: .8rem; padding: .6rem .8rem; border: 1px solid rgba(217,119,6,.5); border-radius: 8px; background: rgba(217,119,6,.06); }
.requests h3 { font-size: .92rem; margin: 0 0 .4rem; }
.req { padding: .4rem 0; border-top: 1px solid var(--line); }
.req:first-of-type { border-top: none; }
.req-head { display: flex; flex-wrap: wrap; justify-content: space-between; gap: .3rem; font-size: .86rem; }
.req-msg { margin: .25rem 0; font-size: .84rem; white-space: pre-wrap; }
.req-actions { display: flex; gap: .4rem; align-items: center; margin-top: .25rem; }
.req-actions button { font-size: .8rem; padding: 2px 8px; }
button.link.danger { color: var(--danger); }
.primary:disabled { opacity: .6; }
.muted { font-size: .85em; }
</style>
