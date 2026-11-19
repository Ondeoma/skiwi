package skiwi.api

import munit.FunSuite

class YDocBuilderSpec extends FunSuite {
  test("paragraphs build a non-empty update and matching JSON") {
    val (bytes, json, text) = YDocBuilder.paragraphs(List("こんにちは🌸", "", "second line"))
    assert(bytes.length > 20)
    assertEquals(text, "こんにちは🌸\nsecond line")
    assertEquals(json.hcursor.downField("content").as[List[io.circe.Json]].map(_.size), Right(3))
    sys.env.get("YDOC_OUT").foreach(p => java.nio.file.Files.write(java.nio.file.Paths.get(p), bytes))
  }
  test("split trims blank edges") {
    assertEquals(YDocBuilder.split("\n\na\r\n\nb\n\n"), List("a", "", "b"))
  }
}
