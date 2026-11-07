<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useWorkspaceStore } from '../stores/workspace'

const store = useWorkspaceStore()
const router = useRouter()
const loading = ref(true)
const name = ref('')

onMounted(async () => {
  await store.init()
  if (store.workspaces.length > 0) {
    router.replace(`/w/${store.workspaces[0].id}`)
  }
  loading.value = false
})

async function create() {
  if (!name.value.trim()) return
  const w = await store.createWorkspace(name.value.trim())
  router.push(`/w/${w.id}`)
}
</script>

<template>
  <main v-if="!loading" style="max-width: 28rem; margin: 20vh auto; padding: 0 1rem">
    <h1>ようこそ</h1>
    <p>参加しているワークスペースがありません。最初のワークスペースを作成してください。</p>
    <form @submit.prevent="create" style="display: flex; gap: .5rem">
      <input v-model="name" placeholder="ワークスペース名" style="flex: 1; padding: .3rem .6rem" />
      <button type="submit">作成</button>
    </form>
  </main>
</template>
