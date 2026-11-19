package skiwi.db

import cats.effect.*
import doobie.*
import doobie.implicits.*

import scala.concurrent.duration.*

/** クラスタのノード登録（ハートビート）。 */
final class ClusterStore(
  xa: Transactor[IO]) {

  def heartbeat(
    id: String,
    url: String
  ): IO[Unit] =
    sql"""insert into cluster_node (node_id, internal_url) values ($id, $url)
          on conflict (node_id) do update set internal_url = excluded.internal_url, heartbeat_at = now()""".update.run.transact(
      xa
    ).void

  /** deadAfter 以内にハートビートのあったノード（ID順）。 */
  def alive(
    deadAfter: FiniteDuration
  ): IO[List[ClusterNode]] = {
    val secs = deadAfter.toSeconds.toInt
    sql"""select node_id, internal_url from cluster_node
          where heartbeat_at > now() - make_interval(secs => $secs) order by node_id"""
      .query[ClusterNode].to[List].transact(xa)
  }

  def remove(
    id: String
  ): IO[Unit] =
    sql"delete from cluster_node where node_id = $id".update.run.transact(xa).void
}
