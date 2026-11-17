package skiwi.collab

import cats.effect.*
import skiwi.core.Rendezvous
import skiwi.db.{ClusterNode, ClusterStore, Periodic}

import java.util.UUID
import scala.concurrent.duration.*

/** 3ノード構成の設定。internalUrl は他ノードから到達できるこのノードのURL（例: http://10.0.0.11:8080）。
  * secret はノード間転送の印（外部から転送済みを装えないようにする）。 */
final case class ClusterConfig(
    nodeId: String,
    internalUrl: String,
    secret: String,
    heartbeatEvery: FiniteDuration = 5.seconds,
    deadAfter: FiniteDuration = 15.seconds
)

/** ノードの生存管理とページの担当決定。生存ノードはDBのハートビートで判断する（etcd等は使わない）。 */
final class ClusterManager(val cfg: ClusterConfig, store: ClusterStore):

  private val nodes: Ref[IO, List[ClusterNode]] = Ref.unsafe(Nil)

  def self: String = cfg.nodeId

  def aliveNodes: IO[List[ClusterNode]] = nodes.get

  /** ページの担当ノード。生存ノードが分からない間（起動直後・DB障害時）は None（＝自ノードで処理）。 */
  def ownerOf(pageId: UUID): IO[Option[ClusterNode]] =
    nodes.get.map(ns => Rendezvous.pick(pageId.toString, ns)(_.id))

  /** ハートビートを打ち、生存ノード集合が変わったら onChange を呼ぶ。 */
  def loop(onChange: IO[Unit]): IO[Nothing] =
    val tick =
      for
        _   <- store.heartbeat(cfg.nodeId, cfg.internalUrl)
        now <- store.alive(cfg.deadAfter)
        old <- nodes.getAndSet(now)
        changed = old.map(_.id) != now.map(_.id)
        _ <- IO.whenA(changed)(
               skiwi.observe.Log.println(s"[cluster] ${cfg.nodeId}: alive nodes = ${now.map(_.id).mkString(", ")}") *> IO.whenA(old.nonEmpty)(onChange)
             )
      yield ()
    Periodic.run(Duration.Zero, cfg.heartbeatEvery)(e => s"[cluster] heartbeat failed: ${e.getMessage}")(tick)

  /** 停止時に登録を消し、他ノードがすぐに担当を引き継げるようにする。 */
  def leave: IO[Unit] = store.remove(cfg.nodeId).handleErrorWith(e => skiwi.observe.Log.println(s"[cluster] leave failed: $e"))

  /** 起動時: 生存ノード一覧だけを読む（自分の登録は、HTTPサーバーが待ち受けを始めてから loop で行う。
    * 先に登録すると、他ノードがまだ接続できないこのノードへ転送してしまうため）。 */
  def refresh: IO[Unit] =
    store.alive(cfg.deadAfter).flatMap(nodes.set).handleErrorWith(e => skiwi.observe.Log.println(s"[cluster] refresh failed: $e"))

  /** 生存確認用の HTTP クライアント（使い回す） */
  private lazy val http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build()

  /** 担当ノードが応答するか（/healthz）。担当でないページの接続を閉じる前に確認する。 */
  def reachable(node: ClusterNode): IO[Boolean] =
    IO.blocking {
      val r = java.net.http.HttpRequest.newBuilder(java.net.URI.create(node.url.stripSuffix("/") + "/healthz"))
        .timeout(java.time.Duration.ofSeconds(2)).GET().build()
      http.send(r, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    }.handleError(_ => false)
