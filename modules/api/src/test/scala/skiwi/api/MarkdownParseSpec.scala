package skiwi.api

import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite

/** Markdown → 本文の JSON。 */
class MarkdownParseSpec extends FunSuite {

  private def j(
    s: String
  ): Json = parse(s).toOption.get
  private def p(
    s: String
  ): Vector[Json] = MarkdownParse.blocks(s)

  test("見出し・段落（行内の改行）・区切り線・コード・mermaid") {
    assertEquals(
      p("# 題\n\n一行目\n二行目\n\n---\n\n```scala\nval x = 1\n```\n\n```mermaid\ngraph TD\n```"),
      Vector(
        j("""{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"題"}]}"""),
        j(
          """{"type":"paragraph","content":[{"type":"text","text":"一行目"},{"type":"hardBreak"},{"type":"text","text":"二行目"}]}"""
        ),
        j("""{"type":"horizontalRule"}"""),
        j("""{"type":"codeBlock","attrs":{"language":"scala"},"content":[{"type":"text","text":"val x = 1"}]}"""),
        j("""{"type":"mermaid","attrs":{"code":"graph TD"}}""")
      )
    )
  }

  test("リスト（入れ子・番号・ToDo）と引用") {
    assertEquals(
      p("- 一\n  - 入れ子\n- 二\n\n3. 三\n4. 四\n\n- [x] 済み\n- [ ] まだ\n\n> 引用\n> 続き"),
      Vector(
        j("""{"type":"bulletList","content":[
        {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"一"}]},
          {"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"入れ子"}]}]}]}]},
        {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"二"}]}]}]}"""),
        j("""{"type":"orderedList","attrs":{"start":3},"content":[
        {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"三"}]}]},
        {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"四"}]}]}]}"""),
        j(
          """{"type":"taskList","content":[
        {"type":"taskItem","attrs":{"checked":true},"content":[{"type":"paragraph","content":[{"type":"text","text":"済み"}]}]},
        {"type":"taskItem","attrs":{"checked":false},"content":[{"type":"paragraph","content":[{"type":"text","text":"まだ"}]}]}]}"""
        ),
        j(
          """{"type":"blockquote","content":[{"type":"paragraph","content":[{"type":"text","text":"引用"},{"type":"hardBreak"},{"type":"text","text":"続き"}]}]}"""
        )
      )
    )
  }

  test("行内の書式・リンク・URL・エスケープ・和文の _") {
    assertEquals(
      MarkdownParse.inlines("**太字**と*斜体*と~~消し~~と`a*b`と[リンク](https://e.com)、https://x.jp/p 。\\*そのまま\\*、snake_case_名前"),
      Vector(
        j("""{"type":"text","text":"太字","marks":[{"type":"bold"}]}"""),
        j("""{"type":"text","text":"と"}"""),
        j("""{"type":"text","text":"斜体","marks":[{"type":"italic"}]}"""),
        j("""{"type":"text","text":"と"}"""),
        j("""{"type":"text","text":"消し","marks":[{"type":"strike"}]}"""),
        j("""{"type":"text","text":"と"}"""),
        j("""{"type":"text","text":"a*b","marks":[{"type":"code"}]}"""),
        j("""{"type":"text","text":"と"}"""),
        j("""{"type":"text","text":"リンク","marks":[{"type":"link","attrs":{"href":"https://e.com"}}]}"""),
        j("""{"type":"text","text":"、"}"""),
        j("""{"type":"text","text":"https://x.jp/p","marks":[{"type":"link","attrs":{"href":"https://x.jp/p"}}]}"""),
        j("""{"type":"text","text":" 。*そのまま*、snake_case_名前"}""")
      )
    )
  }

  test("表") {
    assertEquals(
      p("| 名前 | 値 |\n|---|---:|\n| a\\|b | 1 |"),
      Vector(j("""{"type":"table","content":[
      {"type":"tableRow","content":[
        {"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"名前"}]}]},
        {"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"値"}]}]}]},
      {"type":"tableRow","content":[
        {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"a|b"}]}]},
        {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"1"}]}]}]}]}"""))
    )
  }

  test("書き出し（MarkdownExport）した Markdown を読み直すと同じ本文になる") {
    val md = "## 予定\n\n**太字** と [リンク](https://e.com)\n\n- 一\n  - 入れ子\n\n- [x] 済み\n\n> 引用"
    val doc = Json.obj("type" -> Json.fromString("doc"), "content" -> Json.fromValues(p(md)))
    assertEquals(MarkdownExport(identity, _ => "").body(doc), md)
  }

  test("悪意のある入力（長い行・深い入れ子）でもスタックを使い切らず、すぐに終わる") {
    val inputs = List(
      "a|\n" + "|-" * 60000, // 表の区切り行（正規表現のグループの繰り返し）
      "-" * 200000, // 区切り線
      ">" * 20000 + "a", // 引用の入れ子
      "[" * 30000 + "a" + "](x)" * 30000, // リンクの入れ子
      "# " + " " * 100000 + "x", // 見出しの末尾の空白
      "- a\n" + "\n" * 100000 + "  b", // リストの中の空行
      "**a" * 50000 // 閉じない強調
    )
    for in <- inputs do {
      val t0 = System.nanoTime()
      assert(p(in).nonEmpty)
      assert(System.nanoTime() - t0 < 5_000_000_000L, s"too slow: ${in.take(20)}")
    }
  }

  test("見出しの末尾の # と区切り線・表の判定") {
    assertEquals(
      p("## 題 ##"),
      Vector(j("""{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"題"}]}"""))
    )
    assertEquals(
      p("## C#"),
      Vector(j("""{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"C#"}]}"""))
    )
    assertEquals(p("* * *"), Vector(j("""{"type":"horizontalRule"}""")))
    assert(p("a | b\n:--|--:\n1 | 2").head.hcursor.get[String]("type") == Right("table"))
  }
}
