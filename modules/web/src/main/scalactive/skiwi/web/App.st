<script lang="scala">
import io.github.ondeoma.scalactive.components.routes.RouterComponent
import org.scalajs.dom
import skiwi.web.admin.*
import skiwi.web.database.FormView
import skiwi.web.parts.*
import skiwi.web.views.*
import skiwi.web.workspace.*

// 画面全体の枠. /w/... （ワークスペース・ページ）は WorkspaceView が自分で URL を見て描く
// （ページの移動で編集中のページを作り直さないように外に置く）.
// それ以外の画面は RouterComponent で切り替える（クエリ文字列だけの変化では作り直さない. 使い方の検索語・PDF の設定を URL に残すため）.

def opt(r: io.github.ondeoma.scalactive.facades.URLPatternResult, key: String): Option[String] =
  r.pathname.groups.get(key).filter(v => v != null && !scala.scalajs.js.isUndefined(v) && v.nonEmpty)

val routes: PathPatternRoutes = List(
  ("/", _ => HomeView()),
  // 開発用のログイン（DEV_LOGIN=true のとき）
  ("/login", _ => LoginView()),
  ("/settings/tokens", _ => TokensView()),
  ("/settings/api", _ => ApiDocsView()),
  // データベースのフォーム（ログイン不要）
  ("/f/:token", r => FormView(token = r.pathname.groups("token"))),
  // ユーザーマニュアル
  ("/help", _ => HelpView(chapter = None)),
  ("/help/:chapter", r => HelpView(chapter = opt(r, "chapter"))),
  ("/admin/audit", _ => AuditView()),
  ("/admin/shares", _ => AdminSharesView()),
  ("/admin/integrations", _ => AdminIntegrationsView()),
  ("/admin/agents", _ => AdminAgentsView()),
  // PDF（印刷）用: ページと子ページを目次付きでまとめる
  ("/print/w/:wid/p/:pid", r => PrintView(wid = opt(r, "wid"), pid = opt(r, "pid"), token = None)),
  ("/print/s/:token", r => PrintView(wid = None, pid = None, token = opt(r, "token"))),
  // ゲスト向けの公開ページ（ログイン不要・閲覧のみ）
  ("/s/:token", r => GuestView(token = r.pathname.groups("token"), pid = None)),
  ("/s/:token/:pid", r => GuestView(token = r.pathname.groups("token"), pid = opt(r, "pid"))),
)

/** 存在しない URL の判定に使うルート（ワークスペースの画面を含む. Main で Fallback に渡す） */
val known: PathPatternRoutes = routes ++ List(
  ("/w/:wid", _ => HomeView()),
  ("/w/:wid/p/:pid", _ => HomeView()),
)

def inWorkspace = Router.href.mapC(h => new dom.URL(h).pathname.startsWith("/w/"))

def mounted(): Unit = {
  // ログイン後に戻る場所（フォームなどでログインを求めたとき）
  scala.util.Try {
    Option(dom.window.sessionStorage.getItem("skiwi.returnTo")).foreach { back =>
      if (back.startsWith("/") && !back.startsWith("//")) {
        dom.window.sessionStorage.removeItem("skiwi.returnTo")
        if (dom.window.location.pathname == "/") Router.replace(back)
      }
    }
  }
}

def progressLabel = Store.progress.mapC(_.fold("")(p => s"${p.label}… ${p.done} / ${p.total}"))
def progressStyle = Store.progress.mapC(_.fold("width: 0%")(p => s"width: ${if (p.total > 0) p.done * 100.0 / p.total else 0}%"))
</script>

<template>
  <WorkspaceView v-if="inWorkspace" />
  <RouterComponent v-else :routes="routes" :reloadOnQueryChange="false" />
  <PagePicker />
  <CommandPalette />
  <ShortcutsDialog />
  <div v-if="Store.progress.mapC(_.isDefined)" class="progress-toast" role="status" aria-live="polite" data-testid="progress">
    <div class="label">{{ progressLabel }}</div>
    <div class="bar"><div :style="progressStyle"></div></div>
  </div>
  <DialogHost />
</template>

<style scoped>
.progress-toast { position: fixed; right: 16px; bottom: 16px; z-index: 950; width: 280px; background: var(--bg); border: 1px solid var(--line); border-radius: 10px; padding: .6rem .8rem; box-shadow: 0 10px 30px rgba(0,0,0,.25); }
.label { font-size: .85rem; margin-bottom: .4rem; }
.bar { height: 6px; background: var(--hover); border-radius: 3px; overflow: hidden; }
.bar div { height: 100%; background: var(--accent); transition: width .2s; }
</style>
