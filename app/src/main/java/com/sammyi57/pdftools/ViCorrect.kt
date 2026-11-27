package com.sammyi57.pdftools

import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.ln

/**
 * Fixes tone marks that OCR gets wrong (ể read as ế, ổ as ố…) using a word list taken from Vietnamese company, audit and
 * legal documents: for every run of words it picks, per word, the spelling that is common in that vocabulary and fits
 * the word before it. A word is only changed when the pair is clearly more usual than what was read.
 * The list is assets/vi_corpus.json; without it text is returned as read.
 */
object ViCorrect {
  private class Kho(
    val uni: HashMap<String, Int>,
    val sidx: HashMap<String, List<String>>,
    val big: HashMap<String, HashMap<String, Int>>,
    val o2n: HashMap<String, String>,
  )

  private var kho: Kho? = null
  private var failed = false

  // How much a word's own frequency counts, how much the pair counts, and the bonus for keeping what was read.
  private const val KW = 0.1
  private const val BW = 1.0
  private const val KEEP = 3.0

  @Synchronized
  private fun load(): Kho? {
    kho?.let { return it }
    if (failed) return null
    return try {
      val text = appContext.assets.open("vi_corpus.json").use { it.readBytes().toString(Charsets.UTF_8) }
      val j = JSONObject(text)
      val uni = HashMap<String, Int>()
      j.getJSONObject("uni").let { o -> o.keys().forEach { uni[it] = o.getInt(it) } }
      val sidx = HashMap<String, List<String>>()
      j.getJSONObject("sidx").let { o ->
        o.keys().forEach { k ->
          val a = o.getJSONArray(k)
          sidx[k] = List(a.length()) { a.getString(it) }
        }
      }
      val big = HashMap<String, HashMap<String, Int>>()
      j.getJSONObject("big").let { o ->
        o.keys().forEach { k ->
          val m = o.getJSONObject(k)
          val h = HashMap<String, Int>()
          m.keys().forEach { w -> h[w] = m.getInt(w) }
          big[k] = h
        }
      }
      val o2n = HashMap<String, String>()
      j.getJSONObject("old2new").let { o -> o.keys().forEach { o2n[it] = o.getString(it) } }
      Kho(uni, sidx, big, o2n).also { kho = it }
    } catch (e: Throwable) {
      android.util.Log.w("PdfTools", "Vietnamese word list unavailable", e)
      failed = true
      null
    }
  }

  private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

  /** Without tone marks, hats or horns: "kiểm" → "kiem". */
  private fun strip(s: String): String {
    val d = Normalizer.normalize(s.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
    val b = StringBuilder(d.length)
    for (c in d) if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) b.append(c)
    return b.toString()
  }

  private fun score(k: Kho, prev: String?, w: String, obs: String): Double {
    var s = KW * ln(1.0 + (k.uni[w] ?: 0)) + (if (w == obs) KEEP else 0.0)
    if (prev != null) s += BW * ln(1.0 + (k.big[prev]?.get(w) ?: 0))
    return s
  }

  private fun fixRun(k: Kho, toks: List<String>): List<String> {
    val low = toks.map { val l = nfc(it.lowercase()); k.o2n[l] ?: l }
    val cands = low.map { t ->
      val c = ArrayList(k.sidx[strip(t)] ?: emptyList())
      if (t !in c) c.add(t)
      c
    }
    val n = low.size
    val sc = Array(n) { DoubleArray(cands[it].size) }
    val from = Array(n) { IntArray(cands[it].size) }
    for (j in cands[0].indices) sc[0][j] = score(k, null, cands[0][j], low[0])
    for (i in 1 until n) {
      for (j in cands[i].indices) {
        var best = Double.NEGATIVE_INFINITY
        var bp = 0
        for (p in cands[i - 1].indices) {
          val v = sc[i - 1][p] + score(k, cands[i - 1][p], cands[i][j], low[i])
          if (v > best) { best = v; bp = p }
        }
        sc[i][j] = best
        from[i][j] = bp
      }
    }
    var cur = 0
    for (j in 1 until cands[n - 1].size) if (sc[n - 1][j] > sc[n - 1][cur]) cur = j
    val pick = arrayOfNulls<String>(n)
    for (i in n - 1 downTo 0) {
      pick[i] = cands[i][cur]
      cur = from[i][cur]
    }
    return List(n) { i ->
      val o = pick[i]!!
      val t = toks[i]
      when {
        t.length > 1 && t.all { it.isUpperCase() } -> o.uppercase()
        t[0].isUpperCase() -> o.substring(0, 1).uppercase() + o.substring(1)
        else -> o
      }
    }
  }

  private val word = Regex("\\p{L}+")

  fun fix(raw: String): String {
    val k = load() ?: return raw
    return try {
      val text = nfc(raw).replace('ð', 'đ').replace('Ð', 'Đ')
      val found = word.findAll(text).toList()
      if (found.isEmpty()) return text
      // Runs of words separated only by spaces; digits, punctuation and line ends break a run.
      val groups = ArrayList<List<MatchResult>>()
      var cur = ArrayList<MatchResult>()
      for (m in found) {
        if (cur.isNotEmpty() && text.substring(cur.last().range.last + 1, m.range.first).any { it != ' ' }) {
          groups.add(cur); cur = ArrayList()
        }
        cur.add(m)
      }
      if (cur.isNotEmpty()) groups.add(cur)
      val sb = StringBuilder()
      var pos = 0
      for (g in groups) {
        val fixed = fixRun(k, g.map { it.value })
        for ((i, m) in g.withIndex()) {
          sb.append(text, pos, m.range.first).append(fixed[i])
          pos = m.range.last + 1
        }
      }
      sb.append(text, pos, text.length)
      sb.toString()
    } catch (e: Throwable) {
      raw
    }
  }
}
