package skiwi.shared

import munit.FunSuite

/**
 * カスタム CSS の検査（文字列で決まる部分）。渡す文字列は、ブラウザ（Chromium）が書き出し直す形に合わせてある。
 * ルールの種類の絞り込み（入れ子・グループの中の @font-face など）は DOM が要るので、画面のテスト（frontend/e2e/css.spec.ts）で確かめる。
 */
class CssGuardSpec extends FunSuite {

  private val Attachment = "(?i)^/api/(public/[\\w-]+/)?attachments/[0-9a-f-]{36}$".r
  private def allowed(u: String): Boolean = Attachment.matches(u) || u.toLowerCase.startsWith("data:image/")
  private def problem(text: String) = CssGuard.problem(text, allowed)

  private val Att = "/api/attachments/00000000-0000-0000-0000-000000000000"

  test("a) 文字列リテラルの中の url( から読まない: 後ろの外部の url() を見つける") {
    val v = """"url(data:image/" url("https://evil.example/e")"""
    assertEquals(CssGuard.urls(v), List("https://evil.example/e"))
    assertEquals(problem(v), Some(CssGuard.Problem.ExternalUrl))
    // 文字列の中に書いただけのものは url() ではない
    assertEquals(CssGuard.urls(""""url(https://evil.example/x)""""), Nil)
    assertEquals(problem("""content: "url(https://evil.example/x) image-set( position: fixed";"""), None)
    // 引用符のエスケープ・別の種類の引用符でも読み違えない
    assertEquals(CssGuard.urls(""""a\"url(" url('https://evil.example/q')"""), List("https://evil.example/q"))
  }

  test("b) var() を含む一括指定は、宣言のまとまり全体（cssText）の検査で見つける") {
    assertEquals(
      problem("""background: var(--z,) url("https://evil.example/a");"""),
      Some(CssGuard.Problem.ExternalUrl)
    )
    assertEquals(problem(s"""background: var(--z,) url("$Att");"""), None)
  }

  test("c) 宣言の並びに残った外部の url()・position: fixed を見つける") {
    assertEquals(
      problem("""background-image: url("https://evil.example/b"); position: fixed;"""),
      Some(CssGuard.Problem.ExternalUrl)
    )
    assertEquals(problem("color: red; position: fixed;"), Some(CssGuard.Problem.FixedPosition))
    assertEquals(problem("position: var(--p);"), Some(CssGuard.Problem.FixedPosition))
    assertEquals(problem("position: relative; background-position: 0px 0px; background-attachment: fixed;"), None)
  }

  test("url() を書かずに外部を読み込める関数（image-set・src）と、エスケープで書いた関数名を見つける") {
    assertEquals(
      problem("""background-image: image-set("https://evil.example/a" 1x);"""),
      Some(CssGuard.Problem.UrlFunction)
    )
    assertEquals(
      problem("""background-image: -webkit-image-set("https://evil.example/a" 1x);"""),
      Some(CssGuard.Problem.UrlFunction)
    )
    assertEquals(problem("""src: src("https://evil.example/f.woff2");"""), Some(CssGuard.Problem.UrlFunction))
    // カスタムプロパティの値は書いたままの形で残るので、エスケープを戻してから見る
    assertEquals(problem("""--x: \75rl(https://evil.example/a);"""), Some(CssGuard.Problem.ExternalUrl))
    assertEquals(problem("""--x: \69mage-set("https://evil.example/a");"""), Some(CssGuard.Problem.UrlFunction))
    assertEquals(problem("--x: URL( https://evil.example/a );"), Some(CssGuard.Problem.ExternalUrl))
    // 許すもの: 添付ファイル・data:image・関数でない src（@font-face の記述子）
    assertEquals(problem(s"""font-family: x; src: url("$Att") format("woff2"), local("Arial");"""), None)
    assertEquals(problem("""background: url("data:image/png;base64,AAAA");"""), None)
    assertEquals(problem("""background: url("data:text/html,x");"""), Some(CssGuard.Problem.ExternalUrl))
  }

  test("mapUrls は url() の中身だけを読み替える（文字列リテラルの中は変えない）") {
    def guest(u: String) = u.replace("/api/attachments/", "/api/public/tok/attachments/")
    assertEquals(
      CssGuard.mapUrls(s"""content: "url($Att)"; background: url("$Att"), url($Att);""", guest),
      s"""content: "url($Att)"; background: url("${guest(Att)}"), url("${guest(Att)}");"""
    )
  }

  private val Scope = ".skiwi-page-css"

  test("e) セレクタ: 入れ子の & を使ったものは取り除く") {
    assertEquals(CssGuard.scopeSelector("html:not(&) .toolbar", Scope), (None, List("html:not(&) .toolbar")))
    assertEquals(CssGuard.scopeSelector("& .toolbar, p", Scope), (Some(s"$Scope p"), List("& .toolbar")))
    // 文字列の中の & は入れ子ではない
    assertEquals(CssGuard.scopeSelector("""a[title="A&B"]""", Scope), (Some(s"""$Scope a[title="A&B"]"""), Nil))
  }

  test("セレクタ: 表示エリアの中だけに効くよう書き換え、兄弟へ出るものは取り除く") {
    assertEquals(CssGuard.scopeSelector("p, .a > b", Scope), (Some(s"$Scope p, $Scope .a > b"), Nil))
    assertEquals(CssGuard.scopeSelector("body", Scope), (Some(Scope), Nil))
    assertEquals(CssGuard.scopeSelector(":root p, HTML > p", Scope), (Some(s"$Scope p, $Scope > p"), Nil))
    assertEquals(CssGuard.scopeSelector("body ~ *", Scope), (None, List("body ~ *")))
    assertEquals(CssGuard.scopeSelector("body+div", Scope), (None, List("body+div")))
    // 先頭に :not() などを付けて兄弟へ出るもの
    assertEquals(CssGuard.scopeSelector("body:not(.zz) ~ *", Scope), (None, List("body:not(.zz) ~ *")))
    assertEquals(CssGuard.scopeSelector("html:has(> a) + div p", Scope), (None, List("html:has(> a) + div p")))
    // 括弧の中の結合子・カンマは区切りではない
    assertEquals(CssGuard.scopeSelector("body:has(a ~ b) p", Scope), (Some(s"$Scope:has(a ~ b) p"), Nil))
    assertEquals(CssGuard.scopeSelector(":is(h1, h2) span", Scope), (Some(s"$Scope :is(h1, h2) span"), Nil))
    // html・body で始まる別の名前はそのまま子孫として扱う
    assertEquals(CssGuard.scopeSelector("body-x ~ p", Scope), (Some(s"$Scope body-x ~ p"), Nil))
  }
}
