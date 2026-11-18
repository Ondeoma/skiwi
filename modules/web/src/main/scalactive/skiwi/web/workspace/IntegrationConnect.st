<script lang="scala">
import org.scalajs.dom
import skiwi.web.Dialog

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 外部サービス（skea・skawau・smohua）との連携. 管理者が設定したものだけ出す.
//  - part="notice": 連携の結果のお知らせ（ヘッダに常に置く. 表示のときに状態を読み込む）
//  - part="links": 「skea と連携」「skawau と連携」「smohua と連携」（名前のメニューの中）
case class Props(part: String)

/** notice: (ok / ng, 文) */
case class State(notice: RV[Option[(String, String)]] = RV(None), timer: RV[Option[Int]] = RV(None))

def initState() = State()

final case class Texts(shows: String, lost: String)

val texts: Map[String, Texts] = Map(
  "skea" -> Texts("本文のタスクIDにタイトルと状態が出ます", "本文のタスクIDには、タイトルと状態が出なくなります（リンクは残ります）"),
  "skawau" -> Texts("本文のファイルのリンクにファイル名が出ます", "本文のファイルのリンクは、URL のままの表示に戻ります"),
  "smohua" -> Texts("ページに置いた「フォームの回答」のブロックに、回答の集計と一覧が出ます", "ページに置いた「フォームの回答」のブロックには、回答が出なくなります（ブロックは残ります）"),
)

def message(name: String, r: String): (String, String) = r match {
  case "connected" => ("ok", s"$name と連携しました。${texts(name).shows}。")
  case "denied" => ("ng", s"$name との連携を取りやめました。")
  case _ => ("ng", s"$name と連携できませんでした。時間をおいてやり直すか、管理者に確認してください。")
}

def mounted(s: State, props: Props): Unit =
  if (props.part == "notice") Integrations.init().foreach { results =>
    Integrations.Names.flatMap(n => results.get(n).map(n -> _)).lastOption.foreach { case (n, r) =>
      s.notice := Some(message(n, r))
      s.timer.v.foreach(dom.window.clearTimeout)
      s.timer := Some(dom.window.setTimeout(() => s.notice := None, 8000))
    }
  }

def unmounted(s: State): Unit = s.timer.v.foreach(dom.window.clearTimeout)

def disconnect(name: String): Unit =
  Dialog.confirm(s"$name との連携を解除しますか？\n${texts(name).lost}。", okLabel = "解除").foreach { ok =>
    if (ok) Integrations.disconnect(name).recover { case e => Dialog.alertError("解除できませんでした", e) }
  }

/** 連携の後に戻ってくる場所（今の画面） */
def connectUrl(name: String): String = {
  val u = new dom.URL(Router.href.v)
  Integrations.connectUrl(name, u.pathname + u.search + u.hash)
}

def noticeClass(s: State): String = "integration-notice " + s.notice.v.map(_._1).getOrElse("")
</script>

<template>
  <template v-if='props.part == "links"'>
    <template v-for="name in Integrations.Names" :key="name">
      <template v-if="Integrations.enabled(name)">
        <a v-if="!Integrations.connected(name)" role="menuitem" :href="connectUrl(name)" :data-testid='s"$name-connect"'
           :title='s"$name で許可すると、${texts(name).shows}"'>🔗 {{ name }} と連携</a>
        <a v-else href="#" role="menuitem" :data-testid='s"$name-disconnect"' :title='s"$name と連携済み。クリックで解除"'
           @click.prevent="disconnect(name)">🔗 {{ name }} 連携中（解除）</a>
      </template>
    </template>
  </template>
  <p v-else-if="s.notice.v.isDefined" :class="noticeClass(s)" role="status" data-testid="integration-notice">
    {{ s.notice.v.map(_._2).getOrElse("") }} <button type="button" aria-label="閉じる" @click="s.notice := None">×</button>
  </p>
</template>

<style scoped>
.integration-notice { position: absolute; top: calc(100% + 6px); right: .8rem; z-index: 66; max-width: min(420px, calc(100vw - 1.6rem)); margin: 0; padding: .3rem .5rem; border-radius: 6px; font-size: .78rem; line-height: 1.5; box-shadow: 0 6px 18px rgba(0,0,0,.15); background-clip: padding-box; }
.integration-notice.ok { background: var(--c-green); border: 1px solid var(--c-green-line); }
.integration-notice.ng { background: var(--c-red); border: 1px solid var(--c-red-line); }
.integration-notice button { border: none; padding: 0 .2rem; float: right; }
a { display: block; white-space: nowrap; text-align: left; border: none; padding: .35rem .6rem; border-radius: 6px; font-size: .86rem; color: var(--fg); text-decoration: none; background: none; }
a:hover { background: var(--hover); }
</style>
