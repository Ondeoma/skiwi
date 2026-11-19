package skiwi.ydoc

import io.circe.{Json, JsonObject}

import scala.collection.mutable
import scala.util.Random

/**
 * 本文（ProseMirror の JSON）のブロックを、y-prosemirror と同じ形の Yjs の項目にして、update を作る。
 *  - ブロック・インラインのノード: XmlElement（ノード名）。属性（null でないもの）は要素の属性
 *  - 続いた文字のノード: 1つの XmlText。マークは書式（キー = マーク名、値 = マークの属性）として前後に置く
 * 新しい項目は、文書にまだないクライアント ID で書く。
 */
object YEdit {

  val Root = "default"

  /** 最上位のブロックを入れる位置: left の直後、right の直前（どちらも並びの中のかけら）。 */
  final case class At(origin: Option[YId], rightOrigin: Option[YId])

  private final class Builder(client: Long) {
    val items = Vector.newBuilder[YStruct.Item]
    private var clock = 0L
    private def put(origin: Option[YId],
                    right: Option[YId],
                    parent: Option[YParent],
                    sub: Option[String],
                    c: YContent): YId = {
      val id = YId(client, clock)
      items += YStruct.Item(id, origin, right, parent, sub, c)
      clock += c.length
      id
    }

    private def isText(n: JsonObject) = n("type").flatMap(_.asString).contains("text")
    private def kids(n: JsonObject): Vector[JsonObject] =
      n("content").flatMap(_.asArray).toVector.flatten.flatMap(_.asObject)

    /** ノードを要素として書き、その ID を返す。 */
    def element(n: JsonObject, parent: YParent, origin: Option[YId], right: Option[YId]): YId = {
      val name = n("type").flatMap(_.asString).getOrElse("paragraph")
      val id = put(origin, right, Some(parent), None, YContent.Type(YContent.YXmlElement, Some(name)))
      n("attrs").flatMap(_.asObject).foreach(_.toIterable.foreach {
        (k, v) =>
          if !v.isNull then put(None, None, Some(YParent.Of(id)), Some(k), YContent.AnyC(Vector(v)))
      })
      // 子: 続いた文字のノードは1つの XmlText に、それ以外は要素に
      var prev: Option[YId] = None
      val runs = mutable.ArrayBuffer.empty[Either[Vector[JsonObject], JsonObject]]
      kids(n).foreach { k =>
        if isText(k) then
          runs.lastOption match {
            case Some(Left(ts)) => runs(runs.size - 1) = Left(ts :+ k)
            case _ => runs += Left(Vector(k))
          }
        else runs += Right(k)
      }
      runs.foreach {
        case Left(texts) =>
          val tid = put(prev, None, Some(YParent.Of(id)), None, YContent.Type(YContent.YXmlText, None))
          text(tid, texts)
          prev = Some(tid)
        case Right(k) => prev = Some(element(k, YParent.Of(id), prev, None))
      }
      id
    }

    /** XmlText の中身（Yjs の insertText と同じく、書式を前に置き、後ろで元に戻す）。 */
    private def text(tid: YId, nodes: Vector[JsonObject]): Unit = {
      var last: Option[YId] = None
      def add(c: YContent): Unit = {
        val id = put(last, None, Some(YParent.Of(tid)), None, c)
        last = Some(id + (c.length - 1))
      }
      nodes.foreach { t =>
        val s = t("text").flatMap(_.asString).getOrElse("")
        if s.nonEmpty then {
          val marks = t("marks").flatMap(_.asArray).toVector.flatten.flatMap(_.asObject).map { m =>
            m("type").flatMap(_.asString).getOrElse("") -> m("attrs").filter(_.isObject).getOrElse(Json.obj())
          }.filter(_._1.nonEmpty).distinctBy(_._1)
          marks.foreach((k, v) => add(YContent.Format(k, v.noSpaces)))
          add(YContent.Str(s))
          marks.foreach((k, _) => add(YContent.Format(k, "null")))
        }
      }
    }
  }

  private def newClient(used: Set[Long]): Long =
    Iterator.continually((Random.nextInt() & 0x7fffffff).toLong).find(c => c > 0 && !used.contains(c)).get

  /** ブロックを at に順に入れる項目を作る。 */
  private def blocksAt(b: Builder, blocks: Vector[JsonObject], at: At): Unit = {
    var origin = at.origin
    blocks.foreach { blk =>
      val id = b.element(blk, YParent.Root(Root), origin, at.rightOrigin)
      origin = Some(id)
    }
  }

  /** 新しい文書（空のページ）の update。 */
  def newDocument(blocks: Vector[JsonObject]): Array[Byte] = {
    val b = Builder(newClient(Set.empty))
    blocksAt(b, blocks, At(None, None))
    YCodec.encode(b.items.result(), Map.empty)
  }

  /** 今の文書。all: 最上位の並び（削除済みも含む）。blocks: 削除されていないブロックとその JSON（y-prosemirror と同じ読み方）。 */
  final case class Doc(state: YState, all: Vector[YState#Piece], blocks: Vector[(YState#Piece, Json)]) {
    def json: Vector[Json] = blocks.map(_._2)
  }

  def load(updates: Seq[Array[Byte]]): Doc = {
    val st = YState(updates)
    Doc(st, st.children(YParent.Root(Root)), st.blocks(Root))
  }

  /**
   * 最上位のブロックの from 番目から until 番目の手前まで（削除済みを除いた番号）を消し、そこへ blocks を入れる update。
   * from == until なら消さずに入れるだけ。
   */
  def replace(doc: Doc, from: Int, until: Int, blocks: Vector[JsonObject]): Array[Byte] = {
    val vis = doc.blocks.map(_._1)
    require(0 <= from && from <= until && until <= vis.size, s"bad range $from..$until of ${vis.size}")
    // 入れる位置: from 番目のブロックの直前にあるかけら（削除済みでもよい）の後ろ。from が末尾なら並びの最後の後ろ
    val anchor: Option[YState#Piece] = if from < vis.size then Option(vis(from).left) else doc.all.lastOption
    val right: Option[YState#Piece] = anchor.fold(doc.all.headOption)(a => Option(a.right))
    val b = Builder(newClient(doc.state.clientIds))
    blocksAt(b, blocks, At(anchor.map(_.lastId), right.map(_.id)))
    val deletes =
      vis.slice(from, until).groupBy(_.id.client).map((c, ps) => c -> ps.map(p => (p.id.clock, p.length.toLong)))
    YCodec.encode(b.items.result(), deletes)
  }
}
