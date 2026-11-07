import { createRouter, createWebHistory } from 'vue-router'

// Scalactive(Scala.js)に移した画面. view は skiwi.web.Main.mountView の画面名
const scalactive = (view: string) => ({ component: () => import('./scalactive/ScalactiveView.vue'), props: { view } })

// 画面は開いたときに読み込む（エディタ・ホワイトボードなどの大きな部品を最初の読み込みに含めない）
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: () => import('./views/HomeView.vue') },
    { path: '/settings/tokens', ...scalactive('tokens') },
    { path: '/settings/api', component: () => import('./views/ApiDocsView.vue') },
    // データベースのフォーム（ログイン不要）
    { path: '/f/:token', component: () => import('./views/FormView.vue'), props: true },
    // ユーザーマニュアル
    { path: '/help/:chapter?', component: () => import('./views/HelpView.vue'), props: true },
    { path: '/admin/audit', ...scalactive('audit') },
    { path: '/admin/shares', component: () => import('./views/AdminSharesView.vue') },
    {
      path: '/w/:wid',
      component: () => import('./views/WorkspaceView.vue'),
      props: true,
      children: [{ path: 'p/:pid', component: () => import('./views/PageView.vue'), props: true }],
    },
    // PDF（印刷）用: ページと子ページを目次付きでまとめる
    { path: '/print/w/:wid/p/:pid', component: () => import('./views/PrintView.vue'), props: true },
    { path: '/print/s/:token', component: () => import('./views/PrintView.vue'), props: true },
    // ゲスト向けの公開ページ（ログイン不要・閲覧のみ）
    { path: '/s/:token/:pid?', component: () => import('./views/GuestView.vue'), props: true },
  ],
})
