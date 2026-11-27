package com.sammyi57.pdftools

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * Gets a photographed page ready for OCR: straightens lines of text that run at a slight angle, then cuts the page into
 * blocks (a letterhead's two columns, then the body) so the engine reads them one after another instead of mixing them.
 * Everything is measured on an 800 px wide copy, so it takes a few milliseconds.
 */
object Layout {
  /** A straightened grey page (8 bit, row after row) and the blocks to read, as x0, y0, x1, y1 in its pixels. */
  class Page(val gray: ByteArray, val w: Int, val h: Int, val angle: Double, val regions: List<IntArray>)

  private val loaded = OpenCVLoader.initLocal()

  fun analyze(bmp: Bitmap): Page? {
    if (!loaded) return null
    var rgba: Mat? = null
    var gray: Mat? = null
    var turned: Mat? = null
    return try {
      rgba = Mat()
      Utils.bitmapToMat(bmp, rgba)
      gray = Mat()
      Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
      rgba.release(); rgba = null
      val w = gray.cols()
      val h = gray.rows()
      if (w < 200 || h < 200) return null
      val angle = skew(gray)
      var g: Mat = gray
      if (abs(angle) >= 0.2) {
        turned = Mat()
        val m = Imgproc.getRotationMatrix2D(Point(w / 2.0, h / 2.0), angle, 1.0)
        Imgproc.warpAffine(gray, turned, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, Scalar(255.0))
        m.release()
        g = turned
      }
      val regions = regions(g)
      val bytes = ByteArray(w * h)
      g.get(0, 0, bytes)
      Page(bytes, w, h, if (abs(angle) >= 0.2) angle else 0.0, regions)
    } catch (e: Throwable) {
      android.util.Log.w("PdfTools", "Layout failed", e)
      null
    } finally {
      rgba?.release(); gray?.release(); turned?.release()
    }
  }

  /** Ink = 255 on an 800 px wide copy. */
  private fun binary(gray: Mat): Mat {
    val s = 800.0 / gray.cols()
    val sm = Mat()
    Imgproc.resize(gray, sm, Size(800.0, Math.max(1, (gray.rows() * s).toInt()).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
    val b = Mat()
    Imgproc.adaptiveThreshold(sm, b, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 31, 15.0)
    sm.release()
    return b
  }

  private fun sums(m: Mat, dim: Int): DoubleArray {
    val r = Mat()
    Core.reduce(m, r, dim, Core.REDUCE_SUM, CvType.CV_64F)
    val out = DoubleArray(if (dim == 1) m.rows() else m.cols())
    r.get(0, 0, out)
    r.release()
    return out
  }

  /** Angle (degrees, same sign as getRotationMatrix2D) that makes the lines of text horizontal: the sharpest row profile. */
  private fun skew(gray: Mat): Double {
    val b = binary(gray)
    val hh = b.rows()
    val ww = b.cols()
    val mx = (0.04 * ww).toInt()
    val my = (0.04 * hh).toInt()
    val zero = Scalar(0.0)
    b.submat(0, hh, 0, mx).setTo(zero)
    b.submat(0, hh, ww - mx, ww).setTo(zero)
    b.submat(0, my, 0, ww).setTo(zero)
    b.submat(hh - my, hh, 0, ww).setTo(zero)
    val size = Size(ww.toDouble(), hh.toDouble())
    fun score(a: Double): Double {
      val m = Imgproc.getRotationMatrix2D(Point(ww / 2.0, hh / 2.0), a, 1.0)
      val r = Mat()
      Imgproc.warpAffine(b, r, m, size, Imgproc.INTER_NEAREST)
      val p = sums(r, 1)
      r.release(); m.release()
      var s = 0.0
      for (i in 1 until p.size) { val d = p[i] - p[i - 1]; s += d * d }
      return s
    }
    var best = 0.0
    var bestScore = -1.0
    for (i in 0..24) {
      val a = -3.0 + 0.25 * i
      val s = score(a)
      if (s > bestScore) { bestScore = s; best = a }
    }
    val centre = best
    for (i in 0..10) {
      val a = centre - 0.25 + 0.05 * i
      val s = score(a)
      if (s > bestScore) { bestScore = s; best = a }
    }
    b.release()
    return best
  }

  private fun runs(m: BooleanArray): List<IntArray> {
    val out = ArrayList<IntArray>()
    var s = -1
    for (i in m.indices) {
      if (m[i] && s < 0) s = i
      if (!m[i] && s >= 0) { out.add(intArrayOf(s, i)); s = -1 }
    }
    if (s >= 0) out.add(intArrayOf(s, m.size))
    return out
  }

  /** Blocks in reading order. A band of text with an empty strip down its middle becomes a left block and a right block. */
  private fun regions(gray: Mat): List<IntArray> {
    val full = listOf(intArrayOf(0, 0, gray.cols(), gray.rows()))
    var b = binary(gray)
    val hh = b.rows()
    val ww = b.cols()
    val mx = (0.04 * ww).toInt()
    val my = (0.03 * hh).toInt()
    val zero = Scalar(0.0)
    b.submat(0, hh, 0, mx).setTo(zero)
    b.submat(0, hh, ww - mx, ww).setTo(zero)
    b.submat(0, my, 0, ww).setTo(zero)
    b.submat(hh - my, hh, 0, ww).setTo(zero)
    val opened = Mat()
    Imgproc.morphologyEx(b, opened, Imgproc.MORPH_OPEN, Mat.ones(2, 2, CvType.CV_8U))
    b.release()
    b = opened
    try {
      val rows = sums(b, 1)
      val thr = Math.max(2, (0.004 * ww).toInt()) * 255.0
      val ink = BooleanArray(hh) { rows[it] > thr }
      val gap = (0.014 * hh).toInt()
      val bands = ArrayList<IntArray>()
      for (r in runs(ink)) {
        if (bands.isNotEmpty() && r[0] - bands.last()[1] < gap) bands.last()[1] = r[1] else bands.add(intArrayOf(r[0], r[1]))
      }
      if (bands.isEmpty()) return full
      // y0, y1, gutter start, gutter end (-1 = none)
      val pieces = ArrayList<IntArray>()
      for (bd in bands) {
        var gs = -1
        var ge = -1
        if (bd[1] - bd[0] >= 0.05 * hh) {
          val cols = sums(b.submat(bd[0], bd[1], 0, ww), 0)
          val has = BooleanArray(ww) { cols[it] > 0.0 }
          val empty = BooleanArray(ww) { !has[it] }
          for (e in runs(empty)) {
            val a = e[0]
            val z = e[1]
            if (a > 0.2 * ww && z < 0.8 * ww && z - a >= 0.015 * ww && a > 0 && z < ww) {
              var left = 0
              for (i in 0 until a) if (has[i]) left++
              var right = 0
              for (i in z until ww) if (has[i]) right++
              if (left > 0.08 * ww && right > 0.08 * ww && (gs < 0 || z - a > ge - gs)) { gs = a; ge = z }
            }
          }
        }
        pieces.add(intArrayOf(bd[0], bd[1], gs, ge))
      }
      // [kind (0 = full width, 1 = two columns), y0, y1, gutter middle]
      val blocks = ArrayList<IntArray>()
      var cur: IntArray? = null
      for (p in pieces) {
        if (p[2] < 0) {
          if (cur == null) cur = intArrayOf(p[0], p[1]) else cur[1] = p[1]
        } else {
          cur?.let { blocks.add(intArrayOf(0, it[0], it[1], 0)) }
          cur = null
          blocks.add(intArrayOf(1, p[0], p[1], (p[2] + p[3]) / 2))
        }
      }
      cur?.let { blocks.add(intArrayOf(0, it[0], it[1], 0)) }
      val pad = (0.012 * hh).toInt()
      val k = gray.cols().toDouble() / ww
      val out = ArrayList<IntArray>()
      fun add(x0: Int, y0: Int, x1: Int, y1: Int) {
        out.add(intArrayOf(
          Math.max(0, (x0 * k).toInt()), Math.max(0, (Math.max(0, y0 - pad) * k).toInt()),
          Math.min(gray.cols(), (x1 * k).toInt()), Math.min(gray.rows(), (Math.min(hh, y1 + pad) * k).toInt())
        ))
      }
      for (bl in blocks) {
        if (bl[0] == 0) add(0, bl[1], ww, bl[2])
        else { add(0, bl[1], bl[3], bl[2]); add(bl[3], bl[1], ww, bl[2]) }
      }
      return out.filter { it[2] - it[0] > 20 && it[3] - it[1] > 20 }.ifEmpty { full }
    } finally {
      b.release()
    }
  }
}
