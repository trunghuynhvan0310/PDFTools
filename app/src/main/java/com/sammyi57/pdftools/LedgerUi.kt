package com.sammyi57.pdftools

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The strip of the page where the number or the date is printed, to read against what the field says. */
@Composable
private fun Crop(path: String?, region: Region?, label: String) {
  if (path == null || region == null) return
  val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path, region) {
    value = withContext(Dispatchers.IO) { runCatching { GovScan.crop(path, region)?.asImageBitmap() }.getOrNull() }
  }
  val b = bmp ?: return
  T(label, Type.med, c.sub, Modifier.padding(top = 12.dp, bottom = 4.dp))
  Image(b, label, Modifier.fillMaxWidth().heightIn(max = 90.dp), contentScale = ContentScale.FillWidth)
}

@Composable
private fun Labeled(label: String, value: String, onChange: (String) -> Unit, keyboard: KeyboardType = KeyboardType.Text, modifier: Modifier = Modifier) {
  Column(modifier.padding(top = 12.dp)) {
    T(label, Type.med, c.sub, Modifier.padding(bottom = 4.dp))
    Field(value, onChange, Modifier.fillMaxWidth(), keyboard = keyboard)
  }
}

/** Edits one entry. The number and the date come with a crop of the page: handwriting is the part OCR gets wrong. */
@Composable
fun VanBanEditor(
  start: VanBan,
  pagePath: String?,
  soBox: Region?,
  ngayBox: Region?,
  title: String,
  saveLabel: String,
  onSave: (VanBan) -> Unit,
  onSkip: (() -> Unit)?,
  onDismiss: () -> Unit,
) {
  var v by remember(start.id) { mutableStateOf(start) }
  FullSheet(onDismiss) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
        T(title, Type.title)
        T("Số, ngày và người ký thường viết tay nên máy dễ đọc nhầm. Hãy đối chiếu với ảnh trang giấy.", Type.body, c.sub, Modifier.padding(top = 4.dp))
        Crop(pagePath, soBox, "Chỗ in số và ký hiệu")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Labeled("Số", v.so, { v = v.copy(so = it.trim()) }, modifier = Modifier.weight(1f))
          Labeled("Ký hiệu", v.kyHieu, { v = v.copy(kyHieu = it.trim()) }, modifier = Modifier.weight(2f))
        }
        Crop(pagePath, ngayBox, "Chỗ in ngày tháng")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Labeled("Ngày", v.ngay, { v = v.copy(ngay = it.filter(Char::isDigit).take(2)) }, KeyboardType.Number, Modifier.weight(1f))
          Labeled("Tháng", v.thang, { v = v.copy(thang = it.filter(Char::isDigit).take(2)) }, KeyboardType.Number, Modifier.weight(1f))
          Labeled("Năm", v.nam, { v = v.copy(nam = it.filter(Char::isDigit).take(4)) }, KeyboardType.Number, Modifier.weight(1.4f))
        }
        Labeled("Địa danh", v.diaDanh, { v = v.copy(diaDanh = it) })
        Labeled("Cơ quan ban hành", v.coQuan, { v = v.copy(coQuan = it) })
        Labeled("Loại văn bản", v.loai, { v = v.copy(loai = it) })
        Labeled("Tiêu đề (trích yếu)", v.trichYeu, { v = v.copy(trichYeu = it) })
        Labeled("Kính gửi", v.kinhGui, { v = v.copy(kinhGui = it) })
        Labeled("Nơi nhận", v.noiNhan, { v = v.copy(noiNhan = it) })
        Labeled("Người ký", v.nguoiKy, { v = v.copy(nguoiKy = it) })
        Labeled("Chức vụ", v.chucVu, { v = v.copy(chucVu = it) })
        Spacer(Modifier.size(16.dp))
      }
      Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn(saveLabel, Modifier.fillMaxWidth(), I.check) { onSave(v) }
        if (onSkip != null) Btn("Bỏ qua văn bản này", Modifier.fillMaxWidth(), kind = BtnKind.Secondary) { onSkip() }
        else Btn("Đóng", Modifier.fillMaxWidth(), kind = BtnKind.Secondary) { onDismiss() }
      }
    }
  }
}

