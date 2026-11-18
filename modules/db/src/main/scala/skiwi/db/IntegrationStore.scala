package skiwi.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.time.Instant
import java.util.UUID

/** 外部サービス（skea・skawau）との連携: 設定（キーと値）、ユーザーごとの接続（暗号化したトークン）、許可画面へ送り出したときの state、
  * skiwi が認可サーバーとして発行する認可コード。 */
final class IntegrationStore(xa: Transactor[IO]) extends TxStore(xa):

  /** prefix で始まるキーの設定 */
  def settings(prefix: String): IO[Map[String, String]] =
    tx(sql"select key, value from integration_setting where key like ${prefix + "%"}".query[(String, String)].to[List]).map(_.toMap)

  /** 設定をまとめて書く（None は削除）。by: 変更した人（起動時の初期設定などは None） */
  def setSettings(values: Map[String, Option[String]], by: Option[UUID]): IO[Unit] = tx(
    values.toList.traverse_ {
      case (k, Some(v)) =>
        sql"""insert into integration_setting (key, value, updated_by) values ($k, $v, $by)
              on conflict (key) do update set value = excluded.value, updated_by = excluded.updated_by, updated_at = now()""".update.run
      case (k, None) => sql"delete from integration_setting where key = $k".update.run
    }
  )

  // ---------------------------------------------------------------- 外部サービスとの接続（service: skea・skawau）

  def token(service: String, userId: UUID): IO[Option[String]] =
    tx(sql"select token_enc from integration_connection where service = $service and user_id = $userId".query[String].option)

  def saveToken(service: String, userId: UUID, tokenEnc: String): IO[Unit] = tx(
    sql"""insert into integration_connection (service, user_id, token_enc) values ($service, $userId, $tokenEnc)
          on conflict (service, user_id) do update set token_enc = excluded.token_enc, created_at = now()""".update.run.void
  )

  def deleteToken(service: String, userId: UUID): IO[Boolean] =
    tx(sql"delete from integration_connection where service = $service and user_id = $userId".update.run).map(_ > 0)

  /** 全員の接続を消す（接続先を変えたとき） */
  def deleteAllTokens(service: String): IO[Int] = tx(sql"delete from integration_connection where service = $service".update.run)

  def countTokens(service: String): IO[Int] =
    tx(sql"select count(*)::int from integration_connection where service = $service".query[Int].unique)

  /** state を保存する（10分より古いものは消す） */
  def saveState(service: String, state: String, userId: UUID, verifier: String, returnTo: String, now: Instant): IO[Unit] = tx {
    val cutoff = now.minusSeconds(600)
    sql"delete from integration_oauth_state where created_at < $cutoff".update.run *>
      sql"""insert into integration_oauth_state (state, service, user_id, code_verifier, return_to, created_at)
            values ($state, $service, $userId, $verifier, $returnTo, $now)""".update.run.void
  }

  /** 本人の state を1回だけ消費して (code_verifier, return_to) を返す */
  def takeState(service: String, state: String, userId: UUID, now: Instant): IO[Option[(String, String)]] = tx {
    val cutoff = now.minusSeconds(600)
    sql"""delete from integration_oauth_state where state = $state and service = $service and user_id = $userId and created_at >= $cutoff
          returning code_verifier, return_to""".query[(String, String)].option
  }

  // ---------------------------------------------------------------- skiwi が発行する認可コード（OAuth 2.0 の認可サーバー）

  /** 認可コードを保存する（ハッシュだけ。期限から1時間たったものは消す） */
  def createOAuthCode(hash: Array[Byte], c: OAuthCode, expires: Instant): IO[Unit] = tx(
    sql"""insert into oauth_code (code_hash, client_id, redirect_uri, user_id, code_challenge, scope, expires_at)
          values ($hash, ${c.clientId}, ${c.redirectUri}, ${c.userId}, ${c.codeChallenge}, ${c.scope}, $expires)""".update.run *>
      sql"delete from oauth_code where expires_at < now() - interval '1 hour'".update.run.void
  )

  /** 認可コードを使う（期限内で未使用のものだけ。使ったら使用済みにする） */
  def consumeOAuthCode(hash: Array[Byte]): IO[Option[OAuthCode]] = tx(
    sql"""update oauth_code set used_at = now() where code_hash = $hash and used_at is null and expires_at > now()
          returning client_id, redirect_uri, user_id, code_challenge, scope""".query[OAuthCode].option
  )

/** 認可コードに結び付けた内容 */
final case class OAuthCode(clientId: String, redirectUri: String, userId: UUID, codeChallenge: String, scope: String = "read")
