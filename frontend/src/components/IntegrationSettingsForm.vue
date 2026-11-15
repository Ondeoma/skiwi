<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import type { IntegrationSettings } from '../api'
import type { Integration } from '../ui/integration'
import { confirmDialog } from '../ui/dialog'

/** 管理者: skiwi が外部サービス（skea・skawau）のクライアントになる連携の設定。client_secret は書き込みだけ（設定済みかどうかだけ表示する）。
 *  projectKeys: skea のタスクIDのように、対象を絞るキーを入れる欄を出す */
const props = defineProps<{ svc: Integration<unknown>; projectKeys?: boolean }>()
const name = props.svc.name
const current = ref<IntegrationSettings | null>(null)
const form = reactive({ url: '', clientId: '', clientSecret: '', projectKeys: '' })
const error = ref('')
const saveError = ref('')
const saved = ref(false)
const copied = ref(false)

function fill(s: IntegrationSettings) {
  current.value = s
  Object.assign(form, { url: s.url, clientId: s.clientId, clientSecret: '', projectKeys: s.projectKeys.join(', ') })
}

onMounted(async () => {
  try { fill(await props.svc.api.settings()) } catch (e) { error.value = (e as Error).message }
})

async function save() {
  saveError.value = ''
  saved.value = false
  const before = current.value
  if (before?.connections && before.url && (form.url.trim().replace(/\/$/, '') !== before.url || form.clientId.trim() !== before.clientId)
    && !(await confirmDialog(`${name} の URL か client_id を変えると、連携済みの ${before.connections} 人の連携が外れます（各自で連携し直しが必要です）。保存しますか？`, { okLabel: '保存', danger: true }))) return
  try {
    fill(await props.svc.api.saveSettings({
      url: form.url, clientId: form.clientId, clientSecret: form.clientSecret || undefined,
      ...(props.projectKeys ? { projectKeys: form.projectKeys.split(/[,\s、]+/).filter(Boolean) } : {}),
    }))
    saved.value = true
    void props.svc.reload()
  } catch (e) { saveError.value = (e as Error).message }
}

async function copy() {
  if (!current.value) return
  await navigator.clipboard.writeText(current.value.redirectUri).catch(() => {})
  copied.value = true
  setTimeout(() => (copied.value = false), 1500)
}
</script>

<template>
  <p v-if="error" class="error" :data-testid="`${name}-admin-error`">{{ error }}</p>
  <p v-else-if="!current" class="muted">読み込み中…</p>
  <template v-else>
    <section class="box">
      <h3>{{ name }} に登録する redirect_uri</h3>
      <p class="muted small">{{ name }} の管理者に、クライアント（client_id・client_secret）とあわせて、この URL を登録してもらいます（完全一致で照合されます）。</p>
      <div class="copy">
        <code :data-testid="`${name}-redirect-uri`">{{ current.redirectUri }}</code>
        <button type="button" @click="copy">{{ copied ? 'コピーしました' : 'コピー' }}</button>
      </div>
    </section>
    <form class="box cfg" :data-testid="`${name}-settings`" @submit.prevent="save">
      <label>{{ name }} の URL
        <input v-model="form.url" type="url" :placeholder="`https://${name}.example.com`" :data-testid="`${name}-url`" />
      </label>
      <label>client_id
        <input v-model="form.clientId" type="text" autocomplete="off" placeholder="skiwi" :data-testid="`${name}-client-id`" />
      </label>
      <label>client_secret
        <input v-model="form.clientSecret" type="password" autocomplete="new-password" :data-testid="`${name}-client-secret`"
               :placeholder="current.clientSecretSet ? '設定済み（変えるときだけ入力）' : '未設定'" />
      </label>
      <label v-if="projectKeys">対象のプロジェクトキー
        <input v-model="form.projectKeys" type="text" placeholder="SKEA, DEMO" :data-testid="`${name}-project-keys`" />
        <span class="muted small">カンマ区切り。ここに並べたキーのタスクID（例: SKEA-123）だけをリンクにします（UTF-8 などを誤ってタスクIDと見なさないため）。</span>
      </label>
      <div class="row">
        <button type="submit" class="primary" :data-testid="`${name}-save`">{{ saved ? '保存しました' : '保存' }}</button>
        <span class="muted small">連携済みのユーザー: {{ current.connections }} 人</span>
      </div>
      <p v-if="saveError" class="error" :data-testid="`${name}-save-error`">{{ saveError }}</p>
      <p class="muted small">
        URL・client_id・client_secret{{ projectKeys ? '・プロジェクトキー' : '' }}がすべて揃うと、ユーザーのサイドバーに「{{ name }} と連携」が出ます。URL を空にすると連携を止めます。
      </p>
    </form>
  </template>
</template>
