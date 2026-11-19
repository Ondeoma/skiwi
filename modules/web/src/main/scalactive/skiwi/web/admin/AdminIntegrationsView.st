<script lang="scala">
import skiwi.web.AppNames
import skiwi.web.Nav
import skiwi.web.Dialog

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// 管理者: 外部サービスとの連携の設定（docs/skea-integration.md・docs/skawau-integration.md・docs/smohua-integration.md）。
//  - skiwi → skea: 本文のタスクID に skea のタスクのタイトル・状態を出す（skiwi が skea のクライアント）
//  - skea → skiwi: skea に skiwi のページのタイトルを出す（skea が skiwi のクライアント）
//  - skiwi → skawau: 本文のファイルのリンクにファイル名を出す（skiwi が skawau のクライアント）
//  - skiwi → smohua: ページに置いたブロックに、フォームの回答を出す（skiwi が smohua のクライアント）

// ---------------------------------------------------------------- skea → skiwi（skea をクライアントとして登録する）
case class State(client: RV[Option[OAuthClientSettings]] = RV(None),
                 clientId: RV[String] = RV(""),
                 clientSecret: RV[String] = RV(""),
                 redirectUri: RV[String] = RV(""),
                 name: RV[String] = RV(""),
                 clientError: RV[String] = RV(""),
                 clientSaveError: RV[String] = RV(""),
                 clientSaved: RV[Boolean] = RV(false))

def initState() = State()

def fillClient(s: State, c: OAuthClientSettings): Unit = {
  s.client := Some(c)
  s.clientId := c.clientId
  s.clientSecret := ""
  s.redirectUri := c.redirectUri
  s.name := c.name
}

def mounted(s: State): Unit = AdminApi.oauthClient().onComplete {
  case Success(c) => fillClient(s, c)
  case Failure(e) => s.clientError := e.getMessage
}

def saveClient(s: State): Unit = {
  s.clientSaveError := ""
  s.clientSaved := false
  val ok = s.client.v match {
    case Some(before) if before.connections > 0 && before.clientId.nonEmpty && s.clientId.v.trim != before.clientId =>
      Dialog.confirm(s"client_id を変えると、連携している ${before.connections} 人のトークンが失効します（各自で ${AppNames.nameOf("skea")} から連携し直しが必要です）。保存しますか？",
        okLabel = "保存", danger = true)
    case _ => Future.successful(true)
  }
  ok.foreach { yes =>
    if (yes) AdminApi.saveOauthClient(s.clientId.v, Option(s.clientSecret.v).filter(_.nonEmpty), s.redirectUri.v, s.name.v).onComplete {
      case Success(c) =>
        fillClient(s, c)
        s.clientSaved := true
      case Failure(e) => s.clientSaveError := e.getMessage
    }
  }
}

def removeClient(s: State): Unit = {
  val n = s.client.v.fold(0)(_.connections)
  val extra = if (n > 0) s"連携している $n 人のトークンも失効します。" else ""
  Dialog.confirm(s"${AppNames.nameOf("skea")} の登録を消します。${extra}よろしいですか？", okLabel = "登録を消す", danger = true).foreach { yes =>
    if (yes) AdminApi.removeOauthClient().flatMap(_ => AdminApi.oauthClient()).onComplete {
      case Success(c) => fillClient(s, c)
      case Failure(e) => s.clientSaveError := e.getMessage
    }
  }
}

def client(s: State): Option[OAuthClientSettings] = s.client.v

def hasClient(s: State): Boolean = client(s).exists(_.clientId.nonEmpty)

def secretPlaceholder(s: State): String =
  if (client(s).exists(_.clientSecretSet)) "設定済み（変えるときだけ入力）" else "16文字以上のランダムな文字列"
</script>

