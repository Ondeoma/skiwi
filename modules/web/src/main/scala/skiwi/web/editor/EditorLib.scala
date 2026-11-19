package skiwi.web.editor

import io.circe.Json
import org.scalajs.dom
import skiwi.web.content.{Bytes, ContentApi}
import skiwi.web.views.{GuestApi, GuestPage}
import skiwi.web.{Api, Nav, Palette, Ui}

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.typedarray.Uint8Array

/**
 * エディタ（frontend/src/editor の JS. Tiptap・ProseMirror・Yjs・Excalidraw を直接使う部分）から使う、ライブラリに依らない処理.
 * window.__skiwiLib に置く（型は frontend/src/lib.ts）. 画面の移動・ダイアログ・ページの一覧は Host（window.__skiwiHost）.
 */
object EditorLib {

  private def toJs(j: Json): js.Any = if (j.isNull) null else js.JSON.parse(j.noSpaces)

  private def opt(v: js.Any): Option[js.Any] = if (js.isUndefined(v) || v == null) None else Some(v)

  private def str(o: js.Dynamic, key: String): Option[String] =
    if (js.isUndefined(o) || o == null) None else opt(o.selectDynamic(key)).map(_.toString)

  private def files(a: js.Array[dom.File]): List[dom.File] = a.toList

