package skiwi.collab

import sbirds.db.*
import cats.effect.*
import cats.effect.syntax.all.*
import cats.effect.std.{Queue, Semaphore}
import cats.syntax.all.*
import fs2.*
import org.http4s.*
import org.http4s.client.websocket.{WSClient, WSFrame, WSRequest}
import org.http4s.dsl.Http4sDsl
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame
import org.typelevel.ci.CIString
import scodec.bits.ByteVector
import skiwi.api.SessionAuth
import skiwi.collab.{Protocol as Wire}
import sbirds.auth.Crypto
import skiwi.core.*
import skiwi.db.{DocStore, OpsStore, PgBus, Store}
import sbirds.observe.{Metrics, Telemetry}
import sbirds.http.Errors

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import scala.concurrent.duration.*

final case class CollabConfig(
  compactAfterUpdates: Int = 200,
  compactIdle: FiniteDuration = 5.seconds,
  compactTimeout: FiniteDuration = 30.seconds,
  maxUpdateBytes: Int = 8 * 1024 * 1024,
  /** カーソル・在席（awareness）の1通の上限。閲覧だけの人も送れるので小さく抑える */
  maxAwarenessBytes: Int = 64 * 1024,
  /** 状態断片がこれより多ければ接続時に圧縮してまとめる。 */
  maxParts: Int = 4,
  /** 版履歴を自動で残す最小間隔。 */
  versionEvery: FiniteDuration = 10.minutes,
  /** 更新通知メール: 最後の更新からこの時間編集が止まったら送る。 */
  notifyQuiet: FiniteDuration = 5.minutes,
  /** 更新通知メール: 編集が続いても、最初の更新からこの時間で送る。 */
  notifyMaxDelay: FiniteDuration = 30.minutes,
  /** 接続中セッションの権限を定期的に再確認する間隔（セッション失効・無効化への追従）。 */
  revalidateEvery: FiniteDuration = 60.seconds,
  /** 再確認で、同じ認証情報の接続のまとまり1つにかけてよい時間（1つが止まっても、ほかの接続の確認を進めるため）。 */
  revalidateTimeout: FiniteDuration = 10.seconds,
  /** 接続ごとの送信待ちの上限（件数）。読まない・遅い接続がこれを超えたら、その接続だけを切る。 */
  sendQueueItems: Int = 1024,
  /** 接続ごとの送信待ちの上限（バイト数）。1通だけなら、これより大きくても待たせる。 */
  sendQueueBytes: Long = 16L * 1024 * 1024,
  /** 1人が同時に開ける接続の数（ノードごと。0 以下なら制限しない）。 */
  maxConnectionsPerUser: Int = 100,
  /** 接続ごとに受け取る awareness の数（1秒あたり。0 以下なら制限しない）。超えた分は読むのを待たせる。 */
  awarenessPerSecond: Int = 20,
  /** 接続ごとに受け取る update のバイト数（1秒あたり。0 以下なら制限しない）。超えた分は読むのを待たせる。 */
  updateBytesPerSecond: Long = 2L * 1024 * 1024,
  /** 圧縮の応答で受け取る Yjs の状態の上限（文書全体なので、1通の update の上限より大きい）。 */
  maxCompactBytes: Int = 32 * 1024 * 1024,
  /** 圧縮の応答で受け取る本文の JSON の上限（文字数）。 */
  maxContentChars: Int = 64 * 1024 * 1024,
  /** 圧縮の応答で受け取る本文の文字（検索用）の上限（文字数）。 */
  maxPlainTextChars: Int = 16 * 1024 * 1024)

/** 配信物の識別（圧縮時に「そのクライアントへ届けたもの」を正確に把握するため）。 */
enum Item {
  case Upd(
    seq: Long)
  case Snap(
    id: UUID)
  case CompactReq(
    token: String)
}

/** ルーム内で配信されるメッセージ。target が指定された場合はそのクライアントのみ、なければ送信者以外の全員。 */
final case class Out(from: Long, target: Option[Long], frame: ByteVector, item: Option[Item] = None)

private final case class Pending(token: String, target: Long)

/** クライアントへ届けたもの（updateのseq、スナップショットのID）。 */
private final case class Delivered(seqs: Set[Long], snaps: Set[UUID]) {
  def add(i: Item): Delivered = i match {
    case Item.Upd(s) => copy(seqs = seqs + s)
    case Item.Snap(id) => copy(snaps = snaps + id)
    case _: Item.CompactReq => this
  }
  def --(o: Delivered): Delivered = Delivered(seqs -- o.seqs, snaps -- o.snaps)
}

private object Delivered {
  val empty: Delivered = Delivered(Set.empty, Set.empty)
}

/**
 * 接続ごとの送信待ち。上限（件数・バイト数）を超えたら受け取らない（offer が false。呼び出し側がその接続を切る）。
 * 読まない接続がルームのほかの人への配信や権限の再確認を止めないよう、配る側は決して待たない。
 */
