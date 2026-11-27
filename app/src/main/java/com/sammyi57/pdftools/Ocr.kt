package com.sammyi57.pdftools

import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * Reads text from a picture with Tesseract and its Vietnamese model (tessdata_best, shipped in the app). ML Kit's Latin
 * reader drops or swaps tone marks on ư, ơ, ă, â, ê, ô; Tesseract trained on Vietnamese gets them right far more often.
 * Every call returns null when the engine can't start, so callers can fall back to ML Kit.
 */
object Ocr {
  private var api: TessBaseAPI? = null
  private var failed = false

  @Synchronized
  private fun engine(): TessBaseAPI? {
    api?.let { return it }
    if (failed) return null
    return try {
      val dir = File(appContext.filesDir, "tessdata").apply { mkdirs() }
      val model = File(dir, "vie.traineddata")
      // (assets.openFd would throw here: the model is stored compressed, and that quietly sent every page to ML Kit.)
      if (!model.exists() || model.length() < 1_000_000) {
        val tmp = File(dir, "vie.traineddata.tmp")
        appContext.assets.open("vie.traineddata").use { i -> tmp.outputStream().use { i.copyTo(it, 256 * 1024) } }
        if (!tmp.renameTo(model)) { tmp.copyTo(model, true); tmp.delete() }
      }
      val t = TessBaseAPI()
      if (!t.init(appContext.filesDir.path, "vie", TessBaseAPI.OEM_LSTM_ONLY)) { t.recycle(); failed = true; null }
      else {
        t.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        api = t
        t
      }
    } catch (e: Throwable) {
      android.util.Log.w("PdfTools", "Tesseract unavailable", e)
      failed = true
      null
    }
  }

  /** Lines of text with their boxes as fractions of the picture (y from the top), in reading order. */
  @Synchronized
  fun lines(bmp: Bitmap): List<OcrLine>? = read(bmp, false)

  /** Words with boxes, cut into lines where a line has a wide gap (a letterhead's two columns). */
  @Synchronized
  fun wordLines(bmp: Bitmap): List<OcrLine>? = read(bmp, true)

  /**
   * The page is straightened and cut into blocks first (Layout), each block is read as one column, and the tone marks are
   * checked against the Vietnamese word list (ViCorrect). Boxes are mapped back onto the picture as it came in.
   * If the layout step fails the whole picture is read at once, as before.
   */
  private fun read(bmp: Bitmap, words: Boolean): List<OcrLine>? {
    val t = engine() ?: return null
    return try {
      val out = ArrayList<OcrLine>()
      val page = Layout.analyze(bmp)
      if (page == null) {
        t.setImage(bmp)
        t.utF8Text // runs the recognition
        collect(t, words, 0.0, bmp.width, bmp.height, out)
      } else {
        t.setImage(page.gray, page.w, page.h, 1, page.w)
        t.pageSegMode = if (page.regions.size > 1) TessBaseAPI.PageSegMode.PSM_SINGLE_COLUMN else TessBaseAPI.PageSegMode.PSM_AUTO
        for (r in page.regions) {
          t.setRectangle(r[0], r[1], r[2] - r[0], r[3] - r[1])
          t.utF8Text
          collect(t, words, page.angle, page.w, page.h, out)
        }
      }
      out.map { it.copy(text = ViCorrect.fix(it.text)) }.filter { it.text.isNotBlank() }
    } catch (e: Throwable) {
      android.util.Log.w("PdfTools", "Tesseract failed on a page", e)
      null
    } finally {
      runCatching { t.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO }
      runCatching { t.clear() }
    }
  }

  /** A box found on the straightened page, as fractions of the original picture (turned back by `angle` degrees). */
  private fun frac(r: android.graphics.Rect, angle: Double, w: Int, h: Int): FloatArray {
    val a = Math.toRadians(angle)
    val c = Math.cos(a)
    val s = Math.sin(a)
    val dx = (r.left + r.right) / 2.0 - w / 2.0
    val dy = (r.top + r.bottom) / 2.0 - h / 2.0
    val cx = c * dx - s * dy + w / 2.0
    val cy = s * dx + c * dy + h / 2.0
    val hw = r.width() / 2.0
    val hh = r.height() / 2.0
    return floatArrayOf(((cx - hw) / w).toFloat(), ((cy - hh) / h).toFloat(), ((cx + hw) / w).toFloat(), ((cy + hh) / h).toFloat())
  }

  private fun collect(t: TessBaseAPI, words: Boolean, angle: Double, w: Int, h: Int, out: MutableList<OcrLine>) {
    val level = if (words) TessBaseAPI.PageIteratorLevel.RIL_WORD else TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
    var cur = ArrayList<OcrWord>()
    val flush = { if (cur.isNotEmpty()) { out.addAll(splitColumns(cur)); cur = ArrayList() } }
    val it = t.resultIterator ?: return
    it.begin()
    do {
      if (words && it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)) flush()
      val text = it.getUTF8Text(level)?.trim().orEmpty()
      val r = it.getBoundingRect(level)
      val conf = it.confidence(level)
      if (text.isNotEmpty() && r.width() > 0 && r.height() > 0) {
        val b = frac(r, angle, w, h)
        if (words) {
          if (conf >= 30f) cur.add(OcrWord(text, b[0], b[1], b[2], b[3]))
        } else {
          // A dark page edge or a shadow comes back as "lines" of digits and symbols: low confidence, few real letters.
          val real = text.count { c -> c.isLetter() }
          if (real * 2 >= text.count { c -> !c.isWhitespace() } && conf >= 35f) out.add(OcrLine(text, b[0], b[1], b[2], b[3]))
        }
      }
    } while (it.next(level))
    flush()
    it.delete()
  }
}
