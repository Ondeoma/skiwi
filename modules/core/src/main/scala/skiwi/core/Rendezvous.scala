package skiwi.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Rendezvous hashing（HRW）。キーごとに全ノードのスコアを計算し、最大のノードを担当とする。
 * ノードが1台増減しても、担当が変わるのはそのノードに関係するキーだけ。全ノードが同じ生存ノード集合を見ていれば同じ結果になる。
 */
object Rendezvous {

  def score(node: String, key: String): Long = {
    val d = MessageDigest.getInstance("SHA-256").digest(s"$node\u0000$key".getBytes(StandardCharsets.UTF_8))
    d.take(8).foldLeft(0L)((acc, b) => (acc << 8) | (b & 0xff))
  }

  /** 担当ノード。同点はノードIDの辞書順で決める（決定的にするため）。 */
  def pick[N](key: String, nodes: Seq[N])(id: N => String): Option[N] =
    nodes.maxByOption(n => (score(id(n), key) ^ Long.MinValue, id(n))) // 符号ビットを反転して符号なし比較にする
}
