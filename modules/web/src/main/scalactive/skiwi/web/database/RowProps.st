<script lang="scala">
import skiwi.shared.Titles
import io.circe.Json
import skiwi.web.Dialog

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// データベースの項目（子ページ）の値。ページの上部に出す
case class Props(pageId: String, wid: String)

case class State(data: RV[Option[PageProps]] = RV(None), pop: RV[Option[String]] = RV(None))

def initState() = State()

def load(s: State, props: Props): Unit =
  DbApi.props(props.pageId).onComplete {
    case Success(d) => s.data := Some(d)
    case Failure(_) => s.data := None
  }

def mounted(s: State, props: Props): Unit = load(s, props)

def withValue(d: PageProps, id: String, v: Json): PageProps =
  d.copy(values = if (v.isNull) d.values - id else d.values + (id -> v))

def set(s: State, props: Props, p: DbProp, v: Json): Unit = s.data.v.foreach { d0 =>
  val before = d0.values
  s.data := Some(withValue(d0, p.id, v))
  DbApi.setValues(props.pageId, Map(p.id -> v)).onComplete {
    case Success(r) =>
      // 変えた値だけを反映する（続けて別の値を変えたとき、先の応答で後の変更を戻さないように）
      s.data.v.foreach(d => s.data := Some(withValue(d, p.id, r.values.getOrElse(p.id, Json.Null)).copy(users = d.users ++ r.users)))
    case Failure(e) =>
      s.data.v.foreach(d => s.data := Some(d.copy(values = before)))
      Dialog.alertError("変更できませんでした", e)
  }
}

def ctx(d: PageProps): Ctx = Ctx(d.database.fold(Nil)(_.props), d.targets, d.users)

/** 作成日時など、項目の一覧でだけ分かる列は出さない */
def shownProps(d: PageProps): List[DbProp] =
  d.database.fold(List.empty[DbProp])(_.props).filterNot(p => Set("created_time", "edited_time", "created_by", "edited_by")(p.`type`))

def visible(s: State): Boolean = s.data.v.exists(_.database.exists(_.props.nonEmpty))

def onUser(s: State, id: String, name: String): Unit = s.data.v.foreach(d => s.data := Some(d.copy(users = d.users + (id -> name))))

def dbHref(props: Props, d: PageProps): String = DbDom.pageHref(props.wid, d.database.fold("")(_.pageId))

def dbTitle(d: PageProps): String = Titles.display(d.database.map(_.title))

def value(d: PageProps, p: DbProp): Val = Db.cellValue(p, RowLike(d.values), ctx(d))

def editable(d: PageProps, p: DbProp): Boolean = d.canEdit && !Db.Computed(p.`type`)
</script>

<template>
  <div v-if="visible(s)" class="rowprops" data-testid="row-props">
    <template v-for="d in s.data.v.toList">
      <div class="from">
        <a :href="dbHref(props, d)" @click="DbDom.link(event, dbHref(props, d))">📊 {{ dbTitle(d) }}</a> の項目
      </div>
      <div v-for="p in shownProps(d)" :key="p.id" class="prop" data-testid="row-prop">
        <span class="name">{{ Db.icon(p.`type`) }} {{ p.name }}</span>
        <DbCell class="val" :prop="p" :value="value(d, p)" :users="d.users" :ctx="Some(ctx(d))" :editable="editable(d, p)" :wid="props.wid"
          :onUpdate="v => set(s, props, p, v)" :onUser="(id, n) => onUser(s, id, n)" :pop="Some(s.pop)" :popKey="p.id" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.rowprops { margin: .6rem 0 .2rem; padding: .4rem 0; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); display: grid; grid-template-columns: minmax(7rem, 11rem) 1fr; gap: 2px .8rem; font-size: .85rem; }
.from { grid-column: 1 / -1; font-size: .75rem; color: var(--muted); margin-bottom: .2rem; }
.from a { color: var(--accent); text-decoration: none; }
.prop { display: contents; }
.name { color: var(--muted); padding: 3px 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.val { min-width: 0; }
@media (max-width: 560px) { .rowprops { grid-template-columns: 6.5rem 1fr; } }
</style>
