package skiwi.ydoc

import io.circe.Json

import scala.collection.mutable.ArrayBuffer

/** Yjs の構造体の ID（クライアントとその時計）。 */
final case class YId(client: Long, clock: Long) {
  def +(n: Long): YId = YId(client, clock + n)
}

/** 親: ルートの型（名前）か、型を中身に持つ項目の ID。 */
enum YParent {
  case Root(
    name: String)
  case Of(
    item: YId)
}

/** 項目の中身（Yjs の Content*）。length は Yjs と同じ数え方（文字列は UTF-16 の長さ）。 */
enum YContent {
  case Deleted(
    len: Int)
  case JsonC(
    values: Vector[String])
  case Binary(
    bytes: Array[Byte])
  case Str(
    s: String)
  case Embed(
    json: String)
  case Format(
    key: String,
    json: String)
  case Type(
    ref: Int,
    name: Option[String])
  case AnyC(
    values: Vector[Json])
  case Doc(
    guid: String,
    opts: Json)

  def length: Int = this match {
    case Deleted(n) => n
    case JsonC(vs) => vs.size
    case Str(s) => s.length
    case AnyC(vs) => vs.size
    case _ => 1
  }

  /** off の位置で2つに分ける（長さが 1 を超えうる中身だけ）。 */
  def splitAt(off: Int): (YContent, YContent) = this match {
    case Deleted(n) => (Deleted(off), Deleted(n - off))
    case JsonC(vs) => (JsonC(vs.take(off)), JsonC(vs.drop(off)))
    case Str(s) =>
      // サロゲートペアの途中で分けるときは、両側を U+FFFD にする（Yjs の ContentString.splice と同じ）
      if Character.isHighSurrogate(s.charAt(off - 1)) then
        (Str(s.substring(0, off - 1) + "\uFFFD"), Str("\uFFFD" + s.substring(off + 1)))
      else (Str(s.substring(0, off)), Str(s.substring(off)))
    case AnyC(vs) => (AnyC(vs.take(off)), AnyC(vs.drop(off)))
    case other => throw IllegalStateException(s"cannot split $other")
  }
}

object YContent {
  // 型の種類（Yjs の typeRefs）
  val YArray = 0; val YMap = 1; val YText = 2; val YXmlElement = 3; val YXmlFragment = 4; val YXmlHook = 5;
  val YXmlText = 6
}

/** 更新に含まれる構造体。 */
enum YStruct {
  case GC(
    id: YId,
    len: Int)
  case Skip(
    id: YId,
    len: Int)

  /** parent / parentSub は、origin も rightOrigin もないときだけ書かれる（あるときは左右の項目から決まる）。 */
  case Item(
    id: YId,
    origin: Option[YId],
    rightOrigin: Option[YId],
    parent: Option[YParent],
    parentSub: Option[String],
    content: YContent)

  def id: YId
  def length: Int = this match {
    case GC(_, n) => n
    case Skip(_, n) => n
    case Item(_, _, _, _, _, c) => c.length
  }
}

/** 削除の集合: クライアント → (clock, len) の並び。 */
type DeleteSet = Map[Long, Vector[(Long, Long)]]

/** 1つの更新（Yjs の update v1）。 */
final case class YUpdate(structs: Vector[YStruct], deletes: DeleteSet)

object YCodec {

  private def readContent(r: YReader, ref: Int): YContent = ref match {
    case 1 => YContent.Deleted(r.varUint().toInt)
    case 2 => YContent.JsonC(Vector.fill(r.varUint().toInt)(r.string()))
    case 3 => YContent.Binary(r.varBytes())
    case 4 => YContent.Str(r.string())
    case 5 => YContent.Embed(r.string())
    case 6 => YContent.Format(r.string(), r.string())
    case 7 =>
      val t = r.varUint().toInt
      YContent.Type(t, if t == YContent.YXmlElement || t == YContent.YXmlHook then Some(r.string()) else None)
    case 8 => YContent.AnyC(Vector.fill(r.varUint().toInt)(r.any()))
    case 9 => YContent.Doc(r.string(), r.any())
    case x => throw IllegalArgumentException(s"unknown content ref: $x")
  }

