package skiwi.web.content

import io.circe.*
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.web.Api

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

object ContentApi {

  private def okOrNull[A](
    f: Future[A]
  ): Future[Option[A]] = Api.okOrNone(f)

  /** ページの本文（ProseMirror JSON）. 読めなければ None */
  def pageContent(
    id: String
  ): Future[Option[Json]] =
    okOrNull(Api.request(dom.HttpMethod.GET, s"/api/pages/$id/content", redirectOn401 = false))
      .map(_.flatten.flatMap(_.hcursor.downField("content").focus).filterNot(_.isNull))

  /** 空のページに本文を入れる（取り込み・複製）. state: io の encodeJson / encodeHtml の結果 */
  def importContent(
    id: String,
    state: Json
  ): Future[Unit] =
    Api.request(dom.HttpMethod.POST, s"/api/pages/$id/import", Some(state), redirectOn401 = false).map(_ => ())

  def uploadAttachment(
    pageId: String,
    file: dom.File
  ): Future[Attachment] = {
    val fd = new dom.FormData()
    fd.append("file", file, file.name)
    Api.request(
      dom.HttpMethod.POST,
      s"/api/pages/$pageId/attachments",
      Some(fd),
      redirectOn401 = false
    ).map(Api.as[Attachment])
  }

  /** ページ（と子ページ）・添付ファイルをサーバーで複製する. parentId: None=元と同じ階層、Some(None)=ワークスペースの直下 */
  def duplicate(
    id: String,
    workspaceId: String,
    parentId: Option[Option[String]],
    includeChildren: Boolean,
    title: String
  ): Future[DuplicateResult] = {
    val base = Json.obj(
      "workspaceId" -> workspaceId.asJson,
      "includeChildren" -> includeChildren.asJson,
      "title" -> title.asJson
    )
    Api.post[DuplicateResult](
      s"/api/pages/$id/duplicate",
      parentId.fold(base)(p => base.deepMerge(Json.obj("parentId" -> p.asJson)))
    )
  }

}
