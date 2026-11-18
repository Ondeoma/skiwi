<script lang="scala">
import io.circe.Json
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.web.*

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 共有画面の「ゲストに公開」. 設定は保存ボタンを待たずにその場で反映する.
// pub: 公開の状態（親と共有し、ここで書き換える）. 失敗は onError で親に知らせる（"" で消す）
case class Props(pageId: String, title: String, pub: RV[Option[ShareInfo]], onError: String => Unit = _ => ())

case class State(busy: RV[Boolean] = RV(false),
                 guestHtmlOpen: RV[Boolean] = RV(false),
                 copied: RV[Boolean] = RV(false),
                 releaseNote: RV[String] = RV(""),
                 releaseReload: RV[Int] = RV(0),
                 slugDraft: RV[String] = RV(""),
                 pageSlugDraft: RV[String] = RV(""),
                 descDraft: RV[String] = RV(""))

def initState() = State()

def info(p: Props): Option[ShareInfo] = p.pub.v

/** 公開の設定（公開していなければ空の値. 表示は hasShare で分ける） */
def sh(p: Props): PublicShare = p.pub.v.flatMap(_.share).getOrElse(PublicShare.empty)

def hasShare(p: Props): Boolean = p.pub.v.exists(_.share.isDefined)

def inherited(p: Props): Option[InheritedShare] = p.pub.v.flatMap(_.inherited)

def link(p: Props): String = p.pub.v.flatMap(_.share).fold("")(x => DialogsApi.shareUrl(x.slug.getOrElse(x.token)))

/** 親ページの公開リンクでの、このページのURL */
def inheritedLink(p: Props): String = p.pub.v.flatMap(i => i.inherited.map(x =>
  DialogsApi.shareUrl(x.slug.orElse(x.token).getOrElse(""), i.pageSlug.filter(_.nonEmpty).getOrElse(p.pageId)))).getOrElse("")

def mounted(s: State, props: Props): Unit = {
  def sync(o: Option[ShareInfo], n: Option[ShareInfo], force: Boolean): Unit = {
    val slug = (x: Option[ShareInfo]) => x.flatMap(_.share).flatMap(_.slug)
    val desc = (x: Option[ShareInfo]) => x.flatMap(_.share).flatMap(_.description)
    val pageSlug = (x: Option[ShareInfo]) => x.flatMap(_.pageSlug)
    if (force || slug(o) != slug(n)) s.slugDraft := slug(n).getOrElse("")
    if (force || desc(o) != desc(n)) s.descDraft := desc(n).getOrElse("")
    if (force || pageSlug(o) != pageSlug(n)) s.pageSlugDraft := pageSlug(n).getOrElse("")
  }
  sync(None, props.pub.v, true)
  watch(props.pub, false)((o, n) => sync(o, n, false))
}

val Errors: Map[String, String] = Map(
  "slug_taken" -> "このURL名はほかで使われています")

def message(e: Throwable): String = Errors.getOrElse(e.getMessage, e.getMessage)

/** 変更を送る（その間はボタン等を止め、失敗はメッセージにして知らせる） */
def run(s: State, p: Props)(f: => Future[Unit]): Future[Unit] = {
  s.busy := true
  p.onError("")
  scala.util.Try(f).fold(Future.failed, identity)
    .recover { case e => p.onError(message(e)) }
    .andThen(_ => s.busy := false)
}

def setShare(p: Props, x: Option[PublicShare]): Unit = p.pub := p.pub.v.map(_.copy(share = x))

def setPublic(s: State, p: Props, e: dom.Event): Unit = {
  val on = Dlg.checked(e)
  val el = e.target.asInstanceOf[dom.HTMLInputElement]
  if (on) run(s, p) {
    DialogsApi.enableShare(p.pageId, Json.obj("includeChildren" -> p.pub.v.flatMap(_.share).forall(_.includeChildren).asJson))
      .map(x => setShare(p, Some(x)))
  }
  else Dialog.confirm("公開を停止しますか？\nこれまでのリンクは使えなくなります（再度公開すると別のリンクになります）。", okLabel = "公開を停止", danger = true)
    .foreach { ok =>
      if (!ok) el.checked = true
      else run(s, p)(DialogsApi.disableShare(p.pageId).map(_ => setShare(p, None)))
    }
}

/** 公開の設定を変える（opts: 変える項目だけ. null は解除） */
def updatePublic(s: State, p: Props, opts: (String, Json)*): Future[Unit] =
  if (!hasShare(p)) Future.unit
  else run(s, p)(DialogsApi.enableShare(p.pageId, Json.obj(opts*)).map(x => setShare(p, Some(x))))

def set(s: State, p: Props, key: String, v: Json): Unit = updatePublic(s, p, key -> v)

