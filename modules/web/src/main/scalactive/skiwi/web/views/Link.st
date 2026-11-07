<script lang="scala">
import skiwi.web.Host

// vue-router の router-link に当たるリンク（クリックで画面を移動し、ページは読み込み直さない）
case class Props(to: String, slot: Slot = Slot.empty)

def go(props: Props, e: org.scalajs.dom.MouseEvent): Unit = {
  // Ctrl/⌘クリックなどは、ブラウザに任せて新しいタブで開く
  if (!(e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) && e.button == 0) {
    e.preventDefault()
    Host.navigate(props.to)
  }
}
</script>

<template>
  <a :href="props.to" @click="go(props, event.asInstanceOf[org.scalajs.dom.MouseEvent])"><slot /></a>
</template>
