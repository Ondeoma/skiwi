<script lang="scala">
import org.scalajs.dom
import skiwi.web.Theme

import scala.scalajs.js

// 任意のHTMLを、サンドボックス化した iframe の中で表示する（ゲスト向けの本文の上下）.
//  - allow-same-origin を付けないので、中のスクリプトは別オリジン扱いになり、skiwi の Cookie・API・画面には触れられない
//  - 高さは中身に合わせる（中から postMessage で知らせる）
//  - リンクはページ全体で開く（<base target="_top">。利用者の操作によるときだけ許可）
//  - 画面の CSP を引き継がないよう、srcdoc ではなく /sandbox.html を開き、HTML は読み込み後に postMessage で渡す
//    （HTML が変わったら URL を変えて開き直す）
case class Props(html: String, label: String = "")

case class State(frame: ElementRef[dom.HTMLIFrameElement] = ElementRef(),
                 height: RV[Int] = RV(0),
                 key: String = scala.util.Random.alphanumeric.take(10).mkString)

def initState() = State()

// スクリプトの閉じタグ（.st の <script> を閉じないよう分けて書く）
private val closeScript = "<" + "/script>"

def doc(p: Props, s: State): String =
  s"""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<base target="_top"><meta name="color-scheme" content="${if (Theme.isDark) "dark" else "light"}">
<style>html,body{margin:0;padding:0;background:transparent;font-family:system-ui,-apple-system,"Hiragino Sans","Noto Sans JP",sans-serif;}img,video{max-width:100%;height:auto}</style>
</head><body>${p.html}
<script>(function(){var k="${s.key}";function s(){parent.postMessage({skiwiFrame:k,h:Math.ceil(document.documentElement.getBoundingClientRect().height)},'*')}
if(window.ResizeObserver){new ResizeObserver(s).observe(document.documentElement)}window.addEventListener('load',s);s();})();$closeScript</body></html>"""

def mounted(s: State): Unit = {
  val f = (e: dom.MessageEvent) =>
    s.frame.foreach { fr =>
      if (e.source == fr.contentWindow) {
        val d = e.data.asInstanceOf[js.Dynamic]
        if (d != null && !js.isUndefined(d) && (d.skiwiFrame: Any) == s.key && js.typeOf(d.h) == "number")
          s.height := math.min(math.max(d.h.asInstanceOf[Double], 0), 5000).toInt
      }
    }
  useEventListener[dom.MessageEvent](dom.window, "message")(f)
  ()
}

/** 枠が読み込まれたら HTML を渡す（枠は最初の1回だけ書き込む） */
def loaded(p: Props, s: State): Unit =
  s.frame.foreach(fr => Option(fr.contentWindow).foreach(_.postMessage(js.Dynamic.literal(skiwiSandbox = 1, doc = doc(p, s)), "*")))

def src(p: Props): String = s"/sandbox.html?v=${Integer.toHexString(p.html.hashCode)}"

def title(p: Props): String = if (p.label.isEmpty) "埋め込みHTML" else p.label
</script>

<template>
  <iframe
    ref="s.frame"
    class="sandboxed-html"
    :src="src(props)"
    @load="loaded(props, s)"
    sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox allow-forms allow-top-navigation-by-user-activation"
    referrerpolicy="no-referrer"
    :title="title(props)"
    :style='s"height: ${s.height.v}px"'
    data-testid="guest-html"
  ></iframe>
</template>

<style scoped>
.sandboxed-html { display: block; width: 100%; border: none; background: transparent; }
</style>
