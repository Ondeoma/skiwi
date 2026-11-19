package skiwi.web.content

import io.circe.*
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.web.{Api, Dialog, Nav, Progress, Store}
import skiwi.web.database.{Db, DbApi, DbOption, DbProp, DbView, Formula}
import skiwi.web.editor.Drawio
import skiwi.web.model.Page

import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/** Markdown / HTML / Word / ZIP（Notion・Confluence の書き出しも）の取り込み（旧 ui/importer.ts・editor/markdown.ts の prepareImport） */
object Importer {

  import Dom.*

  private val TextExt = "(?i)\\.(md|markdown|txt|html?)$".r
  private def isText(
    path: String
  ) = TextExt.findFirstIn(path).isDefined
  private def stripTextExt(
    path: String
  ) = TextExt.replaceFirstIn(path, "")

  private val ImageExt = Map(
    "png" -> "image/png",
    "jpg" -> "image/jpeg",
    "jpeg" -> "image/jpeg",
    "gif" -> "image/gif",
    "webp" -> "image/webp",
    "avif" -> "image/avif"
  )
  private val MaxFiles = 500
  private val MaxTotal = 100 * 1024 * 1024

  private val Accept =
    ".md,.markdown,.txt,.html,.htm,.zip,.docx,.csv,text/markdown,text/html,text/plain,text/csv,application/zip," +
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

  /** 進み具合（画面の右下に出す） */
  private def showProgress(
    p: Option[Progress]
  ): Unit = Store.progress := p
  private[content] def stepProgress(): Unit =
    Store.progress.v.foreach(p => Store.progress := Some(p.copy(done = p.done + 1)))

  private[content] def naturalSort(
    a: String,
    b: String
  ): Int =
    a.asInstanceOf[js.Dynamic].localeCompare(b, "ja", js.Dynamic.literal(numeric = true)).asInstanceOf[Double].toInt

  private[content] def dirOf(
    path: String
  ): String = path.split("/", -1).dropRight(1).mkString("/")

  private def resolvePath(
    base: String,
    rel: String
  ): String = {
    val parts = mutable.ArrayBuffer.from(if (base.nonEmpty) base.split("/", -1).toList else Nil)
    js.URIUtils.decodeURIComponent(rel.split("[?#]", -1)(0)).split("/", -1).foreach {
      case "" | "." =>
      case ".." => if (parts.nonEmpty) parts.remove(parts.length - 1)
      case seg => parts += seg
    }
    parts.mkString("/")
  }

  /** 順に実行する（前の結果を待ってから次へ） */
  private[content] def sequentially[A](
    xs: Seq[A]
  )(
    f: A => Future[Unit]
  ): Future[Unit] =
    xs.foldLeft(Future.successful(()))(
      (
        acc,
        x
      ) => acc.flatMap(_ => f(x))
    )

  // ---------------------------------------------------------------- 取り込む文書を整える（Markdown / HTML → エディタ用HTML）

