package com.sammyi57.pdftools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File

// Tool catalog: what each screen asks for and which engine op it runs.

/** A tool's settings, as the UI left them: strings, booleans, and editor state (page order, boxes, items…). */
typealias Opts = JsonObject

val NoOpts = JsonObject(emptyMap())
fun Opts.str(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull ?: ""
fun Opts.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
fun Opts.with(k: String, v: String): Opts = JsonObject(this + (k to JsonPrimitive(v)))
fun Opts.with(k: String, v: Boolean): Opts = JsonObject(this + (k to JsonPrimitive(v)))
fun Opts.with(k: String, v: JsonElement): Opts = JsonObject(this + (k to v))
inline fun <reified T> Opts.get(k: String): T? = this[k]?.let { runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull() }
inline fun <reified T> Opts.put(k: String, v: T): Opts = with(k, json.encodeToJsonElement(v))

sealed class OptDef(val key: String, val label: String, val visible: ((Opts) -> Boolean)?) {
  class Seg(key: String, label: String, val choices: List<Pair<String, String>>, val def: String, visible: ((Opts) -> Boolean)? = null) :
    OptDef(key, label, visible)

  class Text(
    key: String, label: String, val def: String, val placeholder: String = "", val keyboard: KeyboardType = KeyboardType.Text,
    val strip: Regex? = null, visible: ((Opts) -> Boolean)? = null,
  ) : OptDef(key, label, visible)

  class Toggle(key: String, label: String, val def: Boolean, visible: ((Opts) -> Boolean)? = null) : OptDef(key, label, visible)
}

fun defaults(defs: List<OptDef>): Opts = JsonObject(defs.associate {
  it.key to when (it) {
    is OptDef.Seg -> JsonPrimitive(it.def)
    is OptDef.Text -> JsonPrimitive(it.def)
    is OptDef.Toggle -> JsonPrimitive(it.def)
  }
})

enum class Input { Pdf, Pdfs, Images }

class Output(val name: String, val bytes: ByteArray, val ext: String = "pdf", val full: Boolean = false) // full: name already includes the source file's

class Step(val title: String, val opts: List<OptDef>, val fixed: Opts)

class Tool(
  val id: String,
  val section: Int,
  val title: String,
  val desc: String,
  val cta: String,
  val doing: String, // shown on the button while it runs
  val input: Input,
  val min: Int? = null,
  val noPreview: Boolean = false, // output isn't a PDF, or the editor already shows the result
  val opts: List<OptDef> = emptyList(),
  val step: Step? = null, // as a workflow step: its own title and options, and options fixed for it
  val run: (List<Named>, Opts) -> List<Output>,
) {
  val stepTitle get() = step?.title ?: title
  val stepOpts get() = step?.opts ?: opts
}

@Serializable
data class WorkflowStep(val id: String, val op: String, val opts: JsonObject)

// Grouped by the job you're doing, in the order those jobs usually come: get pages in, put them in order, put
// something on them, then finish up. "Capture" is shown as the Scan card on Home, and as a group only in search.
val SECTIONS = listOf("Quét", "Gộp & tách", "Sắp xếp trang", "Điền & đánh dấu", "Bảo vệ & nén", "Tự động hóa")

private fun seg(key: String, label: String, choices: List<Pair<String, String>>, def: String, visible: ((Opts) -> Boolean)? = null) =
  OptDef.Seg(key, label, choices, def, visible)

private fun text(key: String, label: String, def: String, placeholder: String = "", keyboard: KeyboardType = KeyboardType.Text) =
  OptDef.Text(key, label, def, placeholder, keyboard)

// Number keyboards still offer letters on many phones, and some have no comma or dash, so fields filter what they take.
private fun num(key: String, label: String, def: String, placeholder: String) =
  OptDef.Text(key, label, def, placeholder, KeyboardType.Decimal, Regex("[^0-9.]"))

private fun pageList(key: String, label: String, placeholder: String, visible: ((Opts) -> Boolean)? = null) =
  OptDef.Text(key, label, "", placeholder, KeyboardType.Text, Regex("[^0-9,\\-\\s]"), visible)

private object O {
  // "level" (or unset, in older drafts and workflow steps) picks a strength; "size" squeezes until it fits a limit.
  val squeeze = seg("mode", "Nén theo", listOf("level" to "Mức độ", "size" to "Dưới một dung lượng"), "level")
  val quality = seg("quality", "Mức nén", listOf("80" to "Nhẹ", "60" to "Cân bằng", "35" to "Mạnh"), "60") { it.str("mode") != "size" }
  val target = OptDef.Text("targetMB", "Dưới (MB)", "2", "2", KeyboardType.Decimal, Regex("[^0-9.]")) { it.str("mode") == "size" }
  val wmText = text("text", "Chữ hình mờ", "CONFIDENTIAL", "CONFIDENTIAL")
  val wmScale = seg("scale", "Kích thước", listOf("0.6" to "Nhỏ", "1" to "Vừa", "1.4" to "Lớn"), "1")
  val opacity = seg("opacity", "Độ đậm", listOf("0.1" to "10%", "0.2" to "20%", "0.35" to "35%"), "0.2")
  val numPos = seg("position", "Vị trí", listOf("left" to "Trái", "center" to "Giữa", "right" to "Phải"), "center")
  val numFmt = seg("format", "Định dạng", listOf("n" to "1, 2, 3", "of" to "Trang 1/N"), "n")
  val angle = seg("angle", "Xoay theo chiều kim đồng hồ", listOf("90" to "90°", "180" to "180°", "270" to "270°"), "90")
  val axis = seg("axis", "Lật", listOf("h" to "Ngang", "v" to "Dọc", "both" to "Cả hai"), "h")
  val perSheet = seg("perSheet", "Số trang mỗi tờ", listOf("2" to "2", "4" to "4", "6" to "6", "9" to "9", "16" to "16"), "4")
}

// The crop editor renders these itself, above the page it draws on.
val CROP_OPTS: List<OptDef> = listOf(
  seg("mode", "Chế độ", listOf("crop" to "Cắt trang", "resize" to "Đổi cỡ trang"), "crop"),
  seg("size", "Khổ giấy", SIZES.keys.map { it to it }, "A4") { it.str("mode") == "resize" },
)

private fun one(suffix: String, fn: (ByteArray, Opts) -> ByteArray) = { files: List<Named>, o: Opts -> listOf(Output(suffix, fn(files[0].bytes, o))) }
private fun many(fn: (ByteArray, Opts) -> List<Named>) = { files: List<Named>, o: Opts -> fn(files[0].bytes, o).map { Output(it.name, it.bytes) } }

// Workflow steps are the tools themselves: multi-PDF tools combine every current file, single-PDF tools run on each
// file. Excluded: camera input, custom editors, non-PDF output, and password (no step can read the PDF after it).
private val NOT_STEPS = setOf("scan", "workflow", "organize", "redact", "fillSign", "zip", "password", "unlock", "metadata")
fun stepTools() = TOOLS.filter { it.id !in NOT_STEPS }

private fun runWorkflow(files: List<Named>, steps: List<WorkflowStep>): List<Output> {
  if (steps.isEmpty()) fail("Hãy thêm ít nhất một bước.")
  var cur = files.map { Named(it.name.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), ""), it.bytes) }
  for (step in steps) {
    val t = toolById(step.op) ?: fail("Một bước trong quy trình này không còn khả dụng. Hãy xóa bước đó và thử lại.")
    val opts = JsonObject(step.opts + (t.step?.fixed ?: NoOpts))
    if (t.input == Input.Pdfs) {
      val base = cur[0].name
      cur = t.run(cur, opts).map { Named("$base-${it.name}", it.bytes) }
      continue
    }
    cur = cur.flatMap { f -> // one file at a time keeps memory flat
      val outs = t.run(listOf(f), opts)
      outs.map { Named(if (outs.size > 1) "${f.name}-${it.name}" else f.name, it.bytes) }
    }
  }
  return if (cur.size == 1) listOf(Output("processed", cur[0].bytes)) else cur.map { Output("${it.name}-processed", it.bytes, full = true) }
}

