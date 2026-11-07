package skiwi.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

/** 送信待ちの表（メール・Webhook）を処理する共通の手順。 */
private[db] object Outbox:

  /** claim で取得・ロックした行（複数ノードで重複送信しないよう SKIP LOCKED で取る）を f で処理し、結果を table に記録する。
    * 送信はロックを保持したトランザクション内で行う（件数が少ない前提。送信待ちの間ロックされるのは該当行のみ）。
    * 再試行は指数バックオフ（最大1時間）で、maxAttempts 回失敗したら諦める。 */
  def process[A](xa: Transactor[IO], table: String, claim: ConnectionIO[List[A]], id: A => Long, attempts: A => Int, maxAttempts: Int)(
      f: A => IO[OutboxResult]): IO[Int] =
    val upd = Fragment.const(s"update $table set")
    WeakAsync.liftK[IO, ConnectionIO].use { lift =>
      claim.flatMap { items =>
        items.traverse { it =>
          lift(f(it).handleError(e => OutboxResult.Retry(e.getMessage))).flatMap {
            case OutboxResult.Sent =>
              (upd ++ fr"sent_at = now(), attempts = attempts + 1 where id = ${id(it)}").update.run
            case OutboxResult.Skip(reason) =>
              (upd ++ fr"skipped = true, last_error = $reason where id = ${id(it)}").update.run
            case OutboxResult.Retry(err) =>
              val n      = attempts(it)
              val giveUp = n + 1 >= maxAttempts
              (upd ++ fr"""attempts = attempts + 1, last_error = $err, skipped = $giveUp,
                      not_before = now() + make_interval(secs => ${math.min(3600, 30 * (1 << math.min(n, 7)))})
                    where id = ${id(it)}""").update.run
          }
        }.map(_.size)
      }.transact(xa)
    }
