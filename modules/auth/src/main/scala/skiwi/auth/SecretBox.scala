package skiwi.auth

import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import scala.util.Try

/** AES-256-GCM による秘密の値（外部サービスのトークンなど）の暗号化。
  * 形式: base64(IV 12バイト ‖ 暗号文 ‖ 認証タグ 16バイト)。
  * context は付加データ（AAD）として使い、別の用途・別のユーザーの行へ暗号文を移しても復号できないようにする。 */
final class SecretBox private (key: SecretKeySpec):
  private val rnd = new SecureRandom()

  def encrypt(plain: String, context: String): String =
    val iv = new Array[Byte](12)
    rnd.nextBytes(iv)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv))
    c.updateAAD(context.getBytes(UTF_8))
    Base64.getEncoder.encodeToString(iv ++ c.doFinal(plain.getBytes(UTF_8)))

  /** 鍵が違う・改ざんされた・context が違うときは None */
  def decrypt(enc: String, context: String): Option[String] = Try {
    val all = Base64.getDecoder.decode(enc)
    val c   = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12))
    c.updateAAD(context.getBytes(UTF_8))
    new String(c.doFinal(all, 12, all.length - 12), UTF_8)
  }.toOption

object SecretBox:
  /** base64 の32バイトの鍵（環境変数 ENCRYPTION_KEY）から作る */
  def fromBase64(b64: String): Either[String, SecretBox] =
    Try(Base64.getDecoder.decode(b64.trim)).toOption match
      case Some(k) if k.length == 32 => Right(new SecretBox(new SecretKeySpec(k, "AES")))
      case _                         => Left("ENCRYPTION_KEY must be 32 bytes encoded in base64 (e.g. `openssl rand -base64 32`)")
