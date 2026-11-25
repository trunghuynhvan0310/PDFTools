package com.sammyi57.pdftools

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val FEEDBACK_FORM = "https://github.com/trunghuynhvan0310/PDFTools/issues/new"

@Composable
fun SettingsTab() {
  val files = remember(Store.version) { listAllFiles() }
  var clearConfirm by remember { mutableStateOf(false) }
  var clearChoice by remember { mutableStateOf(false) } // files are also in Google Drive
  var driveConfirm by remember { mutableStateOf(false) }
  var wiping by remember { mutableStateOf(false) }
  var terms by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  // Drive goes first: if it fails, the phone keeps its files and the error shows under Google Drive.
  val wipeBoth = {
    wiping = true
    scope.launch {
      try { Backup.deleteEverything() } catch (e: Exception) { Backup.update { it.copy(error = Backup.describe(e)) } }
      wiping = false
    }
    Unit
  }
  val clearAll = { keepDrive: Boolean ->
    if (keepDrive) Backup.keepInDriveOnly(files.map { it.path })
    clearFiles()
    clearChoice = false
  }
  val version = remember { runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull() ?: "" }

  Screen {
    LargeTitle("Cài đặt")

    GroupLabel("Giao diện", first = true)
    ThemePicker()

    GroupLabel("Bộ nhớ")
    Group {
      ListRow("Tệp đã lưu", first = true, lead = { Lead(I.folderOutline) }, value = files.size.toString())
      ListRow("Dung lượng đã dùng", lead = { Lead(I.harddisk) }, value = fmtSize(files.sumOf { it.size }))
      ListRow(
        "Xóa tất cả tệp đã lưu", lead = { Lead(I.trashCanOutline, true) }, danger = true, value = if (wiping) "Đang xóa…" else null,
        onClick = if (files.isEmpty() || wiping) null else ({ if (Backup.hasDriveCopies(files.map { it.path })) clearChoice = true else clearConfirm = true }),
      )
    }

    GroupLabel("Quét")
    Group {
      ListRow(
        "Làm bản quét tìm kiếm được", first = true, lead = { Lead(I.textRecognition) },
        subtitle = "Đọc chữ khi bạn lưu bản quét, để có thể tìm kiếm và sao chép. Chạy ngay trên điện thoại; việc lưu sẽ lâu hơn một chút.",
        onClick = { ScanPrefs.save(!ScanPrefs.searchable) },
      ) { Toggle(ScanPrefs.searchable) { ScanPrefs.save(it) } }
    }

    GroupLabel("Google Drive")
    DriveBackup()

    GroupLabel("Quyền riêng tư")
    Group {
      if (Backup.state.enabled) {
        ListRow("Xử lý ngay trên điện thoại", first = true, lead = { Lead(I.cellphoneLock) }, subtitle = "Mọi công cụ chạy trên thiết bị. PDF của bạn chỉ đồng bộ với Google Drive của chính bạn, không ai khác thấy được.")
      } else {
        ListRow("Tệp nằm trên điện thoại này", first = true, lead = { Lead(I.cellphoneLock) }, subtitle = "Mọi công cụ chạy trên thiết bị. PDF chỉ rời khỏi máy nếu bạn bật đồng bộ Google Drive.")
      }
      ListRow("Không cần tài khoản, không theo dõi", lead = { Lead(I.accountOffOutline) }, subtitle = "Không đăng ký, không phân tích, không quảng cáo.")
      ListRow("Dùng được khi không có mạng", lead = { Lead(I.airplane) }, subtitle = "Mọi công cụ hoạt động ở chế độ máy bay. Việc đồng bộ chờ đến khi có mạng.")
    }

    GroupLabel("Giới thiệu")
    Group {
      ListRow("Phiên bản", first = true, lead = { Lead(I.informationOutline) }, value = version)
      ListRow("Bộ xử lý PDF", lead = { Lead(I.codeTags) }, value = "PdfBox")
      ListRow("Chính sách quyền riêng tư & Điều khoản", lead = { Lead(I.shieldCheckOutline) }, chevron = true, onClick = { terms = true })
      ListRow(
        "Gửi phản hồi", lead = { Lead(I.messageTextOutline) }, subtitle = "Cho biết cần sửa hay thêm gì. Mở trang góp ý trên GitHub.",
        onClick = { openUrl(FEEDBACK_FORM) },
      ) { Icon(I.openInNew, 18.dp, c.faint) }
    }
  }

  if (terms) FullSheet({ terms = false }) { Consent(onClose = { terms = false }) }
  if (clearConfirm) {
    ConfirmDialog(
      "Xóa tất cả tệp đã lưu?", "${plural(files.size, "file")} tạo bởi PDF Tools sẽ bị xóa. Các PDF bạn đã chọn làm đầu vào không bị ảnh hưởng.",
      "Xóa tất cả", danger = true, requireTyping = "xác nhận", onDismiss = { clearConfirm = false },
    ) { clearFiles() }
  }
  if (clearChoice) {
    AppDialog({ clearChoice = false }, "Xóa tất cả tệp đã lưu?", "${plural(files.size, "file")} tạo bởi PDF Tools. Chúng cũng có trong Google Drive của bạn.") {
      Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn("Chỉ xóa khỏi điện thoại", Modifier.fillMaxWidth(), I.cellphoneRemove, BtnKind.Secondary) { clearAll(true) }
        Btn("Xóa khỏi điện thoại và Drive", Modifier.fillMaxWidth(), I.deleteOutline, BtnKind.Danger) { clearChoice = false; driveConfirm = true }
        T(
          "Chỉ xóa khỏi điện thoại sẽ giữ mọi thứ trên Drive; tải lại tệp từ Tệp → Google Drive. Xóa khỏi điện thoại và Drive sẽ chuyển các bản trên Drive vào Thùng rác của Drive trong 30 ngày.",
          Type.small, c.sub, align = TextAlign.Center,
        )
      }
    }
  }
  if (driveConfirm) {
    ConfirmDialog(
      "Xóa khỏi điện thoại và Drive?", "${plural(files.size, "file")} sẽ bị xóa khỏi điện thoại. Mọi thứ trong thư mục PDF Tools trên Drive, gồm cả thư mục và tệp chỉ có trên Drive, sẽ vào Thùng rác của Drive trong 30 ngày.",
      "Xóa tất cả", danger = true, requireTyping = "xác nhận", onDismiss = { driveConfirm = false },
    ) { wipeBoth() }
  }
}

