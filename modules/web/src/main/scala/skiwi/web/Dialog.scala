package skiwi.web

import io.github.ondeoma.scalactive.components.ComponentManager.*
import skiwi.web.model.DirectoryEntry

import scala.concurrent.{Future, Promise}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** 選択肢（choose）. detail は下に小さく出す説明 */
final case class Choice(label: String, value: String, detail: Option[String] = None)

/**
 * 開いている確認・入力ダイアログ（parts/DialogHost.st が表示する）.
 * kind: confirm / prompt / alert / choose. 結果: confirm は Boolean、prompt・choose は Option[String]、alert は Unit
 */
final case class DialogReq(kind: String, message: String, value: RV[String], placeholder: String, okLabel: String, danger: Boolean,
                           multiline: Boolean, options: List[Choice], resolve: Any => Unit)

/**
 * 画面内の確認・入力ダイアログ.
 * window.confirm / prompt / alert は埋め込みブラウザ等で無効化されることがある（即座に false / null が返る）ため使わない.
 * 新しく開くと、開いていたものは取り消し扱いで閉じる.
 */
object Dialog {

  val current: RV[Option[DialogReq]] = RV(None)

  private def open[A](kind: String, message: String, value: String = "", placeholder: String = "", okLabel: String = "OK",
                      danger: Boolean = false, multiline: Boolean = false, options: List[Choice] = Nil)(cancelValue: A): Future[A] = {
    current.v.foreach(cancel)
    val p = Promise[A]()
    current := Some(DialogReq(kind, message, RV(value), placeholder, okLabel, danger, multiline, options, v => {
      current := None
      p.trySuccess(v.asInstanceOf[A])
    }))
    p.future.map(identity)
  }

  def confirm(message: String, okLabel: String = "OK", danger: Boolean = false): Future[Boolean] =
    open[Boolean]("confirm", message, okLabel = okLabel, danger = danger)(false)

  def prompt(message: String, value: String = "", okLabel: String = "OK", placeholder: String = "", multiline: Boolean = false): Future[Option[String]] =
    open[Option[String]]("prompt", message, value, placeholder, okLabel, multiline = multiline)(None)

  /** 選択肢から1つ選ぶ（取り消しは None） */
  def choose(message: String, options: List[Choice]): Future[Option[String]] =
    open[Option[String]]("choose", message, okLabel = "", options = options)(None)

  def alert(message: String): Future[Unit] = open[Unit]("alert", message)(())

  /** 失敗を「（what）: 理由」で知らせる. 例: Dialog.alertError("変更できませんでした", e) */
  def alertError(what: String, e: Throwable): Future[Unit] = alert(s"$what: ${e.getMessage}")

  /** f を実行し、失敗したら「操作に失敗しました」と知らせる. busy: 実行中は true にする */
  def guarded(busy: Option[RV[Boolean]] = None)(f: => Future[Any]): Future[Unit] = {
    busy.foreach(_ := true)
    val r = scala.util.Try(f).fold(Future.failed, identity)
    r.map(_ => ()).recoverWith { case e => alertError("操作に失敗しました", e) }.andThen(_ => busy.foreach(_ := false))
  }

  def ok(d: DialogReq): Unit = d.resolve(d.kind match {
    case "prompt" => Some(d.value.v)
    case "confirm" => true
    case "choose" => None
    case _ => ()
  })

  def cancel(d: DialogReq): Unit = d.resolve(d.kind match {
    case "confirm" => false
    case "alert" => ()
    case _ => None
  })

  def pick(d: DialogReq, value: String): Unit = d.resolve(Some(value))

  /**
   * ユーザーを名前・メールで検索して1人選ぶ（1人だけ一致すればそのまま、複数なら選択肢を出す）.
   * 戻り値: None=取り消し・該当なし、Some(None)=空欄で決定（allowEmpty のときだけ）、Some(Some(u))=選んだユーザー
   */
  def pickUser(message: String, chooseMessage: String, initial: String = "", allowEmpty: Boolean = false): Future[Option[Option[DirectoryEntry]]] =
    prompt(message, initial, okLabel = "検索").flatMap {
      case None => Future.successful(None)
      case Some(q0) =>
        val q = q0.trim
        if (q.isEmpty) Future.successful(if (allowEmpty) Some(None) else None)
        else Api.directory(q).recover(_ => Nil).map(_.filter(_.principalType == "user")).flatMap { users =>
          if (users.isEmpty) alert(s"「$q」に一致するユーザーがいません").map(_ => None)
          else if (users.size == 1) Future.successful(Some(users.headOption))
          else choose(chooseMessage, users.take(10).map(u => Choice(s"👤 ${u.name}", u.id, u.detail)))
            .map(_.flatMap(id => users.find(_.id == id)).map(Some(_)))
        }
    }

}