  /** 取り込んだ文書を、エディタが解釈できるHTMLに整える. 先頭の h1 はページタイトルとして取り出す */
  def prepareImport(
    text: String,
    filename: String
  ): Future[(String, String)] = {
    val isHtml = "(?i)\\.html?$".r.findFirstIn(filename).isDefined ||
      "(?i)^\\s*<(!doctype|html)".r.findFirstIn(text).isDefined
    (if (isHtml) Future.successful(text) else Io.markdownToHtml(text)).map { rawHtml =>
      val doc = parse(rawHtml)
      val root = doc.body

      // 実行されるもの・外部を読み込むものは取り除く
      all(root, "script, style, link, meta, iframe, object, embed, form").foreach(remove)
      all(root, "*").foreach { e =>
        val names = (0 until e.attributes.length).map(e.attributes(_).name).toList
        names.filter(_.toLowerCase.startsWith("on")).foreach(e.removeAttribute)
      }

      // ```mermaid → Mermaid図、$$…$$ → 数式ブロック
      all(root, "pre > code").foreach { code =>
        val classes = (0 until code.classList.length).map(code.classList(_)).toList
        if (classes.find(_.startsWith("language-")).map(_.drop(9)).contains("mermaid")) {
          val div = doc.createElement("div")
          div.setAttribute("data-mermaid", "")
          div.textContent = Option(code.textContent).getOrElse("").stripSuffix("\n")
          replaceWith(code.parentNode.asInstanceOf[dom.Element], div)
        }
      }
      val MathRe = "^\\s*\\$\\$([\\s\\S]+)\\$\\$\\s*$".r
      all(root, "p").foreach { p =>
        MathRe.findFirstMatchIn(Option(p.textContent).getOrElse("")).filter(_ => p.children.length == 0).foreach { m =>
          val div = doc.createElement("div")
          div.setAttribute("data-type", "block-math")
          div.setAttribute("data-latex", JsLike.trim(m.group(1)))
          replaceWith(p, div)
        }
      }

      // - [ ] / - [x] → ToDoリスト
      all(root, "ul").foreach { ul =>
        val items = children(ul).filter(_.tagName == "LI")
        if (
          items.nonEmpty &&
          items.forall(li => first(li, ":scope > input[type=checkbox], :scope > p > input[type=checkbox]").isDefined)
        ) {
          ul.setAttribute("data-type", "taskList")
          items.foreach { li =>
            val box = first(li, "input[type=checkbox]").get
            li.setAttribute("data-type", "taskItem")
            li.setAttribute("data-checked", if (box.hasAttribute("checked")) "true" else "false")
            remove(box)
            if (first(li, ":scope > p").isEmpty) {
              val p = doc.createElement("p")
              def isList(
                n: dom.Node
              ) = n match {
                case e: dom.HTMLElement => e.tagName == "UL" || e.tagName == "OL"
                case _ => false
              }
              while (li.firstChild != null && !isList(li.firstChild)) p.appendChild(li.firstChild)
              li.asInstanceOf[js.Dynamic].prepend(p)
            }
          }
        }
      }

      var title = ""
      first(root, "h1").foreach { h1 =>
        if (root.firstElementChild == h1) { title = Dom.text(h1); remove(h1) }
      }
      if (title.isEmpty && isHtml) title = JsLike.trim(doc.title)
      if (title.isEmpty) title = "(?i)\\.(md|markdown|txt|html?)$".r.replaceFirstIn(filename, "")
      (title, root.innerHTML)
    }
  }

  // ---------------------------------------------------------------- ファイルを読む

  /** path: ページの親子を決めるパス（Notion の ID を外す・Confluence の階層に並べ直したもの）、orig: ファイルの元のパス（リンク・画像の解決用） */
  private final case class Entry(
    path: String,
    text: String,
    orig: Option[String] = None) {
    def source: String = orig.getOrElse(path)
  }

  private final class Node(
    val name: String,
    val path: String) {
    var entry: Option[Entry] = None
    val children: mutable.LinkedHashMap[String, Node] = mutable.LinkedHashMap.empty
    var page: Option[Page] = None
    def sortedChildren: List[Node] = children.values.toList.sortWith(
      (
        a,
        b
      ) => naturalSort(a.name, b.name) < 0
    )
  }

  private final case class Bin(
    data: Uint8Array,
    mime: String)

  private final case class Read(
    texts: List[Entry],
    images: Map[String, Bin],
    csvs: List[Entry])

