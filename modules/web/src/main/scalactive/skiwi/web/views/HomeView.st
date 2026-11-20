<script lang="scala">
import skiwi.web.{Dialog, Nav, Store}

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// ワークスペースがないときの最初の画面（あれば最初のワークスペースへ移る）
case class State(loading: RV[Boolean] = RV(true), name: RV[String] = RV(""))

def initState() = State()

def mounted(s: State): Unit =
  Store.init().onComplete { _ =>
    Store.workspaces.v.headOption.foreach(w => Router.replace(s"/w/${w.id}"))
    s.loading := false
  }

def create(s: State): Unit = {
  val n = s.name.v.trim
  if (n.nonEmpty) Store.createWorkspace(n).map(w => Nav.go(s"/w/${w.id}")).recover { case e => Dialog.alertError("作成できませんでした", e) }
}
</script>

<template>
  <main v-if="!s.loading.v" style="max-width: 28rem; margin: 20vh auto; padding: 0 1rem">
    <h1>ようこそ</h1>
    <p>参加しているワークスペースがありません。最初のワークスペースを作成してください。</p>
    <form style="display: flex; gap: .5rem" @submit.prevent="create(s)">
      <input v-model="s.name" placeholder="ワークスペース名" style="flex: 1; padding: .3rem .6rem">
      <button type="submit">作成</button>
    </form>
  </main>
</template>
