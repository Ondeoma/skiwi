package skiwi.collab

import scodec.bits.ByteVector
import skiwi.ydoc.{YReader, YWriter}

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/** y-websocket互換のフレーム＋圧縮用の独自メッセージ。lib0 の符号化は skiwi.ydoc の YReader / YWriter を使う。
  *
  *   [0][sub][varBytes]   sync (sub: 0=step1, 1=step2, 2=update)
  *   [1][varBytes]        awareness
  *   [100][token]         server→client: スナップショット要求
  *   [101][token][update][json][text]  client→server: スナップショット応答
  *   [102][role]          server→client: 現在の権限 ("edit" | "view" | "none")。接続時と権限変更時に送る
  */
object Protocol:
  val MsgSync        = 0
  val MsgAwareness   = 1
  val MsgCompactReq  = 100
  val MsgCompactResp = 101
  val MsgRole        = 102

  val SyncStep1  = 0
  val SyncStep2  = 1
  val SyncUpdate = 2

  /** フレームを組み立てる */
  private[collab] def write(f: YWriter => Unit): ByteVector =
    val w = YWriter()
    f(w)
    ByteVector.view(w.result)

  /** 長さ付きのバイト列（lib0 の writeVarUint8Array） */
  private[collab] def writeVarBytes(w: YWriter, b: ByteVector): Unit =
    w.varUint(b.size)
    w.bytes(b.toArray)

  /** 読めなければ None（途中で終わる・長すぎる・UTF-8 として正しくないなど） */
  private def read[A](b: ByteVector)(f: YReader => A): Option[A] =
    scala.util.Try(f(YReader(b.toArray))).toOption

  /** 文字列（UTF-8 として正しくなければ失敗にする） */
  private def strictString(r: YReader): String =
    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(r.varBytes())).toString

  /** 空のupdate(構造体0個、削除セット0個)を持つstep2。クライアントに「同期完了」を伝えるマーカー。 */
  val syncDoneMarker: ByteVector = syncStep2(ByteVector(0, 0))

  def syncStep2(payload: ByteVector): ByteVector  = write { w => w.varUint(MsgSync); w.varUint(SyncStep2); writeVarBytes(w, payload) }
  def syncUpdate(payload: ByteVector): ByteVector = write { w => w.varUint(MsgSync); w.varUint(SyncUpdate); writeVarBytes(w, payload) }

  def compactRequest(token: String): ByteVector = write { w => w.varUint(MsgCompactReq); w.string(token) }

  def role(r: String): ByteVector = write { w => w.varUint(MsgRole); w.string(r) }

  /** awareness フレームに含まれるクライアント（ID → (clock, 消えたか)）。中身の状態は解釈しない。 */
  def awarenessClients(frame: ByteVector): List[(Long, Long, Boolean)] =
    read(frame) { r =>
      r.varUint()
      val update = YReader(r.varBytes())
      val n      = update.varUint()
      // 途中で壊れていたら、そこまでに読めた分を返す
      val out = List.newBuilder[(Long, Long, Boolean)]
      var ok  = true
      var i   = 0L
      while ok && i < n && i < 1000 do
        scala.util.Try((update.varUint(), update.varUint(), strictString(update))) match
          case scala.util.Success((client, clock, state)) => out += ((client, clock, state == "null"))
          case _                                          => ok = false
        i += 1
      out.result()
    }.getOrElse(Nil)

  /** 接続が切れたクライアントの awareness を消すフレーム（clock を1つ進めて状態 null） */
  def awarenessRemoval(clients: Map[Long, Long]): ByteVector =
    val body = write { w =>
      w.varUint(clients.size.toLong)
      clients.foreach((id, clock) => { w.varUint(id); w.varUint(clock + 1); w.string("null") })
    }
    write { w => w.varUint(MsgAwareness); writeVarBytes(w, body) }

  enum Inbound:
    case Update(payload: ByteVector)
    case Awareness
    case CompactResponse(token: String, update: ByteVector, json: String, text: String)
    case Ignored
    case Malformed

  def parse(frame: ByteVector): Inbound =
    import Inbound.*
    read(frame) { r =>
      r.varUint() match
        case MsgSync =>
          r.varUint() match
            case sub if sub == SyncStep2 || sub == SyncUpdate =>
              val payload = r.varBytes()
              if r.remaining == 0 then Update(ByteVector.view(payload)) else Malformed
            case SyncStep1 => Ignored // サーバーが全状態を送るため不要
            case _         => Malformed
        case MsgAwareness => Awareness
        case MsgCompactResp =>
          val token  = strictString(r)
          val update = r.varBytes()
          val json   = strictString(r)
          val text   = strictString(r)
          if r.remaining == 0 then CompactResponse(token, ByteVector.view(update), json, text) else Malformed
        case _ => Ignored
    }.getOrElse(Malformed)
