package skiwi.ydoc

import io.circe.{Json, JsonObject}
import io.circe.parser.parse

import scala.collection.mutable

/** いくつかの update から、文書の状態を組み立てる。
  * Yjs の StructStore と Item.integrate（YATA）と同じ規則で項目の並びを再現し、本文を ProseMirror の JSON として読める。 */
final class YState private ():

  /** 構造体のかけら（Yjs と同じく、必要に応じて分割する）。 */
  final class Piece(
      var id: YId,
      var length: Int,
      var origin: Option[YId],
      val rightOrigin: Option[YId],
      val parentRef: Option[YParent],
      val parentSub: Option[String],
      var content: YContent,
      val gc: Boolean
  ):
    var left: Piece       = null
    var right: Piece      = null
    var integrated        = false
    var deleted: Boolean  = gc || content.isInstanceOf[YContent.Deleted]
    def lastId: YId       = id + (length - 1)
    def contains(i: YId)  = i.client == id.client && i.clock >= id.clock && i.clock < id.clock + length
    override def toString = s"Piece($id,$length,${content.toString.take(30)},deleted=$deleted)"

  // クライアントごとに、時計の順に並べたかけら
  private val clients = mutable.Map.empty[Long, mutable.ArrayBuffer[Piece]]

  /** 文書に出てくるクライアント（新しく書くときに重ならない ID を選ぶため）。 */
  def clientIds: Set[Long] = clients.keySet.toSet

  /** ps の中で、clock より後ろで終わる最初のかけらの位置（二分探索）。 */
  private def firstEndingAfter(ps: mutable.ArrayBuffer[Piece], clock: Long): Int =
    var lo = 0; var hi = ps.size
    while lo < hi do
      val mid = (lo + hi) >>> 1
      if ps(mid).id.clock + ps(mid).length <= clock then lo = mid + 1 else hi = mid
    lo

  /** id を含むかけらの位置。 */
  private def indexOf(i: YId): Option[Int] =
    clients.get(i.client).flatMap { ps =>
      val k = firstEndingAfter(ps, i.clock)
      Option.when(k < ps.size && ps(k).contains(i))(k)
    }

  def find(i: YId): Option[Piece] = indexOf(i).map(clients(i.client)(_))

  /** かけら p を off の位置で分け、右側を返す（Yjs の splitItem）。並び・親ごとの索引もつなぎ直す。 */
  private def split(p: Piece, off: Int): Piece =
    val (a, b) = if p.gc then (p.content, p.content) else p.content.splitAt(off)
    val r = Piece(p.id + off, p.length - off, Some(p.id + (off - 1)), p.rightOrigin, p.parentRef, p.parentSub, b, p.gc)
    r.deleted = p.deleted
    p.content = a
    p.length = off
    if p.integrated then
      r.integrated = true
      r.left = p; r.right = p.right
      if p.right != null then p.right.left = r
      p.right = r
    clients(p.id.client).insert(indexOf(p.id).get + 1, r)
    // 右側は左側と同じ親
    parentMemo.get(p.id).foreach { par =>
      parentMemo(r.id) = par
      groups.foreach(g => keyOf(par).foreach(k => g.getOrElseUpdate(k, mutable.ArrayBuffer.empty) += r))
    }
    r

  /** id で始まるかけら（途中なら分ける）。 */
  private def cleanStart(i: YId): Option[Piece] = find(i).map(p => if p.id.clock == i.clock then p else split(p, (i.clock - p.id.clock).toInt))
  /** id で終わるかけら（途中なら分ける）。 */
  private def cleanEnd(i: YId): Option[Piece] =
    find(i).map { p =>
      val off = (i.clock - p.id.clock).toInt
      if off == p.length - 1 then p else { split(p, off + 1); p }
    }

  /** 構造体を加える。すでにある範囲（別の update で受け取ったもの）は加えない。 */
  private def add(s: YStruct): Unit =
    val (start, end) = (s.id.clock, s.id.clock + s.length)
    val ps = clients.getOrElseUpdate(s.id.client, mutable.ArrayBuffer.empty)
    // まだない範囲（重なるかけらの間の隙間）を求める
    var k    = firstEndingAfter(ps, start)
    var cur  = start
    val gaps = mutable.ArrayBuffer.empty[(Long, Long)]
    while k < ps.size && ps(k).id.clock < end do
      if ps(k).id.clock > cur then gaps += ((cur, ps(k).id.clock))
      cur = math.max(cur, ps(k).id.clock + ps(k).length)
      k += 1
    if cur < end then gaps += ((cur, end))
    gaps.foreach { (a, b) =>
      val off = (a - start).toInt
      val len = (b - a).toInt
      val piece = s match
        case YStruct.GC(id, _)  => Some(Piece(id + off, len, None, None, None, None, YContent.Deleted(len), gc = true))
        case YStruct.Skip(_, _) => None
        case YStruct.Item(id, origin, rightOrigin, parent, parentSub, content) =>
          var c = content
          if off > 0 then c = c.splitAt(off)._2
          if len < c.length then c = c.splitAt(len)._1
          // 途中から切り出したかけらは、左隣の文字を origin にする（Yjs の分割と同じ）
          Some(Piece(id + off, len, if off == 0 then origin else Some(id + (off - 1)), rightOrigin, parent, parentSub, c, gc = false))
      piece.foreach(p => ps.insert(firstEndingAfter(ps, a), p))
    }

  /** 削除の集合を当てる（範囲の端でかけらを分ける）。 */
  private def applyDeletes(ds: DeleteSet): Unit =
    ds.foreach { (client, ranges) =>
      clients.get(client).foreach { ps =>
        ranges.foreach { (clock, len) =>
          val end = clock + len
          var k   = firstEndingAfter(ps, clock)
          while k < ps.size && ps(k).id.clock < end do
            val p0 = ps(k)
            val p  = if p0.id.clock < clock then split(p0, (clock - p0.id.clock).toInt) else p0
            if p.id.clock + p.length > end then split(p, (end - p.id.clock).toInt)
            p.deleted = true
            k = indexOf(p.id).get + 1
        }
      }
    }

  // ---------------------------------------------------------------- 親の解決

  private enum Parent:
    case Known(p: YParent, sub: Option[String])
    case Gone // ガベージコレクト済み（またはつながらない）

  private def keyOf(p: Parent): Option[(YParent, Option[String])] = p match
    case Parent.Known(pp, sub) => Some((pp, sub))
    case Parent.Gone           => None

  private val parentMemo = mutable.Map.empty[YId, Parent]

  /** Yjs の Item.getMissing と同じ規則で、かけらの親を決める。 */
  private def parentOf(p: Piece): Parent =
    if p.gc then Parent.Gone
    else
      parentMemo.get(p.id) match
        case Some(x) => x
        case None =>
          val left  = p.origin.flatMap(find)
          val right = p.rightOrigin.flatMap(find)
          val r =
            if left.exists(_.gc) || right.exists(_.gc) then Parent.Gone
            else
              p.parentRef match
                case Some(YParent.Of(pid)) =>
                  if find(pid).exists(!_.gc) then Parent.Known(YParent.Of(pid), p.parentSub) else Parent.Gone
                case Some(root) => Parent.Known(root, p.parentSub)
                case None =>
                  left.orElse(right) match
                    case Some(n) => parentOf(n)
                    case None    => Parent.Gone
          parentMemo(p.id) = r
          r

  /** 親（と属性名）ごとの子の索引。最初に使うときに一度だけ作り、分割したかけらは split で足す。 */
  private var groups: Option[mutable.Map[(YParent, Option[String]), mutable.ArrayBuffer[Piece]]] = None
  private def group(key: (YParent, Option[String])): mutable.ArrayBuffer[Piece] =
    val g = groups.getOrElse {
      val m = mutable.Map.empty[(YParent, Option[String]), mutable.ArrayBuffer[Piece]]
      clients.values.flatten.toVector.foreach(p => keyOf(parentOf(p)).foreach(k => m.getOrElseUpdate(k, mutable.ArrayBuffer.empty) += p))
      groups = Some(m)
      m
    }
    g.getOrElse(key, mutable.ArrayBuffer.empty)

  // ---------------------------------------------------------------- 並び（YATA）

  private val ordered = mutable.Map.empty[(YParent, Option[String]), Vector[Piece]]

  /** 親 parent の子の並び（sub は属性名。属性なら最後のものが今の値）。削除済みのものも含む。 */
  def children(parent: YParent, sub: Option[String] = None): Vector[Piece] =
    val key = (parent, sub)
    ordered.getOrElseUpdate(key, {
      var start: Piece = null

      def integrate(p: Piece): Unit =
        var left: Piece  = p.origin.flatMap(cleanEnd).orNull
        val right: Piece = p.rightOrigin.flatMap(cleanStart).orNull
        if (left == null && (right == null || right.left != null)) || (left != null && left.right != right) then
          var o: Piece = if left != null then left.right else start
          val conflicting  = mutable.Set.empty[Piece]
          val beforeOrigin = mutable.Set.empty[Piece]
          var continue     = true
          while continue && o != null && o != right do
            beforeOrigin += o; conflicting += o
            if p.origin == o.origin then
              if o.id.client < p.id.client then { left = o; conflicting.clear() }
              else if p.rightOrigin == o.rightOrigin then continue = false
            else
              val oo = o.origin.flatMap(find)
              if oo.isDefined && beforeOrigin.contains(oo.get) then
                if !conflicting.contains(oo.get) then { left = o; conflicting.clear() }
              else continue = false
            if continue then o = o.right
        p.left = left
        if left != null then { p.right = left.right; left.right = p }
        else { p.right = start; start = p }
        if p.right != null then p.right.left = p
        p.integrated = true

      // origin・rightOrigin のかけらが並びに入ったものから順に入れる（YATA は依存の順を守れば入れる順によらず同じ並びになる）
      def ready(i: Option[YId]) = i.forall(x => find(x).exists(_.integrated))
      var pending  = group(key).filterNot(_.integrated).sortBy(p => (p.id.client, p.id.clock))
      var progress = true
      while pending.nonEmpty && progress do
        progress = false
        pending.foreach { p =>
          if !p.integrated && ready(p.origin) && ready(p.rightOrigin) then
            integrate(p)
            progress = true
        }
        // 分割で増えたかけらも含め、まだ入っていないものを集め直す（同じ親の子だけ）
        pending = group(key).filterNot(_.integrated).sortBy(p => (p.id.client, p.id.clock))
      if pending.nonEmpty then throw IllegalStateException(s"could not order ${pending.size} item(s): missing dependencies")
      val out = Vector.newBuilder[Piece]
      var o   = start
      while o != null do { out += o; o = o.right }
      out.result()
    })

  // ---------------------------------------------------------------- 本文の JSON（y-prosemirror と同じ読み方）

  /** 要素ごとの属性名（親ごとの索引から一度だけ作る）。 */
  private lazy val attrNames: Map[YId, Vector[String]] =
    group((YParent.Root(""), None)) // 索引を作る
    groups.get.keys.toVector.collect { case (YParent.Of(pid), Some(k)) => pid -> k }.groupMap(_._1)(_._2).view.mapValues(_.sorted).toMap

  /** 要素の今の属性（属性名ごとの並びの最後が今の値。削除されていれば値なし）。 */
  private def attributes(el: Piece): JsonObject =
    JsonObject.fromIterable(attrNames.getOrElse(el.id, Vector.empty).flatMap { k =>
      children(YParent.Of(el.id), Some(k)).lastOption.filterNot(_.deleted).flatMap(_.content match
        case YContent.AnyC(vs) => vs.lastOption
        case _                 => None
      ).filterNot(_.isNull).map(k -> _)
    })

  /** XmlText の中身を、マーク付きの文字のノードにする（書式の項目で今の属性を切り替える）。 */
  private def textNodes(t: Piece): Vector[Json] =
    val out   = Vector.newBuilder[Json]
    val attrs = mutable.LinkedHashMap.empty[String, Json]
    val buf   = StringBuilder()
    def flush(): Unit =
      if buf.nonEmpty then
        val marks = attrs.toVector.map((k, v) => if v.asObject.exists(_.nonEmpty) then Json.obj("type" -> Json.fromString(k), "attrs" -> v) else Json.obj("type" -> Json.fromString(k)))
        out += (if marks.isEmpty then Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(buf.toString))
                else Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(buf.toString), "marks" -> Json.fromValues(marks)))
        buf.clear()
    children(YParent.Of(t.id)).filterNot(_.deleted).foreach(_.content match
      case YContent.Str(s) => buf ++= s
      case YContent.Format(k, json) =>
        flush()
        parse(json).toOption.filterNot(_.isNull) match
          case Some(v) => attrs(k) = v
          case None    => attrs.remove(k)
      case _ => ()
    )
    flush()
    out.result()

  private def element(el: Piece): Option[Json] = el.content match
    case YContent.Type(YContent.YXmlElement, Some(name)) =>
      val content = children(YParent.Of(el.id)).filterNot(_.deleted).flatMap { c =>
        c.content match
          case YContent.Type(YContent.YXmlText, _) => textNodes(c)
          case _                                   => element(c).toVector
      }
      val attrs = attributes(el)
      Some(Json.fromFields(Vector("type" -> Json.fromString(name)) ++ Option.when(attrs.nonEmpty)("attrs" -> Json.fromJsonObject(attrs)) ++
        Option.when(content.nonEmpty)("content" -> Json.fromValues(content))))
    case _ => None

  /** ルートの XmlFragment の、削除されていない最上位の要素とその JSON。 */
  def blocks(root: String): Vector[(Piece, Json)] =
    children(YParent.Root(root)).filterNot(_.deleted).flatMap(p => element(p).map(p -> _))

object YState:
  /** いくつかの update（スナップショットと更新ログ）から状態を作る。 */
  def apply(updates: Seq[Array[Byte]]): YState =
    val st      = new YState()
    val decoded = updates.map(YCodec.decode)
    decoded.foreach(_.structs.foreach(st.add))
    decoded.foreach(u => st.applyDeletes(u.deletes))
    st
