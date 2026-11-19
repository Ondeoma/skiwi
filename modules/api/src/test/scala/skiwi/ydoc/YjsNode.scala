package skiwi.ydoc

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

/** テストから本物の Yjs（frontend/test/yjs-check.mjs を node で動かす）を呼ぶ。node と frontend/node_modules がなければ使えない。 */
object YjsNode {

  val frontend: Option[File] =
    Iterator.iterate(new File(".").getAbsoluteFile)(_.getParentFile).takeWhile(_ != null)
      .map(new File(_, "frontend")).find(f =>
        new File(f, "test/yjs-check.mjs").exists && new File(f, "node_modules/yjs").exists
      )

  def available: Boolean = frontend.isDefined

  /** 要求の配列を送り、結果の配列を受け取る（失敗は例外）。 */
  def run(
    reqs: Vector[Json]
  ): Vector[Json] = {
    val p = new ProcessBuilder("node", "test/yjs-check.mjs").directory(frontend.get).start()
    p.getOutputStream.write(Json.fromValues(reqs).noSpaces.getBytes(UTF_8)); p.getOutputStream.close()
    val out = String(p.getInputStream.readAllBytes(), UTF_8)
    val err = String(p.getErrorStream.readAllBytes(), UTF_8)
    if p.waitFor() != 0 then throw IllegalStateException(s"node failed: $err")
    val res = parse(out).toOption.flatMap(_.asArray).getOrElse(throw IllegalStateException(s"bad output: $out $err"))
    res.foreach(r => r.hcursor.get[String]("error").foreach(e => throw IllegalStateException(e)))
    res
  }

  /** update を順に当てた文書の本文（最上位のブロックの JSON）。 */
  def blocks(
    updates: Seq[Array[Byte]]
  ): Vector[Json] = {
    val req =
      Json.obj("mode" -> "apply".asJson, "updates" -> updates.map(u => Base64.getEncoder.encodeToString(u)).asJson)
    run(Vector(req)).head.hcursor.downField("doc").downField("content").focus.flatMap(_.asArray).getOrElse(Vector.empty)
  }
}
