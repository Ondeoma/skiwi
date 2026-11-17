<script lang="scala">
// ページのアイコン（左メニューの一覧など）. 利用者が付けた絵文字があればそれ、なければ線の SVG アイコン（文書・データベース）
case class Props(icon: Option[String] = None, database: Boolean = false)

def cls(p: Props): String = if (p.database) "ico ico-database page-ico" else "ico ico-page page-ico"
</script>

<template>
  <template v-if="props.icon.isDefined">{{ props.icon.getOrElse("") }}</template>
  <span v-else :class="cls(props)" aria-hidden="true"></span>
</template>

<style scoped>
.page-ico { color: var(--muted); }
</style>