  /** ファイル（複数・ZIP）を読み、本文ファイルと画像に分ける. Word（.docx）は HTML にし、CSV（Notion のデータベース）は別に分ける */
  private def readFiles(
    files: List[dom.File]
  ): Future[Read] = {
    val texts = mutable.ArrayBuffer.empty[Entry]
    val csvs = mutable.ArrayBuffer.empty[Entry]
    val images = mutable.Map.empty[String, Bin]
    var total = 0
    def add(
      path: String,
      data: Uint8Array,
      depth: Int
    ): Future[Unit] =
      if ("(^|/)(__MACOSX|\\.)".r.findFirstIn(path).isDefined) Future.successful(())
      else {
        total += data.length
        if (total > MaxTotal) Future.failed(new Exception("合計 100MB までです"))
        else {
          val ext = if (path.contains(".")) path.split("\\.", -1).last.toLowerCase else path.toLowerCase
          if (ext == "zip" && depth < 2) {
            // Notion の書き出しは ZIP の中に ZIP が入っていることがある
            Io.unzip(data).flatMap(es =>
              sequentially(es.filterNot(_._1.endsWith("/")))(
                (
                  p,
                  d
                ) => add(p, d, depth + 1)
              )
            )
          } else if (isText(path)) { texts += Entry(path, Db.decodeText(data)); Future.successful(()) }
          else if (ext == "csv") { csvs += Entry(path, Db.decodeText(data)); Future.successful(()) }
          else if (ext == "docx")
            Io.docxToHtml(data).map(h => texts += Entry("(?i)\\.docx$".r.replaceFirstIn(path, ".html"), h))
          else {
            val mime = ImageExt.getOrElse(
              ext,
              if (ext == "pdf") "application/pdf"
              else if (ext == "excalidraw") "application/json" else "application/octet-stream"
            )
            images(path) = Bin(data, mime)
            Future.successful(())
          }
        }
      }
    sequentially(files)(f => Bytes.read(f).flatMap(add(f.name, _, 0))).flatMap { _ =>
      if (texts.length > MaxFiles) Future.failed(new Exception(s"一度に取り込めるのは $MaxFiles ページまでです"))
      else Future.successful(Read(texts.toList, images.toMap, csvs.toList))
    }
  }

  // ---------------------------------------------------------------- Notion・Confluence の書き出しを整える

  /** Notion の書き出しのファイル名の末尾の ID（「ページ名 0123…cdef.md」） */
  private val NotionId = "(?i) [0-9a-f]{32}(?=$|\\.[a-z]+$)".r

  private def isNotion(
    texts: List[Entry],
    csvs: List[Entry]
  ): Boolean = (texts ++ csvs).count(e => e.path.split("/", -1).exists(s => NotionId.findFirstIn(s).isDefined)) >=
    math.max(1.0, (texts.size + csvs.size) / 2.0)

  private def stripNotion(
    path: String
  ): String = path.split("/", -1).map(s => NotionId.replaceFirstIn(s, "")).mkString("/")

  private val IndexRe = "(?i)(^|/)index\\.html?$".r
  private def isIndex(
    e: Entry
  ) = IndexRe.findFirstIn(e.path).isDefined
  private def hasMain(
    e: Entry
  ) = e.text.contains("id=\"main-content\"")

  /** Confluence の HTML 書き出し（index.html と「ページ名_数字.html」、attachments/） */
  private def isConfluence(
    texts: List[Entry]
  ): Boolean =
    texts.exists(e =>
      isIndex(e) && "id=\"(main-content|breadcrumb-section)\"|class=\"pageSection".r.findFirstIn(e.text).isDefined
        || "(?i)Confluence".r.findFirstIn(e.text.take(3000)).isDefined
    ) && texts.exists(hasMain)

  /** Confluence のページ: タイトルと本文（#main-content）だけにする */
  private def cleanConfluence(
    html: String
  ): String = {
    val doc = parse(html)
    var title = first(doc, "#title-text").map(text).getOrElse(JsLike.trim(doc.title))
    title = "^.*?\\s:\\s".r.replaceFirstIn(title, "") // 「スペース名 : ページ名」
    val main = first(doc, "#main-content").getOrElse(doc.body)
    all(main, ".confluence-information-macro-icon, .expand-control-icon, script, style").foreach(remove)
    s"<h1>${title.replace("<", "&lt;")}</h1>${main.innerHTML}"
  }

  /** Confluence の index.html の入れ子のリストから、ページの親子（ファイル → 親のファイル）を読む */
  private def confluenceParents(
    index: String,
    base: String
  ): Map[String, Option[String]] = {
    val doc = parse(index)
    val out = mutable.Map.empty[String, Option[String]]
    def walkUl(
      ul: dom.Element,
      parent: Option[String]
    ): Unit =
      children(ul).filter(_.tagName == "LI").foreach { li =>
        val href = first(li, ":scope > a[href]").map(attr(_, "href")).getOrElse("")
        val path =
          if (href.nonEmpty && "(?i)^[a-z]+:".r.findFirstIn(href).isEmpty) Some(resolvePath(base, href)) else None
        path.foreach(p => out(p) = parent)
        children(li).filter(_.tagName == "UL").foreach(walkUl(_, path.orElse(parent)))
      }
    first(doc, ".pageSection ul, #main-content ul, ul").foreach(walkUl(_, None))
    out.toMap
  }

