package skiwi.web.database

import io.circe.*
import io.circe.syntax.*
import skiwi.web.Api
import skiwi.web.model.Page

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// データベース・フォームの API の型

/** 選択肢 */
final case class DbOption(id: String, name: String, color: String)

object DbOption {

  given Encoder[DbOption] = Encoder.forProduct3("id", "name", "color")(o => (o.id, o.name, o.color))

  given Decoder[DbOption] = Decoder.instance { c =>
    for {
      id <- c.get[String]("id")
      name <- c.get[String]("name")
      color <- c.getOrElse[String]("color")("gray")
    } yield DbOption(id, name, color)
  }

}

/**
 * 列（項目の種類）. type: text・number・select・multi_select・date・person・checkbox・url・relation・rollup・formula・
 * created_time・edited_time・created_by・edited_by・unique_id.
 * relation: target（関連先のデータベース）/ rollup: relation（関連の列）・fn（集計）・prop（関連先の列）/
 * formula: expr（式）/ unique_id: prefix（例: TASK → TASK-12）
 */
final case class DbProp(id: String,
                        name: String,
                        `type`: String,
                        options: Option[List[DbOption]] = None,
                        target: Option[String] = None,
                        relation: Option[String] = None,
                        fn: Option[String] = None,
                        prop: Option[String] = None,
                        expr: Option[String] = None,
                        prefix: Option[String] = None) derives Codec.AsObject {

  def opts: List[DbOption] = options.getOrElse(Nil)

  def tpe: String = `type`

}

/** 絞り込みの条件. op: contains・is・isNot・empty・notEmpty・gt・lt */
final case class DbFilter(prop: String, op: String, value: Option[String] = None) derives Codec.AsObject

/** 並べ替え. dir: asc・desc */
final case class DbSort(prop: String, dir: String) derives Codec.AsObject

/**
 * ビュー. type: table・board・calendar・list・gallery・timeline.
 * groupBy: ボードで列に分ける項目（単一選択）/ dateProp: カレンダーの日付の項目 / hidden: 表示しない列 /
 * group: 表・リストでグループ化する列 / subItems: 表でサブ項目を親の下に入れる（既定は true）/ calcs: 表の列の下の集計 /
 * startProp・endProp: タイムラインの開始・終了 / cover: ギャラリーの表紙（content・none・URL の列のID）
 */
final case class DbView(id: String,
                        name: String,
                        `type`: String,
                        groupBy: Option[String] = None,
                        dateProp: Option[String] = None,
                        sort: Option[DbSort] = None,
                        filters: Option[List[DbFilter]] = None,
                        hidden: Option[List[String]] = None,
                        group: Option[String] = None,
                        subItems: Option[Boolean] = None,
                        calcs: Option[Map[String, String]] = None,
                        startProp: Option[String] = None,
                        endProp: Option[String] = None,
                        cover: Option[String] = None) derives Codec.AsObject {

  def tpe: String = `type`

  def filterList: List[DbFilter] = filters.getOrElse(Nil)

  def hiddenList: List[String] = hidden.getOrElse(Nil)

  def calcMap: Map[String, String] = calcs.getOrElse(Map.empty)

}

object DbView {

  /** ビューの JSON（ページの中のデータベースの views）を読む */
  def parseList(s: String): List[DbView] = parser.decode[List[DbView]](s).getOrElse(Nil)

  def toJson(vs: List[DbView]): String = vs.asJson.deepDropNullValues.noSpaces

}

/** 項目の情報（自動の列・ギャラリー用） */
final case class RowMeta(createdAt: String,
                         createdBy: Option[String] = None,
                         editedAt: String,
                         editedBy: Option[String] = None,
                         cover: Option[String] = None,
                         excerpt: Option[String] = None) derives Codec.AsObject

private def valuesOf(c: HCursor, key: String = "values"): Decoder.Result[Map[String, Json]] =
  c.getOrElse[Map[String, Json]](key)(Map.empty)

/** 項目（子ページ）と値 */
final case class DbRow(page: Page, values: Map[String, Json], meta: Option[RowMeta] = None)

object DbRow {

