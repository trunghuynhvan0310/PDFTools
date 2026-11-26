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
  fun lines(bmp: Bitmap): List<OcrLine>? {
    val t = engine() ?: return null
    return try {
      t.setImage(bmp)
      t.utF8Text // runs the recognition
      val w = bmp.width.toFloat()
      val h = bmp.height.toFloat()
      val out = ArrayList<OcrLine>()
      val it = t.resultIterator
      it.begin()
      do {
        val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)?.trim().orEmpty()
        val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
        // A dark page edge or a shadow comes back as "lines" of digits and symbols: low confidence, few real letters.
        val real = text.count { c -> c.isLetter() }
        val keep = real * 2 >= text.count { c -> !c.isWhitespace() } && it.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE) >= 35f
        if (keep && text.isNotEmpty() && r.width() > 0 && r.height() > 0) out.add(OcrLine(text, r.left / w, r.top / h, r.right / w, r.bottom / h))
      } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
      it.delete()
      out
    } catch (e: Throwable) {
      android.util.Log.w("PdfTools", "Tesseract failed on a page", e)
      null
    } finally {
      runCatching { t.clear() }
    }
  }

  /** Words with boxes, cut into lines where a line has a wide gap (a letterhead's two columns). */
  @Synchronized
  fun wordLines(bmp: Bitmap): List<OcrLine>? {
    val t = engine() ?: return null
    return try {
      t.setImage(bmp)
      t.utF8Text
      val w = bmp.width.toFloat()
      val h = bmp.height.toFloat()
      val out = ArrayList<OcrLine>()
      var cur = ArrayList<OcrWord>()
      val flush = { if (cur.isNotEmpty()) { out.addAll(splitColumns(cur)); cur = ArrayList() } }
      val it = t.resultIterator
      it.begin()
      do {
        if (it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)) flush()
        val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
        val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
        if (it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD) >= 30f && text.isNotEmpty() && r.width() > 0 && r.height() > 0) cur.add(OcrWord(text, r.left / w, r.top / h, r.right / w, r.bottom / h))
      } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
      flush()
      it.delete()
      out
    } catch (e: Throwable) {
      android.util.Log.w("PdfTools", "Tesseract failed on a page", e)
      null
    } finally {
      runCatching { t.clear() }
    }
  }
}
