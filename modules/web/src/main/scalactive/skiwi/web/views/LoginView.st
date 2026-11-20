<script lang="scala">
import io.circe.Json
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.web.{Api, AppNames}

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// 開発用のログイン（DEV_LOGIN=true のとき。本番は OIDC の IdP の画面になる）.
// 「管理者としてログイン」は、ADMIN_GROUPS のグループに入れてログインする
case class State(name: RV[String] = RV(""), email: RV[String] = RV(""), admin: RV[Boolean] = RV(true), error: RV[String] = RV(""))

def initState() = State()

// 戻り先はサーバーが確かめる（同じサイトの相対パスだけ。応答の redirect へ移る）
def returnTo(): String = Option(new dom.URL(dom.window.location.href).searchParams.get("returnTo")).getOrElse("/")

def login(s: State): Unit = {
  s.error := ""
  val body = Json.obj("name" -> s.name.v.trim.asJson, "email" -> s.email.v.trim.asJson, "admin" -> s.admin.v.asJson, "returnTo" -> returnTo().asJson)
  Api.post[Json]("/auth/dev-login", body).onComplete {
    case Success(j) => dom.window.location.href = j.hcursor.get[String]("redirect").toOption.filter(_.startsWith("/")).getOrElse("/")
    case Failure(e) => s.error := e.getMessage
  }
}

def mounted(): Unit = dom.document.title = "ログイン - skiwi"
</script>

<template>
  <div class="login">
    <img :src='AppNames.logo("/logo.svg").v' :alt="AppNames.self.v" height="96" class="logo">
    <h1>skiwi にログイン</h1>
    <p class="muted">開発用のログインです（IdP が設定されていない環境だけで使えます）。</p>
    <form @submit.prevent="login(s)">
      <label class="field"><span>名前</span><input type="text" v-model="s.name" placeholder="山田 太郎" v-focus data-testid="dev-login-name"></label>
      <label class="field"><span>メールアドレス（任意）</span><input type="email" v-model="s.email"></label>
      <label class="check"><input type="checkbox" v-model="s.admin"> 管理者としてログイン</label>
      <p v-if="s.error.mapC(_.nonEmpty)" class="error">{{ s.error }}</p>
      <p><button type="submit" class="primary">ログイン</button></p>
    </form>
  </div>
</template>

<style scoped>
.login { max-width: 420px; margin: 8vh auto 0; padding: 1.6rem; border: 1px solid var(--line); border-radius: 10px; }
.logo { display: block; margin: 0 auto .6rem; width: auto; }
h1 { text-align: center; font-size: 1.4rem; }
.field { display: flex; flex-direction: column; gap: .25rem; margin: .8rem 0; }
.field input { padding: .4rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: transparent; }
.check { display: flex; align-items: center; gap: .4rem; margin: .8rem 0; }
</style>
