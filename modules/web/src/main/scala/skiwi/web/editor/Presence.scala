package skiwi.web.editor

import scala.scalajs.js

/** 同じページを開いている人（同時編集の awareness から） */
object Presence {

  /** サーバーが発行するアイコンの URL の形（同時編集で他の人から届いた値は、この形のものだけを使う） */
  private val AvatarUrl = "^/api/users/[0-9a-f-]{36}/avatar\\?v=\\d+$".r
  private val ColorRe = "(?i)^#[0-9a-f]{6}$".r

  def safeAvatarUrl(
    u: Any
  ): Option[String] = u match {
    case s: String if AvatarUrl.matches(s) => Some(s)
    case _ => None
  }

  private final case class Person(
    name: String,
    color: String,
    editing: Boolean,
    avatar: Option[String])

  /** awareness の状態（クライアント ID → 状態）から、自分以外の人. 同じ人の複数タブは1人にまとめ、名前の順に並べる */
  def people(
    states: js.Any,
    self: Double
  ): js.Array[js.Any] = {
    var people = Map.empty[String, Person]
    states.asInstanceOf[js.Dynamic].forEach(
      (
        st: js.Dynamic,
        client: Double
      ) => {
        val user: js.Dynamic = if (js.isUndefined(st) || st == null) js.Dynamic.literal() else st.user
        val name = if (js.isUndefined(user) || user == null || js.isUndefined(user.name) || user.name == null) ""
        else user.name.toString
        if (client != self && name.nonEmpty) {
          val cur = people.get(name)
          val color = String.valueOf(user.color)
          people += name -> Person(
            name,
            if (ColorRe.matches(color)) color else "#888888",
            (!js.isUndefined(st.mode) && st.mode.toString == "edit") || cur.exists(_.editing),
            safeAvatarUrl(user.avatar.asInstanceOf[Any]).orElse(cur.flatMap(_.avatar))
          )
        }
      }
    )
    js.Array(people.values.toList.sortWith(
      (
        a,
        b
      ) => skiwi.web.workspace.WsText.jaLess(a.name, b.name)
    ).map { p =>
      js.Dynamic.literal(name = p.name, color = p.color, editing = p.editing, avatar = p.avatar.orNull): js.Any
    }*)
  }

}
