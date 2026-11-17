package skiwi.web.admin

export skiwi.web.model.PublicShare

import io.circe.*
import io.circe.syntax.*
import skiwi.web.Api

import scala.concurrent.Future

// 管理者の画面（公開中のページ・外部連携）が使う API（frontend/src/api.ts の shareApi.adminList・disable、integrationApi、oauthClientApi と同じ）


final case class AdminShare(pageId: String, title: String, icon: Option[String] = None, workspaceId: String, workspaceName: String,
                            share: PublicShare) derives Codec.AsObject

/** skiwi が外部サービスのクライアントになる連携の設定（client_secret は返らない. 設定済みかどうかだけ） */
final case class IntegrationSettings(url: String, clientId: String, clientSecretSet: Boolean, projectKeys: List[String] = Nil,
                                     redirectUri: String, connections: Int = 0) derives Codec.AsObject

/** skea → skiwi: skiwi に登録する skea（OAuth のクライアント）. connections: 連携しているユーザーの数 */
final case class OAuthClientSettings(clientId: String, clientSecretSet: Boolean, redirectUri: String, name: String,
                                     connections: Int = 0) derives Codec.AsObject

object AdminApi {

  def shares(): Future[List[AdminShare]] = Api.get[List[AdminShare]]("/api/admin/shares")

  def disableShare(pageId: String): Future[Unit] = Api.del(s"/api/pages/$pageId/share")

  /** key: 公開リンクのトークンまたはURL名 */
  def shareUrl(key: String): String = s"${org.scalajs.dom.window.location.origin}/s/$key"

  def integrationSettings(svc: String): Future[IntegrationSettings] =
    Api.get[IntegrationSettings](s"/api/admin/integrations/$svc")

  /** clientSecret: 空なら今の値のまま. projectKeys: skea だけ */
  def saveIntegrationSettings(svc: String, url: String, clientId: String, clientSecret: Option[String],
                              projectKeys: Option[List[String]]): Future[IntegrationSettings] =
    Api.put[IntegrationSettings](s"/api/admin/integrations/$svc",
      Api.obj("url" -> url.asJson, "clientId" -> clientId.asJson, "clientSecret" -> clientSecret.asJson, "projectKeys" -> projectKeys.asJson))

  /** 連携の状態を読み直す（ログイン中の画面で共有している状態. workspace.Integrations） */
  def reloadIntegration(svc: String): Unit = skiwi.web.workspace.Integrations.reload(svc)

  def oauthClient(): Future[OAuthClientSettings] = Api.get[OAuthClientSettings]("/api/admin/integrations/skea/client")

  def saveOauthClient(clientId: String, clientSecret: Option[String], redirectUri: String, name: String): Future[OAuthClientSettings] =
    Api.put[OAuthClientSettings]("/api/admin/integrations/skea/client",
      Api.obj("clientId" -> clientId.asJson, "clientSecret" -> clientSecret.asJson, "redirectUri" -> redirectUri.asJson, "name" -> name.asJson))

  def removeOauthClient(): Future[Unit] = Api.del("/api/admin/integrations/skea/client")

}
