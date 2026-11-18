<script lang="scala">
import org.scalajs.dom
import skiwi.web.Dialog

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// 管理者: skiwi が外部サービス（skea・skawau・smohua）のクライアントになる連携の設定。client_secret は書き込みだけ（設定済みかどうかだけ表示する）。
// svc: skea / skawau / smohua. projectKeys: skea のタスクIDのように、対象を絞るキーを入れる欄を出す
case class Props(svc: String, projectKeys: Boolean = false)

case class State(current: RV[Option[IntegrationSettings]] = RV(None),
                 url: RV[String] = RV(""),
                 clientId: RV[String] = RV(""),
                 clientSecret: RV[String] = RV(""),
                 projectKeys: RV[String] = RV(""),
                 error: RV[String] = RV(""),
                 saveError: RV[String] = RV(""),
                 saved: RV[Boolean] = RV(false),
                 copied: RV[Boolean] = RV(false))

def initState() = State()

def fill(s: State, c: IntegrationSettings): Unit = {
  s.current := Some(c)
  s.url := c.url
  s.clientId := c.clientId
  s.clientSecret := ""
  s.projectKeys := c.projectKeys.mkString(", ")
}

def mounted(s: State, props: Props): Unit = AdminApi.integrationSettings(props.svc).onComplete {
  case Success(c) => fill(s, c)
  case Failure(e) => s.error := e.getMessage
}

def save(s: State, props: Props): Unit = {
  s.saveError := ""
  s.saved := false
  val name = props.svc
  val ok = s.current.v match {
    case Some(before) if before.connections > 0 && before.url.nonEmpty &&
      (s.url.v.trim.stripSuffix("/") != before.url || s.clientId.v.trim != before.clientId) =>
      Dialog.confirm(s"$name の URL か client_id を変えると、連携済みの ${before.connections} 人の連携が外れます（各自で連携し直しが必要です）。保存しますか？",
        okLabel = "保存", danger = true)
    case _ => Future.successful(true)
  }
  ok.foreach { yes =>
    if (yes) {
      val keys = Option.when(props.projectKeys)(s.projectKeys.v.split("[,\\s、]+").toList.filter(_.nonEmpty))
      AdminApi.saveIntegrationSettings(name, s.url.v, s.clientId.v, Option(s.clientSecret.v).filter(_.nonEmpty), keys).onComplete {
        case Success(c) =>
          fill(s, c)
          s.saved := true
          AdminApi.reloadIntegration(name)
        case Failure(e) => s.saveError := e.getMessage
      }
    }
  }
}

def copy(s: State): Unit = s.current.v.foreach { c =>
  dom.window.navigator.clipboard.writeText(c.redirectUri).toFuture.onComplete(_ => ())
  s.copied := true
  dom.window.setTimeout(() => s.copied := false, 1500)
}

def cur(s: State): Option[IntegrationSettings] = s.current.v

def secretPlaceholder(s: State): String = if (cur(s).exists(_.clientSecretSet)) "設定済み（変えるときだけ入力）" else "未設定"
</script>

<template>
  <p v-if="s.error.v.nonEmpty" class="error" :data-testid='props.svc + "-admin-error"'>{{ s.error }}</p>
  <p v-else-if="cur(s).isEmpty" class="muted">読み込み中…</p>
  <template v-else>
    <section class="box">
      <h3>{{ props.svc }} に登録する redirect_uri</h3>
      <p class="muted small">{{ props.svc }} の管理者に、クライアント（client_id・client_secret）とあわせて、この URL を登録してもらいます（完全一致で照合されます）。</p>
      <div class="copy">
        <code :data-testid='props.svc + "-redirect-uri"'>{{ cur(s).fold("")(_.redirectUri) }}</code>
        <button type="button" @click="copy(s)">{{ if (s.copied.v) "コピーしました" else "コピー" }}</button>
      </div>
    </section>
    <form class="box cfg" :data-testid='props.svc + "-settings"' @submit.prevent="save(s, props)">
      <label>{{ props.svc }} の URL
        <input v-model="s.url" type="url" :placeholder='"https://" + props.svc + ".example.com"' :data-testid='props.svc + "-url"'>
      </label>
      <label>client_id
        <input v-model="s.clientId" type="text" autocomplete="off" placeholder="skiwi" :data-testid='props.svc + "-client-id"'>
      </label>
      <label>client_secret
        <input v-model="s.clientSecret" type="password" autocomplete="new-password" :data-testid='props.svc + "-client-secret"'
               :placeholder="secretPlaceholder(s)">
      </label>
      <label v-if="props.projectKeys">対象のプロジェクトキー
        <input v-model="s.projectKeys" type="text" placeholder="SKEA, DEMO" :data-testid='props.svc + "-project-keys"'>
        <span class="muted small">カンマ区切り。ここに並べたキーのタスクID（例: SKEA-123）だけをリンクにします（UTF-8 などを誤ってタスクIDと見なさないため）。</span>
      </label>
      <div class="row">
        <button type="submit" class="primary" :data-testid='props.svc + "-save"'>{{ if (s.saved.v) "保存しました" else "保存" }}</button>
        <span class="muted small">連携済みのユーザー: {{ cur(s).fold(0)(_.connections) }} 人</span>
      </div>
      <p v-if="s.saveError.v.nonEmpty" class="error" :data-testid='props.svc + "-save-error"'>{{ s.saveError }}</p>
      <p class="muted small">
        URL・client_id・client_secret{{ if (props.projectKeys) "・プロジェクトキー" else "" }}がすべて揃うと、ユーザーのサイドバーに「{{ props.svc }} と連携」が出ます。URL を空にすると連携を止めます。
      </p>
    </form>
  </template>
</template>

<style scoped>
/* 外部連携の画面（AdminIntegrationsView）の .wrap の中と同じ見た目 */
h3 { font-size: 1rem; margin: 0 0 .3rem; }
.box { border: 1px solid var(--line); border-radius: 8px; padding: 1rem; margin: 1rem 0; }
.small { font-size: .8rem; }
.copy { display: flex; gap: .5rem; align-items: center; flex-wrap: wrap; }
.copy code { padding: .2rem .4rem; border-radius: 4px; background: var(--hover); word-break: break-all; }
.cfg { display: flex; flex-direction: column; gap: .8rem; }
.cfg label { display: flex; flex-direction: column; gap: .2rem; font-size: .9rem; }
.cfg input { padding: .35rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); }
.row { display: flex; gap: .8rem; align-items: center; }
</style>
