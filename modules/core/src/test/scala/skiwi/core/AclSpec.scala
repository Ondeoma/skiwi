package skiwi.core

import java.time.Instant
import java.util.UUID

class AclSpec extends munit.FunSuite:
  private val alice = Principal(PrincipalType.User, UUID.randomUUID())
  private val staff = Principal(PrincipalType.Group, UUID.randomUUID())
  private val who   = Set(alice, staff)
  private def node(inherit: Boolean, es: (Principal, Role)*) =
    AclNode(inherit, es.toList.map((p, r) => AclEntry(p, r)))

  test("workspace default applies when no entries") {
    assertEquals(Acl.resolve(List(node(true)), Nil, Some(Role.View), who), Some(Role.View))
    assertEquals(Acl.resolve(List(node(true)), Nil, None, who), None)
  }

  test("workspace acl beats workspace default") {
    val ws = List(AclEntry(staff, Role.Edit))
    assertEquals(Acl.resolve(Nil, ws, Some(Role.View), who), Some(Role.Edit))
  }

  test("max role among user and group entries at the same level") {
    val chain = List(node(true, alice -> Role.View, staff -> Role.Edit))
    assertEquals(Acl.resolve(chain, Nil, None, who), Some(Role.Edit))
  }

  test("child entry overrides parent (can lower permission)") {
    val chain = List(node(true, alice -> Role.View), node(true, alice -> Role.Edit))
    assertEquals(Acl.resolve(chain, Nil, None, who), Some(Role.View))
  }

  test("inherits from parent when child has no matching entry") {
    val other = Principal(PrincipalType.User, UUID.randomUUID())
    val chain = List(node(true, other -> Role.Edit), node(true, staff -> Role.View))
    assertEquals(Acl.resolve(chain, Nil, None, who), Some(Role.View))
  }

  test("inherit=false stops inheritance") {
    val other = Principal(PrincipalType.User, UUID.randomUUID())
    val chain = List(node(false, other -> Role.Edit), node(true, staff -> Role.Edit))
    assertEquals(Acl.resolve(chain, Nil, Some(Role.Edit), who), None)
    // 自分に一致するエントリがあれば許可される
    val chain2 = List(node(false, alice -> Role.View), node(true, staff -> Role.Edit))
    assertEquals(Acl.resolve(chain2, Nil, None, who), Some(Role.View))
  }

  test("resolveAll builds chains from the page tree") {
    val now = Instant.now()
    def page(id: UUID, parent: Option[UUID]) = Page(id, UUID.randomUUID(), parent, "a", "t", None, now, now)
    val root, child, grand, secret = UUID.randomUUID()
    val pages = List(page(root, None), page(child, Some(root)), page(grand, Some(child)), page(secret, Some(root)))
    val nodes = Map(
      root   -> node(true, staff -> Role.Edit),
      grand  -> node(true, staff -> Role.View),
      secret -> node(false)
    )
    val res = Acl.resolveAll(pages, nodes, Nil, None, who)
    assertEquals(res.get(root), Some(Role.Edit))
    assertEquals(res.get(child), Some(Role.Edit))
    assertEquals(res.get(grand), Some(Role.View))
    assertEquals(res.get(secret), None)
  }

  test("explain reports which entry granted access, consistent with resolve") {
    import Acl.Source.*
    val chain = List(node(true), node(true, staff -> Role.Edit), node(false, alice -> Role.View))
    assertEquals(Acl.explain(chain, Nil, None, who), Some(Role.Edit -> PageEntry(1, staff)))
    assertEquals(Acl.explain(List(node(true)), List(AclEntry(alice, Role.View)), Some(Role.Edit), who), Some(Role.View -> WorkspaceEntry(alice)))
    assertEquals(Acl.explain(List(node(true)), Nil, Some(Role.View), who), Some(Role.View -> WorkspaceDefault))
    assertEquals(Acl.explain(List(node(false)), Nil, Some(Role.View), who), None)
    // resolve と同じ結果になる
    for c <- List(chain, List(node(true)), List(node(false, alice -> Role.Edit))) do
      assertEquals(Acl.explain(c, Nil, Some(Role.View), who).map(_._1), Acl.resolve(c, Nil, Some(Role.View), who))
  }
