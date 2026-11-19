package skiwi.api

import cats.effect.*
import com.comcast.ip4s.IpAddress
import fs2.io.net.Network
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder

import java.net.{Inet4Address, Inet6Address, InetAddress}
import scala.concurrent.duration.*

/** サーバーから外へ接続するとき（リンクのカード・Webhook）に、社内のアドレスへ向けさせないための判定（SSRF 対策）。 */
object NetGuard {

  /** 外部（インターネット）のアドレスか */
  def isPublic(a: InetAddress): Boolean =
    !(a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress ||
      a.isMulticastAddress || reserved(a))

  /** Java の判定に含まれない、社内・特殊用途の範囲 */
  private def reserved(a: InetAddress): Boolean = {
    val b = a.getAddress.map(_ & 0xff)
    a match {
      case _: Inet4Address =>
        b(0) == 0 || // 0.0.0.0/8
        (b(0) == 100 && (b(1) & 0xc0) == 64) || // 100.64.0.0/10（CGNAT・Tailscale など）
        (b(0) == 192 && b(1) == 0 && b(2) == 0) || // 192.0.0.0/24
        (b(0) == 198 && (b(1) & 0xfe) == 18) || // 198.18.0.0/15
        b(0) >= 240 // 240.0.0.0/4・ブロードキャスト
      case _: Inet6Address =>
        (b(0) & 0xfe) == 0xfc || // fc00::/7（ユニークローカル）
        (b(0) == 0x00 && b(1) == 0x64 && b(2) == 0xff && b(3) == 0x9b) || // 64:ff9b::/96（NAT64）
        (b(0) == 0x20 && b(1) == 0x02) || // 2002::/16（6to4）
        (b(0) == 0x20 && b(1) == 0x01 && b(2) == 0 && b(3) == 0) // 2001::/32（Teredo）
      case _ => true
    }
  }

  /** 社内のアドレスへの接続を断った */
  final case class Blocked(host: String) extends Exception(s"社内のアドレスには接続できません: $host")

  /**
   * 外へ接続する HTTP クライアント（リダイレクトはたどらない）。allowPrivate=false なら、接続のたびにホスト名を引いて
   * すべてのアドレスが外部のものであることを確かめ、確かめたアドレスにそのまま接続する
   * （確かめてから接続するまでの間に名前の向き先を変えられても、社内へはつながない。TLS の検証と SNI は元のホスト名で行う）
   */
  def client(allowPrivate: Boolean): Resource[IO, Client[IO]] = {
    given org.typelevel.log4cats.LoggerFactory[IO] = org.typelevel.log4cats.noop.NoOpFactory[IO]
    def build(using Network[IO]) =
      EmberClientBuilder.default[IO].withTimeout(15.seconds).withIdleConnectionTime(30.seconds).build
    if allowPrivate then build(using Network[IO])
    else build(using fs2.io.net.GuardedNetwork(Network[IO], h => publicAddress(h.toString)))
  }

  /** ホスト名を引き、すべてのアドレスが外部のものならその1つ目（そうでなければ Blocked） */
  private def publicAddress(host: String): IO[IpAddress] =
    IO.blocking(InetAddress.getAllByName(host.stripPrefix("[").stripSuffix("]")).toList).flatMap { addrs =>
      if addrs.nonEmpty && addrs.forall(isPublic) then IO.pure(IpAddress.fromInetAddress(addrs.head))
      else IO.raiseError(Blocked(host))
    }
}