// 公開する内容: live（編集中の内容をそのまま）/ pinned（「公開」を押した時点の内容）
def publishNow(s: State, p: Props): Unit =
  if (hasShare(p)) run(s, p) {
    DialogsApi.publish(p.pageId, Dlg.nonBlank(s.releaseNote.v)).map { x =>
      setShare(p, Some(x))
      s.releaseNote := ""
      s.releaseReload := s.releaseReload.v + 1
    }
  }

// 公開期間（この端末の時刻で入力する）
def setStarts(s: State, p: Props, v: String): Unit = set(s, p, "startsAt", Dlg.fromLocal(v).asJson)

def setExpiry(s: State, p: Props, v: String): Unit = set(s, p, "expiresAt", Dlg.fromLocal(v).asJson)

def setPassword(s: State, p: Props): Unit =
  Dialog.prompt("閲覧用のパスワード（4文字以上）", "", okLabel = "設定", placeholder = "ゲストに別の手段で伝えてください").foreach {
    case None => ()
    case Some(pw) if pw.length < 4 => p.onError("パスワードは4文字以上にしてください")
    case Some(pw) => set(s, p, "password", pw.asJson)
  }

// URL名・検索エンジン・説明文
def saveSlug(s: State, p: Props): Unit = set(s, p, "slug", Dlg.nonBlank(s.slugDraft.v.toLowerCase).asJson)

def saveDescription(s: State, p: Props): Unit = {
  val v = Dlg.nonBlank(s.descDraft.v)
  if (v != p.pub.v.flatMap(_.share).flatMap(_.description)) set(s, p, "description", v.asJson)
}

def savePageSlug(s: State, p: Props): Unit = run(s, p) {
  DialogsApi.setPageSlug(p.pageId, Dlg.nonBlank(s.pageSlugDraft.v.toLowerCase)).map(r => p.pub := p.pub.v.map(_.copy(pageSlug = r.slug)))
}

def slugUnchanged(s: State, p: Props): Boolean = s.slugDraft.v.trim.toLowerCase == sh(p).slug.getOrElse("")

def pageSlugUnchanged(s: State, p: Props): Boolean = s.pageSlugDraft.v.trim.toLowerCase == p.pub.v.flatMap(_.pageSlug).getOrElse("")

def copyText(s: State, text: String): Unit = Dlg.copy(text, s.copied)

def copyLabel(s: State): String = if (s.copied.v) "コピーしました" else "コピー"

def pdfHref(p: Props): String = s"/print/s/${sh(p).slug.getOrElse(sh(p).token)}"

def inheritedTitle(p: Props): String = Dlg.untitled(inherited(p).flatMap(_.title))

def hasInheritedToken(p: Props): Boolean = inherited(p).exists(_.token.exists(_.nonEmpty))
</script>

