package skiwi.db

/** 検索用の文字列正規化とあいまい一致。正規化そのものは PGroonga（pgroonga_normalize）で行い、索引と同じ規則に揃える。 */
object TextNorm:

  /** V7__fuzzy_search.sql の索引と同じ指定。 */
  val Normalizer: String =
    """NormalizerNFKC150("unify_kana", true, "unify_kana_case", true, "unify_hyphen_and_prolonged_sound_mark", true, "unify_middle_dot", true)"""

  /** あいまい一致とみなす、検索語の2文字組のうち対象に含まれる割合の下限。 */
  val FuzzyThreshold: Double = 0.5

  /** 入力を空白（全角含む）で区切った検索語。最大10語。 */
  def words(input: String): List[String] =
    input.trim.split("[\\s　]+").toList.filter(_.nonEmpty).take(10)

  /** 文字（コードポイント）単位の2文字組。2文字未満の語はその語そのもの。 */
  def bigrams(w: String): List[String] =
    val cps = w.codePoints().toArray.toList.map(cp => new String(Character.toChars(cp)))
    if cps.size < 2 then List(w) else cps.sliding(2).map(_.mkString).toList.distinct

  /** 正規化済みの語 w が、正規化済みの対象 target にどれだけ含まれるか（0〜1）。全体が含まれれば1。 */
  def ratio(w: String, target: String): Double =
    if target.contains(w) then 1.0
    else
      val bs = bigrams(w)
      if bs.size < 2 then 0.0 else bs.count(target.contains).toDouble / bs.size

  /** Groonga クエリ構文のフレーズ（"..."）としてエスケープ。 */
  def phrase(w: String): String = "\"" + w.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
