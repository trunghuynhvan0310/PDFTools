package com.sammyi57.pdftools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/** One document found in a run of scanned pages: which pages it covers (0-based, in scan order) and what was read off it. */
data class GovDoc(val first: Int, val count: Int, val fields: Extracted)

/** Reads scanned administrative documents with ML Kit and hands the lines to [DocFields]. */
object GovScan {
  private const val MAX_SIDE = 3600
  private const val MIN_SIDE = 2800 // smaller pages are enlarged: ML Kit reads small print better at this size

  private fun load(path: String): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    if (o.outWidth <= 0) return null
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= MAX_SIDE) s *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
  }

  /** The red channel as a gray picture: names under a red seal stay readable, which plain grayscale loses. */
  private fun redChannel(src: Bitmap): Bitmap {
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val m = ColorMatrix(floatArrayOf(
      1f, 0f, 0f, 0f, 0f,
      1f, 0f, 0f, 0f, 0f,
      1f, 0f, 0f, 0f, 0f,
      0f, 0f, 0f, 1f, 0f,
    ))
    Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
    return out
  }

  private fun lines(path: String): List<OcrLine> {
    val src = load(path) ?: return emptyList()
    val red = redChannel(src).let { r ->
      val long = maxOf(r.width, r.height)
      if (long >= MIN_SIDE) r else Bitmap.createScaledBitmap(r, r.width * MIN_SIDE / long, r.height * MIN_SIDE / long, true).also { r.recycle() }
    }
    val w = red.width.toFloat()
    val h = red.height.toFloat()
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
      Ocr.wordLines(red)?.let { return it } // Tesseract, Vietnamese model; ML Kit below only if it can't start
      val text = Tasks.await(recognizer.process(InputImage.fromBitmap(red, 0)), 120, TimeUnit.SECONDS)
      return text.textBlocks.flatMap { it.lines }.flatMap { line ->
        val words = line.elements.mapNotNull { e ->
          val b = e.boundingBox ?: return@mapNotNull null
          if (e.text.isBlank()) null else OcrWord(e.text, b.left / w, b.top / h, b.right / w, b.bottom / h)
        }
        splitColumns(words)
      }
    } finally {
      red.recycle()
      if (src !== red) src.recycle()
    }
  }

  private fun startsDocument(page: List<OcrLine>): Boolean =
    page.any { it.y0 < 0.3f && DocFields.foldKeep(it.text).lowercase().replace(" ", "").contains("conghoaxahoi") }

  /** Reads every page image, cuts the run into documents at each letterhead, and extracts the fields of each. */
  fun analyze(paths: List<String>): List<GovDoc> {
    if (paths.isEmpty()) return emptyList()
    val pages = paths.map { runCatching { lines(it) }.getOrDefault(emptyList()) }
    val starts = pages.indices.filter { it == 0 || startsDocument(pages[it]) }
    return starts.mapIndexed { n, s ->
      val end = starts.getOrElse(n + 1) { pages.size }
      GovDoc(s, end - s, DocFields.extract(pages.subList(s, end)))
    }
  }

  /** A strip of the page for the person to check against what was read; null when the file can't be opened. */
  fun crop(path: String, r: Region, pad: Float = 0.01f): Bitmap? {
    val src = load(path) ?: return null
    val x0 = ((r.x0 - pad).coerceIn(0f, 1f) * src.width).toInt()
    val y0 = ((r.y0 - pad).coerceIn(0f, 1f) * src.height).toInt()
    val x1 = ((r.x1 + pad).coerceIn(0f, 1f) * src.width).toInt().coerceAtLeast(x0 + 1)
    val y1 = ((r.y1 + pad).coerceIn(0f, 1f) * src.height).toInt().coerceAtLeast(y0 + 1)
    val out = Bitmap.createBitmap(src, x0, y0, (x1 - x0).coerceAtMost(src.width - x0), (y1 - y0).coerceAtMost(src.height - y0))
    return out
  }
}

/** Reads the scanned pages of a Công văn scan from the originals (no filter), so a filter can't cost a letter. Paths stay for cropping. */
suspend fun govAnalyze(shots: List<Shot>): Pair<List<GovDoc>, List<String>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
  val paths = shots.map { s -> DocVision.process(appContext, s.src, ProcessOptions(s.corners, s.rotation, "original", 0f, 0f, emptyList(), 4800, true)).path }
  GovScan.analyze(paths) to paths
}
