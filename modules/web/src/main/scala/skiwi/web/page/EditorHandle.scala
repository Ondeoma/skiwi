package skiwi.web.page

import io.circe.*
import scala.scalajs.js

/**
 * エディタ（JS の editor モジュールの create が返す handle）. 旧 components/Editor.vue の公開している操作.
 * JS の値と circe の JSON の行き来もここで行う.
 */
final class EditorHandle(
  val h: js.Dynamic) {

  def setEditable(
    b: Boolean
  ): Unit = h.setEditable(b)

  def isDestroyed: Boolean = h.isDestroyed().asInstanceOf[Boolean]

  /** 本文（Tiptap の JSON） */
  def getJSON(): js.Any = h.getJSON().asInstanceOf[js.Any]

  /** 現在の文書状態（{ydoc, content, text}）. 版の保存・復元前の退避に使う */
  def getState(): Json = EditorHandle.toJson(h.getState().asInstanceOf[js.Any])

  def applyContent(
    content: Json
  ): Unit = h.applyContent(EditorHandle.toJs(content))

  def setCommentMark(
    from: Int,
    to: Int,
    id: String
  ): Unit = h.setCommentMark(from, to, id)

  def unsetCommentMark(
    id: String
  ): Unit = h.unsetCommentMark(id)

  def destroy(): Unit = h.destroy()

}

object EditorHandle {

  def toJson(
    v: js.Any
  ): Json =
    if (v == null || js.isUndefined(v)) Json.Null else io.circe.parser.parse(js.JSON.stringify(v)).getOrElse(Json.Null)

  def toJs(
    j: Json
  ): js.Any = js.JSON.parse(j.noSpaces)

}
