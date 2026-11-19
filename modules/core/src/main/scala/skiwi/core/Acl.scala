package skiwi.core

import java.util.UUID
import scala.annotation.tailrec

/**
 * 権限解決（純粋関数）。
 *
 * 規則:
 *  1. 対象ページから根に向かって、自分(ユーザー本人またはその所属グループ)に一致するエントリを持つ最初のページを探す。
 *     そのページでの最大権限が結果になる（下位ページで上書き可能）。
 *  2. 一致がなく、そのページが inherit=false なら、権限なし（継承停止）。
 *  3. 根まで到達したらワークスペースのACL、なければワークスペース既定権限。
 */
object Acl {

  private def best(entries: List[AclEntry], who: Set[Principal]): Option[Role] =
    entries.collect { case AclEntry(p, r) if who.contains(p) => r }.maxOption

  /** chain は対象ページ(先頭)から根(末尾)の順。 */
  def resolve(chain: List[AclNode],
              workspaceAcl: List[AclEntry],
              workspaceDefault: Option[Role],
              who: Set[Principal]): Option[Role] = {
    @tailrec def go(rest: List[AclNode]): Option[Role] = rest match {
      case Nil => best(workspaceAcl, who).orElse(workspaceDefault)
      case n :: tail =>
        best(n.entries, who) match {
          case found @ Some(_) => found
          case None => if n.inherit then go(tail) else None
        }
    }
    go(chain)
  }

  /** 権限の根拠。depth はページの階層（0=対象ページ、1=親…）。 */
  enum Source {
    case PageEntry(
      depth: Int,
      principal: Principal)
    case WorkspaceEntry(
      principal: Principal)
    case WorkspaceDefault
  }

  /** resolve と同じ規則で、権限とその根拠（一致したエントリ）を返す。 */
  def explain(chain: List[AclNode],
              workspaceAcl: List[AclEntry],
              workspaceDefault: Option[Role],
              who: Set[Principal]): Option[(Role, Source)] = {
    def bestEntry(entries: List[AclEntry]): Option[AclEntry] =
      entries.filter(e => who.contains(e.principal)).maxByOption(_.role)(summon[Ordering[Role]])
    @tailrec def go(rest: List[AclNode], depth: Int): Option[(Role, Source)] = rest match {
      case Nil =>
        bestEntry(workspaceAcl).map(e => e.role -> Source.WorkspaceEntry(e.principal))
          .orElse(workspaceDefault.map(_ -> Source.WorkspaceDefault))
      case n :: tail =>
        bestEntry(n.entries) match {
          case Some(e) => Some(e.role -> Source.PageEntry(depth, e.principal))
          case None => if n.inherit then go(tail, depth + 1) else None
        }
    }
    go(chain, 0)
  }

  /** ワークスペース全ページの権限を一括解決する。閲覧できないページは結果に含めない。 */
  def resolveAll(pages: List[Page],
                 nodes: Map[UUID, AclNode],
                 workspaceAcl: List[AclEntry],
                 workspaceDefault: Option[Role],
                 who: Set[Principal]): Map[UUID, Role] = {
    val byId = pages.map(p => p.id -> p).toMap
    val empty = AclNode(inherit = true, Nil)

    def chainOf(p: Page): List[AclNode] = {
      @tailrec def up(cur: Option[Page], acc: List[AclNode], seen: Set[UUID]): List[AclNode] =
        cur match {
          case Some(pg) if !seen(pg.id) =>
            up(pg.parentId.flatMap(byId.get), nodes.getOrElse(pg.id, empty) :: acc, seen + pg.id)
          case _ => acc.reverse
        }
      up(Some(p), Nil, Set.empty)
    }

    pages.flatMap { p =>
      resolve(chainOf(p), workspaceAcl, workspaceDefault, who).map(p.id -> _)
    }.toMap
  }
}
