package skiwi.api

import cats.effect.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*

import java.net.InetAddress

/** 全体の防御（CSRF・本文の上限・ヘッダ）と、外部の HTML・アドレスの扱い。 */
class SecuritySpec extends CatsEffectSuite:

  private val echo = HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" / "x"                   => req.as[String].flatMap(b => Ok(b.length.toString))
    case req @ POST -> Root / "api" / "pages" / _ / "attachments" => req.as[String].flatMap(b => Ok(b.length.toString))
    case GET -> Root / "api" / "x"                          => Ok("ok")
  }
  private val app = Security.headers("https://wiki.example.com")(
    cats.data.Kleisli((req: Request[IO]) =>
      Security.bodyLimit(Security.csrf("https://wiki.example.com", "skiwi_session")(echo)).orNotFound.run(req).recoverWith {
        case e: Security.BodyTooLarge => PayloadTooLarge(e.getMessage)
      }))

  private def post(headers: (String, String)*)(cookie: Boolean = true) =
    val r = Request[IO](Method.POST, uri"/api/x").withEntity("{}").putHeaders(headers.map((k, v) => Header.Raw(org.typelevel.ci.CIString(k), v))*)
    app.run(if cookie then r.addCookie("skiwi_session", "t") else r)

  test("Cookie 認証の POST は同じオリジンからだけ受け付ける") {
    for
      same  <- post("Origin" -> "https://wiki.example.com")()
      cross <- post("Origin" -> "https://files.example.com")()
      proxy <- post("Origin" -> "http://localhost:5173", "Host" -> "localhost:5173")()
      site  <- post("Sec-Fetch-Site" -> "same-site")()
      none  <- post()()
      token <- post("Origin" -> "https://evil.example", "Authorization" -> "Bearer x")()
      guest <- post("Origin" -> "https://evil.example")(cookie = false)
      get   <- app.run(Request[IO](Method.GET, uri"/api/x").addCookie("skiwi_session", "t").putHeaders(Header.Raw(org.typelevel.ci.CIString("Origin"), "https://evil.example")))
    yield
      assertEquals(same.status, Status.Ok)
      assertEquals(cross.status, Status.Forbidden)
      assertEquals(proxy.status, Status.Ok) // 開発時のプロキシ（Host と同じオリジン）
      assertEquals(site.status, Status.Forbidden)
      assertEquals(none.status, Status.Ok)  // ブラウザ以外
      assertEquals(token.status, Status.Ok) // API トークンは対象外
      assertEquals(guest.status, Status.Ok) // Cookie なし
      assertEquals(get.status, Status.Ok)
  }

  test("本文の大きさの上限（添付ファイルは各ルートに任せる）") {
    val big = "x" * (17 * 1024 * 1024)
    for
      r1 <- app.run(Request[IO](Method.POST, uri"/api/x").withEntity(big))
      // Content-Length がない（チャンク）場合も途中で止める
      r2 <- app.run(Request[IO](Method.POST, uri"/api/x").withBodyStream(fs2.Stream.emits(big.getBytes).covary[IO]))
      r3 <- app.run(Request[IO](Method.POST, uri"/api/pages/p/attachments").withEntity(big))
    yield
      assertEquals(r1.status, Status.PayloadTooLarge)
      assertEquals(r2.status, Status.PayloadTooLarge)
      assertEquals(r3.status, Status.Ok)
  }

  test("セキュリティヘッダを付ける（ルートが付けたものは上書きしない）") {
    val own = Security.headers("https://wiki.example.com")(HttpRoutes.of[IO] { case GET -> Root => Ok().map(_.putHeaders("X-Frame-Options" -> "DENY")) }.orNotFound)
    for
      r <- app.run(Request[IO](Method.GET, uri"/api/x"))
      o <- own.run(Request[IO](Method.GET, uri"/"))
    yield
      assertEquals(r.headers.get(org.typelevel.ci.CIString("X-Content-Type-Options")).map(_.head.value), Some("nosniff"))
      assertEquals(r.headers.get(org.typelevel.ci.CIString("X-Frame-Options")).map(_.head.value), Some("SAMEORIGIN"))
      assert(r.headers.get(org.typelevel.ci.CIString("Strict-Transport-Security")).isDefined)
      assertEquals(o.headers.get(org.typelevel.ci.CIString("X-Frame-Options")).map(_.head.value), Some("DENY"))
  }

  test("HTML の読み取りは壊れた入力でも線形時間") {
    assertEquals(Html.toText("<p>a</p><script>x</script>b<br>c &amp; d").replaceAll("[ ]+", "").split("\n").toList, List("a", "b", "c&d"))
    assertEquals(Html.firstElementText("<head><TITLE>題</title>", "title"), Some("題"))
    for in <- List("<" * 2_000_000, "<script" * 300_000, "<title>x" * 100_000, "<meta " * 300_000) do
      val t0 = System.nanoTime()
      Html.toText(in); Html.tags(in, Set("meta")); Html.firstElementText(in, "title")
      assert(System.nanoTime() - t0 < 3_000_000_000L, in.take(10))
  }

  test("社内・特殊用途のアドレスは外部とみなさない") {
    val priv = List("127.0.0.1", "10.1.2.3", "192.168.0.1", "172.16.0.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "198.18.0.1",
      "::1", "fd00::1", "fe80::1", "64:ff9b::a00:1", "2002:a00:1::1", "::ffff:10.0.0.1")
    priv.foreach(a => assert(!NetGuard.isPublic(InetAddress.getByName(a)), a))
    List("8.8.8.8", "2606:4700:4700::1111").foreach(a => assert(NetGuard.isPublic(InetAddress.getByName(a)), a))
  }
