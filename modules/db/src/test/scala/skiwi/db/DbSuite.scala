package skiwi.db

import cats.effect.IO
import munit.CatsEffectSuite

/** DB を使うテストの共通部分。TEST_DATABASE_URL がなければテストを飛ばす。 */
trait DbSuite extends CatsEffectSuite:

  protected val cfg: Option[DbConfig] = sys.env.get("TEST_DATABASE_URL").map { url =>
    DbConfig(url, sys.env.getOrElse("TEST_DATABASE_USER", "skiwi"), sys.env.getOrElse("TEST_DATABASE_PASSWORD", "skiwi"))
  }

  protected def run(body: => IO[Unit]): IO[Unit] = if cfg.isEmpty then IO(assume(false, "TEST_DATABASE_URL not set")) else body

  // for内のタプルパターン用（Scala 3.3は withFilter を要求する）
  extension [A](io: IO[A]) protected def withFilter(p: A => Boolean): IO[A] = io
