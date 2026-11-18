package fs2.io.net

import cats.effect.{IO, Resource}
import com.comcast.ip4s.*

/** 接続先を確かめてからつなぐ Network（skiwi の NetGuard が使う）.
  * http4s の ember クライアントは暗黙の Network の connect で接続するので、connect だけを差し替え、ほかはそのまま base に任せる.
  * fs2 の Network は sealed で、実装できるのはこのパッケージの UnsealedNetwork だけなので、ここに置く（fs2 を上げたら確かめること）.
  * resolve: ホスト名を引いて、接続してよいアドレスを返す（だめなら失敗させる） */
final class GuardedNetwork(base: Network[IO], resolve: Host => IO[IpAddress]) extends Network.UnsealedNetwork[IO]:

  def connect(address: GenSocketAddress, options: List[SocketOption]): Resource[IO, Socket[IO]] = address match
    case SocketAddress(host: Host, port) => Resource.eval(resolve(host)).flatMap(ip => base.connect(SocketAddress(ip, port), options))
    case other                          => Resource.raiseError[IO, Socket[IO], Throwable](new IllegalArgumentException(s"接続できない宛先です: $other"))

  def client(to: SocketAddress[Host], options: List[SocketOption]): Resource[IO, Socket[IO]] = connect(to, options)

  def bind(address: GenSocketAddress, options: List[SocketOption])                 = base.bind(address, options)
  def bindAndAccept(address: GenSocketAddress, options: List[SocketOption])        = base.bindAndAccept(address, options)
  def bindDatagramSocket(address: GenSocketAddress, options: List[SocketOption])   = base.bindDatagramSocket(address, options)
  def tlsContext                                                                   = base.tlsContext
  def dns                                                                          = base.dns
  def interfaces                                                                   = base.interfaces
  def socketGroup(threadCount: Int, threadFactory: java.util.concurrent.ThreadFactory) = base.socketGroup(threadCount, threadFactory)
  def datagramSocketGroup(threadFactory: java.util.concurrent.ThreadFactory)        = base.datagramSocketGroup(threadFactory)
  def server(address: Option[Host], port: Option[Port], options: List[SocketOption]) = base.server(address, port, options)
  def serverResource(address: Option[Host], port: Option[Port], options: List[SocketOption]) = base.serverResource(address, port, options)
  def openDatagramSocket(address: Option[Host], port: Option[Port], options: List[SocketOption], protocolFamily: Option[java.net.ProtocolFamily]) =
    base.openDatagramSocket(address, port, options, protocolFamily)