  def exposeToJs(): Unit = {
    val lib = js.Dynamic.literal(
      // ---- 色・絵文字
      colors = Palette.Colors.map(c => js.Dynamic.literal(id = c.id, label = c.label): js.Any).toJSArray,
      isColor = (v: js.Any) => Palette.isColor(v),
      findEmoji = (q: String) =>
        Emoji.find(q).map(e => js.Dynamic.literal(emoji = e.emoji, name = e.name, keys = e.keys): js.Any).toJSArray,

      // ---- URL の検査・埋め込み
      safeLinkUrl = (u: js.Any) => SafeUrl.link(u).orNull,
      safeEmbedUrl = (u: js.Any) => SafeUrl.embed(u).orNull,
      isAttachmentPath = (u: js.Any) => SafeUrl.isAttachmentPath(u),
      fetchAttachment = (u: js.Any) => SafeUrl.fetchAttachment(u).toJSPromise,
      knownEmbedUrl = (raw: String) => EmbedUrl.known(raw).orNull,
      toEmbedUrl = (raw: String) => EmbedUrl.toEmbed(raw).orNull,

      // ---- ページへのリンク・ゲスト表示・同期ブロック
      parsePageUrl = (raw: String) =>
        PageLinks.parse(raw).fold[js.Any](null) { p =>
          val o = js.Dynamic.literal(workspaceId = p.workspaceId, pageId = p.pageId)
          p.blockId.foreach(o.blockId = _)
          p.anchor.foreach(o.anchor = _)
          o
        },
      pageHref = (wid: String, pid: String) => Nav.pageHref(wid, pid),
      guestHref = (token: String, p: js.Dynamic) =>
        GuestApi.guestHref(token, GuestPage(id = str(p, "id").getOrElse(""), slug = str(p, "slug"))),
      pageInfo = (pid: String) => {
        val i = PageLinks.pageInfo(pid)
        js.Dynamic.literal(state = i.state, title = i.title, icon = i.icon.orNull, workspaceId = i.workspaceId)
      },
      guestContext = () => PageLinks.guestToJs,
      onLinksChange = (f: js.Function0[Unit]) => (PageLinks.onChange(f): js.Function0[Unit]),
      fetchSyncedSource = (pid: String, token: String) =>
        PageLinks.syncedSource(pid, Option(token)).map(_.fold[js.Any](null)(s =>
          js.Dynamic.literal(title = s.title, content = toJs(s.content))
        )).toJSPromise,
      onSyncedRefresh = (f: js.Function0[Unit]) => (PageLinks.onSyncedRefresh(f): js.Function0[Unit]),
      findSyncedBlocks = (doc: js.Any) => PageLinks.syncedBlocks(doc),

      // ---- 外部サービス（skea のタスク・skawau のファイル）の表示
      openNewTab = (url: String) => Linked.openNewTab(url),
      skawauPatterns = (base: String) => Linked.skawauPatterns(base),
      skawauTitle = (connected: Boolean, file: js.Any) => Linked.skawauTitle(connected, file),
      skawauChip = (url: String, file: js.Dynamic) => Linked.skawauChip(url, file),
      skeaPattern = (keys: js.Array[String]) => Linked.skeaPattern(keys),
      skeaTitle = (connected: Boolean, task: js.Any) => Linked.skeaTitle(connected, task),
      skeaChip = (url: String, task: js.Any) => Linked.skeaChip(url, task),

      // ---- draw.io
      drawioUrl = () => Drawio.url().toJSPromise,
      drawioEditorSrc = (base: String) => Drawio.editorSrc(base),
      drawioLabels = (xml: String) => Drawio.labelsOf(xml).toJSPromise,
      drawioTitle = (xml: String) => Drawio.titleOf(xml).orNull,
      drawioFileName = (title: js.Any) => Drawio.fileName(opt(title).map(_.toString)),
      dataUrlToFile = (dataUrl: String, name: String) => Drawio.dataUrlToFile(dataUrl, name),
      urlToDataUrl = (url: String) => Drawio.urlToDataUrl(url).toJSPromise,

      // ---- 添付・API
      uploadFiles = (pageId: String, fs: js.Array[dom.File]) => Uploads.uploadAll(pageId, files(fs)).toJSPromise,
      uploadAttachment =
        (pageId: String, f: dom.File) => ContentApi.uploadAttachment(pageId, f).map(Uploads.toJs).toJSPromise,
      pageContent = (id: String) => ContentApi.pageContent(id).map(_.fold[js.Any](null)(toJs)).toJSPromise,
      searchUsers = (q: String, limit: Int) =>
        Api.directory(q).map(_.filter(_.principalType == "user").take(limit)
          .map(d => js.Dynamic.literal(id = d.id, name = d.name, detail = d.detail.orNull): js.Any).toJSArray).recover {
          case _ => js.Array[js.Any]()
        }.toJSPromise,
      unfurl = (url: String) =>
        Api.request(dom.HttpMethod.GET, s"/api/unfurl${Api.query("url" -> Some(url))}", redirectOn401 = false)
          .map(_.fold[js.Any](null)(toJs)).recover { case _ => null }.toJSPromise,

      // ---- 画面の部品
      pickFiles = (accept: String, multiple: js.UndefOr[Boolean]) =>
        EditorDom.pickFiles(accept, multiple.getOrElse(false)).map(_.toJSArray).toJSPromise,
      blockCategory = (title: String) => Blocks.categoryOf(title),
      filterBlocks = (items: js.Array[js.Dynamic], q: String) => Blocks.filter(items, q),
      openBlockPicker =
        (items: js.Array[js.Dynamic], q: String) => Blocks.openPicker(items, q).map(_.orNull).toJSPromise,
      openIconPicker = (parent: dom.Element, o: js.Dynamic) => {
        val at = opt(o.at).map(a =>
          (a.asInstanceOf[js.Dynamic].x.asInstanceOf[Double], a.asInstanceOf[js.Dynamic].y.asInstanceOf[Double])
        )
        val close = Blocks.openIconPicker(
          parent,
          icon => o.onSelect(icon.orNull),
          () => o.onClose(),
          opt(o.head).map(_.asInstanceOf[dom.Element]),
          !opt(o.allowNone).contains(false),
          str(o, "noneLabel"),
          at
        )
        (() => close()): js.Function0[Unit]
      },
      onDismiss = (inside: js.Function1[dom.Node, Boolean], close: js.Function0[Unit], o: js.UndefOr[js.Dynamic]) => {
        val escape = o.toOption.flatMap(x => opt(x.escape)).map(f => () => { f.asInstanceOf[js.Function0[Unit]](); () })
        val defer = o.toOption.exists(x => opt(x.defer).contains(true))
        val stop = EditorDom.onDismiss(n => inside(n), () => close(), escape, defer)
        (() => stop()): js.Function0[Unit]
      },
      copyWithFlash = (btn: dom.HTMLElement, text: String, label: String, done: js.UndefOr[String]) =>
        EditorDom.copyWithFlash(btn, text, label, done.getOrElse("コピーしました")).toJSPromise,
      selectArticleOnModA = (el: js.Function0[dom.HTMLElement]) => {
        val stop = EditorDom.selectArticleOnModA(() => el())
        (() => stop()): js.Function0[Unit]
      },
      drawStatus =
        (el: dom.HTMLElement, revoked: Boolean, state: String, synced: Boolean, canEdit: Boolean, uploading: Int) =>
          EditorDom.drawStatus(el, revoked, state, synced, canEdit, uploading),
      people = (states: js.Any, self: Double) => Presence.people(states, self),
      isDark = () => EditorDom.isDark,
      onThemeChange = (f: js.Function1[Boolean, Unit]) => {
        val stop = EditorDom.onThemeChange(d => f(d))
        (() => stop()): js.Function0[Unit]
      },
      download = (filename: String, blob: dom.Blob) => Ui.download(filename.replaceAll("[\\\\/:*?\"<>|]", "_"), blob),
      ymd = (d: js.Date) => skiwi.web.database.Formula.ymd(d),
      toBase64 = (u: Uint8Array) => Bytes.toBase64(u),
      blobToDataUrl = (b: dom.Blob) => Bytes.dataUrl(b).toJSPromise,
    )
    dom.window.asInstanceOf[js.Dynamic].__skiwiLib = lib
  }

}
