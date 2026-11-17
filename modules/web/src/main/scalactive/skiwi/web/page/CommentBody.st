<script lang="scala">
// コメント本文の表示. @[名前](ID) をメンションの印にする（HTMLとしては解釈しない）.
case class Props(body: String)

/** 本文の区切り（mention: メンションの名前） */
case class Part(mention: Boolean, v: String)

val Mark = """@\[([^\]]*)\]\([0-9a-fA-F-]{36}\)""".r

def parts(body: String): List[Part] = {
  val out = List.newBuilder[Part]
  var last = 0
  Mark.findAllMatchIn(body).foreach { m =>
    if (m.start > last) out += Part(false, body.substring(last, m.start))
    out += Part(true, m.group(1))
    last = m.end
  }
  if (last < body.length) out += Part(false, body.substring(last))
  out.result()
}
</script>

<template>
  <div class="cbody"><template v-for="p in parts(props.body)"><span v-if="p.mention" class="cm">@{{ p.v }}</span><template v-else>{{ p.v }}</template></template></div>
</template>

<style scoped>
.cbody { white-space: pre-wrap; word-break: break-word; font-size: .88rem; line-height: 1.55; }
.cm { color: var(--accent); background: rgba(37,99,235,.10); border-radius: 4px; padding: 0 .15em; font-weight: 500; }
</style>