  /** 取り込むファイルの並びを整える（Notion: ID を外す、Confluence: 親子の階層に並べ直す）. kind: notion / confluence */
  private def normalize(
    texts: List[Entry],
    csvs: List[Entry]
  ): (List[Entry], List[Entry], Option[String]) =
    if (isNotion(texts, csvs)) {
      def fix(
        e: Entry
      ) = e.copy(orig = Some(e.path), path = stripNotion(e.path))
      // 「X.csv」と「X_all.csv」がある場合は _all（非表示の列も含む）を使う
      val AllRe = "(?i)_all\\.csv$".r
      val allSet =
        csvs.filter(c => AllRe.findFirstIn(c.path).isDefined).map(c => AllRe.replaceFirstIn(c.path, ".csv")).toSet
      (
        texts.map(fix),
        csvs.filterNot(c => allSet(c.path)).map(c =>
          fix(c).copy(path = AllRe.replaceFirstIn(stripNotion(c.path), ".csv"))
        ),
        Some("notion")
      )
    } else if (isConfluence(texts)) {
      val index = texts.find(isIndex)
      val parents = index.fold(Map.empty[String, Option[String]])(i => confluenceParents(i.text, dirOf(i.path)))
      val pages = texts.filter(e => !index.contains(e) && hasMain(e))
      val nameOf = pages.map(e => e.path -> "(?i)\\.html?$".r.replaceFirstIn(e.path.split("/", -1).last, "")).toMap
      def virtual(
        path: String,
        seen: mutable.Set[String]
      ): String = {
        val name = nameOf.getOrElse(path, path)
        parents.get(path).flatten match {
          case Some(parent) if !seen(parent) && nameOf.contains(parent) =>
            seen += path
            s"${virtual(parent, seen)}/$name"
          case _ => name
        }
      }
      (
        pages.map(e => Entry(s"${virtual(e.path, mutable.Set.empty)}.html", cleanConfluence(e.text), Some(e.path))),
        Nil,
        Some("confluence")
      )
    } else (texts, csvs, None)

  /** パスから親子関係を作る.「A.md」と「A/」、「A/index.md」（README.md）は同じページとして扱う */
  private def buildTree(
    texts: List[Entry]
  ): Node = {
    val root = new Node("", "")
    texts.foreach { e =>
      val segs = e.path.split("/", -1).toList
      var cur = root
      segs.dropRight(1).foreach { s =>
        cur = cur.children.getOrElseUpdate(s, new Node(s, if (cur.path.nonEmpty) s"${cur.path}/$s" else s))
      }
      val base = stripTextExt(segs.last).stripSuffix(".__db__")
      if ("(?i)^(index|readme)$".r.findFirstIn(base).isDefined && (cur ne root)) cur.entry = Some(e)
      else {
        val node = cur.children.getOrElse(base, new Node(base, if (cur.path.nonEmpty) s"${cur.path}/$base" else base))
        node.entry = Some(e)
        cur.children(base) = node
      }
    }
    root
  }

  private def walk(
    n: Node
  ): List[Node] = n.children.values.toList.flatMap(c => c :: walk(c))

  private def findNode(
    tree: Node,
    path: String
  ): Option[Node] =
    path.split("/", -1).foldLeft(Option(tree))(
      (
        cur,
        s
      ) => cur.flatMap(_.children.get(s))
    )

  private def isDb(
    n: Node
  ) = n.entry.exists(_.path.endsWith(".__db__"))

  // ---------------------------------------------------------------- 取り込み

