package skiwi.web.content

import org.scalajs.dom

import scala.scalajs.js

/** DOM の操作の補助（Scala.js の型にないもの・使いにくいものは JS のまま呼ぶ） */
private[content] object Dom {

  def parse(
    html: String
  ): dom.HTMLDocument =
    new dom.DOMParser().parseFromString(html, dom.MIMEType.`text/html`).asInstanceOf[dom.HTMLDocument]

  def all(
    root: js.Any,
    sel: String
  ): List[dom.Element] = {
    val nl = root.asInstanceOf[js.Dynamic].querySelectorAll(sel).asInstanceOf[dom.NodeList[dom.Element]]
    (0 until nl.length).map(nl(_)).toList
  }

  def first(
    root: js.Any,
    sel: String
  ): Option[dom.Element] =
    Option(root.asInstanceOf[js.Dynamic].querySelector(sel).asInstanceOf[dom.Element])

  def children(
    e: dom.Element
  ): List[dom.Element] = (0 until e.children.length).map(e.children(_)).toList

  def remove(
    e: dom.Element
  ): Unit = e.asInstanceOf[js.Dynamic].remove()

  def replaceWith(
    e: dom.Element,
    by: dom.Element
  ): Unit = e.asInstanceOf[js.Dynamic].replaceWith(by)

  /** JS の textContent?.trim() ?? '' */
  def text(
    e: dom.Node
  ): String = Option(e.textContent).fold("")(JsLike.trim)

  def attr(
    e: dom.Element,
    name: String
  ): String = Option(e.getAttribute(name)).getOrElse("")

}
