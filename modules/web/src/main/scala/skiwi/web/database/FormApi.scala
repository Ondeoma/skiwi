package skiwi.web.database

import io.circe.*
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.web.{Api, ApiError}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** フォームの API */
object FormApi {

  def list(
    dbId: String
  ): Future[List[DbFormDef]] = Api.get[List[DbFormDef]](s"/api/pages/$dbId/forms")

  def create(
    dbId: String,
    b: SaveForm
  ): Future[DbFormDef] = Api.post[DbFormDef](s"/api/pages/$dbId/forms", b.asJson)

  def update(
    id: String,
    b: SaveForm
  ): Future[DbFormDef] = Api.put[DbFormDef](s"/api/forms/$id", b.asJson)

  def remove(
    id: String
  ): Future[Unit] = Api.del(s"/api/forms/$id")

  private def publicUrl(
    token: String
  ) = s"/api/forms/public/${Api.enc(token)}"

  /** 回答画面のフォーム（ログイン不要） */
  def publicGet(
    token: String
  ): Future[PublicForm] =
    Api.request(dom.HttpMethod.GET, publicUrl(token), redirectOn401 = false).map(Api.as[PublicForm])
      .recoverWith { case _ => Future.failed(new Exception("このフォームは見つからないか、受付を終了しています。")) }

  def publicSubmit(
    token: String,
    values: Map[String, Json]
  ): Future[Unit] =
    Api.request(
      dom.HttpMethod.POST,
      publicUrl(token),
      Some(Json.obj("values" -> Json.fromFields(values))),
      redirectOn401 = false
    ).map(_ => ())
      .recoverWith {
        case ApiError(429, _, _) => Future.failed(new Exception("送信が集中しています。少し待ってからもう一度お試しください。"))
        case ApiError(401, _, _) => Future.failed(new Exception("ログインしてから回答してください。"))
        case _ => Future.failed(new Exception("送信できませんでした。入力内容を確かめてください。"))
      }

}