  /**
   * Markdown / HTML（ConfluenceのHTML書き出しなど）を取り込む. 複数ファイル・ZIP（フォルダ構成をページの親子にする）に対応.
   * parentId を省くとワークスペース直下. 画像は添付ファイルとしてアップロードし、ファイル間のリンクはページへのリンクにする.
   */
  def importPage(
    workspaceId: String,
    parentId: Option[String]
  ): Future[Unit] =
    Db.pickFiles(Accept, multiple = true).flatMap { files =>
      if (files.isEmpty) Future.successful(())
      else {
        val created = mutable.ArrayBuffer.empty[(Node, String)]
        run(workspaceId, parentId, files, created).recoverWith { case e =>
          val reload =
            if (Store.currentId.v.contains(workspaceId)) Store.reloadPages().recover(_ => ()) else Future.successful(())
          reload.flatMap(_ =>
            Dialog.alert(s"取り込めませんでした: ${Bytes.message(e)}${if (created.nonEmpty) "\n（途中まで作成したページは残っています）" else ""}")
          )
        }.andThen(_ => showProgress(None))
      }
    }

  private def run(
    workspaceId: String,
    parentId: Option[String],
    files: List[dom.File],
    created: mutable.ArrayBuffer[(Node, String)]
  ): Future[Unit] =
    readFiles(files).flatMap { read =>
      val (texts, csvs, kind) = normalize(read.texts, read.csvs)
      val images = read.images
      if (texts.isEmpty && csvs.isEmpty) Dialog.alert("取り込める Markdown / HTML / Word / CSV ファイルがありませんでした")
      else {
        // CSV（Notion のデータベース）は、同じ名前のフォルダのページにする（フォルダがなければページだけ作る）
        val dbEntries =
          csvs.filterNot(c => texts.exists(t => stripTextExt(t.path) == "(?i)\\.csv$".r.replaceFirstIn(c.path, "")))
            .map(c => c.copy(path = "(?i)\\.csv$".r.replaceFirstIn(c.path, ".__db__")))
        val tree = buildTree(texts ++ dbEntries)
        showProgress(Some(Progress("ページを作成中", 0, texts.size + walk(tree).count(_.entry.isEmpty))))

        // 1) ページを作る（親から順に. 同じ階層は名前順）
        val byPath = mutable.LinkedHashMap.empty[String, Node]
        def create(
          node: Node,
          parent: Option[String]
        ): Future[Unit] = sequentially(node.sortedChildren) { child =>
          val prepared = child.entry.filterNot(_ => isDb(child)) match {
            case Some(e) => prepareImport(e.text, e.path.split("/", -1).last).map(Some(_))
            case None => Future.successful(None)
          }
          prepared.flatMap { pr =>
            val title = pr.map(_._1).filter(_.nonEmpty).getOrElse(child.name)
            Api.createPage(workspaceId, parent, title).flatMap { page =>
              child.page = Some(page)
              for (e <- child.entry; (_, html) <- pr) { byPath(e.source) = child; created += ((child, html)) }
              stepProgress()
              create(child, Some(page.id))
            }
          }
        }

        create(tree, parentId).flatMap { _ =>
          // 2) 本文を入れる（画像のアップロード、ファイル間リンクの付け替え）
          showProgress(Some(Progress("本文を取り込み中", 0, created.size)))
          sequentially(created.toList)(
            (
              node,
              html
            ) => fillContent(workspaceId, node, html, images, byPath).map(_ => stepProgress())
          )
        }.flatMap { _ =>
          // 3) CSV（Notion のデータベース）: 同じ名前のページをデータベースにし、列と値を入れる
          sequentially(csvs) { c =>
            findNode(
              tree,
              "(?i)\\.csv$".r.replaceFirstIn(c.path, "")
            ).filter(_.page.isDefined).fold(Future.successful(()))(importCsvDatabase(_, c.text))
          }
        }.flatMap { _ =>
          if (Store.currentId.v.contains(workspaceId)) Store.reloadPages() else Future.successful(())
        }.flatMap { _ =>
          tree.sortedChildren.headOption.flatMap(_.page).foreach(p => Nav.go(s"/w/$workspaceId/p/${p.id}"))
          val from = kind match {
            case Some("notion") => "（Notion の書き出し）"
            case Some("confluence") => "（Confluence の書き出し）"
            case _ => ""
          }
          if (texts.size > 1 || csvs.nonEmpty)
            Dialog.alert(s"${texts.size} ページ${if (csvs.nonEmpty) s"・${csvs.size} データベース" else ""}を取り込みました。$from")
          else Future.successful(())
        }
      }
    }

  private val MxRe = "<mx(file|GraphModel)".r
  private def isMx(
    s: String
  ) = MxRe.findFirstIn(s).isDefined

