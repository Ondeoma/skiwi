package skiwi.web

import org.scalajs.dom
import skiwi.shared.CssGuard

import scala.collection.mutable
import scala.scalajs.js

/**
 * カスタムCSSを安全に適用する（旧 ui/customCss.ts）.
 *  - すべてのルールを表示エリア（scope）の中だけに効くよう書き換える（:root / html / body は scope に置き換え）
 *  - 外部の読み込み（@import、http(s) などの url()）、画面全体を覆える position: fixed は取り除く
 *  - url() は添付ファイル（/api/attachments/…）と data:image のみ許す
 *  - 使えるルールは、スタイルのルール・@media・@supports・@keyframes・@font-face だけ. 入れ子のルールは使えない
 * 解析はブラウザ自身のCSSパーサ（CSSStyleSheet）で行い、出力は検査した部品から組み立て直す（processRules）.
 * 文字列だけで決まる検査（url() の読み取り・セレクタの書き換え）は skiwi.shared.CssGuard にある（JVM のテストで確かめる）.
 */
object CustomCss {

  val PageScope = ".skiwi-page-css"

  /** css: 範囲限定・安全化した CSS / warnings: 適用されない記述の警告 */
  final case class Scoped(css: String, warnings: List[String])

  private type Warn = String => Unit
  private type MapUrl = Option[String => String]

  private def re(src: String, flags: String = ""): js.RegExp = new js.RegExp(src, flags)

  private def test(src: String, flags: String, s: String): Boolean = re(src, flags).test(s)

  private def jsTrim(s: String): String = s.asInstanceOf[js.Dynamic].trim().asInstanceOf[String]

  private def urlAllowed(u: String): Boolean = {
    val s = jsTrim(u)
    skiwi.web.editor.SafeUrl.isAttachmentPath(s) || test("""^data:image\/""", "i", s)
  }

  private def problemText(p: CssGuard.Problem): String = p match {
    case CssGuard.Problem.ExternalUrl => "外部の url() は使えません（添付ファイルと data:image のみ）"
    case CssGuard.Problem.UrlFunction => "image-set() と src() は使えません（url() で添付ファイルを指定してください）"
    case CssGuard.Problem.FixedPosition => "position: fixed は使えません（画面全体を覆えるため）"
  }

  /** 範囲の外に効くセレクタ（「body ~ *」のような兄弟の指定、入れ子の &）は取り除く. 全部取り除いたら None */
  private def scopeSelector(sel: String, scope: String, warn: Warn): Option[String] = {
    val (out, rejected) = CssGuard.scopeSelector(sel, scope)
    rejected.foreach(s => warn(s"$s: 表示エリアの外に効く指定は使えません"))
    out
  }

  /**
   * 宣言のまとまりを安全にして、出力する文字列（宣言の並び）を返す. まとまりごと使えなければ None.
   * まずプロパティごとに使えないものを取り除き、そのあと全体（cssText）をもう一度検査する:
   * var() を含む一括指定（background: var(--z,) url(…) など）は、個別のプロパティの値が空になって最初の検査にかからないため
   */
  private def sanitizeStyle(style: js.Dynamic, warn: Warn, mapUrl: MapUrl): Option[String] = {
    val n = style.length.asInstanceOf[Int]
    val props = (0 until n).map(i => style.item(i).asInstanceOf[String]).toList
    props.foreach { prop =>
      val value = style.getPropertyValue(prop).asInstanceOf[String]
      // var() で値を後から入れられると検査をすり抜けるので、position には使わせない
      val problem =
        if (prop == "position" && CssGuard.fixedPosition(value)) Some(CssGuard.Problem.FixedPosition)
        else CssGuard.problem(value, urlAllowed)
      problem.foreach { p =>
        style.removeProperty(prop)
        warn(if (p == CssGuard.Problem.FixedPosition) problemText(p) else s"$prop: ${problemText(p)}")
      }
    }
    val text = style.cssText.asInstanceOf[String]
    CssGuard.problem(text, urlAllowed) match {
      case Some(p) =>
        warn(s"${problemText(p)}（この { } の中の指定は、すべて適用されません）")
        None
      case None => Some(mapUrl.fold(text)(f => CssGuard.mapUrls(text, f)))
    }
  }

