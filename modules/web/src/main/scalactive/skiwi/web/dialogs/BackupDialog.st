<script lang="scala">
import org.scalajs.dom
import skiwi.web.*
import skiwi.web.parts.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// ワークスペースの定期バックアップ（管理者のみ）. Markdown＋添付の ZIP をオブジェクトストレージに保存する
case class Props(wid: String, name: String, onClose: Unit => Unit)

case class State(enabled: RV[Boolean] = RV(false),
                 hour: RV[Int] = RV(3),
                 keep: RV[Int] = RV(7),
                 configured: RV[Boolean] = RV(false),
                 list: RV[Option[List[Backup]]] = RV(None),
                 storage: RV[Boolean] = RV(true),
                 error: RV[String] = RV(""),
                 saved: RV[Boolean] = RV(false))

def initState() = State()

def setConfig(s: State, c: BackupConfig): Unit = {
  s.enabled := c.enabled
  s.hour := c.hour
  s.keep := c.keep
}

def load(s: State, p: Props): Unit = DialogsApi.backups(p.wid).onComplete {
  case Success(r) =>
    r.config.foreach { c => setConfig(s, c); s.configured := true }
    s.list := Some(r.backups)
    s.storage := r.storage
  case Failure(e) => s.error := e.getMessage
}

// 実行中があれば数秒ごとに読み直す
def mounted(s: State, props: Props): Unit = {
  load(s, props)
  useInterval(3000)(if (s.list.v.exists(_.exists(_.status == "running"))) load(s, props))
  ()
}

def save(s: State, p: Props): Unit = {
  s.error := ""
  DialogsApi.setBackupConfig(p.wid, BackupConfig(s.enabled.v, s.hour.v, s.keep.v)).onComplete {
    case Success(c) =>
      setConfig(s, c)
      s.configured := true
      s.saved := true
      dom.window.setTimeout(() => s.saved := false, 1500)
    case Failure(e) => s.error := e.getMessage
  }
}

def runNow(s: State, p: Props): Unit = {
  s.error := ""
  DialogsApi.runBackup(p.wid).onComplete {
    case Success(_) => load(s, p)
    case Failure(e) => s.error := e.getMessage
  }
}

def remove(s: State, p: Props, b: Backup): Unit =
  Dialog.confirm(s"${Ui.date(b.createdAt)} のバックアップを削除しますか？", okLabel = "削除", danger = true).foreach { ok =>
    if (ok) DialogsApi.removeBackup(b.id).onComplete {
      case Success(_) => load(s, p)
      case Failure(e) => s.error := e.getMessage
    }
  }

def size(n: Option[Double]): String = n.fold("") { x =>
  if (x > 1048576) f"${x / 1048576}%.1f MB" else s"${math.ceil(x / 1024).toLong} KB"
}

def summary(b: Backup): String =
  s"${b.pages.fold("")(_.toString)}ページ・添付${b.attachments.fold("")(_.toString)}件・${size(b.size)}"

def title(p: Props): String = s"💾 定期バックアップ — ${p.name}"
</script>

<template>
  <Modal label="定期バックアップ" :title="title(props)" testid="backups" @close="props.onClose(())">
    <template #head><HelpLink to="admin#定期バックアップ" /></template>
    <p class="note">毎日決まった時刻に、このワークスペースのすべてのページ（権限を絞ったページも）を ZIP にしてオブジェクトストレージに保存します。
      中身は「⤓ 書き出し」→ ZIP と同じ形（Markdown・添付ファイル・ホワイトボード）で、そのまま「⤒ 取り込む」で戻せます。
      ページの親子・本文の JSON・ラベル・データベースの定義と値は <code>skiwi-backup.json</code> に入ります。管理者だけが扱えます。</p>
    <p v-if="!s.storage.v" class="error">オブジェクトストレージ（S3）が設定されていないため、バックアップできません。</p>
    <p v-if="s.error.v.nonEmpty" class="error" data-testid="backup-error">{{ s.error }}</p>

    <form class="cfg" @submit.prevent="save(s, props)">
      <label class="row"><input v-model="s.enabled" type="checkbox" data-testid="backup-enabled"> 毎日バックアップする</label>
      <label class="row">時刻
        <select v-model="s.hour" data-testid="backup-hour"><option v-for="h in (0 until 24).toList" :key="h" :value="h" :selected="s.hour.v == h">{{ h }}:00</option></select>
        ごろ（10分ごとに確認）
      </label>
      <label class="row">残す数 <input v-model="s.keep" type="number" min="1" max="90" data-testid="backup-keep"> 世代（古いものから消します）</label>
      <div class="row">
        <button type="submit" class="primary" data-testid="backup-save">{{ if (s.saved.v) "保存しました" else "保存" }}</button>
        <span class="spacer"></span>
        <button type="button" :disabled="!s.storage.v" data-testid="backup-run" @click="runNow(s, props)">今すぐバックアップ</button>
      </div>
    </form>

    <h3>バックアップ</h3>
    <p v-if="s.list.v.exists(_.isEmpty)" class="muted">まだありません。</p>
    <table v-else-if="s.list.v.isDefined" class="tbl" data-testid="backup-list">
      <tr v-for="b in s.list.v.getOrElse(Nil)" :key="b.id" data-testid="backup-item">
        <td>{{ Ui.date(b.createdAt) }}<span v-if="b.manual" class="tag">手動</span></td>
        <td>
          <span v-if='b.status == "running"' class="muted">作成中…</span>
          <span v-else-if='b.status == "failed"' class="error" :title='b.error.getOrElse("")'>失敗: {{ b.error.getOrElse("") }}</span>
          <span v-else>{{ summary(b) }}</span>
        </td>
        <td class="acts">
          <a v-if='b.status == "done"' :href="DialogsApi.backupDownloadUrl(b.id)" data-testid="backup-download">ダウンロード</a>
          <button v-if='b.status != "running"' class="link danger" @click="remove(s, props, b)">削除</button>
        </td>
      </tr>
    </table>
  </Modal>
</template>

<style scoped>
.x { padding: 0 4px; }
h3 { font-size: .9rem; margin: 1rem 0 .4rem; }
.x { border: none; color: var(--muted); padding: 0 4px; }
.note { line-height: 1.55; }
.cfg { display: flex; flex-direction: column; gap: .45rem; border: 1px solid var(--line); border-radius: 8px; padding: .6rem .7rem; font-size: .85rem; }
.row { display: flex; align-items: center; gap: .4rem; flex-wrap: wrap; }
.spacer { flex: 1; }
select, input[type=number] { font: inherit; padding: .2rem .4rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; width: auto; }
input[type=number] { width: 4.5rem; }
.tbl { width: 100%; border-collapse: collapse; font-size: .82rem; }
.tbl td { padding: .35rem .3rem; border-bottom: 1px solid var(--line); vertical-align: top; }
.acts { white-space: nowrap; text-align: right; }
.acts a { color: var(--accent); margin-right: .5rem; }
.tag { font-size: .68rem; border: 1px solid var(--line); border-radius: 4px; padding: 0 4px; margin-left: .3rem; color: var(--muted); }
.link { border: none; background: none; color: var(--accent); padding: 0; font-size: .8rem; }
.danger:hover { color: var(--danger); }
.error { font-size: .82rem; }
</style>
