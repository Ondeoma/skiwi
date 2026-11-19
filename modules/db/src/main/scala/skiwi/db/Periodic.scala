package skiwi.db

import cats.effect.*
import skiwi.core.ErrorCode

import scala.concurrent.duration.*

/** 定期的に動かす処理（キャンセルされるまで終わらない）。 */
object Periodic {

  /** initialDelay 待ってから action を動かし、以後 every ごとに繰り返す。失敗しても止めず、code を付けてログに出す。 */
  def run(initialDelay: FiniteDuration, every: FiniteDuration, code: ErrorCode)(action: IO[Any]): IO[Nothing] =
    IO.sleep(initialDelay) *>
      (action.void.handleErrorWith(e => sbirds.observe.Log.report(code, e.toString, e)) *> IO.sleep(every)).foreverM
}
