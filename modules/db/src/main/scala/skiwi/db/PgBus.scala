package skiwi.db

import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import org.postgresql.PGConnection

import java.sql.{Connection, DriverManager}
import scala.concurrent.duration.*
import skiwi.core.ErrorCode

/**
 * PostgreSQL の LISTEN/NOTIFY によるノード間メッセージ。Redis等を使わずに済ませるため。
 * 受信側は専用のJDBC接続を張り、切れたら（Patroniのフェイルオーバー等）張り直して onConnect を呼ぶ。
 * 切断中の通知は失われるため、受信側は onConnect で状態を取り直すこと。
 */
final class PgBus(cfg: DbConfig, xa: Transactor[IO]) {

  /** ペイロードは8000バイト未満であること（PostgreSQLの制限）。 */
  def publish(channel: String, payload: String): IO[Unit] =
    sql"select count(*) from (select pg_notify($channel, $payload)) t".query[Long].unique.transact(xa).void

  private def connect: Resource[IO, Connection] =
    Resource.make(IO.blocking {
      val p = new java.util.Properties()
      p.setProperty("user", cfg.user)
      p.setProperty("password", cfg.password)
      p.setProperty("tcpKeepAlive", "true")
      DriverManager.getConnection(cfg.url, p)
    })(c => IO.blocking(c.close()).handleError(_ => ()))

  /** 指定チャネルを購読し続ける（キャンセルされるまで終わらない）。 */
  def subscribe(channels: List[String], onConnect: IO[Unit])(handle: (String, String) => IO[Unit]): IO[Nothing] = {
    val once: IO[Unit] = connect.use { c =>
      val pg = c.unwrap(classOf[PGConnection])
      IO.blocking {
        val st = c.createStatement()
        try channels.foreach(ch => st.execute("LISTEN \"" + ch.replace("\"", "") + "\""))
        finally st.close()
      } *> onConnect *> {
        // 1秒ごとに通知を待つ。接続が切れていれば例外になり、外側で再接続する
        val poll: IO[Unit] = IO.blocking(Option(pg.getNotifications(1000)).toList.flatten).flatMap { ns =>
          ns.traverse_(n =>
            handle(n.getName, n.getParameter).handleErrorWith(e =>
              sbirds.observe.Log.report(ErrorCode.BusHandlerFailed, e.toString, e)
            )
          )
        } *> IO.blocking {
          // getNotifications(timeout) は切断を検出しないことがあるため、軽いクエリで生存確認する
          val st = c.createStatement();
          try st.execute("select 1")
          finally st.close()
        }
        poll.foreverM
      }
    }
    Periodic.run(Duration.Zero, 2.seconds, ErrorCode.BusListenerDisconnected)(once)
  }
}