<template>
  <section class="public" data-testid="public-share">
    <h3>🌐 ゲストに公開（閲覧のみ）</h3>
    <label class="row">
      <input type="checkbox" :checked="hasShare(props)" :disabled="s.busy.v" data-testid="public-toggle" @change="setPublic(s, props, event)">
      リンクを知っている人は、ログインしなくても閲覧できる
    </label>
    <template v-if="hasShare(props)">
      <div class="link-row">
        <input :value="link(props)" readonly data-testid="public-url" @focus="Dlg.select(event)">
        <button @click="copyText(s, link(props))">{{ copyLabel(s) }}</button>
        <a :href="link(props)" target="_blank" rel="noopener">開く</a>
        <a :href="pdfHref(props)" target="_blank" rel="noopener" title="ゲストに見えている内容（公開版を固定していればその版）を、目次付きのPDFにする" data-testid="public-pdf">PDF</a>
      </div>
      <p v-if="sh(props).expired" class="error" data-testid="public-state">⛔ 公開期間が終わりました。ゲストは閲覧できません。</p>
      <p v-else-if="sh(props).notStarted" class="warn" data-testid="public-state">⏳ 公開前です。{{ sh(props).startsAt.fold("")(Ui.date) }} からゲストが閲覧できます。</p>
      <label class="row">
        <input type="checkbox" :checked="sh(props).includeChildren" :disabled="s.busy.v" data-testid="public-children" @change='set(s, props, "includeChildren", Dlg.checked(event).asJson)'>
        子ページも公開する
      </label>

      <h4>公開する内容</h4>
      <label class="row">
        <input type="radio" name="pub-mode" :checked='sh(props).mode == "live"' :disabled="s.busy.v" data-testid="public-mode-live" @change='set(s, props, "mode", "live".asJson)'>
        編集中の内容をそのまま公開する（常に最新）
      </label>
      <label class="row">
        <input type="radio" name="pub-mode" :checked='sh(props).mode == "pinned"' :disabled="s.busy.v" data-testid="public-mode-pinned" @change='set(s, props, "mode", "pinned".asJson)'>
        「公開」を押した時点の内容を公開する（編集中の変更はゲストに見えない）
      </label>
      <div v-if='sh(props).mode == "pinned"' class="pinned" data-testid="public-pinned">
        <div>
          公開中の版: {{ sh(props).publishedAt.fold("—")(Ui.date) }}<span v-if="sh(props).publishedBy.isDefined" class="muted">（{{ sh(props).publishedBy.getOrElse("") }}）</span>
          <div v-if="sh(props).pendingChanges.contains(true)" class="warn small" data-testid="public-pending">⚠ 公開版に反映していない変更があります</div>
          <div v-else class="muted small">公開版は現在の内容と同じです</div>
        </div>
        <button class="primary" :disabled="s.busy.v" data-testid="public-publish" @click="publishNow(s, props)">現在の内容を公開</button>
      </div>
      <template v-if='sh(props).mode == "pinned"'>
        <label class="col">
          <span>変更点のメモ（任意。「現在の内容を公開」のときに履歴へ残します）</span>
          <textarea v-model="s.releaseNote" rows="2" maxlength="2000" placeholder="例: インストール手順に Windows 版を追加しました" :disabled="s.busy.v" data-testid="public-release-note"></textarea>
        </label>
        <label class="row">
          <input type="checkbox" :checked="sh(props).showReleases" :disabled="s.busy.v" data-testid="public-show-releases" @change='set(s, props, "showReleases", Dlg.checked(event).asJson)'>
          ゲストの画面に「更新履歴」（公開日時と変更点のメモ）を出す
        </label>
        <ReleaseHistory :page-id="props.pageId" :reload="s.releaseReload" />
      </template>

      <h4>公開期間</h4>
      <div class="row">
        <span class="lbl-w">開始:</span>
        <input type="datetime-local" :value="Dlg.toLocal(sh(props).startsAt)" :disabled="s.busy.v" data-testid="public-starts" @change="setStarts(s, props, Dlg.value(event))">
        <span v-if="sh(props).startsAt.isEmpty" class="muted">すぐに公開</span>
        <button v-else class="link" :disabled="s.busy.v" @click='setStarts(s, props, "")'>解除</button>
      </div>
      <div class="row">
        <span class="lbl-w">終了:</span>
        <input type="datetime-local" :value="Dlg.toLocal(sh(props).expiresAt)" :disabled="s.busy.v" data-testid="public-expiry" @change="setExpiry(s, props, Dlg.value(event))">
        <span v-if="sh(props).expiresAt.isEmpty" class="muted">なし（無期限）</span>
        <button v-else class="link" :disabled="s.busy.v" @click='setExpiry(s, props, "")'>期限なしにする</button>
      </div>

      <h4>URL・検索エンジン</h4>
      <div class="row slug-row">
        <span class="lbl-w">URL名:</span>
        <span class="prefix">/s/</span>
        <input v-model="s.slugDraft" placeholder="例: manual（英小文字・数字・-）" maxlength="64" :disabled="s.busy.v" data-testid="public-slug" @keydown.enter.prevent="saveSlug(s, props)">
        <button :disabled="s.busy.v || slugUnchanged(s, props)" data-testid="public-slug-save" @click="saveSlug(s, props)">保存</button>
      </div>
      <p class="note">URL名を付けると、わかりやすいURLで開けます（トークンのURLもそのまま使えます）。子ページのURL名は、それぞれのページの共有画面で付けられます。</p>
      <label class="row">
        <input type="checkbox" :checked="sh(props).indexable" :disabled="s.busy.v || sh(props).hasPassword" data-testid="public-indexable" @change='set(s, props, "indexable", Dlg.checked(event).asJson)'>
        検索エンジンに載せる<span v-if="sh(props).hasPassword" class="muted">（パスワード付きのリンクは載りません）</span>
      </label>
      <label class="col">
        <span>共有したときの説明文（SNS・チャットのリンクの表示。空欄なら本文の冒頭）</span>
        <textarea v-model="s.descDraft" rows="2" maxlength="300" :disabled="s.busy.v" data-testid="public-description" @blur="saveDescription(s, props)"></textarea>
      </label>

      <h4>表示</h4>
      <div class="row">
        サイドメニュー（ページ一覧）:
        <select :disabled="s.busy.v" data-testid="public-sidebar" @change='set(s, props, "sidebar", Dlg.value(event).asJson)'>
          <option value="auto" :selected='sh(props).sidebar == "auto"'>自動（公開するページが2つ以上なら表示）</option>
          <option value="show" :selected='sh(props).sidebar == "show"'>常に表示</option>
          <option value="hide" :selected='sh(props).sidebar == "hide"'>表示しない</option>
        </select>
      </div>
      <label class="row">
        <input type="checkbox" :checked="sh(props).hideHeader" :disabled="s.busy.v" data-testid="public-hide-header" @change='set(s, props, "hideHeader", Dlg.checked(event).asJson)'>
        ゲストの画面に上部のヘッダー（「公開ページ」「ログイン」）を出さない
      </label>
      <div class="row">
        <button class="link" data-testid="guest-html-open" @click="s.guestHtmlOpen := true">🧩 本文の上下に入れるHTML（ゲスト向けの表示）…</button>
      </div>
      <div class="row">
        パスワード:
        <template v-if="sh(props).hasPassword">
          <span>🔑 設定済み</span>
          <button class="link" :disabled="s.busy.v" @click="setPassword(s, props)">変更</button>
          <button class="link" :disabled="s.busy.v" data-testid="public-password-clear" @click='set(s, props, "password", Json.Null)'>解除</button>
        </template>
        <template v-else>
          <span class="muted">なし</span>
          <button class="link" :disabled="s.busy.v" data-testid="public-password" @click="setPassword(s, props)">設定する</button>
        </template>
      </div>
      <p class="note">
        権限を引き継がない子ページ（個別に閲覧を制限したページ）とその下は公開されません。
        公開の設定は保存ボタンを押さなくてもすぐに反映されます。
      </p>
    </template>
    <template v-else-if="inherited(props).isDefined">
      <p class="note">
        <template v-if="hasInheritedToken(props)">このページは親ページ「{{ inheritedTitle(props) }}」の公開リンクに含まれ、すでにゲストが閲覧できます。</template>
        <template v-else>このページは、あなたが閲覧できない上位のページの公開リンクに含まれ、すでにゲストが閲覧できます。</template>
        <template v-if='inherited(props).exists(_.mode == "pinned")'>公開版を固定しているため、変更は親ページの共有画面で「現在の内容を公開」を押すと反映されます。</template>
      </p>
      <div v-if="hasInheritedToken(props)" class="link-row">
        <input :value="inheritedLink(props)" readonly data-testid="inherited-url" @focus="Dlg.select(event)">
        <button @click="copyText(s, inheritedLink(props))">{{ copyLabel(s) }}</button>
      </div>
      <div class="row slug-row">
        <span class="lbl-w">このページのURL名:</span>
        <input v-model="s.pageSlugDraft" placeholder="例: install（英小文字・数字・-）" maxlength="64" :disabled="s.busy.v" data-testid="page-slug" @keydown.enter.prevent="savePageSlug(s, props)">
        <button :disabled="s.busy.v || pageSlugUnchanged(s, props)" data-testid="page-slug-save" @click="savePageSlug(s, props)">保存</button>
      </div>
    </template>
    <GuestHtmlDialog v-if="s.guestHtmlOpen.v" kind="page" :target-id="props.pageId" :title="props.title" @close="s.guestHtmlOpen := false" />
  </section>
