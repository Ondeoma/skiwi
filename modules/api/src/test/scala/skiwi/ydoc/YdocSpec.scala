package skiwi.ydoc

import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

import java.util.Base64

/** サーバー側の Yjs の実装を、本物の Yjs（frontend/test/yjs-check.mjs を node で動かす）と突き合わせる。
  * node と frontend/node_modules がない環境では飛ばす。 */
class YdocSpec extends FunSuite:

  private def node(reqs: Vector[Json]): Vector[Json] = YjsNode.run(reqs)

  private def run(body: => Unit): Unit =
    assume(YjsNode.available, "node / frontend/node_modules not found")
    body

  private val b64  = Base64.getEncoder
  private val unb64 = Base64.getDecoder
  private def bytes(j: Json): Array[Byte] = unb64.decode(j.asString.get)

  private final case class Case(full: Array[Byte], parts: Vector[Array[Byte]], ids: Vector[YId], doc: Json)

  private def gen(seeds: Range): Vector[Case] =
    node(seeds.toVector.map(s => Json.obj("mode" -> "gen".asJson, "seed" -> s.asJson, "editors" -> (2 + s % 3).asJson, "steps" -> (30 + s % 50).asJson)))
      .map { r =>
        val c = r.hcursor
        Case(
          bytes(c.downField("full").focus.get),
          c.downField("parts").focus.get.asArray.get.map(bytes),
          c.downField("ids").focus.get.asArray.get.map(a => YId(a.asArray.get(0).asNumber.get.toLong.get, a.asArray.get(1).asNumber.get.toLong.get)),
          c.downField("doc").focus.get
        )
      }

  private def blocksOf(doc: Json): Vector[Json] = doc.hcursor.downField("content").focus.flatMap(_.asArray).getOrElse(Vector.empty)

  test("最上位のブロックの並びが Yjs と同じ（全状態から・各編集の update から）") {
    run {
      gen(1 to 200).zipWithIndex.foreach { (c, i) =>
        val ids = (d: YEdit.Doc) => d.blocks.map(_._1.id)
        assertEquals(ids(YEdit.load(Seq(c.full))), c.ids, s"full #$i")
        assertEquals(ids(YEdit.load(c.parts)), c.ids, s"parts #$i")
        // 重なった update（全状態と各編集）を混ぜても同じ
        assertEquals(ids(YEdit.load(c.parts.take(c.parts.size / 2) :+ c.full)), c.ids, s"mixed #$i")
        // 本文の JSON（属性・書式つきの文字）も Yjs（y-prosemirror と同じ読み方）と同じ
        assertEquals(YEdit.load(c.parts).json.map(normalize), blocksOf(c.doc).map(normalize), s"json #$i")
      }
    }
  }

  private val newBlocks: Vector[Json] = Vector(
    parse("""{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"追加した見出し"}]}""").toOption.get,
    parse("""{"type":"paragraph","content":[{"type":"text","text":"太字","marks":[{"type":"bold"}]},{"type":"text","text":"と"},
      {"type":"text","text":"リンク😀","marks":[{"type":"link","attrs":{"href":"https://example.com"}},{"type":"italic"}]},
      {"type":"hardBreak"},{"type":"text","text":"改行の後"}]}""").toOption.get,
    parse("""{"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"一つ目"}]},
      {"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"入れ子"}]}]}]}]}]}""").toOption.get,
    parse("""{"type":"paragraph"}""").toOption.get,
    parse("""{"type":"codeBlock","attrs":{"language":"scala"},"content":[{"type":"text","text":"val x = 1\nprintln(x)"}]}""").toOption.get
  )
  private def objs(js: Vector[Json]): Vector[JsonObject] = js.flatMap(_.asObject)

  test("置き換え・追記・先頭への挿入を本物の Yjs に当てると、期待した本文になる") {
    run {
      val cases = gen(100 to 140)
      val reqs = cases.zipWithIndex.flatMap { (c, i) =>
        val doc = YEdit.load(Seq(c.full))
        val n   = doc.blocks.size
        val ranges = Vector((0, n), (n, n), (0, 0), (n / 3, (n * 2 + 2) / 3))
        ranges.distinct.map { (from, until) =>
          val upd      = YEdit.replace(doc, from, until, objs(newBlocks))
          val expected = blocksOf(c.doc).patch(from, newBlocks, until - from)
          (Json.obj("mode" -> "apply".asJson, "updates" -> Json.arr(b64.encodeToString(c.full).asJson, b64.encodeToString(upd).asJson)), expected, s"#$i $from..$until of $n")
        }
      }
      node(reqs.map(_._1)).zip(reqs).foreach { (res, req) =>
        assertEquals(blocksOf(res.hcursor.downField("doc").focus.get), req._2.map(normalize), req._3)
      }
    }
  }

  test("空の文書を作る update（新しいページ）") {
    run {
      val upd = YEdit.newDocument(objs(newBlocks))
      val res = node(Vector(Json.obj("mode" -> "apply".asJson, "updates" -> Json.arr(b64.encodeToString(upd).asJson))))
      assertEquals(blocksOf(res.head.hcursor.downField("doc").focus.get), newBlocks.map(normalize))
    }
  }

  test("Markdown から作った本文を本物の Yjs で読むと同じ本文になる") {
    run {
      val md = "# 題\n\n**太字**と*斜体*と`code`と[リンク](https://e.com)\n二行目\n\n- 一\n  - 入れ子\n1. 番号\n\n- [x] 済み\n\n> 引用\n\n" +
        "```js\nconst a = 1\n```\n\n| 名前 | 値 |\n|---|---|\n| a | 1 |\n\n---\n\n![図](https://e.com/a.png)"
      val blocks = skiwi.api.MarkdownParse.blocks(md)
      assertEquals(YjsNode.blocks(Seq(YEdit.newDocument(objs(blocks)))), blocks.map(normalize))
    }
  }

  /** 比べるための形: マークは名前順、属性のないマークは attrs を付けない。 */
  private def normalize(j: Json): Json = j.arrayOrObject(
    j,
    arr => Json.fromValues(arr.map(normalize)),
    obj =>
      Json.fromJsonObject(obj.toIterable.foldLeft(JsonObject.empty) { case (acc, (k, v)) =>
        if k == "marks" then
          acc.add(k, Json.fromValues(v.asArray.get.map { m =>
            val o = m.asObject.get
            if o("attrs").flatMap(_.asObject).forall(_.isEmpty) then Json.obj("type" -> o("type").get) else m
          }.sortBy(_.hcursor.get[String]("type").getOrElse(""))))
        else acc.add(k, normalize(v))
      })
  )