<template>
  <main class="wrap">
    <p><a href="/" @click='Nav.link(event, "/")'>← 戻る</a></p>
    <h1>🔗 外部サービスとの連携</h1>
    <h2 class="dir">{{ AppNames.self }} → {{ AppNames.of("skea") }}（タスクの表示）</h2>
    <p class="muted">
      ページの本文に出てくる {{ AppNames.of("skea") }} のタスクID（<code>SKEA-123</code> など）を、{{ AppNames.of("skea") }} のタスクへのリンクにし、タイトルと状態を横に表示します。
      タスクは各ユーザーが「{{ AppNames.of("skea") }} と連携」で許可した、本人の {{ AppNames.of("skea") }} の権限で読みます。
    </p>
    <IntegrationSettingsForm svc="skea" :projectKeys="true" />

    <h2 class="dir">{{ AppNames.of("skea") }} → {{ AppNames.self }}（ページのタイトルの表示）</h2>
    <p class="muted">
      {{ AppNames.of("skea") }} のタスクに書いた {{ AppNames.self }} のページのリンクに、ページのタイトルを表示します。{{ AppNames.of("skea") }} を {{ AppNames.self }} のクライアントとして登録すると、
      各ユーザーが {{ AppNames.of("skea") }} の個人設定から連携でき、本人の {{ AppNames.self }} の権限で（見られるページだけ、読み取り専用で）タイトルを読みます。
      連携は各ユーザーの「APIトークン」の画面に「{{ AppNames.of("skea") }} 連携」として出て、そこからいつでも解除できます。
    </p>
    <p v-if="s.clientError.v.nonEmpty" class="error" data-testid="oauth-client-error">{{ s.clientError }}</p>
    <p v-else-if="client(s).isEmpty" class="muted">読み込み中…</p>
    <form v-else class="box cfg" data-testid="oauth-client" @submit.prevent="saveClient(s)">
      <p class="muted small">{{ AppNames.of("skea") }} の管理者と同じ値を設定します（{{ AppNames.of("skea") }} の環境変数 <code>SKIWI_CLIENT_ID</code>・<code>SKIWI_CLIENT_SECRET</code>）。</p>
      <label>client_id
        <input v-model="s.clientId" type="text" autocomplete="off" placeholder="skea" data-testid="oauth-client-id">
      </label>
      <label>client_secret
        <input v-model="s.clientSecret" type="password" autocomplete="new-password" data-testid="oauth-client-secret"
               :placeholder="secretPlaceholder(s)">
      </label>
      <label>redirect_uri
        <input v-model="s.redirectUri" type="url" placeholder="https://skea.example.com/api/integrations/skiwi/callback" data-testid="oauth-client-redirect">
        <span class="muted small">{{ AppNames.of("skea") }} の URL + <code>/api/integrations/skiwi/callback</code>。完全一致で照合します。</span>
      </label>
      <label>表示名
        <input v-model="s.name" type="text" placeholder="skea" data-testid="oauth-client-name">
        <span class="muted small">許可の画面と、APIトークンの名前（「表示名 連携」）に使います。</span>
      </label>
      <div class="row">
        <button type="submit" class="primary" data-testid="oauth-client-save">{{ if (s.clientSaved.v) "保存しました" else "保存" }}</button>
        <button v-if="hasClient(s)" type="button" data-testid="oauth-client-remove" @click="removeClient(s)">登録を消す</button>
        <span v-if="hasClient(s)" class="muted small">連携しているユーザー: {{ client(s).fold(0)(_.connections) }} 人</span>
      </div>
      <p v-if="s.clientSaveError.v.nonEmpty" class="error" data-testid="oauth-client-save-error">{{ s.clientSaveError }}</p>
    </form>

    <h2 class="dir">{{ AppNames.self }} → {{ AppNames.of("skawau") }}（ファイル名の表示）</h2>
    <p class="muted">
      ページの本文にある {{ AppNames.of("skawau") }} のファイル・フォルダのリンク（<code>…/view/{ID}</code> など）に、ファイル名を表示します。
      閲覧中は URL の代わりに「📄 ファイル名」を出します。ファイル名は各ユーザーが「{{ AppNames.of("skawau") }} と連携」で許可した、本人の {{ AppNames.of("skawau") }} の権限で読みます。
    </p>
    <IntegrationSettingsForm svc="skawau" />

    <h2 class="dir">{{ AppNames.self }} → {{ AppNames.of("smohua") }}（フォームの回答の表示）</h2>
    <p class="muted">
      ページに置いた「フォームの回答（{{ AppNames.of("smohua") }}）」のブロック（<code>/</code> のメニューから追加）に、{{ AppNames.of("smohua") }} のフォームで集めた回答の集計と一覧を表示します。
      回答は各ユーザーが「{{ AppNames.of("smohua") }} と連携」で許可した、本人の {{ AppNames.of("smohua") }} の権限で読みます（そのフォームを見られない人には表示されません）。
      {{ AppNames.of("smohua") }} の <code>OAUTH_CLIENTS</code> に、下の「リダイレクト URI」で {{ AppNames.self }} を登録してください。
    </p>
    <IntegrationSettingsForm svc="smohua" />
  </main>
</template>

<style scoped>
/* フォームの部品（IntegrationSettingsForm）は同じ見た目を自分で持つ */
.wrap { max-width: 760px; margin: 0 auto; padding: 1.5rem; }
h1 { font-size: 1.4rem; }
h2.dir { font-size: 1.1rem; margin: 1.6rem 0 .3rem; padding-bottom: .3rem; border-bottom: 1px solid var(--line); }
.box { border: 1px solid var(--line); border-radius: 8px; padding: 1rem; margin: 1rem 0; }
.small { font-size: .8rem; }
.cfg { display: flex; flex-direction: column; gap: .8rem; }
.cfg label { display: flex; flex-direction: column; gap: .2rem; font-size: .9rem; }
.cfg input { padding: .35rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); }
.row { display: flex; gap: .8rem; align-items: center; }
</style>
