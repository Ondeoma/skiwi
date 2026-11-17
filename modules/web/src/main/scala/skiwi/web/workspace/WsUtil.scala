package skiwi.web.workspace

import io.github.ondeoma.scalactive.components.ComponentManager.*
import io.github.ondeoma.scalactive.routes.Router
import org.scalajs.dom
import skiwi.web.Prefs

import scala.scalajs.js

/** URL（/w/:wid/p/:pid）から開いているワークスペース・ページを読む */
object WsRoute {

  private val Re = """^/w/([^/]+)(?:/p/([^/]+))?/?$""".r

  def path(href: String): String = new dom.URL(href).pathname

  /** (wid, pid) */
  def parse(href: String): (Option[String], Option[String]) = path(href) match {
    case Re(w, p) => (Some(js.URIUtils.decodeURIComponent(w)), Option(p).map(js.URIUtils.decodeURIComponent))
    case _ => (None, None)
  }

  def wid(href: String): Option[String] = parse(href)._1

  def pid(href: String): Option[String] = parse(href)._2

  /** 今開いているページ（リアクティブに読む） */
  def currentPid: Option[String] = pid(Router.href.v)

  def currentWid: Option[String] = wid(Router.href.v)

  /** 今の URL のパスが path か（RouterLink の router-link-active と同じ判定. "/" は完全一致だけ） */
  def isActive(p: String): Boolean = {
    val cur = path(Router.href.v).stripSuffix("/")
    val t = p.stripSuffix("/")
    cur == t || (t.nonEmpty && cur.startsWith(t + "/"))
  }

}


/** 表示している間だけ付けるイベントリスナー（mounted で on、unmounted で clear） */
final class Listeners {

  private var offs: List[() => Unit] = Nil

  def on[E <: dom.Event](target: dom.EventTarget, tpe: String, capture: Boolean = false)(f: E => Unit): Unit = {
    val h: js.Function1[dom.Event, Unit] = (e: dom.Event) => f(e.asInstanceOf[E])
    target.addEventListener(tpe, h, capture)
    offs ::= (() => target.removeEventListener(tpe, h, capture))
  }

  def timer(ms: Int)(f: => Unit): Unit = {
    val id = dom.window.setInterval(() => f, ms)
    offs ::= (() => dom.window.clearInterval(id))
  }

  def add(off: () => Unit): Unit = offs ::= off

  def clear(): Unit = {
    offs.foreach(_())
    offs = Nil
  }

}

/**
 * ⌘K のクイック切り替えなどからの操作（window の 'skiwi:command' イベント. detail が操作名）.
 * new-page / trash / toggle-side / notifications / search / new-workspace / edit-page / comments
 */
object WsCommand {

  val Event = "skiwi:command"

  def send(cmd: String): Unit =
    dom.window.dispatchEvent(new dom.CustomEvent(Event, new dom.CustomEventInit { detail = cmd }))

  def name(e: dom.Event): String = e.asInstanceOf[dom.CustomEvent].detail match {
    case s: String => s
    case _ => ""
  }

  def openPalette(): Unit = dom.window.dispatchEvent(new dom.Event("skiwi:palette"))

  def openShortcuts(): Unit = dom.window.dispatchEvent(new dom.Event("skiwi:shortcuts"))

}

/**
 * サイドメニューの開閉. 広い画面では横に置き、開閉をこのブラウザに保存する.
 * 狭い画面（スマートフォンなど）では閉じた状態から始め、狭くなったときも閉じる（保存はしない）.
 */
object SideMenu {

  private val Key = "skiwi.sideOpen"
  private val mq = dom.window.matchMedia("(max-width: 768px)")

  val narrow: RV[Boolean] = RV(mq.matches)
  val open: RV[Boolean] = RV(if (mq.matches) false else !Prefs.get(Key).contains("0"))

  mq.addEventListener("change", (_: dom.Event) => {
    narrow := mq.matches
    if (mq.matches) open := false
  })

  open.addWatcher(_ => if (!narrow.v) Prefs.set(Key, if (open.v) "1" else "0"))

  def toggle(): Unit = open := !open.v

}

object WsText {

  /** 日本語の並び（localeCompare(b, 'ja')）で a が先か */
  def jaLess(a: String, b: String): Boolean = a.asInstanceOf[js.Dynamic].localeCompare(b, "ja").asInstanceOf[Double] < 0

}

/**
 * このブラウザに保存する RV（Prefs.stored と同じ. 監視をどのコンポーネントにも結び付けないため addWatcher で保存する）.
 * serialize が None なら保存しない
 */
object WsPrefs {

  def stored[A](key: String, parse: Option[String] => A, serialize: A => Option[String] = (a: A) => Some(a.toString)): RV[A] = {
    val r = RV(parse(Prefs.get(key)))
    r.addWatcher(_ => serialize(r.v).foreach(Prefs.set(key, _)))
    r
  }

}
