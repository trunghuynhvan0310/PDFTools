package com.sammyi57.pdftools

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/** One recognized line of text and where it sits on its page, as fractions of the page (y from the top). */
data class OcrLine(val text: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
  val cx get() = (x0 + x1) / 2
  val h get() = y1 - y0
}

/** One recognized word, same units as [OcrLine]. */
data class OcrWord(val text: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/**
 * Turns one recognized line's words into lines, cutting where words are far apart: a letterhead's two columns come out
 * of OCR as a single line, and they have to be read separately.
 */
fun splitColumns(words: List<OcrWord>, gap: Float = 0.03f): List<OcrLine> {
  val out = ArrayList<OcrLine>()
  var cur = ArrayList<OcrWord>()
  fun flush() {
    if (cur.isEmpty()) return
    out.add(OcrLine(cur.joinToString(" ") { it.text }, cur.minOf { it.x0 }, cur.minOf { it.y0 }, cur.maxOf { it.x1 }, cur.maxOf { it.y1 }))
    cur = ArrayList()
  }
  for (w in words.sortedBy { it.x0 }) {
    if (cur.isNotEmpty() && w.x0 - cur.last().x1 > gap) flush()
    cur.add(w)
  }
  flush()
  return out
}

/** A rectangle on the first page, as fractions, for cutting out what the user has to check. */
data class Region(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/** What could be read off a Vietnamese administrative document (Nghị định 30/2020/NĐ-CP layout). Empty = not found. */
data class Extracted(
  val so: String = "", // number before the slash: "231"
  val kyHieu: String = "", // after the slash: "BC-KTNB"
  val soSure: Boolean = false, // the number came out as clean digits
  val ngay: String = "", val thang: String = "", val nam: String = "",
  val diaDanh: String = "", // "Thành phố Hồ Chí Minh"
  val coQuan: String = "", // issuing body, as printed in the top-left
  val loai: String = "", // Quyết định, Báo cáo, Tờ trình ...
  val trichYeu: String = "", // the subject line(s)
  val kinhGui: String = "",
  val noiNhan: String = "",
  val nguoiKy: String = "", val chucVu: String = "",
  val soBox: Region? = null, val ngayBox: Region? = null, // where the number and the date are, on page 1
) {
  /** dd/MM/yyyy when all three parts are present. */
  val ngayDayDu: String get() = if (ngay.isNotEmpty() && thang.isNotEmpty() && nam.isNotEmpty()) "%02d/%02d/%s".format(ngay.toInt(), thang.toInt(), nam) else ""
  val soKyHieu: String get() = if (so.isEmpty() && kyHieu.isEmpty()) "" else if (kyHieu.isEmpty()) so else "$so/$kyHieu"
}

// Reads the printed parts of a document from OCR lines. Numbers and dates are often filled in by hand in a scan and OCR
// gets those wrong, so the app always shows them to the person for checking; everything here is a best guess.
// Pure Kotlin: no Android types, so it runs on the JVM tests.
object DocFields {
  /** Lowercase, no accents, đ→d, one output char per input char so indexes in the folded text match the original. */
  fun foldKeep(s: String): String = buildString(s.length) {
    for (ch in s) {
      val c = if (ch == 'đ' || ch == 'Đ' || ch == 'Ð' || ch == 'ð') 'd' else ch
      val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
      append((d ?: ' ').lowercaseChar())
    }
  }

  private fun squash(s: String) = s.replace(Regex("\\s+"), " ").trim()
  private fun clean(s: String) = squash(s.replace('Ð', 'Đ').replace('ð', 'đ'))

  // Document types by their folded name; the value is how to show them.
  private val TYPES = linkedMapOf(
    "quyet dinh" to "Quyết định", "bao cao" to "Báo cáo", "to trinh" to "Tờ trình", "thong bao" to "Thông báo",
    "bien ban" to "Biên bản", "nghi quyet" to "Nghị quyết", "ke hoach" to "Kế hoạch", "chi thi" to "Chỉ thị",
    "huong dan" to "Hướng dẫn", "quy che" to "Quy chế", "quy dinh" to "Quy định", "thong tu" to "Thông tư",
    "nghi dinh" to "Nghị định", "giay moi" to "Giấy mời", "thu moi" to "Thư mời", "giay uy quyen" to "Giấy ủy quyền",
    "hop dong" to "Hợp đồng", "de an" to "Đề án", "de xuat" to "Đề xuất", "phieu trinh" to "Phiếu trình",
    "giay gioi thieu" to "Giấy giới thiệu", "cong dien" to "Công điện", "thong cao" to "Thông cáo", "ket luan" to "Kết luận",
    "chuong trinh" to "Chương trình", "phuong an" to "Phương án", "quy trinh" to "Quy trình",
  )

  private fun lev(a: String, b: String): Int {
    val d = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
      var prev = d[0]
      d[0] = i
      for (j in 1..b.length) {
        val t = d[j]
        d[j] = min(min(d[j] + 1, d[j - 1] + 1), prev + if (a[i - 1] == b[j - 1]) 0 else 1)
        prev = t
      }
    }
    return d[b.length]
  }

  private fun upperRatio(s: String): Double {
    val letters = s.filter { it.isLetter() }
    return if (letters.length < 3) 0.0 else letters.count { it.isUpperCase() }.toDouble() / letters.length
  }

  private fun letterRatio(s: String): Double = if (s.isEmpty()) 0.0 else s.count { it.isLetter() || it == ' ' }.toDouble() / s.length
  // Letters, digits and the punctuation of dates and numbers: what a real line is made of, as opposed to stamp noise.
  private fun textRatio(s: String): Double = if (s.isEmpty()) 0.0 else s.count { it.isLetterOrDigit() || it == ' ' || it == '/' || it == '.' || it == ',' || it == '-' }.toDouble() / s.length

  // Common signing titles, to repair what a stamp over the text left half-read.
  private val ROLES = listOf(
    "TỔNG GIÁM ĐỐC", "PHÓ TỔNG GIÁM ĐỐC", "GIÁM ĐỐC", "PHÓ GIÁM ĐỐC", "CHỦ TỊCH", "PHÓ CHỦ TỊCH", "KẾ TOÁN TRƯỞNG",
    "TRƯỞNG PHÒNG", "PHÓ TRƯỞNG PHÒNG", "TRƯỞNG BAN", "PHỤ TRÁCH BAN", "PHÓ TRƯỞNG BAN", "CHỦ TỊCH HỘI ĐỒNG THÀNH VIÊN",
    "CHỦ TỊCH HỘI ĐỒNG QUẢN TRỊ", "TRƯỞNG BAN KIỂM SOÁT", "CHÁNH VĂN PHÒNG", "PHÓ CHÁNH VĂN PHÒNG", "THỦ TRƯỞNG ĐƠN VỊ",
  )

  /** Drops the words OCR made up from a stamp or a pen stroke, then snaps to a known title when it's within a letter or two. */
  private fun cleanRole(raw: String): String {
    val kept = raw.split(' ').filter { t -> t.isNotEmpty() && (t.count { it.isLetter() } >= 2 && upperRatio(t) >= 0.7 && t.all { c -> c.isLetter() || c == '.' }) }
    val text = kept.joinToString(" ")
    val prefixes = setOf("TM.", "KT.", "TL.", "Q.", "TUQ.")
    val lead = kept.takeWhile { it in prefixes }
    val core = kept.drop(lead.size).joinToString(" ")
    val f = foldKeep(core)
    val hit = ROLES.firstOrNull { val g = foldKeep(it); f == g || (f.length >= 6 && lev(f, g) <= max(1, g.length / 8)) }
    return if (hit != null && core.isNotEmpty()) (lead + hit).joinToString(" ") else text
  }

  /** The name in `s` when the line is one (junk punctuation around it is ignored), else null. */
  private fun nameFrom(s: String): String? {
    val words = s.split(' ').map { it.trim { c -> !c.isLetter() } }.filter { it.isNotEmpty() }
    return words.joinToString(" ").takeIf { nameLike(it) }
  }

  /** A person's name: 2 to 6 words, each starting with a capital, no digits. */
  private fun nameLike(s: String): Boolean {
    val words = s.trim().split(' ').filter { it.isNotEmpty() }
    if (words.size !in 2..6 || s.any { it.isDigit() }) return false
    if (!words.all { it.length >= 2 && it[0].isUpperCase() && it.drop(1).all { c -> c.isLetter() && c.isLowerCase() } }) return false
    return s.count { it.isLetter() } >= 6
  }

  /** "Nơi nhận:" even when OCR turned the italic "Nơi" into something else. */
  private fun isNoiNhan(text: String): Boolean {
    val f = foldKeep(text)
    return f.startsWith("noi nhan") || Regex("^\\S{2,4}\\s*nhan\\s*[:;.]").containsMatchIn(f)
  }

  private fun sorted(lines: List<OcrLine>) = lines.sortedWith(compareBy({ it.y0 }, { it.x0 }))

  fun extract(pages: List<List<OcrLine>>): Extracted {
    if (pages.isEmpty()) return Extracted()
    val first = sorted(pages[0].map { it.copy(text = clean(it.text)) }.filter { it.text.isNotEmpty() })
    var r = header(first)
    r = r.copy(coQuan = agency(first))
    r = body(first, r)

    // "Nơi nhận" and the signature: the first page that has the words (scanning forward, so an annex can't hide them),
    // otherwise the last page.
    val rest = pages.map { p -> sorted(p.map { it.copy(text = clean(it.text)) }.filter { it.text.isNotEmpty() }) }
    val at = rest.indices.firstOrNull { i -> rest[i].any { l -> isNoiNhan(l.text) && l.x0 < 0.5f && (i > 0 || l.y0 > 0.45f) } }
    if (at != null) r = tail(rest[at], r)
    return r
  }

  // ── Header: number, date ───────────────────────────────────────────────────

  private fun header(lines: List<OcrLine>): Extracted {
    var r = Extracted()
    val top = lines.filter { it.y0 < 0.42f }

    val soLine = top.firstOrNull { Regex("^s[oe0]\\s*[:;.,]").containsMatchIn(foldKeep(it.text)) && it.x0 < 0.6f }
      ?: top.firstOrNull { Regex("/\\s*\\p{Lu}[\\p{L}\\p{N}\\-.]+").containsMatchIn(it.text) && it.x0 < 0.6f }
    if (soLine != null) {
      val label = Regex("^s[oe0]\\s*[:;.,]").find(foldKeep(soLine.text))
      val body = if (label != null) soLine.text.substring(label.range.last + 1) else soLine.text
      val m = Regex("^(.*?)/\\s*(\\p{Lu}[\\p{L}\\p{N}\\-.]*)").find(body.trim())
      if (m != null) {
        val num = m.groupValues[1].trim().trim('.', ' ')
        val digits = num.filter { it.isDigit() }
        val ky = m.groupValues[2].trimEnd('.', '-').replace("ĐĐ", "Đ")
        r = r.copy(so = digits, kyHieu = ky, soSure = num.isNotEmpty() && num.all { it.isDigit() } && num.length <= 5)
      } else {
        val digits = body.filter { it.isDigit() }
        r = r.copy(so = digits, soSure = body.trim().all { it.isDigit() } && digits.isNotEmpty())
      }
      r = r.copy(soBox = pad(Region(soLine.x0, soLine.y0, soLine.x1, soLine.y1)))
    }

    // Date: "..., ngày 14 tháng 8 năm 2026", or "ngày 01/10/2026".
    for (l in top.filter { it.y0 < 0.42f }) {
      val f = foldKeep(l.text)
      val m = Regex("ngay\\s*(\\S{0,4}?)\\s*thang\\s*(\\S{0,4}?)\\s*nam\\s*(\\d{4})").find(f)
      val n = if (m == null) Regex("(\\d{1,2})\\s*[/.\\-]\\s*(\\d{1,2})\\s*[/.\\-]\\s*(20\\d\\d)").find(f).takeIf { f.contains("ngay") } else null
      val g = m ?: n ?: continue
      fun part(s: String, lo: Int, hi: Int) = s.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } && it.length <= 2 && it.toInt() in lo..hi }?.toInt()?.toString().orEmpty()
      val place = if (m != null) l.text.substring(0, m.range.first).trim(' ', ',', '.', '-') else l.text.substring(0, max(0, f.indexOf("ngay"))).trim(' ', ',', '.', '-')
      r = r.copy(
        ngay = part(g.groupValues[1], 1, 31), thang = part(g.groupValues[2], 1, 12), nam = g.groupValues[3],
        diaDanh = if (place.length in 3..40 && letterRatio(place) > 0.8) place else "",
        ngayBox = pad(Region(l.x0, l.y0, l.x1, l.y1)),
      )
      break
    }
    return r
  }

  private fun pad(r: Region) = Region((r.x0 - 0.02f).coerceAtLeast(0f), (r.y0 - 0.008f).coerceAtLeast(0f), (r.x1 + 0.02f).coerceAtMost(1f), (r.y1 + 0.008f).coerceAtMost(1f))

  /** The issuing body: the top-left lines above the "Số:" line. */
  private fun agency(lines: List<OcrLine>): String {
    val soY = lines.firstOrNull { Regex("^s[oe0]\\s*[:;.,]").containsMatchIn(foldKeep(it.text)) && it.x0 < 0.6f && it.y0 < 0.42f }?.y0
      ?: lines.firstOrNull { it.y0 < 0.42f && Regex("ngay\\s*\\S{0,4}\\s*thang").containsMatchIn(foldKeep(it.text)) }?.y0 ?: 0.25f
    val left = lines.filter { it.y1 <= soY + 0.005f && it.cx < 0.5f && it.y0 < 0.3f && !foldKeep(it.text).contains("cong hoa") && !foldKeep(it.text).contains("doc lap") }
    return squash(left.joinToString(" ") { it.text })
  }

  // ── Body: type, subject, recipients ────────────────────────────────────────

  private fun body(lines: List<OcrLine>, start: Extracted): Extracted {
    var r = start
    val afterY = max(r.ngayBox?.y1 ?: 0f, r.soBox?.y1 ?: 0f).let { if (it == 0f) 0.2f else it }
    val cand = lines.filter { it.y0 >= afterY - 0.01f }
    val gapLimit = 2.2f

    // Type line.
    var loai: Pair<Int, String>? = null // index in cand, display
    var rest = ""
    for ((i, l) in cand.withIndex().take(14)) {
      if (l.cx < 0.25f || l.cx > 0.75f) continue
      val f = squash(foldKeep(l.text.filter { it.isLetter() || it == ' ' }))
      if (f.length < 4 || upperRatio(l.text) < 0.6) continue
      val hit = TYPES.keys.firstOrNull { f == it || lev(f, it) <= 1 || f.startsWith("$it ") }
      if (hit != null) {
        loai = i to TYPES.getValue(hit)
        if (f.startsWith("$hit ") && f != hit) rest = l.text.substring(min(l.text.length, hit.length)).trim()
        break
      }
    }
    var from = -1
    if (loai != null) {
      r = r.copy(loai = loai.second)
      from = loai.first + 1
    } else {
      // A "công văn" has no type line: the subject starts at "V/v".
      val vv = cand.indexOfFirst { val f = foldKeep(it.text); f.startsWith("v/v") || f.startsWith("ve viec") }
      if (vv >= 0) { r = r.copy(loai = "Công văn"); from = vv }
      else {
        // Unknown type: the first big all-caps line in the middle.
        val i = cand.withIndex().firstOrNull { (_, l) -> l.cx in 0.3f..0.7f && upperRatio(l.text) > 0.8 && l.text.count { it.isLetter() } in 4..40 }?.index
        if (i != null) { r = r.copy(loai = cand[i].text.lowercase().replaceFirstChar { it.uppercase() }); from = i + 1 }
        else {
          // A title the OCR garbled: the first line in the middle that is set noticeably bigger than the rest.
          val typical = lines.map { it.h }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
          val j = cand.withIndex().firstOrNull { (_, l) -> l.cx in 0.3f..0.7f && typical > 0 && l.h >= typical * 1.25f && upperRatio(l.text) > 0.5 && textRatio(l.text) > 0.9 && l.text.count { it.isLetter() } in 4..60 }?.index
          if (j != null) { r = r.copy(loai = clean(cand[j].text).lowercase().replaceFirstChar { it.uppercase() }); from = j + 1 }
        }
      }
    }
    if (from >= 0) {
      val subject = ArrayList<String>()
      if (rest.isNotEmpty()) subject.add(rest)
      var prev = cand.getOrNull(from - 1)
      for (l in cand.drop(from).take(8)) {
        val f = foldKeep(l.text)
        if (f.startsWith("kinh gui") || f.startsWith("can cu") || f.startsWith("theo ") || f.startsWith("1.") || f.startsWith("dieu 1")) break
        if (upperRatio(l.text) > 0.85 && l.text.count { it.isLetter() } > 6 && !f.startsWith("v/v")) break // next heading, e.g. the body's addressee
        if (prev != null && prev.h > 0 && l.y0 - prev.y1 > gapLimit * prev.h) break
        if (textRatio(l.text) < 0.7 || l.text.count { it.isLetterOrDigit() } < 3) continue // stamp noise
        subject.add(l.text)
        prev = l
      }
      r = r.copy(trichYeu = squash(subject.joinToString(" ")).trim('-', '—', ' '))
    }

    // Kính gửi.
    val kg = cand.indexOfFirst { foldKeep(it.text).startsWith("kinh gui") && it.x0 < 0.5f }
    if (kg >= 0) {
      val items = ArrayList<String>()
      val head = cand[kg].text.substringAfter(':', "").trim()
      if (head.isNotEmpty()) items.add(head)
      var prev = cand[kg]
      for (l in cand.drop(kg + 1).take(10)) {
        val f = foldKeep(l.text)
        if (f.startsWith("dong kinh gui") || f.startsWith("can cu") || f.startsWith("thuc hien") || f.startsWith("theo ")) break
        if (Regex("^(\\d+|[ivx]+)\\s*[.)]").containsMatchIn(f)) break // "1. Tóm tắt": the body has begun
        if (l.y0 - prev.y1 > 1.8f * max(prev.h, 0.01f)) break
        val dash = l.text.firstOrNull() in setOf('-', '–', '—', '•', '·', '+')
        // After a one-line "Kính gửi: X" only a dashed line adds another addressee; a plain line continues the last one
        // when the last one wasn't finished.
        val unfinished = items.isNotEmpty() && !items.last().trimEnd().let { it.endsWith(";") || it.endsWith(".") }
        if (dash || items.isEmpty()) items.add(l.text.trimStart('-', '–', '—', '•', '·', '+', ' '))
        else if (unfinished && l.text.firstOrNull()?.isLowerCase() == true) items[items.lastIndex] = items.last() + " " + l.text
        else if (head.isNotEmpty() && items.size == 1 && !dash) break
        else items[items.lastIndex] = items.last() + " " + l.text
        prev = l
      }
      r = r.copy(kinhGui = items.joinToString("; ") { it.trim(';', ',', '.', ' ') })
    }
    return r
  }

  // ── Tail: "Nơi nhận" and the signature ────────────────────────────────────

  private fun tail(lines: List<OcrLine>, start: Extracted): Extracted {
    var r = start
    val nn = lines.firstOrNull { isNoiNhan(it.text) && it.x0 < 0.5f } ?: return r
    val items = ArrayList<String>()
    var prev = nn
    for (l in lines.filter { it.y0 > nn.y0 + 0.001f && it.x0 < 0.55f && it.cx < 0.62f }.take(14)) {
      if (l.y0 - prev.y1 > 2.2f * max(prev.h, 0.01f)) break
      if (letterRatio(l.text) < 0.55) continue
      val dash = l.text.firstOrNull() in setOf('-', '–', '—', '•', '·', '‹', '«', '.')
      val text = l.text.trimStart('-', '–', '—', '•', '·', '‹', '«', '.', ' ')
      if (dash || items.isEmpty()) items.add(text) else items[items.lastIndex] = items.last() + " " + text
      prev = l
      if (foldKeep(text).startsWith("luu")) break
    }
    r = r.copy(noiNhan = items.filter { it.count { c -> c.isLetter() } >= 4 && it.split(' ').any { w -> w.count { c -> c.isLetter() } >= 3 } && textRatio(it) > 0.75 }.joinToString("; ") { it.trim(';', ',', '.', ' ') })

    // Signature block: the right-hand column from just above "Nơi nhận" down.
    val right = lines.filter { it.x0 >= 0.42f && it.y0 >= nn.y0 - 0.14f }
    val role = right.filter { l ->
      val f = foldKeep(l.text)
      (upperRatio(l.text) > 0.8 && l.text.count { it.isLetter() } >= 4 && letterRatio(l.text) > 0.8 && l.y0 <= nn.y0 + 0.25f) || Regex("^(tm|kt|tl|q|tuq)[.\\s]").containsMatchIn(f)
    }
    val nameLine = right.lastOrNull { nameFrom(it.text) != null && it.y0 >= nn.y0 - 0.02f }
    val roleText = role.filter { nameLine == null || it.y0 < nameLine.y0 }.joinToString(" ") { it.text }
    r = r.copy(chucVu = cleanRole(roleText), nguoiKy = nameLine?.let { nameFrom(it.text) }.orEmpty())
    return r
  }
}