  /** draw.io の図のブロック（Drawio ノードの parseHTML の形） */
  private def drawioFigure(
    doc: dom.Document,
    src: Option[String],
    xmlSrc: Option[String],
    title: String,
    text: String
  ): dom.Element = {
    val fig = doc.createElement("figure")
    fig.setAttribute("data-drawio", "")
    fig.setAttribute("data-title", title)
    fig.setAttribute("data-text", text)
    xmlSrc.foreach(fig.setAttribute("data-xml-src", _))
    val img = doc.createElement("img")
    img.setAttribute("src", src.getOrElse(""))
    fig.appendChild(img)
    fig
  }

  /** リンクだけの段落なら段落ごと、そうでなければリンクを置き換える */
  private def linkHost(
    link: dom.Element
  ): dom.Element = {
    val p = link.asInstanceOf[js.Dynamic].parentElement.asInstanceOf[dom.Element]
    if (p != null && p.tagName == "P" && text(p) == text(link)) p else link
  }

  private def upload(
    page: Page,
    data: Uint8Array,
    name: String,
    mime: String
  ): Future[Attachment] =
    ContentApi.uploadAttachment(page.id, Bytes.file(data, name, mime))

  private val DataUrlRe = "(?i)^data:(image/[\\w.+-]+);base64,(.+)$".r

