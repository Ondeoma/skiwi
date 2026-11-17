package skiwi.web.content

import skiwi.web.page.{DiffPiece, DiffRow}

import scala.collection.mutable.ArrayBuffer

/** 版の差分（ブロック単位の差分と、変更されたブロック内の文字単位の差分. 旧 editor/diff.ts） */
object Diff {

  private enum Op[T] {
    case Same(a: T, b: T)
    case Add(b: T)
    case Del(a: T)
  }

  /** 最長共通部分列による差分（a → b） */
  private def lcs[T](a: IndexedSeq[T], b: IndexedSeq[T]): Vector[Op[T]] = {
    // 前後の一致を先に取り除く（大きな文書でも表を小さくする）
    var start = 0
    while (start < a.length && start < b.length && a(start) == b(start)) start += 1
    var endA = a.length
    var endB = b.length
    while (endA > start && endB > start && a(endA - 1) == b(endB - 1)) { endA -= 1; endB -= 1 }
    val A = a.slice(start, endA)
    val B = b.slice(start, endB)
    val n = A.length
    val m = B.length
    val out = ArrayBuffer.empty[Op[T]]
    for (i <- 0 until start) out += Op.Same(a(i), b(i))
    if (n.toLong * m > 4000000L) {
      // 大きすぎる場合は全削除＋全追加として扱う
      A.foreach(x => out += Op.Del(x))
      B.foreach(y => out += Op.Add(y))
    } else {
      val t = Array.fill(n + 1)(new Array[Int](m + 1))
      var i = n - 1
      while (i >= 0) {
        var j = m - 1
        while (j >= 0) {
          t(i)(j) = if (A(i) == B(j)) t(i + 1)(j + 1) + 1 else math.max(t(i + 1)(j), t(i)(j + 1))
          j -= 1
        }
        i -= 1
      }
      i = 0
      var j = 0
      while (i < n && j < m) {
        if (A(i) == B(j)) { out += Op.Same(A(i), B(j)); i += 1; j += 1 }
        else if (t(i + 1)(j) >= t(i)(j + 1)) { out += Op.Del(A(i)); i += 1 }
        else { out += Op.Add(B(j)); j += 1 }
      }
      while (i < n) { out += Op.Del(A(i)); i += 1 }
      while (j < m) { out += Op.Add(B(j)); j += 1 }
    }
    for (k <- 0 until a.length - endA) out += Op.Same(a(endA + k), b(endB + k))
    out.toVector
  }

  /** 文字列をコードポイントごとに分ける（JS の [...s] と同じ） */
  private def codePoints(s: String): IndexedSeq[String] = {
    val out = ArrayBuffer.empty[String]
    var i = 0
    while (i < s.length) {
      val n = Character.charCount(s.codePointAt(i))
      out += s.substring(i, i + n)
      i += n
    }
    out.toIndexedSeq
  }

  /** 文字単位の差分（コードポイント単位） */
  private def charDiff(a: String, b: String): List[DiffPiece] = {
    val out = ArrayBuffer.empty[DiffPiece]
    lcs(codePoints(a), codePoints(b)).foreach { o =>
      val (kind, text) = o match {
        case Op.Same(x, _) => ("same", x)
        case Op.Add(y) => ("add", y)
        case Op.Del(x) => ("del", x)
      }
      if (out.nonEmpty && out.last.kind == kind) out(out.length - 1) = out.last.copy(text = out.last.text + text)
      else out += DiffPiece(kind, text)
    }
    out.toList
  }

  /** ブロック列の差分. 変更のない部分は前後 context ブロックを残して折りたたむ */
  def diffBlocks(before: Seq[String], after: Seq[String], context: Int = 2): List[DiffRow] = {
    val ops = lcs(before.toIndexedSeq, after.toIndexedSeq)
    val rows = ArrayBuffer.empty[DiffRow]
    var k = 0
    while (k < ops.length) {
      var merged = false
      // 削除の直後の追加は「変更」として文字単位で比べる（似ている場合のみ）
      (ops(k), ops.lift(k + 1)) match {
        case (Op.Del(a), Some(Op.Add(b))) =>
          val pieces = charDiff(a, b)
          val same = pieces.filter(_.kind == "same").map(_.text.length).sum
          if (same >= math.min(a.length, b.length) * 0.4) {
            rows += DiffRow("change", pieces = Some(pieces))
            k += 1
            merged = true
          }
        case _ =>
      }
      if (!merged) rows += (ops(k) match {
        case Op.Same(a, _) => DiffRow("same", text = Some(a))
        case Op.Add(b) => DiffRow("add", text = Some(b))
        case Op.Del(a) => DiffRow("del", text = Some(a))
      })
      k += 1
    }
    // 折りたたみ
    val near = rows.indices.map(i => rows(i).kind != "same" || rows.slice(math.max(0, i - context), i + context + 1).exists(_.kind != "same"))
    val out = ArrayBuffer.empty[DiffRow]
    var folded = 0
    rows.indices.foreach { i =>
      if (near(i)) {
        if (folded > 0) { out += DiffRow("fold", count = Some(folded)); folded = 0 }
        out += rows(i)
      } else folded += 1
    }
    if (folded > 0) out += DiffRow("fold", count = Some(folded))
    out.toList
  }

  /** 変更のあった行の数 */
  def changeCount(rows: List[DiffRow]): Int = rows.count(r => r.kind != "same" && r.kind != "fold")

}
