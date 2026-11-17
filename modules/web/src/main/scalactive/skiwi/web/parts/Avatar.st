<script lang="scala">
// ユーザーのアイコン. 画像がない・読めないときは、名前の1文字目を色付きの丸で出す.
case class Props(name: String = "", url: Option[String] = None, size: Int = 24, color: Option[String] = None, testid: String = "")

case class State(failed: RV[Boolean] = RV(false))

def initState() = State()

val Colors = List("#2563eb", "#16a34a", "#d97706", "#dc2626", "#7c3aed", "#0891b2", "#db2777", "#4b5563")

/** 画像がないときの丸の色（名前から決まる） */
def colorOf(name: String): String = {
  var h = 0L
  var i = 0
  while (i < name.length) {
    val cp = name.codePointAt(i)
    h = (h * 31 + cp) & 0xffffffffL
    i += Character.charCount(cp)
  }
  Colors((h % Colors.size).toInt)
}

def initial(p: Props): String = { val t = p.name.trim; if (t.isEmpty) "?" else t.substring(0, t.offsetByCodePoints(0, 1)) }

def showImg(s: State, p: Props) = s.failed.mapC(f => p.url.nonEmpty && !f)

def style(s: State, p: Props) = s.failed.mapC { f =>
  val bg = if (p.url.nonEmpty && !f) "transparent" else p.color.getOrElse(colorOf(p.name))
  s"width: ${p.size}px; height: ${p.size}px; font-size: ${math.round(p.size * 0.46)}px; background: $bg"
}
</script>

<template>
  <span class="user-avatar" :style="style(s, props)" aria-hidden="true" :data-testid="props.testid">
    <img v-if="showImg(s, props)" :src='props.url.getOrElse("")' alt="" @error="s.failed := true">
    <template v-else>{{ initial(props) }}</template>
  </span>
</template>

<style scoped>
.user-avatar { flex: none; display: inline-grid; place-items: center; border-radius: 50%; overflow: hidden; color: #fff; font-weight: 700; line-height: 1; vertical-align: middle; }
.user-avatar img { width: 100%; height: 100%; object-fit: cover; display: block; }
</style>
