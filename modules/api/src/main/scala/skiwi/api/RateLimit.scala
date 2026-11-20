package skiwi.api

import cats.effect.*
import org.http4s.*
import sbirds.http.RateLimit.Path
import sbirds.http.RateLimitConfig
import skiwi.core.ErrorCode

import scala.concurrent.duration.FiniteDuration

/**
 * skiwi の数え方（どの入口をどのカウンタで数えるか）.
 * 共通の数え方（/auth/…・/oauth/authorize は auth、/oauth/token・/oauth/revoke・/api/public/… は public の枠）は
 * sbirds-lib の RateLimit.standard. ここでは、skiwi だけの違いを決める.
 * 公開リンクの添付（/api/public/{キー}/attachments/…）は、1ページの表示で画像の数だけ読み込むので数えない.
 * フォームの回答（/api/forms/public/…）・受信の Webhook（/api/hooks/…）は、共通の数え方の対象外で、
 * フォーム・Webhook ごとの上限が別にある（FormRoutes・InboundRoutes の perMinute）.
 * 公開リンクのパスワードの試行の上限（ShareRoutes. リンクと接続元ごと）は、ここの上限とは別に効く.
 */
object RateLimit {

  def apply(routes: HttpRoutes[IO],
            cfg: RateLimitConfig,
            clock: IO[FiniteDuration] = IO.realTime): HttpRoutes[IO] =
    sbirds.http.RateLimit.standard(routes, cfg, ErrorCode.TooManyRequests, clock) { b =>
      { case Path("api" :: "public" :: _ :: "attachments" :: _) => b.skip }
    }
}