private final class Subscriber(val id: Long,
                               queue: Queue[IO, Out],
                               load: Ref[IO, (Int, Long)],
                               maxItems: Int,
                               maxBytes: Long,
                               /** あふれたときに接続を切る */
                               val kick: IO[Unit]) {
  def offer(o: Out): IO[Boolean] =
    load.modify { case (n, b) =>
      // 1通だけなら大きくても受け取る（大きな update を1つも送れなくならないように）
      if n >= maxItems || (n > 0 && b + o.frame.size > maxBytes) then ((n, b), false)
      else ((n + 1, b + o.frame.size), true)
    }.flatTap(ok => if ok then queue.offer(o) else IO.unit).uncancelable

  /** 送る順に取り出す（取り出した分だけ空きが増える） */
  val stream: Stream[IO, Out] =
    Stream.fromQueueUnterminated(queue).evalTap(o => load.update { case (n, b) => (n - 1, b - o.frame.size) })
}

private object Subscriber {
  def create(id: Long, maxItems: Int, maxBytes: Long, kick: IO[Unit]): IO[Subscriber] =
    (
      Queue.unbounded[IO, Out],
      Ref.of[IO, (Int, Long)]((0, 0L))
    ).mapN(new Subscriber(id, _, _, maxItems, maxBytes, kick))
}

/** 受信の頻度の上限（トークンバケット）。使い切ったら、足りない分がたまるまでの待ち時間を返す（perSecond が 0 以下なら制限しない）。 */
private final class Throttle(perSecond: Double, burst: Double, state: Ref[IO, (Double, Long)]) {
  def take(n: Double): IO[FiniteDuration] =
    if perSecond <= 0 then IO.pure(Duration.Zero)
    else
      IO.monotonic.flatMap { now =>
        state.modify { case (tokens, last) =>
          val t = math.min(burst, tokens + (now.toNanos - last) / 1e9 * perSecond) - n
          ((t, now.toNanos), if t >= 0 then Duration.Zero else (-t / perSecond * 1000).toLong.millis)
        }
      }

  /** n だけ使い、使いすぎていれば待つ（この接続の次の受信が遅れるだけ） */
  def pace(n: Double): IO[Unit] = take(n).flatMap(d => if d > Duration.Zero then IO.sleep(d) else IO.unit)
}

private object Throttle {
  def create(perSecond: Double, burst: Double): IO[Throttle] =
    IO.monotonic.flatMap(now => Ref.of[IO, (Double, Long)]((burst, now.toNanos))).map(new Throttle(perSecond, burst, _))
}

private final class Room(
  val pageId: UUID,
  /** このノードでつないでいる接続（接続の ID → 送信待ち） */
  val subs: Ref[IO, Map[Long, Subscriber]],
  val lock: Semaphore[IO],
  val editors: Ref[IO, Set[Long]],
  val sinceSnapshot: Ref[IO, Int],
  val pending: Ref[IO, Option[Pending]],
  val debounce: Ref[IO, Option[Fiber[IO, Throwable, Unit]]],
  /** 前回の版以降に更新を送ったユーザー（版の作者）。 */
  val versionAuthors: Ref[IO, Set[UUID]],
  /** 前回の圧縮以降に更新を送ったユーザー（更新通知の編集者）。 */
  val recentEditors: Ref[IO, Set[UUID]]) {

  /**
   * ルームに配る（target があればその接続だけ、なければ送信者以外の全員）。待たずに各接続の送信待ちへ積み、
   * あふれた接続（読まない・遅い）はルームから外して切る。
   */
  def publish(o: Out): IO[Unit] =
    subs.get.flatMap(_.values.toList.traverse_ { sub =>
      if o.target.fold(o.from != sub.id)(_ == sub.id) then
        sub.offer(o).flatMap(ok => if ok then IO.unit else subs.update(_ - sub.id) *> sub.kick)
      else IO.unit
    })
}

/** 接続中のクライアント。 */
private final class Conn(
  val id: Long,
  val pageId: UUID,
  val userId: UUID,
  /** 元のリクエストでセッションを引き直す（グループ変更・失効を反映するため）。 */
  val reauth: IO[Option[AuthSession]],
  /** 認証情報（Cookie・Authorization）。同じものの接続は再検証をまとめる */
  val authKey: String,
  val role: Ref[IO, Role],
  val room: Deferred[IO, Room],
  val stop: Deferred[IO, Unit],
  val delivered: Ref[IO, Delivered],
  /** 圧縮要求を送った時点の Delivered（トークンごと）。応答時はこれだけを削除対象にする。 */
  val captured: Ref[IO, Map[String, Delivered]])

/**
 * 同時編集サーバー。Yjsの中身は解釈せず、updateをそのまま永続化・中継する。
 *
 * 3ノード構成:
 *   - ページごとに担当ノードを rendezvous hashing で決め、担当でないノードが受けたWebSocketは担当ノードへ転送する（親和性）。
 *   - ただし正しさは親和性に依存しない。updateはすべてDBに保存して `NOTIFY` で他ノードへも配信し、
 *     圧縮は「圧縮したクライアントに届けたもの」だけを消すため、同じページが複数ノードで開かれても内容は失われない。
 */