  private def is(rule: js.Dynamic, cls: String): Boolean = {
    val c = dom.window.asInstanceOf[js.Dynamic].selectDynamic(cls)
    !js.isUndefined(c) && js.special.instanceof(rule, c)
  }

  private def ruleList(rules: js.Dynamic): List[js.Dynamic] =
    if (js.isUndefined(rules) || rules == null) Nil
    else (0 until rules.length.asInstanceOf[Int]).map(i => rules.item(i).asInstanceOf[js.Dynamic]).toList

  private def cssEscape(name: String): String = js.Dynamic.global.CSS.escape(name).asInstanceOf[String]

  /**
   * 使えるルールだけを、検査した部品から組み立て直して返す（許可したもの以外は出力しない）.
   * ブラウザの rule.cssText をそのまま出すと、検査していない部分（入れ子のルール、その後ろの宣言、グループの中の別の種類のルール）が
   * 一緒に出てしまうので、使わない.
   *  - スタイルのルール: セレクタを範囲の中に書き換え、先頭の宣言だけを出す. 入れ子のルール（& で範囲の外に効かせられる）は出さない
   *  - @media / @supports: 条件はそのまま、中身は同じ規則で組み立て直す
   *  - @keyframes / @font-face: 宣言を検査して出す
   */
  private def processRules(rules: js.Dynamic, scope: String, warn: Warn, mapUrl: MapUrl): List[String] =
    ruleList(rules).flatMap { rule =>
      if (is(rule, "CSSStyleRule")) {
        if (ruleList(rule.cssRules).nonEmpty) warn("入れ子のルール（{ } の中の { }）は使えません（外側の指定だけが適用されます）")
        for {
          body <- sanitizeStyle(rule.style, warn, mapUrl).toList
          if jsTrim(body).nonEmpty
          sel <- scopeSelector(rule.selectorText.asInstanceOf[String], scope, warn).toList
        } yield s"$sel { $body }"
      } else if (is(rule, "CSSMediaRule")) group(s"@media ${rule.media.mediaText}", rule, scope, warn, mapUrl)
      else if (is(rule, "CSSSupportsRule")) group(s"@supports ${rule.conditionText}", rule, scope, warn, mapUrl)
      else if (is(rule, "CSSKeyframesRule")) {
        val frames = ruleList(rule.cssRules).filter(is(_, "CSSKeyframeRule")).flatMap { kf =>
          sanitizeStyle(kf.style, warn, mapUrl).map(body => s"${kf.keyText} { $body }")
        }
        List(s"@keyframes ${cssEscape(rule.name.asInstanceOf[String])} { ${frames.mkString(" ")} }")
      } else if (is(rule, "CSSFontFaceRule")) {
        val text = rule.style.cssText.asInstanceOf[String]
        CssGuard.problem(text, urlAllowed) match {
          case Some(_) => warn("@font-face: 外部のフォントは使えません（添付ファイルのみ）"); Nil
          case None => List(s"@font-face { ${mapUrl.fold(text)(f => CssGuard.mapUrls(text, f))} }")
        }
      } else {
        val text = rule.cssText.asInstanceOf[String]
        val head = text.asInstanceOf[js.Dynamic].split(re("""[\s{]""")).asInstanceOf[js.Array[String]](0)
        // 入れ子のルールの後ろに書いた宣言（CSSNestedDeclarations）などは名前がない
        warn(if (head.startsWith("@")) s"$head は使えません" else "この位置の指定は使えません")
        Nil
      }
    }

  /** @media / @supports: 中身を組み立て直す（中身が全部使えなければ出さない） */
  private def group(head: String, rule: js.Dynamic, scope: String, warn: Warn, mapUrl: MapUrl): List[String] = {
    val inner = processRules(rule.cssRules, scope, warn, mapUrl)
    if (inner.isEmpty) Nil else List(s"$head {\n${inner.mkString("\n")}\n}")
  }

  /**
   * 利用者が @font-face・@keyframes で定義した名前に付ける接頭辞.
   * 名前は文書全体に効くので、そのままだと画面（ツールバーなど）のフォントやアニメーションを置き換えられてしまう
   */
  private val NamePrefix = "skiwi-u-"
  private val NameProps = List("font-family", "font", "animation", "animation-name")

