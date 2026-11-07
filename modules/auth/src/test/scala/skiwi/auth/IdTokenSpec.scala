package skiwi.auth

import io.circe.Json
import io.circe.syntax.*

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

class IdTokenSpec extends munit.FunSuite:
  private val cfg = OidcConfig("https://auth.example/application/o/skiwi/", "skiwi-client", "secret", "https://skiwi.example/auth/callback")
  private val issuer = cfg.issuer
  private val now = Instant.parse("2026-11-04T00:00:00Z")

  private def claims(over: (String, Json)*): Json =
    val base = Map[String, Json](
      "iss" -> issuer.asJson, "aud" -> "skiwi-client".asJson, "exp" -> (now.getEpochSecond + 300).asJson,
      "nonce" -> "n1".asJson, "sub" -> "u1".asJson, "email" -> "a@example.com".asJson,
      "name" -> "日本 太郎".asJson, "groups" -> List("skiwi-admins", "dev").asJson
    )
    Json.fromFields((base ++ over).toList)

  private def jwt(c: Json): String =
    val e = Base64.getUrlEncoder.withoutPadding()
    val h = e.encodeToString("""{"alg":"none"}""".getBytes)
    s"$h.${e.encodeToString(c.noSpaces.getBytes(StandardCharsets.UTF_8))}.sig"

  test("valid token yields identity with groups and Japanese name") {
    val id = IdToken.decodeClaims(jwt(claims())).flatMap(IdToken.validate(_, issuer, cfg, "n1", now))
    assertEquals(id, Right(Identity("u1", Some("a@example.com"), "日本 太郎", List("skiwi-admins", "dev"))))
  }

  test("aud may be an array") {
    val c = claims("aud" -> List("x", "skiwi-client").asJson)
    assert(IdToken.validate(c, issuer, cfg, "n1", now).isRight)
  }

  test("rejects wrong issuer / audience / nonce / expired") {
    assert(IdToken.validate(claims("iss" -> "https://evil".asJson), issuer, cfg, "n1", now).isLeft)
    assert(IdToken.validate(claims("aud" -> "other".asJson), issuer, cfg, "n1", now).isLeft)
    assert(IdToken.validate(claims(), issuer, cfg, "different", now).isLeft)
    assert(IdToken.validate(claims("exp" -> (now.getEpochSecond - 600).asJson), issuer, cfg, "n1", now).isLeft)
  }

  test("name falls back to preferred_username, then email") {
    val c1 = claims("name" -> "".asJson, "preferred_username" -> "taro".asJson)
    assertEquals(IdToken.validate(c1, issuer, cfg, "n1", now).map(_.name), Right("taro"))
    val c2 = Json.fromFields(claims().asObject.get.toList.filterNot(k => Set("name", "preferred_username")(k._1)))
    assertEquals(IdToken.validate(c2, issuer, cfg, "n1", now).map(_.name), Right("a@example.com"))
  }

  test("pkce challenge matches RFC 7636 example") {
    assertEquals(
      Crypto.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
      "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    )
  }

  test("malformed tokens are rejected") {
    assert(IdToken.decodeClaims("abc").isLeft)
    assert(IdToken.decodeClaims("a.!!!.c").isLeft)
  }