val TOOLS: List<Tool> = listOf(
  Tool(
    "scan", 0, "Quét tài liệu", "Dùng camera để quét bất kỳ tài liệu nào và xuất ra PDF, xử lý ngay trên điện thoại.",
    "Tạo PDF", "Đang tạo PDF", Input.Images,
  ) { _, _ -> fail("Hãy mở máy quét bằng nút Quét.") }, // the scanner builds its own PDF
  Tool(
    "workflow", 5, "Quy trình tự động",
    "Chọn một hoặc nhiều PDF, rồi nối các công cụ theo thứ tự — gộp, thêm hình mờ, nén, tách và nhiều hơn nữa chỉ trong một lần.",
    "Chạy quy trình", "Đang chạy các bước", Input.Pdfs, min = 1,
  ) { files, o -> runWorkflow(files, o.get<List<WorkflowStep>>("steps") ?: emptyList()) },
  Tool(
    "merge", 1, "Gộp PDF", "Gộp nhiều PDF thành một tài liệu. Sắp xếp lại thứ tự tệp trước khi gộp.", "Gộp PDF", "Đang gộp", Input.Pdfs,
  ) { files, _ -> listOf(Output("merged", merge(files))) },
  Tool(
    "alternate", 1, "Trộn trang xen kẽ", "Xếp xen kẽ các trang của hai hay nhiều PDF, lần lượt mỗi tệp một trang.",
    "Trộn trang", "Đang xen kẽ trang", Input.Pdfs,
    opts = listOf(OptDef.Toggle("reverse", "Đảo ngược các tệp sau (mặt sau của bản quét hai mặt)", false)),
  ) { files, o -> listOf(Output("mixed", alternate(files, o.bool("reverse")))) },
  Tool(
    "compress", 4, "Nén PDF", "Thu nhỏ ảnh và bản quét trong PDF để tệp nhẹ đi nhiều. Chữ vẫn sắc nét.",
    "Nén PDF", "Đang nén lại ảnh", Input.Pdf, opts = listOf(O.squeeze, O.quality, O.target),
    run = one("compressed") { b, o ->
      if (o.str("mode") == "size") compressToSize(b, o.str("targetMB")) else compress(b, o.str("quality").toIntOrNull() ?: 60)
    },
  ),
  Tool(
    "split", 1, "Tách PDF", "Tách PDF thành từng trang riêng hoặc trích các khoảng trang cụ thể.", "Tách PDF", "Đang tách", Input.Pdf,
    opts = listOf(
      seg("mode", "Tách thành", listOf("every" to "Từng trang", "ranges" to "Khoảng tùy chọn"), "every"),
      pageList("ranges", "Khoảng trang — mỗi khoảng một tệp", "1-3, 4-6, 7-") { it.str("mode") == "ranges" },
    ),
    run = many { b, o -> split(b, o.str("mode"), o.str("ranges")) },
  ),
  Tool(
    "splitText", 1, "Tách PDF theo chữ", "Bắt đầu một tệp mới ở mỗi trang chứa cụm từ bạn chọn.",
    "Tách theo chữ", "Đang đọc từng trang", Input.Pdf, opts = listOf(text("phrase", "Bắt đầu tệp mới ở các trang chứa", "", "vd: Số hóa đơn")),
    run = many { b, o -> splitByText(b, o.str("phrase")) },
  ),
  Tool(
    "splitBookmarks", 1, "Tách PDF theo dấu trang", "Biến mỗi chương hoặc mục có dấu trang thành một tệp PDF riêng.",
    "Tách theo dấu trang", "Đang đọc dấu trang", Input.Pdf, run = many { b, _ -> splitByBookmarks(b) },
  ),
  Tool(
    "splitHalf", 1, "Tách đôi PDF", "Cắt PDF thành hai tệp, hoặc cắt mỗi trang ở giữa cho bản quét sách.",
    "Tách đôi", "Đang cắt trang", Input.Pdf,
    opts = listOf(seg("mode", "Cách tách", listOf("file" to "Hai tệp", "vertical" to "Trái | Phải", "horizontal" to "Trên / Dưới"), "file")),
    run = many { b, o -> splitHalf(b, o.str("mode")) },
  ),
  Tool(
    "splitSize", 1, "Tách PDF theo dung lượng", "Chia PDF lớn thành các phần, mỗi phần nhỏ hơn giới hạn dung lượng.",
    "Tách theo dung lượng", "Đang đo các phần", Input.Pdf, opts = listOf(num("maxMB", "Dung lượng tối đa mỗi phần (MB)", "5", "5")),
    run = many { b, o -> splitBySize(b, o.str("maxMB")) },
  ),
  Tool(
    "organize", 2, "Sắp xếp trang", "Xoay, xóa và sắp xếp lại thứ tự các trang PDF dễ dàng.", "Lưu trang", "Đang lưu thứ tự trang",
    Input.Pdf, noPreview = true,
  ) { files, o -> listOf(Output("organized", organize(files[0].bytes, o.get<List<PageOrder>>("order") ?: emptyList()))) },
  Tool(
    "rotate", 2, "Xoay PDF", "Xoay từng trang hoặc cả PDF — 90°, 180°, 270°.", "Xoay PDF", "Đang xoay", Input.Pdf,
    opts = listOf(O.angle, pageList("pages", "Trang (để trống nếu chọn tất cả)", "vd: 1, 3-5")),
    run = one("rotated") { b, o -> rotate(b, o.str("angle").toIntOrNull() ?: 90, o.str("pages")) },
  ),
  Tool(
    "flip", 2, "Lật PDF", "Lật đối xứng mọi trang của PDF theo chiều ngang, dọc hoặc cả hai.", "Lật PDF", "Đang lật trang", Input.Pdf,
    opts = listOf(O.axis), run = one("flipped") { b, o -> flip(b, o.str("axis")) },
  ),
  Tool(
    "nup", 2, "Nhiều trang trên một tờ", "Xếp 2, 4, 6, 9 hoặc 16 trang PDF lên một tờ để tiết kiệm giấy.",
    "Xếp lên tờ", "Đang bố trí tờ", Input.Pdf, opts = listOf(O.perSheet, OptDef.Toggle("lines", "Đường kẻ mảnh giữa các trang", false)),
    run = one("n-up") { b, o -> nUp(b, o.str("perSheet"), o.bool("lines")) },
  ),
  Tool(
    "crop", 2, "Cắt & đổi cỡ",
    "Tự động cắt lề hoặc kéo các góc vào để giữ lại đúng phần bạn muốn, hoặc đổi cỡ mọi trang về khổ chuẩn (A4, Letter).",
    "Áp dụng", "Đang đổi cỡ trang", Input.Pdf, opts = CROP_OPTS,
    step = Step("Đổi cỡ trang", listOf(seg("size", "Khổ giấy", SIZES.keys.map { it to it }, "A4")), JsonObject(mapOf("mode" to JsonPrimitive("resize")))),
    run = one("resized") { b, o -> cropResize(b, o.str("mode").ifEmpty { "crop" }, o.str("size").ifEmpty { "A4" }, o.get<Map<String, List<Float>>>("boxes") ?: emptyMap()) },
  ),
  Tool(
    "zip", 1, "PDF sang ZIP", "Gói nhiều PDF vào một tệp nén ZIP.", "Tạo ZIP", "Đang nén ZIP", Input.Pdfs, min = 1, noPreview = true,
  ) { files, _ -> listOf(Output("bundle", zipFiles(files), ext = "zip")) },
  Tool(
    "fillSign", 3, "Điền & ký",
    "Gõ trực tiếp lên biểu mẫu và chèn ảnh chữ ký của bạn vào bất kỳ đâu trên mọi trang — biểu mẫu thật hay bản quét đều được.",
    "Áp dụng", "Đang ghi lên các trang", Input.Pdf,
    run = one("signed") { b, o ->
      val items = o.get<List<SignItem>>("items") ?: emptyList()
      if (items.isEmpty()) fail("Hãy thêm chữ hoặc ảnh lên một trang trước.")
      val images = HashMap<String, ByteArray>()
      for (it in items) {
        if (it.kind != "image" || it.uri in images) continue
        // A resumed draft can point at a picture the OS has since cleared from the cache.
        val f = File(pathOf(it.uri))
        if (!f.exists()) fail("Ảnh bạn thêm ở trang ${it.page + 1} không còn khả dụng. Hãy xóa và thêm lại.")
        images[it.uri] = f.readBytes()
      }
      fillSign(b, items, images)
    },
  ),
  Tool(
    "watermark", 3, "Thêm hình mờ", "Đóng dấu chữ chéo như CONFIDENTIAL hoặc DRAFT lên mọi trang.",
    "Thêm hình mờ", "Đang đóng dấu mọi trang", Input.Pdf, opts = listOf(O.wmText, O.wmScale, O.opacity),
    run = one("watermarked") { b, o -> watermark(b, o.str("text"), o.str("opacity").toFloatOrNull() ?: 0.2f, o.str("scale").toFloatOrNull() ?: 1f) },
  ),
  Tool(
    "pageNumbers", 3, "Đánh số trang", "Đánh số mọi trang ở cuối trang, bên trái, giữa hoặc bên phải.", "Đánh số trang", "Đang đánh số trang",
    Input.Pdf, opts = listOf(O.numPos, O.numFmt), run = one("numbered") { b, o -> pageNumbers(b, o.str("position"), o.str("format")) },
  ),
  Tool(
    "redact", 3, "Che thông tin PDF", "Bôi đen tên, số hoặc mọi thông tin riêng tư vĩnh viễn, để không thể sao chép, tìm kiếm hay khôi phục.",
    "Che thông tin", "Đang bôi đen", Input.Pdf,
  ) { files, o ->
    val boxes = o.get<Map<String, List<List<Float>>>>("boxes") ?: emptyMap()
    if (boxes.isEmpty()) fail("Hãy kéo chọn ít nhất một vùng để bôi đen.")
    listOf(Output("redacted", redactPdf(files[0].bytes, boxes)))
  },
  Tool(
    "ocr", 4, "OCR – nhận dạng chữ",
    "Đọc chữ trong PDF đã quét và đặt lớp chữ vô hình lên các trang, để có thể tìm kiếm, chọn và sao chép. Chạy ngay trên điện thoại.",
    "Cho phép tìm kiếm", "Đang đọc từng trang", Input.Pdf,
    run = one("searchable") { b, _ -> ocrPdf(b) },
  ),
  Tool(
    "unlock", 4, "Gỡ mật khẩu", "Lưu bản sao của PDF bị khóa, mở được mà không cần mật khẩu và không bị hạn chế.",
    "Gỡ mật khẩu", "Đang mở khóa", Input.Pdf, noPreview = true,
    run = one("unlocked") { b, _ -> removePassword(b) }, // the screen asks for the password when the file is picked
  ),
  Tool(
    "metadata", 3, "Sửa thông tin PDF", "Đổi tiêu đề, tác giả, chủ đề và từ khóa mà các ứng dụng khác và Drive hiển thị cho PDF.",
    "Lưu thông tin", "Đang lưu thông tin", Input.Pdf, noPreview = true,
  ) { files, o -> listOf(Output("details", writeDetails(files[0].bytes, o.str("title"), o.str("author"), o.str("subject"), o.str("keywords")))) },
  Tool(
    "password", 4, "Đặt mật khẩu", "Khóa PDF để yêu cầu nhập mật khẩu trước khi mở.", "Đặt mật khẩu", "Đang mã hóa", Input.Pdf,
    noPreview = true, opts = listOf(text("password", "Mật khẩu", "", "Nhập mật khẩu", KeyboardType.Password)),
    run = one("protected") { b, o ->
      val pw = o.str("password")
      if (pw.isEmpty()) fail("Hãy nhập mật khẩu.")
      setPdfPassword(b, pw) ?: fail("PDF này đã có mật khẩu.")
    },
  ),
)

