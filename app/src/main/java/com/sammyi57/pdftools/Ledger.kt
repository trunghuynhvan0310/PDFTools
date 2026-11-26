package com.sammyi57.pdftools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.LocalDate

/** One line of the "Sổ công văn": a scanned administrative document and what was read (and checked) from it. */
@Serializable
data class VanBan(
  val id: String,
  val created: Long,
  val pdf: String = "", // where the scanned PDF is in Files; empty when it was left out or deleted
  val pages: Int = 0,
  val so: String = "", val kyHieu: String = "",
  val ngay: String = "", val thang: String = "", val nam: String = "",
  val diaDanh: String = "", val coQuan: String = "", val loai: String = "", val trichYeu: String = "",
  val kinhGui: String = "", val noiNhan: String = "", val nguoiKy: String = "", val chucVu: String = "",
) {
  val soKyHieu get() = if (so.isEmpty() && kyHieu.isEmpty()) "" else if (kyHieu.isEmpty()) so else "$so/$kyHieu"

  /** Whole date as dd/MM/yyyy, or "" while any part is missing or impossible. */
  val ngayDayDu: String get() = date()?.let { "%02d/%02d/%04d".format(it.dayOfMonth, it.monthValue, it.year) } ?: ""

  fun date(): LocalDate? = runCatching { LocalDate.of(nam.toInt(), thang.toInt(), ngay.toInt()) }.getOrNull()

  /** A date to sort by even when only part of it is known. */
  val sortKey: Long get() = (nam.toIntOrNull() ?: 0) * 10_000L + (thang.toIntOrNull() ?: 0) * 100 + (ngay.toIntOrNull() ?: 0)

  /** What to call the entry in lists: "Báo cáo 231/BC-KTNB". */
  val title: String get() = listOf(loai, soKyHieu).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "Văn bản chưa đặt tên" }

  /** "14/08/2026", or what is known of it ("08/2026", "…"). */
  val ngayHienThi: String get() = ngayDayDu.ifEmpty {
    listOf(ngay, thang, nam).filter { it.isNotEmpty() }.joinToString("/").ifEmpty { "chưa có ngày" }
  }
}

/** The ledger on disk (a small JSON file in the app's private storage) and in memory for the screens to watch. */
object Ledger {
  private val file get() = File(appContext.filesDir, "so-cong-van.json")

  var items by mutableStateOf(load())
    private set

  private fun load(): List<VanBan> = runCatching { json.decodeFromString<List<VanBan>>(file.readText()) }.getOrDefault(emptyList()).sorted()

  private fun List<VanBan>.sorted() = sortedWith(compareByDescending<VanBan> { it.sortKey }.thenByDescending { it.created })

  private fun commit(list: List<VanBan>) {
    val sorted = list.sorted()
    items = sorted
    runCatching {
      val tmp = File(file.path + ".tmp")
      tmp.writeText(json.encodeToString(sorted))
      if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }
  }

  fun upsert(v: VanBan) = commit(items.filter { it.id != v.id } + v)

  fun remove(id: String) = commit(items.filter { it.id != id })

  fun get(id: String) = items.firstOrNull { it.id == id }
}

// ── Excel export ────────────────────────────────────────────────────────────

/**
 * The ledger as an .xlsx workbook, oldest document first, with the number on the first column. Written by hand (a zip of
 * a few XML parts) so no spreadsheet library is needed; real dates are stored as dates so Excel can sort and filter them.
 */
fun ledgerXlsx(items: List<VanBan>): ByteArray = xlsxOf(
  items.sortedWith(compareBy<VanBan> { it.sortKey }.thenBy { it.created }).map {
    ExportRow(it.soKyHieu, it.date(), it.ngayHienThi.takeIf { d -> d != "chưa có ngày" }.orEmpty(), it.coQuan, it.loai, it.trichYeu, it.kinhGui, it.noiNhan, it.nguoiKy, it.chucVu, it.pages, if (it.pdf.isEmpty()) "" else File(it.pdf).name)
  },
)