/** After a Công văn scan: goes through each document found, one at a time, for the person to check and put in the ledger. */
@Composable
fun GovConfirm(docs: List<GovDoc>, pagePaths: List<String>, pdfPath: String, onDone: (saved: Int) -> Unit) {
  var at by remember { mutableStateOf(0) }
  var saved by remember { mutableStateOf(0) }
  val doc = docs.getOrNull(at)
  if (doc == null) { LaunchedEffect(Unit) { onDone(saved) }; return }
  val f = doc.fields
  val start = remember(at) {
    VanBan(
      id = uid(), created = System.currentTimeMillis(), pdf = pdfPath, pages = doc.count,
      so = f.so, kyHieu = f.kyHieu, ngay = f.ngay, thang = f.thang, nam = f.nam, diaDanh = f.diaDanh, coQuan = f.coQuan,
      loai = f.loai, trichYeu = f.trichYeu, kinhGui = f.kinhGui, noiNhan = f.noiNhan, nguoiKy = f.nguoiKy, chucVu = f.chucVu,
    )
  }
  val last = at == docs.lastIndex
  val next = { at += 1 }
  VanBanEditor(
    start, pagePaths.getOrNull(doc.first), f.soBox, f.ngayBox,
    title = if (docs.size > 1) "Xác nhận · văn bản ${at + 1}/${docs.size}" else "Xác nhận văn bản",
    saveLabel = if (last) "Lưu vào sổ" else "Lưu và xem văn bản tiếp",
    onSave = { Ledger.upsert(it); saved++; next() },
    onSkip = next,
    onDismiss = { onDone(saved) },
  )
}

/** The Sổ công văn: every document put in the ledger, with search, editing and an Excel export. */
@Composable
fun LedgerScreen() {
  val nav = LocalNav.current
  var q by remember { mutableStateOf("") }
  var editing by remember { mutableStateOf<VanBan?>(null) }
  var removing by remember { mutableStateOf<VanBan?>(null) }
  var failure by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()
  val all = Ledger.items
  val tokens = fold(q.trim()).split(Regex("\\s+")).filter { it.isNotEmpty() }
  val shown = if (tokens.isEmpty()) all else all.filter { v ->
    val hay = fold(listOf(v.soKyHieu, v.ngayHienThi, v.coQuan, v.loai, v.trichYeu, v.kinhGui, v.noiNhan, v.nguoiKy, v.chucVu).joinToString(" "))
    tokens.all { hay.contains(it) }
  }

  Screen(bottom = 40.dp) {
    BackBar("Công cụ") { nav.back() }
    LargeTitle("Sổ công văn", if (all.isEmpty()) "Chưa có văn bản nào" else "${all.size} văn bản, mới nhất ở trên")
    if (all.isEmpty()) {
      Empty({ Icon(I.fileDocumentOutline, 56.dp, c.sub) }, "Sổ còn trống", "Quét bằng chế độ Công văn, kiểm tra các trường máy đọc được rồi bấm Lưu vào sổ.") {
        Btn("Quét công văn", Modifier.fillMaxWidth(), I.fileDocumentEditOutline) { nav.scan() }
      }
      return@Screen
    }
    SearchField(q, { q = it }, "Số, tiêu đề, người ký…")
    Btn("Xuất Excel", Modifier.fillMaxWidth().padding(top = 12.dp), I.downloadOutline) {
      scope.launch {
        try {
          val bytes = withContext(Dispatchers.Default) { ledgerXlsx(all) }
          val f = saveTemp("So-cong-van.xlsx", bytes)
          share(f.path, "So-cong-van.xlsx")
        } catch (e: Exception) {
          failure = e.message ?: "Không xuất được tệp."
        }
      }
    }
    if (shown.isEmpty()) {
      T("Không có văn bản nào khớp \"${q.trim()}\".", Type.body, c.sub, Modifier.fillMaxWidth().padding(top = 32.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
    } else {
      Group(Modifier.padding(top = 14.dp)) {
        shown.forEachIndexed { i, v ->
          ListRow(
            v.title, first = i == 0, chevron = true,
            subtitle = listOf(v.ngayHienThi, v.trichYeu.ifEmpty { v.coQuan }).filter { it.isNotEmpty() }.joinToString(" · "),
            onLongClick = { removing = v }, onClick = { editing = v },
          )
        }
      }
      T("Giữ lâu một dòng để xóa khỏi sổ.", Type.body, c.sub, Modifier.padding(top = 10.dp, start = 4.dp))
    }
  }

  editing?.let { v ->
    VanBanEditor(
      v, null, null, null, "Sửa văn bản", "Lưu thay đổi",
      onSave = { Ledger.upsert(it); editing = null }, onSkip = null, onDismiss = { editing = null },
    )
  }
  removing?.let { v ->
    ConfirmDialog(
      title = "Xóa khỏi sổ?",
      message = "${v.title} sẽ bị xóa khỏi sổ. Tệp PDF đã quét vẫn còn trong Tệp.",
      confirmLabel = "Xóa", danger = true,
      onDismiss = { removing = null },
    ) { Ledger.remove(v.id) }
  }
  failure?.let { m -> AppDialog({ failure = null }, "Không xuất được") { T(m, Type.body, c.sub) } }
}
