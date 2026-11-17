package skiwi.db

import cats.effect.*

import scala.concurrent.duration.*

/** 定期的に動かす処理（キャンセルされるまで終わらない）。 */
object Periodic:

  /** initialDelay 待ってから action を動かし、以後 every ごとに繰り返す。失敗しても止めず、onError の文を出力する。 */
  def run(initialDelay: FiniteDuration, every: FiniteDuration)(onError: Throwable => String)(action: IO[Any]): IO[Nothing] =
    IO.sleep(initialDelay) *> (action.void.handleErrorWith(e => skiwi.observe.Log.println(onError(e))) *> IO.sleep(every)).foreverM
