<script lang="scala">
// 使い方（ユーザーマニュアル）へのリンク. to: 章（例: "07-sharing#公開"）
case class Props(to: String, label: String = "")

def href(p: Props): String = p.to.split("#", 2) match {
  case Array(ch, sec) => s"/help/$ch#${scala.scalajs.js.URIUtils.encodeURIComponent(sec)}"
  case _ => s"/help/${p.to}"
}
</script>

<template>
  <a class="help-link" :href="href(props)" target="_blank" rel="noopener" title="この機能の使い方（マニュアル）を開く" data-testid="help-link">？ {{ if (props.label.isEmpty) "使い方" else props.label }}</a>
</template>

<style scoped>
.help-link { font-size: .75rem; color: var(--muted); text-decoration: none; border: 1px solid var(--line); border-radius: 999px; padding: 0 8px; white-space: nowrap; font-weight: 400; }
.help-link:hover { color: var(--accent); border-color: var(--accent); }
</style>
