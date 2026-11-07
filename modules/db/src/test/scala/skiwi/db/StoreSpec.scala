package skiwi.db

import cats.effect.*
import cats.syntax.all.*
import skiwi.core.*

import java.time.Instant
import java.util.UUID

/** TEST_DATABASE_URL (例: jdbc:postgresql://localhost:54329/skiwi) が設定されている場合のみ実行。 */
class StoreSpec extends DbSuite:

  private val storeResource: Resource[IO, Store] = cfg match
    case None    => Resource.eval(IO.raiseError[Store](new IllegalStateException("TEST_DATABASE_URL not set")))
    case Some(c) => Resource.eval(Database.migrate(c)) *> Database.transactor(c).map(new Store(_))

  private val storeFixture = ResourceSuiteLocalFixture("store", storeResource)
  override def munitFixtures = if cfg.isDefined then List(storeFixture) else Nil

  private def store = storeFixture()
  private def withDb(body: => IO[Unit])(using munit.Location): IO[Unit] =
    if cfg.isEmpty then IO(assume(false, "TEST_DATABASE_URL not set")) else body

  private def newUser(name: String, groups: List[String] = Nil, admin: Boolean = false): IO[AuthSession] =
    val sub = s"$name-${UUID.randomUUID()}"
    for
      u  <- store.upsertUser(sub, Some(s"$name@example.com"), name)
      _  <- store.syncGroups(u.id, groups)
      gs <- groupsOf(u, groups)
    yield AuthSession(u, gs, admin)

  // グループはセッション経由で取得して検証する
  private def groupsOf(u: User, names: List[String]): IO[List[Group]] =
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString.getBytes)
    for
      _ <- store.createSession(hash, u.id, Instant.now().plusSeconds(60))
      r <- store.findSession(hash, Instant.now())
      _ <- store.deleteSession(hash)
    yield r.map(_._2).getOrElse(Nil).filter(g => names.contains(g.externalId))

  test("sessions: create, find, expire") {
    withDb {
      for
        u <- store.upsertUser("sess-" + UUID.randomUUID(), None, "s")
        hash = java.security.MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString.getBytes)
        _ <- store.createSession(hash, u.id, Instant.now().plusSeconds(60))
        ok <- store.findSession(hash, Instant.now())
        expired <- store.findSession(hash, Instant.now().plusSeconds(120))
        _ <- store.deleteSession(hash)
        gone <- store.findSession(hash, Instant.now())
      yield
        assertEquals(ok.map(_._1.id), Some(u.id))
        assertEquals(expired, None)
        assertEquals(gone, None)
    }
  }

  test("login state is consumed once") {
    withDb {
      val st = "state-" + UUID.randomUUID()
      for
        _ <- store.saveLoginState(st, "ver", "non", Instant.now())
        a <- store.takeLoginState(st, Instant.now())
        b <- store.takeLoginState(st, Instant.now())
      yield
        assertEquals(a, Some(("ver", "non")))
        assertEquals(b, None)
    }
  }

  test("group sync replaces memberships") {
    withDb {
      for
        u <- store.upsertUser("g-" + UUID.randomUUID(), None, "g")
        _ <- store.syncGroups(u.id, List("a", "b"))
        g1 <- groupsOf(u, List("a", "b"))
        _ <- store.syncGroups(u.id, List("b"))
        g2 <- groupsOf(u, List("a", "b"))
      yield
        assertEquals(g1.map(_.externalId).toSet, Set("a", "b"))
        assertEquals(g2.map(_.externalId).toSet, Set("b"))
    }
  }

  test("workspace + page tree + acl end-to-end") {
    withDb {
      val gname = "team-" + UUID.randomUUID()
      for
        owner  <- newUser("owner")
        member <- newUser("member", List(gname))
        guest  <- newUser("guest")
        admin  <- newUser("admin", admin = true)
        ws     <- store.createWorkspace("ws", owner.user.id)

        // 作成者はワークスペースEdit、他人は権限なし
        ownerRole <- store.workspaceRole(ws.id, owner)
        guestRole <- store.workspaceRole(ws.id, guest)
        _ = assertEquals(ownerRole.map(_._2), Some(Role.Edit))
        _ = assertEquals(guestRole, None)

        // ツリー作成
        root  <- store.createPage(ws.id, None, Placement.Last, "root", None, owner.user.id).map(_.toOption.get)
        a     <- store.createPage(ws.id, Some(root.id), Placement.Last, "a", None, owner.user.id).map(_.toOption.get)
        b     <- store.createPage(ws.id, Some(root.id), Placement.Last, "b", None, owner.user.id).map(_.toOption.get)
        first <- store.createPage(ws.id, Some(root.id), Placement.First, "first", None, owner.user.id).map(_.toOption.get)
        mid   <- store.createPage(ws.id, Some(root.id), Placement.After(a.id), "mid", None, owner.user.id).map(_.toOption.get)
        pages <- store.listPages(ws.id, owner)
        _ = assertEquals(
          pages.map(_._1).filter(_.parentId.contains(root.id)).map(_.title),
          List("first", "a", "mid", "b")
        )

        // 閲覧権限: グループにView付与 → member は見える、guest は見えない
        _ <- store.setPageAcl(root.id, PageAcl(true, List(AclEntry(Principal(PrincipalType.Group, member.groups.head.id), Role.View))))
        mSees <- store.pageAccess(a.id, member)
        gSees <- store.pageAccess(a.id, guest)
        _ = assertEquals(mSees.map(_._2), Some(Role.View))
        _ = assertEquals(gSees, None)
        mList <- store.listPages(ws.id, member)
        _ = assertEquals(mList.size, 5)
        wsList <- store.listWorkspaces(member)
        _ = assert(wsList.exists(_._1.id == ws.id), "workspace visible via page acl")

        // 子ページでEdit上書き（下位で昇格）
        _ <- store.setPageAcl(a.id, PageAcl(true, List(AclEntry(Principal(PrincipalType.User, member.user.id), Role.Edit))))
        aRole <- store.pageAccess(a.id, member)
        bRole <- store.pageAccess(b.id, member)
        _ = assertEquals(aRole.map(_._2), Some(Role.Edit))
        _ = assertEquals(bRole.map(_._2), Some(Role.View))

        // 継承停止: b を非公開にすると member は見えない
        _ <- store.setPageAcl(b.id, PageAcl(false, Nil))
        bHidden <- store.pageAccess(b.id, member)
        bOwner  <- store.pageAccess(b.id, owner)
        bAdmin  <- store.pageAccess(b.id, admin)
        _ = assertEquals(bHidden, None)
        _ = assertEquals(bOwner, None) // 継承停止ページはワークスペース権限も及ばない（APIは設定者本人を自動でEditに追加する）
        _ = assertEquals(bAdmin.map(_._2), Some(Role.Edit))
      yield ()
    }
  }

  test("move: cycle is rejected, move reorders, delete cascades") {
    withDb {
      for
        owner <- newUser("mover")
        ws    <- store.createWorkspace("mv", owner.user.id)
        p1    <- store.createPage(ws.id, None, Placement.Last, "p1", None, owner.user.id).map(_.toOption.get)
        p2    <- store.createPage(ws.id, Some(p1.id), Placement.Last, "p2", None, owner.user.id).map(_.toOption.get)
        p3    <- store.createPage(ws.id, Some(p2.id), Placement.Last, "p3", None, owner.user.id).map(_.toOption.get)
        cyc   <- store.movePage(p1.id, Some(p3.id), Placement.Last)
        self  <- store.movePage(p1.id, Some(p1.id), Placement.Last)
        _ = assert(cyc.isLeft && self.isLeft)
        moved <- store.movePage(p3.id, None, Placement.First)
        _ = assertEquals(moved.toOption.get.parentId, None)
        n <- store.deletePage(p1.id)
        _ = assertEquals(n, 2) // p1, p2（p3 は移動済み）
        gone <- store.pageAccess(p2.id, owner)
        kept <- store.pageAccess(p3.id, owner)
        _ = assertEquals(gone, None)
        _ = assert(kept.isDefined)
      yield ()
    }
  }
