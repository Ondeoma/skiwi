package skiwi.web.workspace

import io.circe.Json
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.web.Api

import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/**
 * 外部サービス（skea・skawau・smohua）ひとつとの連携の状態と、項目（タスク・ファイル・フォーム）のキャッシュ（旧 ui/integration.ts）.
 * ログイン中の画面で共有する. ゲスト向けの公開ページでは使わない（API を呼ばない）.
 * 状態と項目は API の JSON のまま持ち、エディタ（JS）へは window.__skiwiHost.integration(name) で渡す.
 */
final class Integration(
  val name: String) {

  private val Ttl = 60000.0
  private val Batch = 100

  /** 今の状態（読めていない・連携なしは None） */
  private var current: Option[Json] = None

  /** current を JS の値にしたもの（エディタが何度も読むので作っておく） */
  private var currentJs: js.Any = null
  private var loading: Option[Future[Option[Json]]] = None
  private val listeners = mutable.LinkedHashMap.empty[Int, () => Unit]
  private var nextId = 0

  /** キー → (読んだ時刻, 項目). 項目: undefined はまだ分からない・取れなかった、null は見つからない・見る権限がない */
  private val cache = mutable.Map.empty[String, (Double, js.Any)]
  private val inflight = mutable.Set.empty[String]

  private def toJs(
    j: Json
  ): js.Any = js.JSON.parse(j.noSpaces)

  private def bool(
    j: Json,
    key: String
  ): Boolean = j.hcursor.get[Boolean](key).getOrElse(false)

  private def fire(): Unit = listeners.values.toList.foreach(_())

  private def setStatus(
    s: Option[Json]
  ): Unit = {
    current = s
    currentJs = s.fold[js.Any](null)(toJs)
    Integrations.refresh()
    fire()
  }

  /** 連携先の URL（管理者が設定したもの） */
  def url: Option[String] = current.flatMap(_.hcursor.get[String]("url").toOption)

  def status: Option[IntegrationState] = current.map(j => IntegrationState(bool(j, "enabled"), bool(j, "connected")))

  /** 状態を読む（1回だけ. force で読み直す）. 読めなければ None（連携なしとして扱う） */
  def load(
    force: Boolean = false
  ): Future[Option[Json]] = loading match {
    case Some(f) if !force => f
    case _ =>
      val f = Api.request(dom.HttpMethod.GET, s"/api/integrations/$name", redirectOn401 = false).map(Api.as[Json])
        .map { s => setStatus(Some(s)); Option(s) }
        .recover { case _ => loading = None; None }
      loading = Some(f)
      f
  }

  /** 状態や項目が変わったときに呼ぶ（エディタの表示の更新）. 戻り値で解除 */
  def onChange(
    f: () => Unit
  ): () => Unit = {
    val id = nextId
    nextId += 1
    listeners += id -> f
    () => listeners -= id
  }

  /** 分かっている項目（JS の値. undefined / null の意味は cache と同じ） */
  def item(
    key: String
  ): js.Any = cache.get(key).fold[js.Any](js.undefined)(_._2)

  /** まだ知らない（または古い）項目だけ、まとめて問い合わせる. 結果が届いたら onChange に知らせる */
  def request(
    keys: Seq[String]
  ): Unit =
    if (status.exists(s => s.enabled && s.connected)) {
      val now = js.Date.now()
      val need = keys.distinct.filter(k => !inflight(k) && !cache.get(k).exists(_._1 + Ttl > now))
      need.grouped(Batch).foreach { chunk =>
        inflight ++= chunk
        Api.request(
          dom.HttpMethod.POST,
          s"/api/integrations/$name/lookup",
          Some(Json.obj("keys" -> chunk.asJson)),
          redirectOn401 = false
        )
          .map(Api.as[Json])
          .map { r =>
            if (!bool(r, "connected")) {
              // 相手側で連携が切れた
              cache.clear()
              current.foreach(c => setStatus(Some(c.deepMerge(Json.obj("connected" -> false.asJson)))))
            } else {
              val got = r.hcursor.get[List[Json]]("items").getOrElse(Nil)
                .flatMap(t => t.hcursor.get[String]("key").toOption.map(_ -> t)).toMap
              chunk.foreach(k => cache(k) = (js.Date.now(), got.get(k).fold[js.Any](null)(toJs)))
            }
          }
          .recover { case _ => chunk.foreach(k => cache(k) = (js.Date.now(), js.undefined)) }
          .foreach { _ =>
            inflight --= chunk
            fire()
          }
      }
    }

  /** 連携・解除のあとに状態を読み直し、キャッシュを捨てる */
  def reload(): Future[Unit] = {
    cache.clear()
    load(force = true).map(_ => ())
  }

  /** 連携の結果（コールバックで戻り先に付く ?{サービス名}=connected|denied|error）を取り出し、URL から消す */
  def takeResult(): Option[String] = {
    val u = new dom.URL(dom.window.location.href)
    Option(u.searchParams.get(name)).filter(Set("connected", "denied", "error")).map { v =>
      u.searchParams.delete(name)
      dom.window.history.replaceState(dom.window.history.state, "", u.pathname + u.search + u.hash)
      v
    }
  }

  /** 連携を解除する */
  def disconnect(): Future[Unit] = Api.del(s"/api/integrations/$name/connection").flatMap(_ => reload())

  /** エディタ（JS）へ渡す口（frontend/src/ui/host.ts の HostIntegration） */
  lazy val jsApi: js.Dynamic = js.Dynamic.literal(
    status = () => currentJs,
    load = (force: js.UndefOr[Boolean]) =>
      load(force.getOrElse(false)).map(_.fold[js.Any](null)(_ => currentJs)).toJSPromise,
    onChange = (f: js.Function0[Unit]) => {
      val off = onChange(() => f())
      (() => off()): js.Function0[Unit]
    },
    item = (key: String) => item(key),
    request = (keys: js.Array[String]) => request(keys.toSeq),
  )

}