fun toolById(id: String?) = TOOLS.find { it.id == id }

/** Tools pinned to the top of Home, in the order they were pinned. */
object Pins {
  private val file get() = File(appContext.filesDir, "pinned-tools.txt")
  var ids by mutableStateOf(runCatching { file.readLines().filter { toolById(it) != null } }.getOrDefault(emptyList()))
    private set

  fun toggle(id: String) {
    ids = if (id in ids) ids - id else ids + id
    runCatching { file.writeText(ids.joinToString("\n")) }
  }
}

// ── The tool left unfinished ────────────────────────────────────────────────
// Its picked files and options, kept after going back or the app closing, so it can be reopened as you left it.

@Serializable
data class ToolDraft(val tool: String, val files: List<DraftFile>, val opts: JsonObject)

@Serializable
data class DraftFile(val name: String, @SerialName("uri") val path: String, val size: Long? = null)

object Drafts {
  private val file get() = File(appContext.filesDir, "tool-draft.json") // same file the Expo app used
  var current by mutableStateOf(read())
    private set

  private fun read(): ToolDraft? = try {
    val d = json.decodeFromString<ToolDraft>(file.readText())
    // Device picks live in the cache folder, which the OS may have cleared since.
    val files = d.files.map { it.copy(path = pathOf(it.path)) }.filter { File(it.path).exists() }
    if (files.isEmpty()) null else d.copy(files = files)
  } catch (_: Exception) {
    null
  }

  fun set(d: ToolDraft?) {
    current = d
    try {
      if (d == null) file.delete() else file.writeText(json.encodeToString(d))
    } catch (_: Exception) {} // disk trouble: the draft just won't survive a restart
  }
}

fun Picked.toDraft() = DraftFile(name, path, size)
fun DraftFile.toPicked() = Picked(name, path, size)
