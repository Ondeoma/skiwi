package skiwi.api

import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import skiwi.core.*

import java.time.LocalDate
import java.util.UUID

/** データベースの定義（列・ビュー）と項目の値の検証。 */
object DatabaseSchema {
  val Types = Set(
    "text",
    "number",
    "select",
    "multi_select",
    "date",
    "person",
    "checkbox",
    "url",
    "relation",
    "rollup",
    "formula",
    "created_time",
    "edited_time",
    "created_by",
    "edited_by",
    "unique_id"
  )

  /** 値を持たない（計算・自動で決まる）列 */
  val Computed = Set("rollup", "formula", "created_time", "edited_time", "created_by", "edited_by", "unique_id")
  val RollupFns = Set("count", "count_values", "sum", "avg", "min", "max", "checked")
  val ViewTypes = Set("table", "board", "calendar", "list", "gallery", "timeline")
  private val Id = "^[A-Za-z0-9_-]{1,24}$".r

  /** target: 関連先のデータベース（relation） */
  final case class Prop(id: String, name: String, tpe: String, options: Set[String], target: Option[UUID] = None)

  private def str(o: JsonObject, k: String) = o(k).flatMap(_.asString)

  /** 列の定義を確かめる。Right: 列の一覧 */
  def props(j: Json): Either[String, List[Prop]] =
    j.asArray.toRight("props は配列にしてください").flatMap { arr =>
      if arr.size > 50 then Left("列が多すぎます（50列まで）")
      else
        arr.toList.traverse { p =>
          p.asObject.toRight("列の定義が正しくありません").flatMap { o =>
            (str(o, "id"), str(o, "name"), str(o, "type")) match {
              case (Some(id), Some(name), Some(t))
                  if Id.matches(id) && name.trim.nonEmpty && name.length <= 100 && Types(t) =>
                val opts = o("options").flatMap(_.asArray).getOrElse(Vector.empty)
                if opts.size > 100 then Left("選択肢が多すぎます（100個まで）")
                else {
                  val target = str(o, "target").flatMap(x => Either.catchNonFatal(UUID.fromString(x)).toOption)
                  if t == "relation" && target.isEmpty then Left(s"$name: 関連の相手のデータベースを指定してください")
                  else if t == "rollup" && !(str(o, "relation").exists(Id.matches) && str(o, "fn").exists(RollupFns))
                  then Left(s"$name: 集計の指定が正しくありません")
                  else if t == "formula" && !str(o, "expr").exists(_.length <= 2000) then Left(s"$name: 数式を入力してください")
                  else
                    opts.toList.traverse(x =>
                      x.asObject.flatMap(oo => (str(oo, "id"), str(oo, "name")).tupled)
                        .filter((i, n) => Id.matches(i) && n.trim.nonEmpty && n.length <= 100).map(
                          _._1
                        ).toRight("選択肢が正しくありません")
                    )
                      .map(ids => Prop(id, name, t, ids.toSet, target))
                }
              case _ => Left("列の定義が正しくありません")
            }
          }
        }.flatMap(ps => if ps.map(_.id).distinct.size != ps.size then Left("列の ID が重複しています") else Right(ps))
    }

  def views(j: Json): Either[String, Unit] =
    j.asArray.toRight("views は配列にしてください").flatMap { arr =>
      if arr.size > 20 then Left("ビューが多すぎます（20個まで）")
      else if j.noSpaces.length > 100000 then Left("ビューの設定が大きすぎます")
      else
        arr.toList.traverse_ { v =>
          v.asObject.flatMap(o => (str(o, "id"), str(o, "name"), str(o, "type")).tupled)
            .filter((id, n, t) => Id.matches(id) && n.length <= 100 && ViewTypes(t)).toRight("ビューが正しくありません").void
        }
    }

  /** 値を確かめて正規化する（null は「消す」）。 */
  def value(p: Prop, v: Json): Either[String, Json] =
    if v.isNull then Right(Json.Null)
    else
      p.tpe match {
        case "text" => v.asString.filter(_.length <= 5000).map(Json.fromString).toRight(s"${p.name}: 文字列を指定してください")
        case "url" => v.asString.map(_.trim).filter(s =>
            s.isEmpty || (s.length <= 2000 && s.matches("(?i)^(https?://|mailto:).+"))
          ).map(s => if s.isEmpty then Json.Null else Json.fromString(s)).toRight(s"${p.name}: URL が正しくありません")
        case "number" => v.asNumber.flatMap(_.toBigDecimal).map(Json.fromBigDecimal).toRight(s"${p.name}: 数値を指定してください")
        case "checkbox" => v.asBoolean.map(Json.fromBoolean).toRight(s"${p.name}: true か false を指定してください")
        case "date" => v.asString.filter(s => Either.catchNonFatal(LocalDate.parse(s)).isRight).map(
            Json.fromString
          ).toRight(s"${p.name}: date (YYYY-MM-DD) expected")
        case "select" => v.asString.filter(p.options).map(Json.fromString).toRight(s"${p.name}: 選択肢にない値です")
        case "multi_select" =>
          v.asArray.map(_.flatMap(_.asString).distinct).filter(xs => xs.forall(p.options) && xs.size <= 100).map(xs =>
            Json.fromValues(xs.map(Json.fromString))
          ).toRight(s"${p.name}: 選択肢にない値です")
        case "relation" =>
          v.asArray.map(_.flatMap(_.asString).distinct).filter(xs => xs.size <= 100 && xs.forall(uuidOpt(_).isDefined))
            .map(xs => Json.fromValues(xs.map(Json.fromString))).toRight(s"${p.name}: ページの ID の配列を指定してください")
        case t if Computed(t) => Left(s"${p.name}: 計算で決まる列は変えられません")
        case "person" =>
          v.asArray.map(_.flatMap(_.asString).distinct).filter(xs => xs.size <= 20 && xs.forall(uuidOpt(_).isDefined))
            .map(xs => Json.fromValues(xs.map(Json.fromString))).toRight(s"${p.name}: ユーザーの ID の配列を指定してください")
        case _ => Left("不明な種類です")
      }

  def patch(ps: List[Prop], values: JsonObject): Either[String, JsonObject] =
    values.toList.traverse {
      (k, v) =>
        ps.find(_.id == k).toRight(s"不明な列です: $k").flatMap(p => value(p, v)).map(k -> _)
    }.map(JsonObject.fromIterable)
}