</template>

<style scoped>
.row { display: flex; gap: .5rem; align-items: center; margin: .4rem 0; }
.note { font-size: .8rem; margin: .2rem 0 .6rem; }
select { font: inherit; padding: .1rem .3rem; background: var(--bg); color: inherit; border: 1px solid var(--line); border-radius: 4px; }
.public { margin-top: 1rem; padding-top: .8rem; border-top: 1px solid var(--line); }
.public h3 { font-size: .95rem; margin: 0 0 .3rem; }
.link-row { display: flex; gap: .4rem; align-items: center; margin: .4rem 0; }
.link-row input { flex: 1; min-width: 0; padding: .3rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--side); color: inherit; font-size: .82rem; }
button.link { border: none; background: none; color: var(--accent); padding: 0 .2rem; font-size: .85rem; }
.link-row a { font-size: .85rem; color: var(--accent); }
.public h4 { font-size: .8rem; color: var(--muted); margin: .9rem 0 .2rem; font-weight: 600; }
.lbl-w { min-width: 2.6em; flex: none; }
.row input[type="datetime-local"], .slug-row input { font: inherit; font-size: .85rem; padding: .15rem .35rem; background: var(--bg); color: inherit; border: 1px solid var(--line); border-radius: 4px; }
.slug-row input { flex: 1; min-width: 0; }
.prefix { color: var(--muted); font-size: .85rem; margin-right: -.3rem; }
.col { display: flex; flex-direction: column; gap: .25rem; font-size: .8rem; color: var(--muted); margin: .4rem 0; }
.col textarea { font: inherit; font-size: .85rem; color: var(--fg); padding: .3rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); resize: vertical; }
.pinned { display: flex; align-items: center; justify-content: space-between; gap: .6rem; padding: .5rem .7rem; margin: .3rem 0; border: 1px solid var(--line); border-radius: 8px; background: var(--side); font-size: .85rem; }
.pinned .primary { flex: none; }
.warn { color: #b45309; }
.primary:disabled { opacity: .6; }
.muted { font-size: .85em; }
.small { font-size: .78rem; }
</style>
