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
  val book: String = DEFAULT_BOOK, // which sổ it belongs to
  val modified: Long = 0, // last change, to settle which copy wins when two phones synced
  val deleted: Boolean = false, // kept as a marker so a deletion reaches the other phone
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

const val DEFAULT_BOOK = "default"

/** A named sổ (book) holding its own list of documents. */
@Serializable
data class Book(val id: String, val name: String, val created: Long, val modified: Long = 0, val deleted: Boolean = false)

/** Everything in the ledger file, including deletion markers; this is also what is synced to Drive. */
@Serializable
data class LedgerData(val books: List<Book> = emptyList(), val items: List<VanBan> = emptyList())

/** Keeps, for each id, whichever copy was changed last. Pure, so the same rule applies on every phone. */
fun mergeLedger(a: LedgerData, b: LedgerData): LedgerData {
  fun <T> pick(x: List<T>, y: List<T>, id: (T) -> String, at: (T) -> Long): List<T> {
    val m = LinkedHashMap<String, T>()
    for (t in x + y) { val k = id(t); val old = m[k]; if (old == null || at(t) > at(old)) m[k] = t }
    return m.values.toList()
  }
  return LedgerData(pick(a.books, b.books, { it.id }, { it.modified }), pick(a.items, b.items, { it.id }, { it.modified }))
}

/** The ledger on disk (a small JSON file in the app's private storage) and in memory for the screens to watch. */
object Ledger {
  private val file get() = File(appContext.filesDir, "so-cong-van.json")
  private val prefs get() = appContext.getSharedPreferences("ledger", android.content.Context.MODE_PRIVATE)

  private var data by mutableStateOf(load())

  private fun load(): LedgerData {
    val text = runCatching { file.readText() }.getOrNull() ?: return LedgerData()
    val d = runCatching { json.decodeFromString<LedgerData>(text) }.getOrNull()
      ?: runCatching { LedgerData(items = json.decodeFromString<List<VanBan>>(text)) }.getOrNull() // the first version: a bare list
      ?: return LedgerData()
    return d
  }

  /** The books people can see, oldest first. There is always one: the default is made on first use. */
  val books: List<Book> get() = data.books.filter { !it.deleted }.sortedBy { it.created }.ifEmpty { listOf(Book(DEFAULT_BOOK, "Sổ công văn", 0)) }

  /** Which sổ is open; remembered between runs. */
  private var sel by mutableStateOf<String?>(null)
  var current: String
    get() = (sel ?: prefs.getString("current", null))?.takeIf { id -> books.any { it.id == id } } ?: books.first().id
    set(v) { sel = v; prefs.edit().putString("current", v).apply() }

  /** Documents of the open sổ (or of [book]), newest first. */
  fun items(book: String = current): List<VanBan> =
    data.items.filter { !it.deleted && it.book == book }.sortedWith(compareByDescending<VanBan> { it.sortKey }.thenByDescending { it.created })

  fun count(book: String) = data.items.count { !it.deleted && it.book == book }

  private fun commit(d: LedgerData, sync: Boolean = true) {
    data = d
    runCatching {
      val tmp = File(file.path + ".tmp")
      tmp.writeText(json.encodeToString(d))
      if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }
    if (sync) syncSoon()
  }

  private fun now() = System.currentTimeMillis()

  /** Makes sure at least one real (not deleted) book exists before anything is put in it: the default one is made or revived. */
  private fun withBooks(): LedgerData {
    if (data.books.any { !it.deleted }) return data
    val t = now()
    val old = data.books.firstOrNull { it.id == DEFAULT_BOOK }
    return data.copy(books = data.books.filter { it.id != DEFAULT_BOOK } + (old?.copy(deleted = false, modified = t) ?: Book(DEFAULT_BOOK, "Sổ công văn", 0, t)))
  }

  fun upsert(v: VanBan) { val d = withBooks(); commit(d.copy(items = d.items.filter { it.id != v.id } + v.copy(modified = now(), deleted = false))) }

  fun remove(id: String) { data.items.firstOrNull { it.id == id }?.let { v -> commit(data.copy(items = data.items.filter { it.id != id } + v.copy(deleted = true, modified = now()))) } }

  fun get(id: String) = data.items.firstOrNull { it.id == id && !it.deleted }

  fun addBook(name: String): Book {
    val d = withBooks()
    val b = Book(uid(), name.trim(), now(), now())
    commit(d.copy(books = d.books + b))
    return b
  }

  fun renameBook(id: String, name: String) {
    val d = withBooks()
    commit(d.copy(books = d.books.map { if (it.id == id) it.copy(name = name.trim(), modified = now()) else it }))
  }

  /** Removes a sổ and its documents (the scanned PDFs stay in Files). */
  fun removeBook(id: String) {
    val d = withBooks()
    val t = now()
    commit(d.copy(
      books = d.books.map { if (it.id == id) it.copy(deleted = true, modified = t) else it },
      items = d.items.map { if (it.book == id && !it.deleted) it.copy(deleted = true, modified = t) else it },
    ))
  }

  // ── Drive sync ──

  fun exportJson(): String = json.encodeToString(data)

  /**
   * Merges the copy found on Drive (null when there is none) into this phone's. Returns true when Drive's copy is
   * missing something and needs this phone's version uploaded.
   */
  fun mergeRemote(text: String?): Boolean {
    val remote = text?.let { t -> runCatching { json.decodeFromString<LedgerData>(t) }.getOrNull() }
    if (text != null && remote == null) return false // unreadable: leave Drive's file alone rather than overwrite it
    if (remote == null && data.books.isEmpty() && data.items.isEmpty()) return false // nothing to put there yet
    val merged = if (remote == null) data else mergeLedger(data, remote)
    if (merged != data) commit(merged, sync = false)
    return remote == null || merged != remote
  }
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

