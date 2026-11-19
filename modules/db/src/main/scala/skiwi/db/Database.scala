package skiwi.db

import cats.effect.*
import com.zaxxer.hikari.HikariConfig
import doobie.hikari.HikariTransactor
import org.flywaydb.core.Flyway

final case class DbConfig(url: String, user: String, password: String, maxPoolSize: Int = 10)

object Database {

  def migrate(cfg: DbConfig): IO[Int] =
    IO.blocking {
      Flyway
        .configure()
        .dataSource(cfg.url, cfg.user, cfg.password)
        .load()
        .migrate()
        .migrationsExecuted
    }

  def transactor(cfg: DbConfig): Resource[IO, HikariTransactor[IO]] = {
    val hc = new HikariConfig()
    hc.setJdbcUrl(cfg.url)
    hc.setUsername(cfg.user)
    hc.setPassword(cfg.password)
    hc.setMaximumPoolSize(cfg.maxPoolSize)
    hc.setDriverClassName("org.postgresql.Driver")
    // Patroniのフェイルオーバー対策: 接続待ちを短くし、切れた接続は早めに検出して張り直す。
    // URLは複数ホスト + targetServerType=primary を想定（例: jdbc:postgresql://db1,db2,db3/skiwi?targetServerType=primary）
    hc.setConnectionTimeout(5000)
    hc.setValidationTimeout(3000)
    hc.setKeepaliveTime(60000)
    hc.setMaxLifetime(600000)
    // SQL ごとにトレースの子のスパンと所要時間のメトリクスを残す（skorimako）
    HikariTransactor.fromHikariConfig[IO](hc, Some(sbirds.observe.DbTelemetry.logHandler))
  }
}