  given Decoder[DbRow] = Decoder.instance { c =>
    for {
      page <- c.get[Page]("page")
      values <- valuesOf(c)
      meta <- c.get[Option[RowMeta]]("meta")
    } yield DbRow(page, values, meta)
  }

}

/** 関連先のデータベースの項目 */
final case class RelatedRow(id: String, title: String, icon: Option[String], values: Map[String, Json])

object RelatedRow {

  given Decoder[RelatedRow] = Decoder.instance { c =>
    for {
      id <- c.get[String]("id")
      title <- c.getOrElse[String]("title")("")
      icon <- c.get[Option[String]]("icon")
      values <- valuesOf(c)
    } yield RelatedRow(id, title, icon, values)
  }

}

/** 関連先のデータベース（閲覧できる項目だけ） */
final case class RelatedDb(pageId: String, title: String, props: List[DbProp], rows: List[RelatedRow])

object RelatedDb {

  given Decoder[RelatedDb] = Decoder.instance { c =>
    for {
      pageId <- c.get[String]("pageId")
      title <- c.getOrElse[String]("title")("")
      props <- c.getOrElse[List[DbProp]]("props")(Nil)
      rows <- c.getOrElse[List[RelatedRow]]("rows")(Nil)
    } yield RelatedDb(pageId, title, props, rows)
  }

}

/** データベース: props 列、views ビュー、rows 項目、users 値に出てくるユーザーの名前、targets「関連」の先のデータベース */
final case class Database(pageId: String,
                          props: List[DbProp],
                          views: List[DbView],
                          rows: List[DbRow],
                          users: Map[String, String],
                          canEdit: Boolean,
                          targets: Map[String, RelatedDb])

object Database {

  given Decoder[Database] = Decoder.instance { c =>
    for {
      pageId <- c.get[String]("pageId")
      props <- c.getOrElse[List[DbProp]]("props")(Nil)
      views <- c.getOrElse[List[DbView]]("views")(Nil)
      rows <- c.getOrElse[List[DbRow]]("rows")(Nil)
      users <- c.getOrElse[Map[String, String]]("users")(Map.empty)
      canEdit <- c.getOrElse[Boolean]("canEdit")(false)
      targets <- c.getOrElse[Map[String, RelatedDb]]("targets")(Map.empty)
    } yield Database(pageId, props, views, rows, users, canEdit, targets)
  }

}

/** 項目のページの親のデータベース */
final case class DbRef(pageId: String, title: String, props: List[DbProp])

object DbRef {

  given Decoder[DbRef] = Decoder.instance { c =>
    for {
      pageId <- c.get[String]("pageId")
      title <- c.getOrElse[String]("title")("")
      props <- c.getOrElse[List[DbProp]]("props")(Nil)
    } yield DbRef(pageId, title, props)
  }

}

/** 項目の値. database: 親がデータベースならその列の定義 */
final case class PageProps(database: Option[DbRef],
                           values: Map[String, Json],
                           users: Map[String, String],
                           canEdit: Boolean,
                           targets: Map[String, RelatedDb])

object PageProps {

  given Decoder[PageProps] = Decoder.instance { c =>
    for {
      database <- c.get[Option[DbRef]]("database")
      values <- valuesOf(c)
      users <- c.getOrElse[Map[String, String]]("users")(Map.empty)
      canEdit <- c.getOrElse[Boolean]("canEdit")(false)
      targets <- c.getOrElse[Map[String, RelatedDb]]("targets")(Map.empty)
    } yield PageProps(database, values, users, canEdit, targets)
  }

}

// ---------------------------------------------------------------- フォーム

/** フォームの質問. prop: 列のID（title は項目の名前） */
final case class FormField(prop: String,
                           label: Option[String] = None,
                           required: Boolean = false,
                           help: Option[String] = None) derives Codec.AsObject

final case class DbFormDef(id: String,
                           title: String,
                           description: Option[String],
                           fields: List[FormField],
                           requireLogin: Boolean,
                           respondentProp: Option[String],
                           enabled: Boolean,
                           url: String,
                           createdBy: Option[String] = None,
                           createdAt: String,
                           lastAt: Option[String] = None,
                           useCount: Int = 0) derives Codec.AsObject

