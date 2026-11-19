package skiwi.api

import io.circe.Json
import io.circe.syntax.*
import skiwi.ydoc.YEdit

/**
 * 段落だけの新しい文書を、Yjs の update（v1）として組み立てる（サーバーでページの本文を作るときに使う）。
 * 中身の組み立ては YEdit（エディタの y-prosemirror と同じ形）に任せる。
 */
object YDocBuilder {

  /** paragraphs: 段落の文字（空の段落も可）。戻り値: (Yjs update, ProseMirror JSON, 平文) */
  def paragraphs(
    paragraphs: List[String]
  ): (Array[Byte], Json, String) = {
    val ps = if paragraphs.isEmpty then List("") else paragraphs
    val blocks = ps.map { p =>
      if p.isEmpty then Json.obj("type" -> "paragraph".asJson)
      else
        Json.obj(
          "type" -> "paragraph".asJson,
          "content" -> Json.arr(Json.obj("type" -> "text".asJson, "text" -> p.asJson))
        )
    }
    val json = Json.obj("type" -> "doc".asJson, "content" -> Json.fromValues(blocks))
    (YEdit.newDocument(blocks.flatMap(_.asObject).toVector), json, ps.filter(_.nonEmpty).mkString("\n"))
  }

  /** 本文の文字を段落に分ける（改行ごと。制御文字は除く。最大500段落・各5000文字） */
  def split(
    text: String
  ): List[String] =
    text.replace("\r\n", "\n").split("\n", -1).toList
      .map(_.filterNot(c => c.isControl && c != '\t').take(5000))
      .dropWhile(_.trim.isEmpty).reverse.dropWhile(_.trim.isEmpty).reverse
      .take(500)
}
