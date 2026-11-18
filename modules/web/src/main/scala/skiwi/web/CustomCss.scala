package skiwi.web

import org.scalajs.dom

import scala.collection.mutable
import scala.scalajs.js

/**
 * カスタムCSSを安全に適用する（旧 ui/customCss.ts）.
 *  - すべてのルールを表示エリア（scope）の中だけに効くよう書き換える（:root / html / body は scope に置き換え）
 *  - 外部の読み込み（@import、http(s) などの url()）、画面全体を覆える position: fixed は取り除く
 *  - url() は添付ファイル（/api/attachments/…）と data:image のみ許す
 * 解析はブラウザ自身のCSSパーサ（CSSStyleSheet）で行う. 正規表現は元の TS と同じ動きにするため JS の RegExp を使う.
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

  private val UrlSrc = """url\(\s*(['"]?)(.*?)\1\s*\)"""

  /** 値の中の url(...) の中身 */
  private def urls(value: String): List[String] = {
    val r = re(UrlSrc, "gi")
    val out = mutable.ListBuffer.empty[String]
    var m = r.exec(value)
    while (m != null) {
      out += m(2).getOrElse("")
      if (m(0).forall(_.isEmpty)) r.lastIndex += 1
      m = r.exec(value)
    }
    out.toList
  }

  /** url(...) の中身を f で読み替える */
  private def replaceUrls(value: String, f: String => String): String =
    value.asInstanceOf[js.Dynamic].replace(re(UrlSrc, "gi"),
      ((_: String, q: String, u: String) => s"url($q${f(u)}$q)"): js.Function3[String, String, String, String]).asInstanceOf[String]

  private def urlAllowed(u: String): Boolean = {
    val s = jsTrim(u)
    skiwi.web.editor.SafeUrl.isAttachmentPath(s) || test("""^data:image\/""", "i", s)
  }

  /** 括弧の中の , では区切らない */
  private def splitSelectors(sel: String): List[String] = {
    val out = mutable.ListBuffer.empty[String]
    var depth = 0
    val cur = new StringBuilder
    sel.foreach { ch =>
      if (ch == '(' || ch == '[') depth += 1
      else if (ch == ')' || ch == ']') depth -= 1
      if (ch == ',' && depth == 0) { out += cur.toString; cur.clear() } else cur += ch
    }
    out += cur.toString
    out.toList.map(jsTrim).filter(_.nonEmpty)
  }

  /** 範囲の外に効くセレクタ（「body ~ *」のような兄弟の指定）は取り除く. 全部取り除いたら None */
  private def scopeSelector(sel: String, scope: String, warn: Warn): Option[String] = {
    val out = splitSelectors(sel).flatMap { s =>
      val m = re("""^(:root|html|body)(?![\w-])""", "i").exec(s)
      if (m == null) List(s"$scope $s")
      else {
        val rest = s.substring(m(0).fold(0)(_.length))
        if (test("""^\s*[~+]""", "", rest)) { warn(s"$s: 表示エリアの外に効く指定は使えません"); Nil }
        else List(jsTrim(scope + rest))
      }
    }
    if (out.isEmpty) None else Some(out.mkString(", "))
  }

  private def sanitizeStyle(style: js.Dynamic, warn: Warn, mapUrl: MapUrl): Unit = {
    val n = style.length.asInstanceOf[Int]
    val props = (0 until n).map(i => style.item(i).asInstanceOf[String]).toList
    props.foreach { prop =>
      val value = style.getPropertyValue(prop).asInstanceOf[String]
      // var() で値を後から入れられると検査をすり抜けるので、position には使わせない
      if (prop == "position" && test("""fixed|var\(""", "i", value)) {
        style.removeProperty(prop)
        warn("position: fixed は使えません（画面全体を覆えるため）")
      }
      // image-set("https://…") や src("…") は url() を書かずに外部を読み込めるので使わせない
      else if (test("""image-set\(|(^|[^\w-])src\(""", "i", value)) {
        style.removeProperty(prop)
        warn(s"$prop: image-set() と src() は使えません（url() で添付ファイルを指定してください）")
      }
      else if (test("""url\(""", "i", value)) {
        if (urls(value).exists(u => !urlAllowed(u))) {
          style.removeProperty(prop)
          warn(s"$prop: 外部の url() は使えません（添付ファイルと data:image のみ）")
        } else mapUrl.foreach(f => style.setProperty(prop, replaceUrls(value, f), style.getPropertyPriority(prop)))
      }
    }
  }

  private def is(rule: js.Dynamic, cls: String): Boolean = {
    val c = dom.window.asInstanceOf[js.Dynamic].selectDynamic(cls)
    !js.isUndefined(c) && js.special.instanceof(rule, c)
  }

  private def ruleList(rules: js.Dynamic): List[js.Dynamic] =
    if (js.isUndefined(rules) || rules == null) Nil
    else (0 until rules.length.asInstanceOf[Int]).map(i => rules.item(i).asInstanceOf[js.Dynamic]).toList

  private def processRules(rules: js.Dynamic, scope: String, out: mutable.ListBuffer[String], warn: Warn, mapUrl: MapUrl,
                           nested: Boolean = false): Unit =
    ruleList(rules).foreach { rule =>
      if (is(rule, "CSSStyleRule")) {
        sanitizeStyle(rule.style, warn, mapUrl)
        // 入れ子のルールは中身だけ安全にする
        if (ruleList(rule.cssRules).nonEmpty) processRules(rule.cssRules, scope, mutable.ListBuffer.empty, warn, mapUrl, nested = true)
        if (!nested) scopeSelector(rule.selectorText.asInstanceOf[String], scope, warn).foreach { sel =>
          rule.selectorText = sel
          out += rule.cssText.asInstanceOf[String]
        }
      } else if (is(rule, "CSSGroupingRule")) {
        // @media / @supports / @container / @layer { … }
        processRules(rule.cssRules, scope, mutable.ListBuffer.empty, warn, mapUrl, nested)
        out += rule.cssText.asInstanceOf[String]
      } else if (is(rule, "CSSKeyframesRule")) {
        ruleList(rule.cssRules).foreach(kf => sanitizeStyle(kf.style, warn, mapUrl))
        out += rule.cssText.asInstanceOf[String]
      } else if (is(rule, "CSSFontFaceRule")) {
        val src = rule.style.getPropertyValue("src").asInstanceOf[String]
        if (urls(src).exists(u => !urlAllowed(u))) warn("@font-face: 外部のフォントは使えません（添付ファイルのみ）")
        else {
          val text = rule.cssText.asInstanceOf[String]
          out += mapUrl.fold(text)(f => replaceUrls(text, f))
        }
      } else {
        val text = rule.cssText.asInstanceOf[String]
        val head = text.asInstanceOf[js.Dynamic].split(re("""[\s{]""")).asInstanceOf[js.Array[String]](0)
        warn(s"$head は使えません")
      }
    }

  /** 利用者が @font-face・@keyframes で定義した名前に付ける接頭辞.
    * 名前は文書全体に効くので、そのままだと画面（ツールバーなど）のフォントやアニメーションを置き換えられてしまう */
  private val NamePrefix = "skiwi-u-"
  private val NameProps  = List("font-family", "font", "animation", "animation-name")

  private def unquote(s: String): String = jsTrim(s).stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'")

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
      def esc(n: String) = n.asInstanceOf[js.Dynamic].replace(re("""[.*+?^${}()|[\]\\]""", "g"), "\\$&").asInstanceOf[String]
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
      val parsed = try { sheet.replaceSync(raw); true } catch { case _: Throwable => false }
      if (!parsed) Scoped("", List("CSS を解析できませんでした"))
      else {
        val out = mutable.ListBuffer.empty[String]
        renameDefinitions(sheet.cssRules)
        processRules(sheet.cssRules, scope, out, warn, mapUrl)
        Scoped(out.mkString("\n"), warnings.toList)
      }
    }
  }

  /** 添付ファイルの URL をゲスト向けの公開リンク経由に読み替える */
  def guestAttachmentUrl(token: String)(u: String): String =
    if (u.startsWith("/api/attachments/")) s"/api/public/$token/attachments/" + u.substring("/api/attachments/".length) else u

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
