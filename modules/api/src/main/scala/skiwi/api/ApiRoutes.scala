package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.core.*
import skiwi.db.{DocStore, OpsStore, PageAcl, ShareStore, Store}

import java.util.UUID

/** 権限変更など、接続中の同時編集セッションの再検証が必要になったときに呼ぶ。 */
type AccessChanged = IO[Unit]

/** REST API（Cookieセッション or APIトークン）。権限のないリソースは存在を隠すため 404、閲覧可だが編集不可は 403。 */
final class ApiRoutes(
  protected val store: Store,
  docs: DocStore,
  ops: OpsStore,
  protected val sessions: SessionAuth,
  audit: Audit,
  accessChanged: AccessChanged = IO.unit,
  shares: Option[ShareStore] = None,
  webhooks: Option[WebhookService] = None,
  databases: Option[skiwi.db.DatabaseStore] = None,
  automations: Option[Automations] = None) extends PageRouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ GET -> Root / "api" / "me" => getMe(req)
    case req @ GET -> Root / "api" / "directory" => getDirectory(req)
    case req @ GET -> Root / "api" / "workspaces" => getWorkspaces(req)
    case req @ PUT -> Root / "api" / "workspaces" / UUIDVar(id) / "favorite" => putWorkspaceFavorite(req, id)
    case req @ POST -> Root / "api" / "workspaces" => postWorkspaces(req)
    case req @ GET -> Root / "api" / "workspaces" / UUIDVar(id) / "acl" => getWorkspaceAcl(req, id)
    case req @ PUT -> Root / "api" / "workspaces" / UUIDVar(id) / "acl" => putWorkspaceAcl(req, id)
    case req @ GET -> Root / "api" / "workspaces" / UUIDVar(id) / "pages" => getWorkspacePages(req, id)
    case req @ POST -> Root / "api" / "workspaces" / UUIDVar(id) / "pages" => postWorkspacePages(req, id)
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) => getPage(req, id)
    case req @ PATCH -> Root / "api" / "pages" / UUIDVar(id) => patchPage(req, id)
    case req @ POST -> Root / "api" / "pages" / UUIDVar(id) / "move" => postPageMove(req, id)
    case req @ DELETE -> Root / "api" / "pages" / UUIDVar(id) => deletePage(req, id)
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "acl" => getPageAcl(req, id)
    case req @ PUT -> Root / "api" / "pages" / UUIDVar(id) / "acl" => putPageAcl(req, id)
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "watch" => getPageWatch(req, id)
    case req @ PUT -> Root / "api" / "pages" / UUIDVar(id) / "watch" => putPageWatch(req, id)
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "content" => getPageContent(req, id)
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "versions" => getPageVersions(req, id)
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "versions" / UUIDVar(vid) => getPageVersion(req, id, vid)
    case req @ POST -> Root / "api" / "pages" / UUIDVar(id) / "versions" => postPageVersions(req, id)
    case req @ POST -> Root / "api" / "pages" / UUIDVar(id) / "versions" / UUIDVar(vid) / "restore" =>
      postPageVersionRestore(req, id, vid)
  }

  private def getMe(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      (AvatarRoutes.urls(ops, List(s.user.id)), sessions.appNames(req)).flatMapN(
        (
          av,
          names
        ) =>
          Ok(MeDto(s.user.id, s.user.name, s.user.email, s.groups.map(_.name), s.isAdmin, av.get(s.user.id), names))
      )
    )

  private def getDirectory(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req) { _ =>
      val q = req.uri.query.params.getOrElse("q", "").trim
      if q.isEmpty then Ok(List.empty[DirectoryDto])
      else
        ops.searchDirectory(q.take(100)).flatMap(es => Ok(es.map(e => DirectoryDto(e.tpe, e.id, e.name, e.detail))))
    }

  private def getWorkspaces(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req) { s =>
      (store.listWorkspaces(s), ops.favoriteWorkspaces(s.user.id)).flatMapN {
        (
          ws,
          favs
        ) =>
          val dtos = ws.map(
            (
              w,
              r
            ) => WorkspaceDto(w.id, w.name, r, favs(w.id))
          )
          Ok(dtos.filter(_.favorite) ++ dtos.filterNot(_.favorite))
      }
    }

  private def putWorkspaceFavorite(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req) { s =>
      body[FavoriteDto](req) { b =>
        // 見えるワークスペースだけ登録できる（存在の有無を漏らさないよう、見えなければ404）
        store.workspaceVisible(id, s).ifM(
          ops.setFavorite(id, s.user.id, b.favorite) *> Ok(FavoriteDto(b.favorite)),
          err(ErrorCode.WorkspaceNotFound)
        )
      }
    }

  private def postWorkspaces(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req) { s =>
      decodeBody[CreateWorkspaceReq](req).flatMap {
        case Right(b) if validName(b.name, MaxWorkspaceName).isDefined =>
          store.createWorkspace(b.name.trim, s.user.id).flatTap(w =>
            audit.log(s, "workspace.create", "workspace", w.id, Json.obj("name" -> w.name.asJson))
          ).flatMap(w => Created(WorkspaceDto(w.id, w.name, Role.Edit)))
        case _ => err(ErrorCode.WorkspaceNameInvalid)
      }
    }

  private def getWorkspaceAcl(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withWs(req, id, Role.Edit) {
      (
        _,
        ws,
        _
      ) =>
        (store.workspaceAcl(id), store.workspaceAclExpiry(id)).flatMapN(
          (
            es,
            ex
          ) => aclView(es, ex)
        ).flatMap(v => Ok(WorkspaceAclViewDto(ws.defaultRole, v)))
    }

  private def putWorkspaceAcl(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withWs(req, id, Role.Edit) {
      (
        s,
        ws,
        _
      ) =>
        body[WorkspaceAclDto](req) { b =>
          // 設定者自身が締め出されないよう、自分のEdit権限を保持する（管理者以外）
          val entries =
            b.entries.map(e => AclEntry(Principal(e.principalType, e.principalId), e.role)).distinctBy(_.principal)
          val self = AclEntry(Principal(PrincipalType.User, s.user.id), Role.Edit)
          val safe = if s.isAdmin || entries.exists(_.principal == self.principal) then entries else self :: entries
          for {
            before <- store.workspaceAcl(id)
            res <- store.setWorkspaceAcl(id, b.defaultRole, safe, expiries(b.entries))
            resp <- res match {
              case Left(e) => fail(e)
              case Right(_) =>
                audit.log(
                  s,
                  "workspace.acl",
                  "workspace",
                  id,
                  Json.obj("defaultRole" -> b.defaultRole.map(_.asString).asJson, "entries" -> aclJson(safe))
                ) *>
                  notifyShared(s, None, Some(ws.name), granted(before, safe), Some(id)) *>
                  accessChanged *> NoContent()
            }
          } yield resp
        }
    }

  private def getWorkspacePages(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req) { s =>
      (
        store.listPages(id, s),
        shares.fold(IO.pure(Set.empty[UUID]))(_.sharedIn(id)),
        store.labelsIn(id),
        databases.fold(IO.pure(Set.empty[UUID]))(_.idsIn(id)),
        store.lockedIn(id),
        store.fullWidthIn(id)
      ).flatMapN {
        (
          ps,
          pub,
          labels,
          dbIds,
          locked,
          wide
        ) =>
          store.pageMeta(ps.map(_._1.id)).flatMap { meta =>
            Ok(ps.map(
              (
                p,
                r
              ) =>
                PageDto.of(p, r).withMeta(meta.get(p.id)).copy(
                  published = pub(p.id),
                  labels = labels.getOrElse(p.id, Nil),
                  database = dbIds(p.id),
                  locked = locked(p.id),
                  fullWidth = wide(p.id)
                )
            ))
          }
      }
    }

  private def postWorkspacePages(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req) { s =>
      body[CreatePageReq](req) { b =>
        placement(b.afterId, b.placement) match {
          case Left(m) => err(ErrorCode.InvalidInput, m)
          case Right(pl) =>
            editUnder(s, id, b.parentId) {
              sbirds.observe.Telemetry.span(
                "page.create",
                "workspace.id" -> id.toString
              )(store.createPage(id, b.parentId, pl, b.title.getOrElse(""), b.icon, s.user.id)).flatMap {
                case Left(e) => fail(e)
                case Right(p) =>
                  sbirds.observe.Metrics.count(
                    "skiwi.pages.created",
                    "via" -> (if s.via == AuthVia.Session then "ui" else "api")
                  ) *>
                    // 作成者は自動でウォッチする（他の人の更新をメールで受け取る）
                    ops.setWatch(p.id, s.user.id, on = true) *>
                    // 作成直後は無題のことが多いので、タイトルが付くのを少し待ってから送る
                    webhooks.traverse_(_.emit("page.created", p.id, s.user.name, delay = 60)) *>
                    automations.traverse_(_.fire(AutoEvent("page_created", id, p.id, s.user.name))) *>
                    audit.log(
                      s,
                      "page.create",
                      "page",
                      p.id,
                      Json.obj("title" -> p.title.asJson, "workspaceId" -> id.toString.asJson)
                    ) *>
                    Created(PageDto.of(p, Role.Edit))
              }
            }
        }
      }
    }

  private def getPage(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withPage(req, id, Role.View)(
      (
        _,
        p,
        r
      ) => fullDto(p, r).flatMap(Ok(_))
    )

  private def patchPage(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withPage(req, id, Role.Edit) {
      (
        s,
        p,
        r
      ) =>
        body[UpdatePageReq](req) {
          case b if b.icon.flatten.exists(_.length > 32) => err(ErrorCode.IconTooLong)
          case b if b.status.flatten.exists(st => !UpdatePageReq.Statuses(st)) =>
            err(ErrorCode.PageStateInvalid)
          case b =>
            store.pageMeta(List(id)).map(_.get(id).flatMap(_.status)).flatMap { oldStatus =>
              b.fullWidth.traverse_(store.setFullWidth(id, _)) *>
                store.setPageMeta(id, b.ownerId, b.status).flatMap {
                  case false => err(ErrorCode.OwnerNotFound)
                  case true =>
                    store.updatePage(id, b.title, b.icon).flatMap {
                      case Some(np) =>
                        val changed = Json.obj(
                          "title" -> Option.when(np.title != p.title)(np.title).asJson,
                          "icon" -> Option.when(np.icon != p.icon)(np.icon).asJson,
                          "owner" -> b.ownerId.map(_.fold("(なし)")(_.toString)).asJson,
                          "status" -> b.status.map(_.getOrElse("(なし)")).asJson
                        ).dropNullValues
                        (if changed.asObject.forall(_.isEmpty) then IO.unit
                         else audit.log(s, "page.update", "page", id, changed)) *>
                          // 状態が変わったとき、自動化ルールを動かす
                          (b.status match {
                            case Some(st) if st != oldStatus =>
                              automations.traverse_(_.fire(AutoEvent(
                                "status_changed",
                                p.workspaceId,
                                id,
                                s.user.name,
                                io.circe.JsonObject("status" -> st.asJson)
                              )))
                            case _ => IO.unit
                          }) *>
                          fullDto(np, r).flatMap(Ok(_))
                      case None => err(ErrorCode.PageNotFound)
                    }
                }
            }
        }
    }

  private def postPageMove(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withPage(req, id, Role.Edit) {
      (
        s,
        p,
        r
      ) =>
        body[MovePageReq](req) { b =>
          placement(b.afterId, b.placement) match {
            case Left(m) => err(ErrorCode.InvalidInput, m)
            // 別のワークスペースへ（移動先の親、またはワークスペース直下に編集権限が必要）
            case Right(pl) if b.workspaceId.exists(_ != p.workspaceId) =>
              val target = b.workspaceId.get
              editUnder(s, target, b.parentId) {
                store.moveToWorkspace(id, target, b.parentId, pl).flatMap {
                  case Left(e) => fail(e)
                  case Right(np) =>
                    audit.log(
                      s,
                      "page.move_workspace",
                      "page",
                      id,
                      Json.obj(
                        "title" -> p.title.asJson,
                        "from" -> p.workspaceId.toString.asJson,
                        "to" -> target.toString.asJson
                      )
                    ) *>
                      accessChanged *> store.pageAccess(id, s).flatMap(a => Ok(PageDto.of(np, a.fold(r)(_._2))))
                }
              }
            case Right(pl) =>
              editUnder(s, p.workspaceId, b.parentId) {
                store.movePage(id, b.parentId, pl).flatMap {
                  case Left(e) => fail(e)
                  case Right(np) =>
                    val detail = Json.obj(
                      "from" -> p.parentId.map(_.toString).asJson,
                      "to" -> np.parentId.map(_.toString).asJson
                    )
                    // 親が変わると継承する権限が変わり得る
                    audit.log(s, "page.move", "page", id, detail) *>
                      (if np.parentId != p.parentId then accessChanged else IO.unit) *> Ok(PageDto.of(np, r))
                }
              }
          }
        }
    }

  private def deletePage(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withPage(req, id, Role.Edit) {
      (
        s,
        p,
        _
      ) =>
        uneditableDescendants(s, id, inTrash = false).flatMap {
          case n if n > 0 => err(ErrorCode.DescendantsNotEditable, descendantsBlocked(n))
          case _ =>
            store.deletePage(id, Some(s.user.id)).flatTap(_ =>
              webhooks.traverse_(_.emit("page.deleted", id, s.user.name))
            ).flatMap { n =>
              audit.log(
                s,
                "page.delete",
                "page",
                id,
                Json.obj("title" -> p.title.asJson, "deletedCount" -> n.asJson)
              ) *>
                accessChanged *> NoContent()
            }
        }
    }

  private def getPageAcl(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withPage(req, id, Role.Edit) {
      (
        _,
        _,
        _
      ) =>
        store.getPageAcl(id).flatMap {
          case None => err(ErrorCode.PageNotFound)
          case Some(a) => aclView(a.entries, a.expires).flatMap(v => Ok(AclViewDto(a.inherit, v)))
        }
    }

  private def putPageAcl(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withPage(req, id, Role.Edit) {
      (
        s,
        _,
        _
      ) =>
        body[AclDto](req) { b =>
          val entries =
            b.entries.map(e => AclEntry(Principal(e.principalType, e.principalId), e.role)).distinctBy(_.principal)
          // 継承を止める場合、設定者本人が締め出されないよう自分のEditを追加する（管理者を除く）
          val selfP = Principal(PrincipalType.User, s.user.id)
          val safe =
            if !b.inherit && !s.isAdmin && !s.principals.exists(p => entries.exists(_.principal == p)) then
              AclEntry(selfP, Role.Edit) :: entries
            else entries
          for {
            before <- store.getPageAcl(id).map(_.fold(List.empty[AclEntry])(_.entries))
            ok <- store.setPageAcl(id, PageAcl(b.inherit, safe, expiries(b.entries)))
            resp <-
              if !ok then err(ErrorCode.PageNotFound)
              else
                audit.log(
                  s,
                  "page.acl",
                  "page",
                  id,
                  Json.obj("inherit" -> b.inherit.asJson, "entries" -> aclJson(safe))
                ) *>
                  notifyShared(s, Some(id), None, granted(before, safe)) *>
                  accessChanged *> NoContent()
          } yield resp
        }
    }

  private def getPageWatch(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withPage(req, id, Role.View)(
      (
        s,
        _,
        _
      ) => watchState(id, s).flatMap(Ok(_))
    )

  private def putPageWatch(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withPage(req, id, Role.View) {
      (
        s,
        _,
        _
      ) =>
        body[SetWatchReq](req) { b =>
          val set =
            if b.edits.isDefined || b.comments.isDefined || b.subtree.isDefined then
              ops.watchState(id, s.user.id).flatMap {
                (
                  own,
                  _
                ) =>
                  val cur = own.getOrElse(skiwi.db.WatchOpts(true, true, false))
                  ops.setWatchOpts(
                    id,
                    s.user.id,
                    skiwi.db.WatchOpts(
                      b.edits.getOrElse(cur.edits),
                      b.comments.getOrElse(cur.comments),
                      b.subtree.getOrElse(cur.subtree)
                    )
                  )
              }
            else ops.setWatch(id, s.user.id, b.watching.getOrElse(true))
          set *> watchState(id, s).flatMap(Ok(_))
        }
    }

  private def getPageContent(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withPage(req, id, Role.View) {
      (
        _,
        p,
        _
      ) =>
        docs.currentContent(id).flatMap {
          case Some((json, text, at)) => Ok(PageContentDto(parseOrNull(json), text, at))
          case None => Ok(PageContentDto(Json.Null, "", p.createdAt))
        }
    }

  private def getPageVersions(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withPage(req, id, Role.View)(
      (
        _,
        _,
        _
      ) => docs.listVersions(id).flatMap(vs => Ok(vs.map(versionDto)))
    )

  private def getPageVersion(
    req: Request[IO],
    id: UUID,
    vid: UUID
  ): IO[Response[IO]] =
    withPage(req, id, Role.View) {
      (
        _,
        _,
        _
      ) =>
        docs.getVersion(id, vid).flatMap {
          case Some((m, json, text)) => Ok(VersionContentDto(versionDto(m), parseOrNull(json), text))
          case None => err(ErrorCode.PageVersionNotFound)
        }
    }

  private def postPageVersions(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withPage(req, id, Role.Edit) {
      (
        s,
        _,
        _
      ) =>
        body[DocStateReq](req) {
          case b if b.content.isObject =>
            decodeYdoc(b.ydoc) match {
              case None => err(ErrorCode.PageContentInvalid)
              case Some(y) =>
                val label = b.label.map(_.trim).filter(_.nonEmpty).map(_.take(200))
                docs.createVersion(id, y, b.content.noSpaces, b.text, s.user.id, label).flatMap { vid =>
                  audit.log(
                    s,
                    "page.version.create",
                    "page",
                    id,
                    Json.obj("versionId" -> vid.toString.asJson, "label" -> label.asJson)
                  ) *>
                    docs.getVersion(id, vid).flatMap(v => Created(versionDto(v.get._1)))
                }
            }
          case _ => err(ErrorCode.RequestBodyInvalid)
        }
    }

  private def postPageVersionRestore(
    req: Request[IO],
    id: UUID,
    vid: UUID
  ): IO[Response[IO]] =
    withPage(req, id, Role.Edit) {
      (
        s,
        _,
        _
      ) =>
        body[DocStateReq](req) {
          case b if b.content.isObject =>
            decodeYdoc(b.ydoc) match {
              case None => err(ErrorCode.PageContentInvalid)
              case Some(y) =>
                docs.getVersion(id, vid).flatMap {
                  case None => err(ErrorCode.PageVersionNotFound)
                  case Some((m, json, text)) =>
                    val when = m.createdAt.toString
                    for {
                      backup <- docs.createVersion(id, y, b.content.noSpaces, b.text, s.user.id, Some("復元前の状態"))
                      _ <- audit.log(
                        s,
                        "page.restore",
                        "page",
                        id,
                        Json.obj(
                          "versionId" -> vid.toString.asJson,
                          "versionAt" -> when.asJson,
                          "backupVersionId" -> backup.toString.asJson
                        )
                      )
                      resp <- Ok(VersionContentDto(versionDto(m), parseOrNull(json), text))
                    } yield resp
                }
            }
          case _ => err(ErrorCode.RequestBodyInvalid)
        }
    }

  private def fullDto(
    p: Page,
    r: Role
  ): IO[PageDto] =
    store.pageDetail(p.id).map(d =>
      PageDto.of(p, r).withMeta(Some(d.meta)).copy(
        database = databases.isDefined && d.database,
        locked = d.locked,
        fullWidth = d.fullWidth
      )
    )

  private def placement(
    afterId: Option[UUID],
    kw: Option[String]
  ): Either[String, Placement] =
    (afterId, kw.map(_.toLowerCase)) match {
      case (Some(id), _) => Right(Placement.After(id))
      case (None, None | Some("last")) => Right(Placement.Last)
      case (None, Some("first")) => Right(Placement.First)
      case (None, Some(other)) => Left(s"置き場所が正しくありません: $other")
    }

  private def aclView(
    entries: List[AclEntry],
    expires: Map[Principal, java.time.Instant]
  ): IO[List[AclEntryViewDto]] =
    ops.principalNames(entries.map(_.principal.id)).map { names =>
      entries.map { e =>
        val n = names.get(e.principal.id)
        AclEntryViewDto(
          e.principal.tpe,
          e.principal.id,
          e.role,
          n.fold("(不明)")(_.name),
          n.flatMap(_.detail),
          expires.get(e.principal)
        )
      }
    }

  /** 期限付きのエントリ（過去の日時は無視する） */
  private def expiries(
    es: List[AclEntryDto]
  ): Map[Principal, java.time.Instant] = {
    val now = java.time.Instant.now()
    es.collect {
      case e if e.expiresAt.exists(_.isAfter(now)) => Principal(e.principalType, e.principalId) -> e.expiresAt.get
    }.toMap
  }

  private def aclJson(
    entries: List[AclEntry]
  ): Json =
    entries.map(e =>
      Json.obj(
        "type" -> e.principal.tpe.asString.asJson,
        "id" -> e.principal.id.toString.asJson,
        "role" -> e.role.asString.asJson
      )
    ).asJson

  /** 新たに付与された（または権限が上がった）プリンシパル。共有通知の対象。 */
  private def granted(
    before: List[AclEntry],
    after: List[AclEntry]
  ): List[(Principal, Role)] = {
    val old = before.map(e => e.principal -> e.role).toMap
    after.collect { case e if old.get(e.principal).forall(_ < e.role) => e.principal -> e.role }
  }

  /** 共有通知をメールアウトボックスに積む（本人は除く）。 */
  private def notifyShared(
    s: AuthSession,
    pageId: Option[UUID],
    wsName: Option[String],
    grants: List[(Principal, Role)],
    wsId: Option[UUID] = None
  ): IO[Unit] =
    grants.groupMap(_._2)(_._1).toList.traverse_ {
      (
        role,
        ps
      ) =>
        ops.expandToUsers(ps).flatMap { users =>
          val to = users - s.user.id
          pageId match {
            case Some(pid) => ops.enqueueShared(pid, to, s.user.name, role.asString).void
            case None => ops.enqueueSharedWorkspace(to, s.user.name, role.asString, wsName.getOrElse(""), wsId).void
          }
        }
    }.handleErrorWith(e => sbirds.observe.Log.report(ErrorCode.NotifyEnqueueFailed, s"share: $e", e))

  private def versionDto(
    v: skiwi.db.VersionMeta
  ) = VersionDto(v.id, v.createdAt, v.label, v.createdBy, v.authors)

  /** ウォッチの状態（祖先のウォッチは、閲覧できるものだけ示す） */
  private def watchState(
    id: UUID,
    s: AuthSession
  ): IO[WatchStateDto] =
    ops.watchState(id, s.user.id).flatMap {
      (
        own,
        anc
      ) =>
        anc.filterA(a => store.pageAccess(a._1, s).map(_.isDefined)).map { visibleAnc =>
          val o = own.getOrElse(skiwi.db.WatchOpts(false, false, false))
          WatchStateDto(
            own.isDefined,
            o.edits,
            o.comments,
            o.subtree,
            visibleAnc.map(
              (
                pid,
                t,
                w
              ) => WatchInheritedDto(pid, t, w.edits, w.comments)
            )
          )
        }
    }

}