final class Collab(
  store: Store,
  docs: DocStore,
  sessions: SessionAuth,
  ops: Option[OpsStore] = None,
  cfg: CollabConfig = CollabConfig(),
  bus: Option[PgBus] = None,
  cluster: Option[ClusterManager] = None,
  wsClient: Option[WSClient[IO]] = None,
  /** 本文に新しくメンションが書かれたとき（ページ、メンションされた人、そのとき編集していた人） */
  onMentions: (UUID, Set[UUID], Set[UUID]) => IO[Unit] = (_, _, _) => IO.unit) extends Http4sDsl[IO] {

  def routes(wsb: WebSocketBuilder[IO]): HttpRoutes[IO] = HttpRoutes.of[IO] {
    // 運用確認用（管理者のみ）: このノード、生存ノード、指定ページの担当ノード、このノードで開いているページ数
    case req @ GET -> Root / "api" / "admin" / "cluster" => getAdminCluster(req)
    case req @ GET -> Root / "ws" / "pages" / UUIDVar(pageId) => getWsPage(req, pageId, wsb)
  }

  private def getAdminCluster(req: Request[IO]): IO[Response[IO]] =
    sessions.fromRequest(req).flatMap {
      case Some(s) if s.isAdmin =>
        val page = req.uri.query.params.get("page").flatMap(uuidOpt)
        for {
          alive <- cluster.fold(IO.pure(List.empty[skiwi.db.ClusterNode]))(_.aliveNodes)
          owner <- page.flatTraverse(p => cluster.flatTraverse(_.ownerOf(p)))
          rooms <- registry.get.map(_.size)
          cs <- conns.get.map(_.size)
          body = _root_.io.circe.Json.obj(
            "node" -> _root_.io.circe.Json.fromString(nodeId),
            "alive" -> _root_.io.circe.Json.arr(alive.map(n => _root_.io.circe.Json.fromString(n.id))*),
            "owner" -> owner.fold(_root_.io.circe.Json.Null)(o => _root_.io.circe.Json.fromString(o.id)),
            "openPages" -> _root_.io.circe.Json.fromInt(rooms),
            "connections" -> _root_.io.circe.Json.fromInt(cs)
          )
          resp <- Ok(body.noSpaces, org.http4s.headers.`Content-Type`(MediaType.application.json))
        } yield resp
      case Some(_) => Errors.respond(ErrorCode.AdminOnly)
      case None => Errors.respond(ErrorCode.NotLoggedIn)
    }

  private def getWsPage(req: Request[IO], pageId: UUID, wsb: WebSocketBuilder[IO]): IO[Response[IO]] =
    if isForwarded(req) then serveLocal(req, pageId, wsb) // 転送元で Origin は確認済み
    else if !originOk(req) then Errors.respond(ErrorCode.CollabOriginNotAllowed)
    else
      cluster.flatTraverse(_.ownerOf(pageId)).flatMap {
        case Some(owner) if !cluster.exists(_.self == owner.id) =>
          proxy(owner, req, pageId, wsb).flatMap(_.fold(serveLocal(req, pageId, wsb))(IO.pure))
        case _ => serveLocal(req, pageId, wsb)
      }

  private val nodeId = cluster.fold("local")(_.self)

  // ---------------------------------------------------------------- room registry

  private val registry: Ref[IO, Map[UUID, (Room, Int)]] = Ref.unsafe(Map.empty)
  private val clientIds: Ref[IO, Long] = Ref.unsafe(0L)
  private val conns: Ref[IO, Map[Long, Conn]] = Ref.unsafe(Map.empty)

  private def newRoom(pageId: UUID): IO[Room] =
    (
      Ref.of[IO, Map[Long, Subscriber]](Map.empty),
      Semaphore[IO](1),
      Ref.of[IO, Set[Long]](Set.empty),
      Ref.of[IO, Int](0),
      Ref.of[IO, Option[Pending]](None),
      Ref.of[IO, Option[Fiber[IO, Throwable, Unit]]](None),
      Ref.of[IO, Set[UUID]](Set.empty),
      Ref.of[IO, Set[UUID]](Set.empty)
    ).mapN(new Room(pageId, _, _, _, _, _, _, _, _))

  private def acquire(pageId: UUID): Resource[IO, Room] =
    Resource.make {
      newRoom(pageId).flatMap { cand =>
        registry.modify { m =>
          m.get(pageId) match {
            case Some((r, n)) => (m.updated(pageId, (r, n + 1)), r)
            case None => (m.updated(pageId, (cand, 1)), cand)
          }
        }
      }
    } { _ =>
      registry.modify { m =>
        m.get(pageId) match {
          case Some((r, n)) if n > 1 => (m.updated(pageId, (r, n - 1)), IO.unit)
          case Some((r, _)) =>
            (m - pageId, r.debounce.get.flatMap(_.traverse_(_.cancel)))
          case None => (m, IO.unit)
        }
      }.flatten
    }

  private def localRoom(pageId: UUID): IO[Option[Room]] = registry.get.map(_.get(pageId).map(_._1))

  // ---------------------------------------------------------------- 更新・圧縮

  /**
   * update を保存し、このノードで開かれていればルームの全員（送信者を除く）へ送り、ほかのノードへも知らせる。保存した seq を返す。
   * その後、更新の数に応じて圧縮を要求・予約する。
   */
  private def ingest(room: Option[Room], pageId: UUID, payload: ByteVector, from: Option[Conn]): IO[Long] =
    room.fold(docs.appendUpdate(pageId, payload.toArray)) { r =>
      r.lock.permit.surround(
        docs.appendUpdate(pageId, payload.toArray).flatTap { seq =>
          // 送信者自身は当然その内容を持っている
          from.traverse_(_.delivered.update(_.add(Item.Upd(seq)))) *>
            r.publish(Out(from.fold(0L)(_.id), None, Wire.syncUpdate(payload), Some(Item.Upd(seq))))
        }
      )
    }.flatTap { seq =>
      bus.traverse_(_.publish(Collab.UpdateChannel, s"$nodeId|$pageId|$seq")
        .handleErrorWith(e => sbirds.observe.Log.report(ErrorCode.CollabAccessNotifyFailed, e.toString, e))) *>
        room.traverse_(r =>
          r.sinceSnapshot.updateAndGet(_ + 1).flatMap { n =>
            if n >= cfg.compactAfterUpdates then requestCompaction(r) else scheduleCompaction(r)
          }
        )
    }

  private def onUpdate(room: Room, c: Conn, payload: ByteVector): IO[Unit] =
    Telemetry.span("collab.update", "page.id" -> room.pageId.toString)(
      room.versionAuthors.update(_ + c.userId) *> room.recentEditors.update(_ + c.userId) *>
        ingest(Some(room), room.pageId, payload, Some(c)).void
    ) *> Metrics.count("skiwi.collab.updates", "via" -> "client")

  /** サーバーで作った update（API・MCP での本文の変更）を、クライアントからの update と同じく保存して配る。保存した seq を返す。 */
  def injectUpdate(pageId: UUID, update: Array[Byte]): IO[Long] =
    Telemetry.span(
      "collab.inject",
      "page.id" -> pageId.toString
    )(localRoom(pageId).flatMap(ingest(_, pageId, ByteVector(update), None)))
      .flatTap(_ => Metrics.count("skiwi.collab.updates", "via" -> "server"))

  /** 最後のupdateから一定時間後に圧縮を要求する（連続更新の間は延期）。 */
  private def scheduleCompaction(room: Room): IO[Unit] =
    room.debounce.get.flatMap(_.traverse_(_.cancel)) *>
      (IO.sleep(cfg.compactIdle) *> requestCompaction(room).uncancelable).start.flatMap(f => room.debounce.set(Some(f)))

  private def requestCompaction(room: Room): IO[Unit] =
    room.lock.permit.surround {
      room.pending.get.flatMap {
        case Some(_) => IO.unit
        case None =>
          room.editors.get.flatMap { eds =>
            eds.minOption match {
              case None => IO.unit // 編集可能なクライアントが接続していない。次回接続時に再試行
              case Some(target) =>
                val token = Crypto.randomToken(16)
                room.pending.set(Some(Pending(token, target))) *>
                  room.publish(Out(
                    0L,
                    Some(target),
                    Wire.compactRequest(token),
                    Some(Item.CompactReq(token))
                  )) *>
                  // 応答がなければ要求を破棄して再要求できるようにする
                  (IO.sleep(cfg.compactTimeout) *> room.pending.update(_.filterNot(_.token == token)) *>
                    conns.get.flatMap(_.get(target).traverse_(_.captured.update(_ - token)))).start.void
            }
          }
      }
    }

  private def onCompactResponse(room: Room,
                                c: Conn,
                                token: String,
                                update: ByteVector,
                                json: String,
                                text: String): IO[Unit] =
    if update.size > cfg.maxCompactBytes || json.length > cfg.maxContentChars || text.length > cfg.maxPlainTextChars
    then
      // 大きすぎる応答は保存しない（要求は時間切れで破棄され、次の更新でまた要求する）
      sbirds.observe.Log.report(
        ErrorCode.CollabCompactTooLarge,
        s"client ${c.id} on ${room.pageId}: update ${update.size} bytes, json ${json.length} chars, text ${text.length} chars"
      )
    else onCompactResponseChecked(room, c, token, update, json, text)

  private def onCompactResponseChecked(room: Room,
                                       c: Conn,
                                       token: String,
                                       update: ByteVector,
                                       json: String,
                                       text: String): IO[Unit] = {
    // 本文の JSON はここで1回だけ読む（壊れていれば応答ごと無視する）
    val content = _root_.io.circe.parser.parse(json).toOption
    room.pending.modify {
      case Some(p) if p.token == token && p.target == c.id && content.isDefined => (None, content)
      case other => (other, None)
    }.flatMap {
      case None => IO.unit // 想定外の応答は無視
      case Some(doc) =>
        c.captured.modify(m => (m - token, m.get(token))).flatMap {
          case None => IO.unit // 要求がこのクライアントに届いた記録がない
          case Some(known) =>
            Telemetry.span("collab.compact", "page.id" -> room.pageId.toString)(for {
              authors <- room.versionAuthors.get
              editors <- room.recentEditors.getAndSet(Set.empty)
              res <- docs.saveCompaction(
                room.pageId,
                update.toArray,
                doc,
                text,
                known.seqs,
                known.snaps,
                c.userId,
                authors,
                cfg.versionEvery
              )
              // 新しいスナップショットはこのクライアントの状態そのもの
              _ <- c.delivered.update(d => (d -- known).add(Item.Snap(res.snapshotId)))
              _ <- room.sinceSnapshot.update(n => math.max(0, n - res.removedUpdates))
              _ <- if res.versioned then room.versionAuthors.update(_ -- authors) else IO.unit
              _ <- notifyEdited(room.pageId, editors)
              _ <- if res.newMentions.isEmpty then IO.unit
              else
                onMentions(room.pageId, res.newMentions, if editors.isEmpty then Set(c.userId) else editors)
                  .handleErrorWith(e => sbirds.observe.Log.report(ErrorCode.NotifyEnqueueFailed, s"mentions: $e", e))
            } yield ())
        }
    }
  }

  /** ウォッチしている人へ更新通知を積む（送信はワーカーがまとめて行う）。 */
  private def notifyEdited(pageId: UUID, editors: Set[UUID]): IO[Unit] =
    (ops, editors.nonEmpty) match {
      case (Some(o), true) =>
        Metrics.count("skiwi.pages.updated", "via" -> "collab") *>
          o.enqueuePageEdited(pageId, editors, cfg.notifyQuiet.toSeconds.toInt, cfg.notifyMaxDelay.toSeconds.toInt).void
            .handleErrorWith(e => sbirds.observe.Log.report(ErrorCode.NotifyEnqueueFailed, e.toString, e))
      case _ => IO.unit
    }

  // ---------------------------------------------------------------- ノード間

  /** 文書の全構成要素をルームの全員へ流し直す（取りこぼしの回復。Yjsなので重複は無害）。 */
  private def resync(room: Room): IO[Unit] =
    Telemetry.span("collab.resync", "page.id" -> room.pageId.toString)(docs.load(room.pageId)).flatMap { parts =>
      parts.snapshots.traverse_((id, y) =>
        room.publish(Out(0L, None, Wire.syncUpdate(ByteVector(y)), Some(Item.Snap(id))))
      ) *>
        parts.updates.traverse_((seq, u) =>
          room.publish(Out(0L, None, Wire.syncUpdate(ByteVector(u)), Some(Item.Upd(seq))))
        )
    }

  /** 他ノードで保存されたupdateを、このノードのルームにも配信する。 */
  private def onRemoteUpdate(payload: String): IO[Unit] =
    payload.split('|') match {
      case Array(from, page, seqS) if from != nodeId =>
        (uuidOpt(page), seqS.toLongOption).tupled.traverse_ {
          (pageId, seq) =>
            localRoom(pageId).flatMap(_.traverse_ { room =>
              docs.getUpdate(pageId, seq).flatMap {
                case Some(u) =>
                  room.publish(Out(0L, None, Wire.syncUpdate(ByteVector(u)), Some(Item.Upd(seq))))
                case None => resync(room) // 既に圧縮済み: スナップショットごと流し直す
              }
            })
        }
      case _ => IO.unit
    }

  /** バス（LISTEN接続）の接続・再接続時。切断中の通知は失われるので、開いている全ルームを流し直す。 */
  private val onBusConnected: IO[Unit] =
    registry.get.flatMap(_.values.toList.traverse_((r, _) =>
      resync(r).handleErrorWith(e => sbirds.observe.Log.report(ErrorCode.CollabResyncFailed, e.toString, e))
    ))

  /** ノード間メッセージの購読（Mainで起動する）。 */
  val busLoop: IO[Unit] = bus match {
    case None => IO.never
    case Some(b) =>
      b.subscribe(List(Collab.UpdateChannel, Collab.AccessChannel), onBusConnected) {
        (ch, payload) =>
          ch match {
            case Collab.UpdateChannel => onRemoteUpdate(payload)
            case Collab.AccessChannel => if payload != nodeId then revalidate else IO.unit
            case _ => IO.unit
          }
      }.void
  }

  /** 権限が変わり得る操作の後に呼ぶ。自ノードを再検証し、他ノードにも知らせる。 */
  val accessChanged: IO[Unit] =
    revalidate *> bus.traverse_(_.publish(Collab.AccessChannel, nodeId).handleErrorWith(e =>
      sbirds.observe.Log.report(ErrorCode.CollabAccessNotifyFailed, e.toString, e)
    ))

  /**
   * 担当でないページの接続を閉じ、クライアントに担当ノードへ繋ぎ直させる。生存ノードが変わったときと定期的に呼ぶ
   * （担当ノードへ転送できず自ノードで受けた接続を、担当ノードが応答するようになったら戻すため）。
   */
  val rebalance: IO[Unit] = cluster match {
    case None => IO.unit
    case Some(cl) =>
      conns.get.flatMap { cs =>
        // 担当が他ノードのページ（担当ノード, ページ, 接続）
        cs.values.toList.groupBy(_.pageId).toList.traverseFilter {
          (pageId, list) =>
            cl.ownerOf(pageId).map(_.filter(_.id != cl.self).map(o => (o, pageId, list)))
        }.flatMap { moves =>
          // 応答の確認は担当ノードごとに1回だけ
          moves.groupBy(_._1.id).values.toList.traverse_ { group =>
            cl.reachable(group.head._1).flatMap {
              case true =>
                group.traverse_ {
                  (o, pageId, list) =>
                    sbirds.observe.Log.println(
                      s"[cluster] page $pageId belongs to ${o.id}; closing ${list.size} local connection(s)"
                    ) *>
                      list.traverse_(_.stop.complete(()).void)
                }
              case false => IO.unit // 担当ノードがまだ応答しない: このノードで処理を続ける
            }
          }
        }
      }
  }

  /** 定期的な再配置（Mainで起動する）。 */
  val rebalanceLoop: IO[Nothing] =
    Periodic.run(30.seconds, 30.seconds, ErrorCode.ClusterRebalanceFailed)(rebalance)

  // ---------------------------------------------------------------- 権限の再検証

  /**
   * 接続中の全クライアントの権限を確認し直す。権限変更・グループ変更・無効化の直後と定期的に呼ぶ。
   * 権限を失ったら通知して切断、編集↔閲覧が変わったら通知して以後の扱いを変える。
   */
  lazy val revalidate: IO[Unit] =
    // 実行中に呼ばれたら、終わった後にもう1回だけ行う（権限の変更が続いても、確認を重ねて走らせない）
    revalidateState.modify {
      case (true, _) => ((true, true), IO.unit)
      case (false, _) => ((true, false), revalidateRounds)
    }.flatten

  /** (実行中か, 実行中に新しく頼まれたか) */
  private lazy val revalidateState = Ref.unsafe[IO, (Boolean, Boolean)]((false, false))

  private def revalidateRounds: IO[Unit] =
    revalidateOnce.handleErrorWith(e => sbirds.observe.Log.report(ErrorCode.CollabRevalidateFailed, e.toString, e)) *>
      revalidateState.modify {
        case (_, true) => ((true, false), revalidateRounds)
        case _ => ((false, false), IO.unit)
      }.flatten.onCancel(revalidateState.set((false, false)))

  private def revalidateOnce: IO[Unit] =
    // 同じ認証情報の接続（同じ人の複数のタブなど）は、セッションの引き直しと権限の確認を1回にまとめる。
    // 人ごとの確認は並べて行う（DB の接続を使い切らないよう4つまで）
    conns.get.flatMap(_.values.toList.groupBy(_.authKey).values.toList.parTraverseN_(4) { group =>
      val check: IO[Map[UUID, Role]] = group.head.reauth.flatMap {
        case None => IO.pure(Map.empty)
        case Some(s) => store.pagesAccess(group.map(_.pageId), s).map(_.map { case (pid, (_, r)) =>
            pid -> (if s.readOnly then Role.View else r)
          })
      }
      // 1つのまとまりが止まっても（DB・セッションの引き直しが返らないなど）、ほかの接続の確認を止めない
      check.flatMap(roles => group.traverse_(c => applyRole(c, roles.get(c.pageId)))).timeout(cfg.revalidateTimeout)
        .handleErrorWith(e =>
          sbirds.observe.Log.report(
            ErrorCode.CollabRevalidateFailed,
            s"client(s) ${group.map(_.id).mkString(",")}: $e",
            e
          )
        )
    })

  /** 再検証の結果を接続に反映する。権限を失ったら通知して（少し待ってから）切断する。 */
  private def applyRole(c: Conn, newRole: Option[Role]): IO[Unit] =
    c.room.tryGet.flatMap {
      case None => IO.unit // まだ部屋に入っていない
      case Some(room) =>
        newRole match {
          case None =>
            room.publish(Out(0L, Some(c.id), Wire.role("none"))) *>
              (IO.sleep(200.millis) *> c.stop.complete(()).void).start.void
          case Some(r) =>
            c.role.getAndSet(r).flatMap { old =>
              if old == r then IO.unit
              else
                (if r == Role.Edit then room.editors.update(_ + c.id) else room.editors.update(_ - c.id)) *>
                  room.publish(Out(0L, Some(c.id), Wire.role(r.asString)))
            }
        }
    }

  /** 定期再検証ループ（Mainで起動する）。 */
  val revalidateLoop: IO[Nothing] =
    Periodic.run(cfg.revalidateEvery, cfg.revalidateEvery, ErrorCode.CollabRevalidateFailed)(revalidate)

  // ---------------------------------------------------------------- WebSocket（自ノードで処理）

  private def connection(pageId: UUID,
                         initial: Role,
                         s: AuthSession,
                         reauth: IO[Option[AuthSession]],
                         authKey: String,
                         wsb: WebSocketBuilder[IO]): IO[Response[IO]] =
    for {
      id <- clientIds.updateAndGet(_ + 1)
      roomD <- Deferred[IO, Room]
      stop <- Deferred[IO, Unit]
      role <- Ref.of[IO, Role](initial)
      deliv <- Ref.of[IO, Delivered](Delivered.empty)
      capt <- Ref.of[IO, Map[String, Delivered]](Map.empty)
      // この接続が名乗った awareness のクライアント（切断時に、ほかの人の画面から消すため）
      aware <- Ref.of[IO, Map[Long, Long]](Map.empty)
      conn = new Conn(id, pageId, s.user.id, reauth, authKey, role, roomD, stop, deliv, capt)
      sub <- Subscriber.create(
        id,
        cfg.sendQueueItems,
        cfg.sendQueueBytes,
        sbirds.observe.Log.report(ErrorCode.CollabSlowConsumer, s"client $id on $pageId (user ${s.user.id})") *>
          stop.complete(()).void
      )
      // 受信の頻度の上限（まとめて送る分は少し先まで許す: awareness は3秒分、update は上限の大きさの1通分）
      awarePace <- Throttle.create(cfg.awarenessPerSecond.toDouble, cfg.awarenessPerSecond * 3.0)
      updatePace <- Throttle.create(cfg.updateBytesPerSecond.toDouble, cfg.maxUpdateBytes.toDouble)
      send: Stream[IO, WebSocketFrame] =
        (for {
          // 接続を登録する。1人の接続が上限に達していれば登録せずに閉じる（接続時の確認と登録の間に増えた分もここで止める）
          registered <- Stream.bracket(
            conns.modify { m =>
              if tooMany(m, s.user.id) then (m, false) else (m + (id -> conn), true)
            }.flatTap(ok => if ok then Metrics.upDown("skiwi.collab.connections", 1) else IO.unit)
          )(ok => if ok then conns.update(_ - id) *> Metrics.upDown("skiwi.collab.connections", -1) else IO.unit)
          _ <- if registered then Stream.emit(()) else Stream.exec(stop.complete(()).void)
          room <- Stream.resource(acquire(pageId)).evalTap(roomD.complete)
          _ <- Stream.bracket(role.get.flatMap(r => if r == Role.Edit then room.editors.update(_ + id) else IO.unit))(
            _ => room.editors.update(_ - id)
          )
          _ <- Stream.bracket(IO.unit)(_ =>
            aware.get.flatMap(m =>
              if m.isEmpty then IO.unit else room.publish(Out(id, None, Wire.awarenessRemoval(m)))
            )
          )
          _ <- Stream.bracket(room.subs.update(_ + (id -> sub)))(_ => room.subs.update(_ - id))
          parts <- Stream.eval(docs.load(pageId))
          r0 <- Stream.eval(role.get)
          // 購読開始後に読み込むため、取りこぼしは起きない（重複はCRDTで冪等）
          _ <- Stream.eval(deliv.set(Delivered(parts.updates.map(_._1).toSet, parts.snapshots.map(_._1).toSet)))
          hello = Wire.role(r0.asString) ::
            (parts.snapshots.map((_, y) => Wire.syncUpdate(ByteVector(y))) ++
              parts.updates.map((_, u) => Wire.syncUpdate(ByteVector(u))) :+ Wire.syncDoneMarker)
          _ <- Stream.eval(
            // 未圧縮のログや状態断片が多ければ、接続を契機に圧縮を要求する
            if parts.updates.size >= cfg.compactAfterUpdates || parts.snapshots.size > cfg.maxParts then
              room.sinceSnapshot.set(parts.updates.size) *> requestCompaction(room)
            else IO.unit
          )
          // 宛先の絞り込みは配る側（Room.publish）で済んでいる
          frame <- Stream.emits(hello) ++ sub.stream.evalMap { out =>
            // このクライアントへ届けたものを記録する（ストリームの順序どおり）
            (out.item match {
              case Some(Item.CompactReq(tok)) => deliv.get.flatMap(d => capt.update(_ + (tok -> d)))
              case Some(i) => deliv.update(_.add(i))
              case None => IO.unit
            }).as(out.frame)
          }
        } yield WebSocketFrame.Binary(frame)).interruptWhen(stop.get.attempt)
      receive: Pipe[IO, WebSocketFrame, Unit] = _.evalMap {
        case WebSocketFrame.Binary(data, true) =>
          (roomD.get, role.get).tupled.flatMap {
            (room, r) =>
              val canEdit = r == Role.Edit
              Wire.parse(data) match {
                case Wire.Inbound.Update(p) =>
                  if canEdit && p.size <= cfg.maxUpdateBytes then
                    Telemetry.withUser(s.user.id.toString, s.user.name)(onUpdate(room, conn, p)) *>
                      updatePace.pace(p.size.toDouble)
                  else IO.unit
                case Wire.Inbound.Awareness if data.size > cfg.maxAwarenessBytes => IO.unit
                case Wire.Inbound.Awareness =>
                  (Wire.awarenessAsUser(data, s.user.id.toString, s.user.name) match {
                    case None => IO.unit
                    case Some(own) =>
                      aware.update(m =>
                        Wire.awarenessClients(own).foldLeft(m) { case (acc, (c, clock, removed)) =>
                          if removed then acc - c else acc + (c -> clock)
                        }
                      ) *> room.publish(Out(id, None, own))
                  }) *> awarePace.pace(1)
                case Wire.Inbound.CompactResponse(tok, upd, json, text) =>
                  if canEdit then
                    Telemetry.withUser(
                      s.user.id.toString,
                      s.user.name
                    )(onCompactResponse(room, conn, tok, upd, json, text))
                  else IO.unit
                case Wire.Inbound.Malformed =>
                  sbirds.observe.Log.report(ErrorCode.CollabMalformedFrame, s"client $id on $pageId")
                case Wire.Inbound.Ignored => IO.unit
              }
          }
        case _ => IO.unit
      }.interruptWhen(stop.get.attempt)
      resp <- wsb.build(send, receive)
    } yield resp

  /** userId の接続が、1人が同時に開ける数に達しているか */
  private def tooMany(m: Map[Long, Conn], userId: UUID): Boolean =
    cfg.maxConnectionsPerUser > 0 && m.valuesIterator.count(_.userId == userId) >= cfg.maxConnectionsPerUser

  private def serveLocal(req: Request[IO], pageId: UUID, wsb: WebSocketBuilder[IO]): IO[Response[IO]] = {
    // 再検証用: リクエストの認証情報だけを保持して後から引き直す
    val authOnly = Request[IO](method = Method.GET, headers = req.headers)
    val authKey = List(CIString("Cookie"), CIString("Authorization")).flatMap(n =>
      req.headers.get(n).toList.flatMap(_.toList.map(_.value))
    ).mkString("\n")
    Telemetry.span("collab.connect", "page.id" -> pageId.toString)(sessions.fromRequest(req).flatMap {
      case None => Errors.respond(ErrorCode.NotLoggedIn)
      case Some(s) =>
        Telemetry.withUser(s.user.id.toString, s.user.name)(store.pageAccess(pageId, s).flatMap {
          case None => Errors.respond(ErrorCode.PageNotFound)
          case Some((_, role)) =>
            conns.get.map(tooMany(_, s.user.id)).flatMap {
              case true => Errors.respond(ErrorCode.CollabTooManyConnections)
              case false =>
                Metrics.count("skiwi.collab.sessions", "role" -> (if s.readOnly then Role.View else role).asString) *>
                  connection(
                    pageId,
                    if s.readOnly then Role.View else role,
                    s,
                    sessions.fromRequest(authOnly),
                    authKey,
                    wsb
                  )
            }
        })
    })
  }

  // ---------------------------------------------------------------- WebSocket（担当ノードへ転送）

  private val ForwardHeader = CIString("X-Skiwi-Forwarded")

  private def isForwarded(req: Request[IO]): Boolean =
    cluster.exists { cl =>
      req.headers.get(ForwardHeader).exists(h =>
        MessageDigest.isEqual(
          h.head.value.getBytes(StandardCharsets.UTF_8),
          cl.cfg.secret.getBytes(StandardCharsets.UTF_8)
        )
      )
    }

  /** 担当ノードへWebSocketを中継する。担当ノードが認証を拒否したらその応答を返す。到達できなければ None（自ノードで処理）。 */
  private def proxy(owner: skiwi.db.ClusterNode,
                    req: Request[IO],
                    pageId: UUID,
                    wsb: WebSocketBuilder[IO]): IO[Option[Response[IO]]] =
    (cluster, wsClient).tupled match {
      case None => IO.pure(None)
      case Some((cl, client)) =>
        val keep = Set(CIString("Cookie"), CIString("Authorization"))
        val headers =
          Headers(req.headers.headers.filter(h => keep(h.name))).put(Header.Raw(ForwardHeader, cl.cfg.secret))
        val base = owner.url.stripSuffix("/").replaceFirst("^http", "ws")
        val wsReq = WSRequest(Uri.unsafeFromString(s"$base/ws/pages/$pageId")).withHeaders(headers)
        Telemetry.span(
          "collab.proxy",
          "page.id" -> pageId.toString,
          "owner" -> owner.id
        )(client.connectHighLevel(wsReq).allocated.timeout(2.seconds).attempt).flatMap {
          case Left(e) =>
            Collab.handshakeStatus(e) match {
              case Some(code) => Status.fromInt(code).toOption.map(st => Response[IO](st)).pure[IO]
              case None =>
                sbirds.observe.Log.println(
                  s"[cluster] cannot reach owner ${owner.id} for page $pageId (${e.getMessage}); serving locally"
                ).as(None)
            }
          case Right((up, release)) =>
            release.memoize.flatMap { rel =>
              val toClient: Stream[IO, WebSocketFrame] = up.receiveStream.collect {
                case WSFrame.Binary(d, last) => WebSocketFrame.Binary(d, last)
                case WSFrame.Text(t, last) => WebSocketFrame.Text(t, last)
              }.onFinalize(rel)
              val fromClient: Pipe[IO, WebSocketFrame, Unit] = _.evalMap {
                case WebSocketFrame.Binary(d, last) => up.send(WSFrame.Binary(d, last))
                case WebSocketFrame.Text(t, last) => up.send(WSFrame.Text(t, last))
                case _ => IO.unit
              }.onFinalize(rel)
              wsb.build(toClient, fromClient).map(Some(_))
            }
        }
    }

  /** Originヘッダがあればホストの一致を要求する（Cross-Site WebSocket Hijacking対策）。 */
  private def originOk(req: Request[IO]): Boolean =
    req.headers.get(CIString("Origin")).forall { o =>
      val host = req.headers.get[org.http4s.headers.Host].map(h => h.host + h.port.fold("")(p => s":$p"))
      val originAuthority = o.head.value.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "")
      host.contains(originAuthority)
    }

}

object Collab {
  val UpdateChannel = "skiwi_update"
  val AccessChannel = "skiwi_access"

  /** WebSocketのハンドシェイクで相手が返したHTTPステータス（拒否された場合）。 */
  def handshakeStatus(e: Throwable): Option[Int] =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(10).collectFirst {
      case h: java.net.http.WebSocketHandshakeException => h.getResponse.statusCode()
    }
}