  private def unquote(s: String): String =
    jsTrim(s).stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'")

  /** 定義した名前（フォント・キーフレーム）に接頭辞を付け、それを使う宣言も書き換える */
  private def renameDefinitions(rules: js.Dynamic): Unit = {
    def all(rs: js.Dynamic): List[js.Dynamic] = ruleList(rs).flatMap(r => r :: all(r.cssRules))
    val rs = all(rules)
    val names = rs.flatMap { r =>
      if (is(r, "CSSFontFaceRule")) Some(unquote(r.style.getPropertyValue("font-family").asInstanceOf[String]))
      else if (is(r, "CSSKeyframesRule")) Some(r.name.asInstanceOf[String])
      else None
    }.filter(_.nonEmpty).distinct
    if (names.nonEmpty) {
      def esc(n: String) =
        n.asInstanceOf[js.Dynamic].replace(re("""[.*+?^${}()|[\]\\]""", "g"), "\\$&").asInstanceOf[String]
      // 引用符付き・なしのどちらの書き方でも、区切り（先頭・カンマ・空白）に挟まれた名前だけを置き換える
      val pattern = re(raw"""(^|[\s,])(["']?)(${names.map(esc).mkString("|")})\2(?=$$|[\s,;!])""", "g")
      def renameIn(style: js.Dynamic): Unit =
        if (!js.isUndefined(style) && style != null) NameProps.foreach { prop =>
          val v = style.getPropertyValue(prop).asInstanceOf[String]
          if (v.nonEmpty) {
            val nv = v.asInstanceOf[js.Dynamic].replace(pattern, s"$$1\"$NamePrefix$$3\"").asInstanceOf[String]
            if (nv != v) style.setProperty(prop, nv, style.getPropertyPriority(prop))
          }
        }
      rs.foreach { r =>
        if (is(r, "CSSKeyframesRule")) r.name = NamePrefix + r.name.asInstanceOf[String]
        else renameIn(r.style)
      }
    }
  }

  /** CSS を範囲限定・安全化する. 解析できない部分はブラウザの規則どおり読み飛ばされる */
  def scopeCss(raw: String, scope: String = PageScope, mapUrl: MapUrl = None): Scoped = {
    val warnings = mutable.LinkedHashSet.empty[String]
    val warn: Warn = w => warnings += w
    if (test("""@import\b""", "i", raw)) warn("@import は使えません（外部の読み込み）")
    if (jsTrim(raw).isEmpty) Scoped("", Nil)
    else {
      val sheet = js.Dynamic.newInstance(js.Dynamic.global.CSSStyleSheet)()
      val parsed = try { sheet.replaceSync(raw); true }
      catch { case _: Throwable => false }
      if (!parsed) Scoped("", List("CSS を解析できませんでした"))
      else {
        renameDefinitions(sheet.cssRules)
        val out = processRules(sheet.cssRules, scope, warn, mapUrl)
        Scoped(out.mkString("\n"), warnings.toList)
      }
    }
  }

  /** 添付ファイルの URL をゲスト向けの公開リンク経由に読み替える */
  def guestAttachmentUrl(token: String)(u: String): String =
    if (u.startsWith("/api/attachments/")) s"/api/public/$token/attachments/" + u.substring("/api/attachments/".length)
    else u

  /** 重ねたCSSを <head> に入れる（旧 useCustomCss）. set で入れ替え、destroy で取り除く */
  final class Applier {

    private val el = dom.document.createElement("style").asInstanceOf[dom.HTMLStyleElement]
    el.setAttribute("data-custom-css", "")
    dom.document.head.appendChild(el)

    /** guestToken: ゲスト表示のトークン（添付ファイルを公開リンク経由にする） */
    def set(layers: Seq[String], guestToken: Option[String] = None): Unit = {
      val map = guestToken.map(t => guestAttachmentUrl(t))
      el.textContent = layers.map(c => scopeCss(c, PageScope, map).css).mkString("\n")
    }

    def destroy(): Unit = el.remove()

  }

  def applier(): Applier = new Applier

}
