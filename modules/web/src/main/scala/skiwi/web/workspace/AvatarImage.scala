package skiwi.web.workspace

import org.scalajs.dom

import scala.concurrent.{Future, Promise}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** 自分のアイコンにする画像の加工（旧 ui/avatar.ts の squareImage. 画像がないときの丸の色は parts/Avatar.st の colorOf） */
object AvatarImage {

  val Size = 256
  private val MaxBytes = 200 * 1024

  /** 画像ファイルを、真ん中を正方形に切り抜いて Size に縮める（WebP、使えなければ JPEG. 200KB に収まるまで画質を下げる） */
  def squareImage(file: dom.Blob): Future[dom.Blob] =
    js.Dynamic.global.createImageBitmap(file).asInstanceOf[js.Promise[js.Dynamic]].toFuture
      .recover { case _ => throw new Exception("画像として読めませんでした（PNG・JPEG・WebP・GIF などを選んでください）") }
      .flatMap { bmp =>
        val w = bmp.width.asInstanceOf[Double]
        val h = bmp.height.asInstanceOf[Double]
        val side = math.min(w, h)
        val canvas = dom.document.createElement("canvas").asInstanceOf[dom.HTMLCanvasElement]
        canvas.width = Size
        canvas.height = Size
        val g = canvas.getContext("2d").asInstanceOf[js.Dynamic]
        g.imageSmoothingQuality = "high"
        g.drawImage(bmp, (w - side) / 2, (h - side) / 2, side, side, 0, 0, Size, Size)
        bmp.close()
        def encode(tpe: String, q: Double): Future[Option[dom.Blob]] = {
          val p = Promise[Option[dom.Blob]]()
          canvas.asInstanceOf[js.Dynamic].toBlob((b: dom.Blob) => p.success(Option(b)), tpe, q)
          p.future
        }
        def tryQ(qs: List[Double]): Future[dom.Blob] = qs match {
          case Nil => Future.failed(new Exception("画像を小さくできませんでした"))
          case q :: rest =>
            encode("image/webp", q)
              .flatMap(b => if (b.exists(_.`type` == "image/webp")) Future.successful(b) else encode("image/jpeg", q))
              .flatMap {
                case Some(b) if b.size <= MaxBytes => Future.successful(b)
                case _ => tryQ(rest)
              }
        }
        tryQ(List(0.9, 0.8, 0.65, 0.5))
      }

}