  /** Yjs の update（v1）を読む。 */
  def decode(bytes: Array[Byte]): YUpdate = {
    val r = YReader(bytes)
    val structs = ArrayBuffer.empty[YStruct]
    val nClients = r.varUint()
    var i = 0L
    while i < nClients do {
      val n = r.varUint()
      val client = r.varUint()
      var clock = r.varUint()
      var j = 0L
      while j < n do {
        val info = r.u8()
        val s: YStruct = info & 0x1f match {
          case 0 => YStruct.GC(YId(client, clock), r.varUint().toInt)
          case 10 => YStruct.Skip(YId(client, clock), r.varUint().toInt)
          case ref =>
            val origin = Option.when((info & 0x80) != 0)(r.id())
            val rightOrigin = Option.when((info & 0x40) != 0)(r.id())
            val noOrigins = (info & 0xc0) == 0
            val parent =
              if !noOrigins then None
              else if r.varUint() == 1 then Some(YParent.Root(r.string()))
              else Some(YParent.Of(r.id()))
            val parentSub = Option.when(noOrigins && (info & 0x20) != 0)(r.string())
            YStruct.Item(YId(client, clock), origin, rightOrigin, parent, parentSub, readContent(r, ref))
        }
        structs += s
        clock += s.length
        j += 1
      }
      i += 1
    }
    val ds = (0L until r.varUint()).map { _ =>
      val client = r.varUint()
      client -> Vector.fill(r.varUint().toInt)((r.varUint(), r.varUint()))
    }.toMap
    YUpdate(structs.toVector, ds)
  }

  private def writeContent(w: YWriter, c: YContent): Unit = c match {
    case YContent.Str(s) => w.string(s)
    case YContent.Type(t, name) => w.varUint(t.toLong); name.foreach(w.string)
    case YContent.Format(k, json) => w.string(k); w.string(json)
    case YContent.AnyC(vs) => w.varUint(vs.size.toLong); vs.foreach(w.any)
    case other => throw IllegalArgumentException(s"writing $other is not supported")
  }

  private def ref(c: YContent): Int = c match {
    case _: YContent.Deleted => 1
    case _: YContent.JsonC => 2
    case _: YContent.Binary => 3
    case _: YContent.Str => 4
    case _: YContent.Embed => 5
    case _: YContent.Format => 6
    case _: YContent.Type => 7
    case _: YContent.AnyC => 8
    case _: YContent.Doc => 9
  }

  /** 1つのクライアントの連続した項目（時計の順）と削除の集合を update（v1）にする。 */
  def encode(items: Vector[YStruct.Item], deletes: DeleteSet): Array[Byte] = {
    val w = YWriter()
    if items.isEmpty then w.varUint(0)
    else {
      val client = items.head.id.client
      w.varUint(1); w.varUint(items.size.toLong); w.varUint(client); w.varUint(items.head.id.clock)
      items.foreach { it =>
        val info = ref(it.content) | (if it.origin.isDefined then 0x80 else 0) |
          (if it.rightOrigin.isDefined then 0x40 else 0) |
          (if it.parentSub.isDefined then 0x20 else 0)
        w.u8(info)
        it.origin.foreach(w.id)
        it.rightOrigin.foreach(w.id)
        if it.origin.isEmpty && it.rightOrigin.isEmpty then {
          it.parent match {
            case Some(YParent.Root(name)) => w.varUint(1); w.string(name)
            case Some(YParent.Of(pid)) => w.varUint(0); w.id(pid)
            case None => throw IllegalArgumentException("an item without origins needs a parent")
          }
          it.parentSub.foreach(w.string)
        }
        writeContent(w, it.content)
      }
    }
    val ds = deletes.filter(_._2.nonEmpty)
    w.varUint(ds.size.toLong)
    ds.toList.sortBy(_._1).foreach {
      (client, ranges) =>
        w.varUint(client); w.varUint(ranges.size.toLong)
        ranges.sortBy(_._1).foreach((c, l) => { w.varUint(c); w.varUint(l) })
    }
    w.result
  }
}
