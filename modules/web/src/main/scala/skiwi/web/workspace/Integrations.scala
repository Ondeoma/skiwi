package skiwi.web.workspace

import io.github.ondeoma.scalactive.components.ComponentManager.*

import scala.concurrent.Future
import scala.scalajs.js

/** 外部サービスとの連携の状態（画面の表示用）. enabled: 管理者が設定済み / connected: 自分が連携済み */
final case class IntegrationState(enabled: Boolean, connected: Boolean)

/** skea・skawau・smohua の連携（画面の表示はリアクティブな status を見る） */
object Integrations {

  val skea = new Integration("skea")
  val skawau = new Integration("skawau")
  val smohua = new Integration("smohua")

  val all: List[Integration] = List(skea, skawau, smohua)

  val Names: List[String] = all.map(_.name)

  def byName(name: String): Option[Integration] = all.find(_.name == name)

  /** サービス名 → 状態（読めていないものは入らない） */
  val status: RV[Map[String, IntegrationState]] = RV(Map.empty)

  private[workspace] def refresh(): Unit = status := all.flatMap(i => i.status.map(i.name -> _)).toMap

  def enabled(name: String): Boolean = status.v.get(name).exists(_.enabled)

  def connected(name: String): Boolean = status.v.get(name).exists(_.connected)

  def any: Boolean = status.v.values.exists(_.enabled)

  /** 連携の結果を取り出し（あれば状態を読み直し）、なければ状態を読む. 戻り値: サービス名 → 結果（connected / denied / error） */
  def init(): Future[Map[String, String]] =
    Future.successful(all.flatMap { i =>
      i.takeResult() match {
        case None => i.load(); None
        case Some(r) => i.reload(); Some(i.name -> r)
      }
    }.toMap)

  def connectUrl(name: String, returnTo: String): String =
    s"/api/integrations/$name/connect?returnTo=${js.URIUtils.encodeURIComponent(returnTo)}"

  def disconnect(name: String): Future[Unit] = byName(name).fold(Future.unit)(_.disconnect())

  /** 状態を読み直す（管理者が設定を変えたとき） */
  def reload(name: String): Unit = byName(name).foreach(_.reload())

  /** JS（window.__skiwiHost.integration）へ渡す口. 知らない名前は null */
  def jsApi(name: String): js.Any = byName(name).fold[js.Any](null)(_.jsApi)

}