@Composable
fun Lead(glyph: Int, danger: Boolean = false) {
  Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Icon(glyph, 20.dp, if (danger) c.accent else c.sub) }
}

@Composable
private fun DriveBackup() {
  val scope = rememberCoroutineScope()
  val state = Backup.state
  val syncing = Backup.syncing
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf("") }
  var confirmOff by remember { mutableStateOf(false) }
  // Turning sync on and changing accounts both go through the account picker.
  val connect = {
    scope.launch {
      busy = true
      error = ""
      try { Backup.connect() } catch (e: Exception) { error = e.message ?: "Đăng nhập Google thất bại." }
      busy = false
    }
    Unit
  }
  val status = when {
    !state.enabled -> "Tắt"
    syncing -> "Đang đồng bộ…"
    state.error != null -> "Cần chú ý"
    state.lastSync != null -> "Đã đồng bộ · ${fmtDate(state.lastSync)}"
    else -> "Chưa đồng bộ"
  }
  Group {
    ListRow(
      "Đồng bộ PDF với Google Drive", first = true, lead = { Lead(I.googleDrive) },
      subtitle = if (state.enabled) state.email ?: "Đã đăng nhập" else "Giữ PDF và thư mục giống nhau trên điện thoại và trên Drive của bạn",
    ) {
      if (busy) Spinner(c.accent) else Toggle(state.enabled) { on -> if (on) connect() else confirmOff = true }
    }
    if (state.enabled) {
      ListRow("Trạng thái", lead = { Lead(I.cloudSyncOutline) }, value = status)
      ListRow("Đồng bộ ngay", lead = { Lead(I.sync) }, onClick = if (syncing) null else ({ Backup.syncNow(); Unit }))
      ListRow("Đổi tài khoản", lead = { Lead(I.accountSwitchOutline) }, subtitle = "Đồng bộ với tài khoản Google khác", onClick = if (busy) null else connect)
    }
  }
  // The last run's problem is hidden while a new run is trying again.
  val problem = error.ifEmpty { if (state.enabled && !syncing) state.error ?: "" else "" }
  if (problem.isNotEmpty()) T(problem, Type.small, c.accent, Modifier.padding(top = 8.dp))
  if (state.enabled) {
    T(
      "Đồng bộ hai chiều mỗi khi bạn lưu, đổi tên, chuyển hoặc xóa tệp, và khi bạn mở Tệp. Tệp đã xóa vào Thùng rác của Drive trong 30 ngày. Ứng dụng chỉ thấy các tệp do PDF Tools tạo trên Drive của bạn.",
      Type.small, c.sub, Modifier.padding(top = 8.dp),
    )
  }
  if (confirmOff) {
    ConfirmDialog(
      "Tắt đồng bộ Drive?", "PDF Tools sẽ đăng xuất khỏi Google Drive. Tệp của bạn vẫn ở trên điện thoại và trên Drive, nhưng ngừng đồng bộ.",
      "Tắt", onDismiss = { confirmOff = false },
    ) { scope.launch { busy = true; Backup.disable(); busy = false } }
  }
}

@Composable
private fun ThemePicker() {
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    listOf(ThemePref.System to "Hệ thống", ThemePref.Light to "Sáng", ThemePref.Dark to "Tối").forEach { (p, label) ->
      val on = ThemeState.pref == p
      val border by animateColorAsState(if (on) c.accent else c.lineStrong, tween(220), label = "theme")
      val width by animateDpAsState(if (on) 2.5.dp else 1.dp, tween(220), label = "themeW")
      val shape = RoundedCornerShape(Radius.control)
      Column(Modifier.weight(1f).tap(scaleTo = 0.95f, label = label) { ThemeState.set(p) }, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(88.dp).clip(shape).border(width, border, shape)) {
          if (p == ThemePref.System) {
            Mini(LightPalette, Modifier.weight(1f))
            Mini(DarkPalette, Modifier.weight(1f))
          } else Mini(if (p == ThemePref.Light) LightPalette else DarkPalette, Modifier.weight(1f))
        }
        T(label, if (on) Type.bold.copy(fontSize = Type.small.fontSize) else Type.small, if (on) c.ink else c.sub, Modifier.padding(top = 8.dp))
      }
    }
  }
}

// A tiny drawing of the app in that palette: a title, the hero card, the floating bar.
@Composable
private fun Mini(p: Palette, modifier: Modifier) {
  Column(modifier.fillMaxHeight().background(p.bg).padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
    Box(Modifier.height(6.dp).fillMaxWidth(0.65f).background(p.ink, RoundedCornerShape(3.dp)))
    Box(Modifier.weight(1f).fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(p.hero), RoundedCornerShape(5.dp)))
    Box(Modifier.height(7.dp).fillMaxWidth(0.8f).align(Alignment.CenterHorizontally).background(p.panel, RoundedCornerShape(4.dp)))
  }
}
