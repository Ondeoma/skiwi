<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { oauthClientApi, type OAuthClientSettings } from '../api'
import IntegrationSettingsForm from '../components/IntegrationSettingsForm.vue'
import { skawau, skea } from '../ui/integration'
import { confirmDialog } from '../ui/dialog'

/** 管理者: 外部サービスとの連携の設定（docs/skea-integration.md・docs/skawau-integration.md）。
 *  - skiwi → skea: 本文のタスクID に skea のタスクのタイトル・状態を出す（skiwi が skea のクライアント）
 *  - skea → skiwi: skea に skiwi のページのタイトルを出す（skea が skiwi のクライアント）
 *  - skiwi → skawau: 本文のファイルのリンクにファイル名を出す（skiwi が skawau のクライアント） */
// ---------------------------------------------------------------- skea → skiwi（skea をクライアントとして登録する）
const client = ref<OAuthClientSettings | null>(null)
const clientForm = reactive({ clientId: '', clientSecret: '', redirectUri: '', name: '' })
const clientError = ref('')
const clientSaveError = ref('')
const clientSaved = ref(false)

function fillClient(c: OAuthClientSettings) {
  client.value = c
  Object.assign(clientForm, { clientId: c.clientId, clientSecret: '', redirectUri: c.redirectUri, name: c.name })
}

onMounted(async () => {
  try { fillClient(await oauthClientApi.get()) } catch (e) { clientError.value = (e as Error).message }
})

async function saveClient() {
  clientSaveError.value = ''
  clientSaved.value = false
  const before = client.value
  if (before?.connections && before.clientId && clientForm.clientId.trim() !== before.clientId
    && !(await confirmDialog(`client_id を変えると、連携している ${before.connections} 人のトークンが失効します（各自で skea から連携し直しが必要です）。保存しますか？`, { okLabel: '保存', danger: true }))) return
  try {
    fillClient(await oauthClientApi.save({
      clientId: clientForm.clientId, clientSecret: clientForm.clientSecret || undefined, redirectUri: clientForm.redirectUri, name: clientForm.name,
    }))
    clientSaved.value = true
  } catch (e) { clientSaveError.value = (e as Error).message }
}

async function removeClient() {
  const n = client.value?.connections ?? 0
  if (!(await confirmDialog(`skea の登録を消します。${n ? `連携している ${n} 人のトークンも失効します。` : ''}よろしいですか？`, { okLabel: '登録を消す', danger: true }))) return
  try {
    await oauthClientApi.remove()
    fillClient(await oauthClientApi.get())
  } catch (e) { clientSaveError.value = (e as Error).message }
}

</script>

<template>
  <main class="wrap">
    <p><router-link to="/">← 戻る</router-link></p>
    <h1>🔗 外部サービスとの連携</h1>
    <h2 class="dir">skiwi → skea（タスクの表示）</h2>
    <p class="muted">
      ページの本文に出てくる skea のタスクID（<code>SKEA-123</code> など）を、skea のタスクへのリンクにし、タイトルと状態を横に表示します。
      タスクは各ユーザーが「skea と連携」で許可した、本人の skea の権限で読みます。
    </p>
    <IntegrationSettingsForm :svc="skea" project-keys />

    <h2 class="dir">skea → skiwi（ページのタイトルの表示）</h2>
    <p class="muted">
      skea のタスクに書いた skiwi のページのリンクに、ページのタイトルを表示します。skea を skiwi のクライアントとして登録すると、
      各ユーザーが skea の個人設定から連携でき、本人の skiwi の権限で（見られるページだけ、読み取り専用で）タイトルを読みます。
      連携は各ユーザーの「APIトークン」の画面に「skea 連携」として出て、そこからいつでも解除できます。
    </p>
    <p v-if="clientError" class="error" data-testid="oauth-client-error">{{ clientError }}</p>
    <p v-else-if="!client" class="muted">読み込み中…</p>
    <form v-else class="box cfg" data-testid="oauth-client" @submit.prevent="saveClient">
      <p class="muted small">skea の管理者と同じ値を設定します（skea の環境変数 <code>SKIWI_CLIENT_ID</code>・<code>SKIWI_CLIENT_SECRET</code>）。</p>
      <label>client_id
        <input v-model="clientForm.clientId" type="text" autocomplete="off" placeholder="skea" data-testid="oauth-client-id" />
      </label>
      <label>client_secret
        <input v-model="clientForm.clientSecret" type="password" autocomplete="new-password" data-testid="oauth-client-secret"
               :placeholder="client.clientSecretSet ? '設定済み（変えるときだけ入力）' : '16文字以上のランダムな文字列'" />
      </label>
      <label>redirect_uri
        <input v-model="clientForm.redirectUri" type="url" placeholder="https://skea.example.com/api/integrations/skiwi/callback" data-testid="oauth-client-redirect" />
        <span class="muted small">skea の URL + <code>/api/integrations/skiwi/callback</code>。完全一致で照合します。</span>
      </label>
      <label>表示名
        <input v-model="clientForm.name" type="text" placeholder="skea" data-testid="oauth-client-name" />
        <span class="muted small">許可の画面と、APIトークンの名前（「表示名 連携」）に使います。</span>
      </label>
      <div class="row">
        <button type="submit" class="primary" data-testid="oauth-client-save">{{ clientSaved ? '保存しました' : '保存' }}</button>
        <button v-if="client.clientId" type="button" data-testid="oauth-client-remove" @click="removeClient">登録を消す</button>
        <span v-if="client.clientId" class="muted small">連携しているユーザー: {{ client.connections }} 人</span>
      </div>
      <p v-if="clientSaveError" class="error" data-testid="oauth-client-save-error">{{ clientSaveError }}</p>
    </form>

    <h2 class="dir">skiwi → skawau（ファイル名の表示）</h2>
    <p class="muted">
      ページの本文にある skawau のファイル・フォルダのリンク（<code>…/view/{ID}</code> など）に、ファイル名を表示します。
      閲覧中は URL の代わりに「📄 ファイル名」を出します。ファイル名は各ユーザーが「skawau と連携」で許可した、本人の skawau の権限で読みます。
    </p>
    <IntegrationSettingsForm :svc="skawau" />
  </main>
</template>

<style scoped>
/* フォームの部品（IntegrationSettingsForm）にも効かせる */
.wrap { max-width: 760px; margin: 0 auto; padding: 1.5rem; }
h1 { font-size: 1.4rem; }
h2.dir { font-size: 1.1rem; margin: 1.6rem 0 .3rem; padding-bottom: .3rem; border-bottom: 1px solid var(--line); }
.wrap :deep(h3) { font-size: 1rem; margin: 0 0 .3rem; }
.wrap :deep(.box) { border: 1px solid var(--line); border-radius: 8px; padding: 1rem; margin: 1rem 0; }
.wrap :deep(.small) { font-size: .8rem; }
.wrap :deep(.copy) { display: flex; gap: .5rem; align-items: center; flex-wrap: wrap; }
.wrap :deep(.copy code) { padding: .2rem .4rem; border-radius: 4px; background: var(--hover); word-break: break-all; }
.wrap :deep(.cfg) { display: flex; flex-direction: column; gap: .8rem; }
.wrap :deep(.cfg label) { display: flex; flex-direction: column; gap: .2rem; font-size: .9rem; }
.wrap :deep(.cfg input) { padding: .35rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); }
.wrap :deep(.row) { display: flex; gap: .8rem; align-items: center; }
</style>
