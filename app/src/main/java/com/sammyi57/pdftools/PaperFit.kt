package com.sammyi57.pdftools

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** A standard sheet: its name and long side over short side. */
data class PaperMatch(val name: String, val ratio: Double, val landscape: Boolean)

// Works out how big a photographed page really is. A phone is never held square to the paper, so the four corners form
// a trapezoid and its edge lengths say little about the sheet's shape. Seen through a pinhole camera, though, a
// rectangle pins down the camera's focal length and its own aspect ratio (Zhang & He, "Whiteboard scanning and image
// enhancement", 2007), which is enough to straighten the page to its true proportions and then to the nearest paper.
// Pure math, no Android types, so it can be tested on the JVM.
object PaperFit {
  // Long side over short side. A3, A4 and A5 are the same shape, so a photo can't tell them apart: they all read as "A4".
  private val PAPERS = listOf("A4" to sqrt(2.0), "Letter" to 11.0 / 8.5, "Legal" to 14.0 / 8.5)

  /** How far (relative) a page's shape may be from a standard sheet and still count as that sheet. */
  const val TOLERANCE = 0.045

  private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
  private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

  /**
   * Width over height of the sheet the quad `c` shows, as [x0,y0, x1,y1, x2,y2, x3,y3] pixels (top-left, top-right,
   * bottom-right, bottom-left) in a `w`×`h` image. The camera's centre is taken to be the image centre. Falls back to
   * the quad's own edge lengths when the quad is too skewed for the estimate to be believable.
   */
  fun trueAspect(c: DoubleArray, w: Double, h: Double): Double {
    val naive = naiveAspect(c)
    val m1 = doubleArrayOf(c[0], c[1], 1.0) // top-left
    val m2 = doubleArrayOf(c[2], c[3], 1.0) // top-right
    val m4 = doubleArrayOf(c[4], c[5], 1.0) // bottom-right
    val m3 = doubleArrayOf(c[6], c[7], 1.0) // bottom-left
    val d2 = dot(cross(m2, m4), m3)
    val d3 = dot(cross(m3, m4), m2)
    if (abs(d2) < 1e-9 || abs(d3) < 1e-9) return naive
    val k2 = dot(cross(m1, m4), m3) / d2
    val k3 = dot(cross(m1, m4), m2) / d3
    val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
    val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }

    val u0 = w / 2
    val v0 = h / 2
    val prior = 0.8 * max(w, h) // a phone's main camera, when the corners say nothing about it
    var f = prior
    if (abs(n2[2] * n3[2]) > 1e-9) {
      val f2 = -((n2[0] * n3[0] - (n2[0] * n3[2] + n2[2] * n3[0]) * u0 + n2[2] * n3[2] * u0 * u0) +
        (n2[1] * n3[1] - (n2[1] * n3[2] + n2[2] * n3[1]) * v0 + n2[2] * n3[2] * v0 * v0)) / (n2[2] * n3[2])
      val est = if (f2 > 0) sqrt(f2) else Double.NaN
      // A believable lens: anywhere from a very wide to a long telephoto view of this frame.
      if (est.isFinite() && est in 0.35 * max(w, h)..4.0 * max(w, h)) f = est
    }
    fun q(n: DoubleArray): Double {
      val x = (n[0] - u0 * n[2]) / f
      val y = (n[1] - v0 * n[2]) / f
      return x * x + y * y + n[2] * n[2]
    }
    val a = sqrt(q(n2) / q(n3))
    // A page can't be 40% off its own edges' proportions unless the corners are bad; trust the plain edges then.
    return if (a.isFinite() && a > 0 && abs(Math.log(a / naive)) < Math.log(1.4)) a else naive
  }

  /** Width over height from the edge lengths alone. */
  fun naiveAspect(c: DoubleArray): Double {
    val top = hypot(c[2] - c[0], c[3] - c[1])
    val bottom = hypot(c[4] - c[6], c[5] - c[7])
    val left = hypot(c[6] - c[0], c[7] - c[1])
    val right = hypot(c[4] - c[2], c[5] - c[3])
    return ((top + bottom) / 2).coerceAtLeast(1.0) / ((left + right) / 2).coerceAtLeast(1.0)
  }

  /** The standard sheet whose shape is closest to `ratio` (width over height), or null when none is close enough. */
  fun match(ratio: Double, tolerance: Double = TOLERANCE): PaperMatch? {
    if (!ratio.isFinite() || ratio <= 0) return null
    val landscape = ratio > 1
    val long = if (landscape) ratio else 1 / ratio
    val best = PAPERS.minByOrNull { abs(long / it.second - 1) } ?: return null
    return if (abs(long / best.second - 1) <= tolerance) PaperMatch(best.first, best.second, landscape) else null
  }

  /** `ratio` snapped to its sheet's exact shape (keeping portrait or landscape), or unchanged when it matches none. */
  fun snap(ratio: Double): Double {
    val m = match(ratio) ?: return ratio
    return if (m.landscape) m.ratio else 1 / m.ratio
  }

  /** An image's size as a standard sheet when it's shaped like one, for naming the page ("A4 dọc"). */
  fun matchSize(width: Int, height: Int, tolerance: Double = TOLERANCE): PaperMatch? =
    if (width <= 0 || height <= 0) null else match(width.toDouble() / height, tolerance)

  /** Output size for a warped page: the quad's longest edge stays the long side, the shape becomes `aspect`. */
  fun outputSize(naiveW: Double, naiveH: Double, aspect: Double): Pair<Double, Double> {
    val long = max(naiveW, naiveH).coerceAtLeast(16.0)
    val (w, h) = if (aspect >= 1) long to long / aspect else long * aspect to long
    return max(w, 16.0) to max(h, 16.0)
  }

  /** Short label for a match, e.g. "A4 dọc". */
  fun label(m: PaperMatch) = m.name + if (m.landscape) " ngang" else " dọc"
}