  /** 本文を入れる: 画像のアップロード、ファイル間リンクの付け替え */
  private def fillContent(
    workspaceId: String,
    node: Node,
    html: String,
    images: Map[String, Bin],
    byPath: mutable.LinkedHashMap[String, Node]
  ): Future[Unit] = {
    val page = node.page.get
    val dir = dirOf(node.entry.get.source)
    val doc = parse(html)

    def image(
      img: dom.Element
    ): Future[Unit] = {
      val src = attr(img, "src")
      DataUrlRe.findFirstMatchIn(src) match {
        case Some(m) =>
          // 埋め込まれた画像（Word など）はアップロードする
          val sub = JsLike.replaceFirst(m.group(1).split("/", -1)(1), "jpeg", "jpg").replaceAll("\\+.*", "")
          upload(page, Bytes.fromBase64(m.group(2)), s"image.$sub", m.group(1)).map(a => img.setAttribute("src", a.url))
        case None if "(?i)^[a-z]+:|^/|^data:".r.findFirstIn(src).isDefined => Future.successful(())
        case None =>
          val imgPath = resolvePath(dir, src)
          images.get(imgPath).fold(Future.successful(())) { found =>
            val name = Some(src.split("/", -1).last).filter(_.nonEmpty).getOrElse("image")
            // draw.io の図: 図を埋め込んだ PNG（.drawio.png）、または Confluence の draw.io（「図名.png」と、図の XML の「図名」「図名.drawio」）
            val embeddedF = if (Drawio.isPng(name)) Drawio.xmlFromPng(found.data) else Future.successful(None)
            embeddedF.flatMap { embedded =>
              val xmlFile = if (embedded.isDefined) None
              else {
                val noPng = "(?i)\\.png$".r
                List(noPng.replaceFirstIn(imgPath, ""), noPng.replaceFirstIn(imgPath, ".drawio")).filter(_ != imgPath)
                  .flatMap(p => images.get(p).map(p -> _)).find(
                    (
                      _,
                      f
                    ) => isMx(Bytes.utf8(f.data.subarray(0, 4000)))
                  )
              }
              if (embedded.isDefined || xmlFile.isDefined) {
                val t0 = "(?i)\\.(drawio|dio)?\\.?png$".r.replaceFirstIn(
                  js.URIUtils.decodeURIComponent(name),
                  ""
                ).stripSuffix(".")
                val title = if (t0.isEmpty) "図" else t0
                for {
                  png <- upload(page, found.data, if (embedded.isDefined) name else s"$title.png", "image/png")
                  xmlText = embedded.getOrElse(Bytes.utf8(xmlFile.get._2.data))
                  xmlAtt <- xmlFile.fold(Future.successful(Option.empty[Attachment]))(x =>
                    upload(page, x._2.data, s"$title.drawio", "application/xml").map(Some(_))
                  )
                  labels <- Drawio.labelsOf(xmlText)
                } yield replaceWith(img, drawioFigure(doc, Some(png.url), xmlAtt.map(_.url), title, labels))
              } else upload(page, found.data, name, found.mime).map { a =>
                img.setAttribute("src", a.url)
                if (attr(img, "alt").isEmpty) img.setAttribute("alt", name)
              }
            }
          }
      }
    }

    def link(
      a: dom.Element
    ): Future[Unit] = {
      val href = attr(a, "href")
      if ("(?i)^[a-z]+:|^/|^#".r.findFirstIn(href).isDefined) Future.successful(())
      else {
        val path = resolvePath(dir, href)
        if (isText(path)) {
          // ほかのページへのリンク. 見つからなければ、Notion のファイル名の ID で探す（ID は書き出し全体で一意）
          val nid = "(?i)([0-9a-f]{32})\\.\\w+$".r.findFirstMatchIn(path).map(_.group(1))
          val target = byPath.get(path).orElse(nid.flatMap(id => byPath.find(_._1.contains(id)).map(_._2)))
          target.flatMap(_.page).foreach(p => a.setAttribute("href", s"/w/$workspaceId/p/${p.id}"))
          Future.successful(())
        } else images.get(path).fold(Future.successful(())) { found =>
          if ("(?i)\\.excalidraw$".r.findFirstIn(path).isDefined) {
            // ホワイトボード（Excalidraw のファイル）は、ホワイトボードのブロックに戻す
            try {
              val scene = js.JSON.parse(Bytes.utf8(found.data))
              val elements =
                if (js.isUndefined(scene.elements) || scene.elements == null) js.Array() else scene.elements
              val bg = if (js.isUndefined(scene.appState) || scene.appState == null) js.undefined
              else scene.appState.viewBackgroundColor
              val div = doc.createElement("div")
              div.setAttribute("data-whiteboard", "")
              div.setAttribute(
                "data-snapshot",
                js.JSON.stringify(js.Dynamic.literal(elements = elements, background = bg))
              )
              replaceWith(linkHost(a), div)
            } catch { case _: Throwable => () } // 壊れたファイルはリンクのまま
            Future.successful(())
          } else {
            val xmlText = if (Drawio.isXml(path)) Some(Bytes.utf8(found.data)).filter(isMx) else None
            xmlText match {
              case Some(xml) =>
                // draw.io の図の XML（.drawio）は、図のブロックにする（最初に編集して保存したときに表示用の PNG ができる）
                val title =
                  "(?i)\\.(drawio|dio)$".r.replaceFirstIn(js.URIUtils.decodeURIComponent(path.split("/", -1).last), "")
                for {
                  att <- upload(page, found.data, s"$title.drawio", "application/xml")
                  labels <- Drawio.labelsOf(xml)
                } yield replaceWith(linkHost(a), drawioFigure(doc, None, Some(att.url), title, labels))
              case None =>
                // そのほかの添付ファイル
                val name = Some(path.split("/", -1).last).filter(_.nonEmpty).getOrElse("file")
                upload(page, found.data, name, found.mime).map(att => a.setAttribute("href", att.url))
            }
          }
        }
      }
    }

    for {
      _ <- sequentially(all(doc, "img"))(image)
      _ <- sequentially(all(doc, "a[href]"))(link)
      state <- Io.encodeHtml(doc.body.innerHTML)
      _ <- ContentApi.importContent(page.id, state)
    } yield ()
  }

  // ---------------------------------------------------------------- Notion のデータベース（CSV）

  private def jsNumber(
    s: String
  ): Double = js.Dynamic.global.Number(s).asInstanceOf[Double]

  private def stripNum(
    s: String
  ) = s.replaceAll("[,¥$%]", "")

  /** Notion の日付（「November 7, 2026」「2026/11/07 10:00」「A → B」） */
  private def notionDate(
    s: String
  ): Option[String] = {
    val head = JsLike.trim(s.split("→", -1)(0))
    Db.parseDate(head).orElse {
      val t = js.Date.parse(head)
      if (!t.isNaN && "\\d{4}".r.findFirstIn(head).isDefined) Some(Formula.ymd(new js.Date(t))) else None
    }
  }

  private val Comma = ",\\s*"

