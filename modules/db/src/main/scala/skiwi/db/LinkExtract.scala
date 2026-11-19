package skiwi.db

import io.circe.Json
import skiwi.core.uuidOpt

import java.util.UUID

/** 本文(JSON)からリンク先ページを取り出す（被リンク用）。ページリンクと、ページURLへの通常リンクの両方。 */
object LinkExtract {
  private val PagePath =
    """/w/[0-9a-fA-F-]{36}/p/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})""".r.unanchored

  def targets(json: String): Set[UUID] =
    io.circe.parser.parse(json).toOption.fold(Set.empty)(targets)

  def targets(j: Json): Set[UUID] =
    j.arrayOrObject(
      Set.empty,
      _.flatMap(targets).toSet,
      obj => {
        val tpe = obj("type").flatMap(_.asString)
        val attrs = obj("attrs").flatMap(_.asObject)
        val own: Set[UUID] = tpe match {
          case Some("pageLink") | Some("syncedRef") =>
            attrs.flatMap(_("pageId")).flatMap(_.asString).flatMap(uuidOpt).toSet
          case Some("link") =>
            attrs.flatMap(_("href")).flatMap(_.asString).collect { case PagePath(id) => id }.flatMap(uuidOpt).toSet
          case _ => Set.empty
        }
        own ++ obj("content").fold(Set.empty[UUID])(targets) ++ obj("marks").fold(Set.empty[UUID])(targets)
      }
    )

  /** 本文中のメンション（mention ノードの userId）。 */
  def mentions(json: String): Set[UUID] =
    io.circe.parser.parse(json).toOption.fold(Set.empty)(mentions)

  def mentions(j: Json): Set[UUID] =
    j.arrayOrObject(
      Set.empty,
      _.flatMap(mentions).toSet,
      obj => {
        val own = Option.when(obj("type").flatMap(_.asString).contains("mention"))(
          obj("attrs").flatMap(_.asObject).flatMap(_("userId")).flatMap(_.asString).flatMap(uuidOpt)
        ).flatten.toSet
        own ++ obj("content").fold(Set.empty[UUID])(mentions)
      }
    )

  /** コメント本文のメンション: @[名前](ユーザーID) */
  private val CommentMention = """@\[[^\]]*\]\(([0-9a-fA-F-]{36})\)""".r
  def commentMentions(body: String): Set[UUID] =
    CommentMention.findAllMatchIn(body).flatMap(m => uuidOpt(m.group(1))).toSet

  /** ToDoリストの項目（担当者がいるものだけ）。text は項目の文章（入れ子の項目を除く。メンションは @名前）。 */
  final case class Task(seq: Int,
                        text: String,
                        checked: Boolean,
                        assignees: Set[UUID],
                        due: Option[java.time.LocalDate])

  def tasks(json: String): List[Task] = io.circe.parser.parse(json).toOption.fold(Nil)(tasks)

  def tasks(root: Json): List[Task] = {
    val out = scala.collection.mutable.ListBuffer.empty[Task]
    def tpe(j: Json) = j.hcursor.get[String]("type").toOption
    def kids(j: Json) = j.hcursor.get[List[Json]]("content").getOrElse(Nil)
    def attr(j: Json, k: String) = j.hcursor.downField("attrs").downField(k).focus
    // 項目自身の内容（入れ子のリストには降りない）
    def own(j: Json): List[Json] =
      kids(j).filterNot(k => tpe(k).exists(t => t == "taskList" || t == "bulletList" || t == "orderedList"))
    def flat(j: Json): List[Json] = j :: kids(j).flatMap(flat)
    def textOf(j: Json): String = tpe(j) match {
      case Some("text") => j.hcursor.get[String]("text").getOrElse("")
      case Some("mention") => "@" + attr(j, "label").flatMap(_.asString).getOrElse("")
      case Some("dueDate") => ""
      case _ => kids(j).map(textOf).mkString(if tpe(j).contains("paragraph") then "" else " ")
    }
    def walk(j: Json): Unit = {
      if tpe(j).contains("taskItem") then {
        val mine = own(j).flatMap(flat)
        val assignees = mine.filter(n => tpe(n).contains("mention")).flatMap(n =>
          attr(n, "userId").flatMap(_.asString).flatMap(uuidOpt)
        ).toSet
        val due = mine.filter(n => tpe(n).contains("dueDate")).flatMap(n => attr(n, "date").flatMap(_.asString))
          .flatMap(d => scala.util.Try(java.time.LocalDate.parse(d)).toOption).headOption
        val text = own(j).map(textOf).mkString(" ").replaceAll("\\s+", " ").trim.take(300)
        if assignees.nonEmpty then
          out += Task(out.size, text, attr(j, "checked").flatMap(_.asBoolean).getOrElse(false), assignees, due)
      }
      kids(j).foreach(walk)
    }
    walk(root)
    out.toList
  }
}
