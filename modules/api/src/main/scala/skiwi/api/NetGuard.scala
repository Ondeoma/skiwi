package skiwi.api

import cats.effect.*

import java.net.{Inet4Address, Inet6Address, InetAddress}

/** サーバーから外へ接続するとき（リンクのカード・Webhook）に、社内のアドレスへ向けさせないための判定（SSRF 対策）。 */
object NetGuard:

  /** 外部（インターネット）のアドレスか */
  def isPublic(a: InetAddress): Boolean =
    !(a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress || reserved(a))

  /** Java の判定に含まれない、社内・特殊用途の範囲 */
  private def reserved(a: InetAddress): Boolean =
    val b = a.getAddress.map(_ & 0xff)
    a match
      case _: Inet4Address =>
        b(0) == 0 ||                                   // 0.0.0.0/8
        (b(0) == 100 && (b(1) & 0xc0) == 64) ||        // 100.64.0.0/10（CGNAT・Tailscale など）
        (b(0) == 192 && b(1) == 0 && b(2) == 0) ||     // 192.0.0.0/24
        (b(0) == 198 && (b(1) & 0xfe) == 18) ||        // 198.18.0.0/15
        b(0) >= 240                                    // 240.0.0.0/4・ブロードキャスト
      case _: Inet6Address =>
        (b(0) & 0xfe) == 0xfc ||                       // fc00::/7（ユニークローカル）
        (b(0) == 0x00 && b(1) == 0x64 && b(2) == 0xff && b(3) == 0x9b) || // 64:ff9b::/96（NAT64）
        (b(0) == 0x20 && b(1) == 0x02) ||              // 2002::/16（6to4）
        (b(0) == 0x20 && b(1) == 0x01 && b(2) == 0 && b(3) == 0) // 2001::/32（Teredo）
      case _ => true

  /** ホスト名を引き、すべてのアドレスが外部のものなら true（引けなければ false） */
  def publicHost(host: String): IO[Boolean] =
    if host == null || host.isEmpty then IO.pure(false)
    else IO.blocking(InetAddress.getAllByName(host).toList).attempt.map {
      case Right(addrs) => addrs.nonEmpty && addrs.forall(isPublic)
      case Left(_)      => false
    }