  /** 列の種類を値から推測する */
  private def guess(
    vals: List[String]
  ): String = {
    val v = vals.map(JsLike.trim).filter(_.nonEmpty)
    if (v.isEmpty) "text"
    else if (v.forall(x => "(?i)^(yes|no|true|false|はい|いいえ)$".r.findFirstIn(x).isDefined)) "checkbox"
    else if (v.forall(x => !jsNumber(stripNum(x)).isNaN)) "number"
    else if (v.forall(x => notionDate(x).isDefined)) "date"
    else if (v.forall(_.matches("^https?://[\\s\\S]*"))) "url"
    else {
      val distinct = v.flatMap(_.split(Comma, -1)).toSet
      if (v.exists(_.contains(", ")) && distinct.size <= 40) "multi_select"
      else if (v.toSet.size <= math.min(20.0, math.max(3.0, v.size / 2.0))) "select"
      else "text"
    }
  }

  /** Notion のデータベース（CSV）: 列の種類を値から推測してデータベースにし、子ページ（項目）に値を入れる. ページのない行は項目を作る */
  private def importCsvDatabase(
    node: Node,
    csv: String
  ): Future[Unit] = {
    val rows = Db.parseCsv(csv)
    if (rows.isEmpty) Future.successful(())
    else {
      val head = rows.head
      val body = rows.tail
      val titleCol = 0
      val cols: List[Option[DbProp]] = head.zipWithIndex.map {
        (
          name,
          i
        ) =>
          if (i == titleCol) None
          else {
            val kind = guess(body.map(_.lift(i).getOrElse("")))
            val opts =
              if (kind == "select" || kind == "multi_select")
                Some(body.flatMap(r =>
                  if (kind == "multi_select") r.lift(i).getOrElse("").split(Comma, -1).toList
                  else List(r.lift(i).getOrElse(""))
                )
                  .map(JsLike.trim).filter(_.nonEmpty).distinct.zipWithIndex.map(
                    (
                      n,
                      k
                    ) => DbOption(Db.newId(), n, Db.Colors(k % Db.Colors.size))
                  ))
              else None
            Some(DbProp(
              Db.newId(),
              Some(JsLike.trim(name)).filter(_.nonEmpty).getOrElse(s"列${i + 1}"),
              kind,
              options = opts
            ))
          }
      }
      def valuesOf(
        r: List[String]
      ): Map[String, Json] = cols.zipWithIndex.flatMap {
        case (None, _) => None
        case (Some(c), i) =>
          val x = JsLike.trim(r.lift(i).getOrElse(""))
          if (x.isEmpty) None
          else (c.tpe match {
            case "number" => Some(jsNumber(stripNum(x))).filter(n => !n.isNaN && !n.isInfinite).map(_.asJson)
            case "checkbox" => Some((Db.Truthy(x.toLowerCase) || "(?i)^(yes|true)$".r.findFirstIn(x).isDefined).asJson)
            case "date" => notionDate(x).map(_.asJson)
            case "select" => c.opts.find(_.name == x).map(_.id.asJson)
            case "multi_select" =>
              Some(x.split(Comma, -1).toList.flatMap(y => c.opts.find(_.name == JsLike.trim(y)).map(_.id)).asJson)
            case _ => Some(x.asJson)
          }).map(c.id -> _)
      }.toMap
      val page = node.page.get
      DbApi.save(page.id, cols.flatten, List(DbView(Db.newId(), "表", "table"))).flatMap { _ =>
        val kids = node.children.values.toList.filter(_.page.isDefined)
        val missing = mutable.ArrayBuffer.empty[(String, Map[String, Json])]
        sequentially(body) { r =>
          val title = JsLike.trim(r.lift(titleCol).getOrElse(""))
          kids.find(k => k.page.get.title == title || k.name == title).flatMap(_.page) match {
            case Some(p) => DbApi.setValues(p.id, valuesOf(r)).map(_ => ()).recover(_ => ())
            case None => missing += ((title, valuesOf(r))); Future.successful(())
          }
        }.flatMap(_ =>
          if (missing.nonEmpty) DbApi.addRows(page.id, missing.toList).map(_ => ()) else Future.successful(())
        )
      }
    }
  }

}