final case class SaveForm(title: String,
                          description: Option[String],
                          fields: List[FormField],
                          requireLogin: Boolean,
                          respondentProp: Option[String],
                          enabled: Boolean) derives Codec.AsObject

final case class PublicOption(id: String, name: String) derives Codec.AsObject

final case class PublicField(prop: String,
                             label: String,
                             `type`: String,
                             required: Boolean = false,
                             help: Option[String] = None,
                             options: List[PublicOption] = Nil)

object PublicField {

  given Decoder[PublicField] = Decoder.instance { c =>
    for {
      prop <- c.get[String]("prop")
      label <- c.getOrElse[String]("label")("")
      tpe <- c.get[String]("type")
      required <- c.getOrElse[Boolean]("required")(false)
      help <- c.get[Option[String]]("help")
      options <- c.getOrElse[List[PublicOption]]("options")(Nil)
    } yield PublicField(prop, label, tpe, required, help, options)
  }

}

final case class PublicForm(title: String,
                            description: Option[String],
                            requireLogin: Boolean,
                            loggedIn: Boolean,
                            userName: Option[String],
                            fields: List[PublicField])

object PublicForm {

  given Decoder[PublicForm] = Decoder.instance { c =>
    for {
      title <- c.get[String]("title")
      description <- c.get[Option[String]]("description")
      requireLogin <- c.getOrElse[Boolean]("requireLogin")(false)
      loggedIn <- c.getOrElse[Boolean]("loggedIn")(false)
      userName <- c.get[Option[String]]("userName")
      fields <- c.getOrElse[List[PublicField]]("fields")(Nil)
    } yield PublicForm(title, description, requireLogin, loggedIn, userName, fields)
  }

}

/** データベースの API */
object DbApi {

  private def valuesJson(values: Map[String, Json]): Json = Json.fromFields(values)

  def get(id: String): Future[Database] = Api.get[Database](s"/api/pages/${Api.seg(id)}/database")

  /** 列・ビューを保存する（null の項目は送らない） */
  def save(id: String, props: List[DbProp], views: List[DbView]): Future[Database] =
    Api.put[Database](
      s"/api/pages/${Api.seg(id)}/database",
      Json.obj("props" -> props.asJson, "views" -> views.asJson).deepDropNullValues
    )

  def remove(id: String): Future[Unit] = Api.del(s"/api/pages/${Api.seg(id)}/database")

  /** ページをデータベースにする（既定の列: 状態・担当者・期限、ビュー: 表・ボード・カレンダー） */
  def createDefault(id: String): Future[Unit] = {
    val (props, views) = Db.defaultDatabase()
    save(id, props, views).map(_ => ())
  }

  /** parentId: サブ項目にするときの親の項目 */
  def addRow(id: String,
             title: String,
             values: Map[String, Json] = Map.empty,
             parentId: Option[String] = None): Future[DbRow] =
    Api.post[DbRow](
      s"/api/pages/${Api.seg(id)}/database/rows",
      Json.obj("title" -> title.asJson, "values" -> valuesJson(values)).deepMerge(parentId.fold(Json.obj())(p =>
        Json.obj("parentId" -> p.asJson)
      ))
    )

  /** 戻り値: 作った数 */
  def addRows(id: String, rows: List[(String, Map[String, Json])]): Future[Int] =
    Api.post[Json](
      s"/api/pages/${Api.seg(id)}/database/rows/bulk",
      Json.obj("rows" -> Json.arr(rows.map((t, v) => Json.obj("title" -> t.asJson, "values" -> valuesJson(v)))*))
    )
      .map(_.hcursor.get[Int]("created").getOrElse(0))

  def props(id: String): Future[PageProps] = Api.get[PageProps](s"/api/pages/${Api.seg(id)}/props")

  /** 値を変える（null は消す） */
  def setValues(id: String, values: Map[String, Json]): Future[PageProps] =
    Api.patch[PageProps](s"/api/pages/${Api.seg(id)}/props", Json.obj("values" -> valuesJson(values)))

}
