package com.sammyi57.pdftools

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Filter(val label: String) { All("Tất cả"), Pdf("PDF"), Zip("ZIP"), Recent("Gần đây") }

private enum class DriveFilter(val label: String) { All("Tất cả"), Recent("Gần đây"), OnPhone("Trên máy"), DriveOnly("Chỉ trên Drive") }

/** Saved: what the app made. Phone: every PDF on the device. Drive: the synced Google Drive folder. */
private enum class Place { Saved, Phone, Drive }

private sealed interface Menu {
  data class OfFile(val f: Saved) : Menu
  data class OfFolder(val f: Folder, val count: Int) : Menu
}

@Composable
fun FilesTab() {
  val nav = LocalNav.current
  val scope = rememberCoroutineScope()
  var folderPath by rememberSaveable { mutableStateOf<String?>(null) }
  var driveFolder by rememberSaveable { mutableStateOf<String?>(null) }
  var place by rememberSaveable { mutableStateOf(Place.Saved) }
  val onDrive = place == Place.Drive
  var filter by rememberSaveable { mutableStateOf(Filter.All) }
  var query by rememberSaveable { mutableStateOf("") }

  val version = Store.version
  val folders = remember(version) { listFolders().map { it to listFiles(it.path).size } }
  val folder = folders.firstOrNull { it.first.path == folderPath }?.first
  val files = remember(version, folderPath) { listFiles(folderPath) }
  val all = remember(version) { listAllFiles() }
  val scans = remember(version) { listDocs().mapNotNull { d -> d.pdf?.let { it.name to d.id } }.toMap() }

  // Opening Files also pulls in changes made in Drive.
  LaunchedEffect(Unit) { if (Backup.state.enabled) Backup.syncNow() }

  val folderName = if (onDrive) driveFolder else if (place == Place.Phone) null else folder?.name
  val goBack = { folderPath = null; driveFolder = null; query = ""; filter = Filter.All }
  BackHandler(folderName != null) { goBack() }

  var prompt by remember { mutableStateOf<PromptSpec?>(null) }
  var confirm by remember { mutableStateOf<ConfirmSpec?>(null) }
  var menu by remember { mutableStateOf<Menu?>(null) }
  var moving by remember { mutableStateOf<Saved?>(null) }
  var deleteChoice by remember { mutableStateOf<DeleteChoice?>(null) }
  var exporting by remember { mutableStateOf<Pair<Saved, Boolean>?>(null) }

  val needle = query.trim().lowercase()
  val filtered = files.filter {
    when (filter) {
      Filter.Pdf -> it.name.lowercase().endsWith(".pdf")
      Filter.Zip -> it.name.lowercase().endsWith(".zip")
      Filter.Recent -> System.currentTimeMillis() - it.modified < 7 * 86_400_000L
      Filter.All -> true
    } && (needle.isEmpty() || fold(it.name).contains(fold(needle)))
  }
  val filteredBytes = filtered.sumOf { it.size }
  val subtitle = when {
    needle.isNotEmpty() || filter != Filter.All -> if (filtered.isNotEmpty()) "${plural(filtered.size, "file")} · ${fmtSize(filteredBytes)}" else "Không có tệp phù hợp"
    folder != null -> if (files.isNotEmpty()) "${plural(files.size, "file")} · ${fmtSize(filteredBytes)}" else "Thư mục trống"
    all.isNotEmpty() && folders.isNotEmpty() -> "${plural(all.size, "file")} trong ${plural(folders.size, "folder")} · ${fmtSize(all.sumOf { it.size })} trên điện thoại"
    all.isNotEmpty() -> "${plural(all.size, "file")} · ${fmtSize(all.sumOf { it.size })} trên điện thoại"
    folders.isNotEmpty() -> plural(folders.size, "folder")
    else -> null
  }

  val newFolder = { prompt = PromptSpec("Thư mục mới", "Nhập tên cho thư mục này", "Tên thư mục", "", "Tạo") { createFolder(it) } }
  val deleteFile = { f: Saved ->
    if (Backup.hasDriveCopies(listOf(f.path))) deleteChoice = DeleteChoice("Xóa tệp này?", "\"${f.name}\"", listOf(f.path)) { removeFile(f.path) }
    else confirm = ConfirmSpec("Xóa tệp này?", "\"${f.name}\" sẽ bị xóa khỏi điện thoại. Các bản bạn đã chia sẻ không bị ảnh hưởng.") { removeFile(f.path) }
  }
  val deleteFolder = { f: Folder, count: Int ->
    val paths = listFiles(f.path).map { it.path }
    // Both sides: the whole Drive folder goes to the Trash, Drive-only files too, not just the copies sync linked.
    val both = {
      scope.launch { try { Backup.deleteFolder(f.name) } catch (e: Exception) { Backup.update { it.copy(error = Backup.describe(e)) } } }
      Unit
    }
    if (Backup.hasDriveCopies(paths)) deleteChoice = DeleteChoice("Xóa thư mục?", "\"${f.name}\" và ${plural(count, "file")} bên trong", paths, both) { removeFolder(f.path) }
    else confirm = ConfirmSpec("Xóa thư mục?", "\"${f.name}\" và ${if (count == 0) "mọi tệp bên trong" else "${plural(count, "file")} bên trong"} sẽ bị xóa vĩnh viễn.") { removeFolder(f.path) }
  }

  Screen {
    if (folderName != null) {
      Row(Modifier.padding(top = 4.dp).tap(scaleTo = 0.95f, onClick = goBack).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(I.arrowLeft, 20.dp)
        Spacer(Modifier.size(6.dp))
        T("Tất cả tệp", Type.med, c.sub)
      }
      LargeTitle(folderName, if (onDrive) "Trong Google Drive" else subtitle)
    } else {
      LargeTitle("Tệp", when (place) { Place.Drive -> "Đồng bộ với Google Drive"; Place.Phone -> "Mọi PDF trên điện thoại"; Place.Saved -> subtitle })
    }

    // Saved / Phone / Google Drive, only at the top level: inside a folder the subtitle already says which side it is.
    if (folderName == null) {
      Segmented(listOf(Place.Saved to "Đã lưu", Place.Phone to "Điện thoại", Place.Drive to "Drive"), place, icons = listOf(I.folderOutline, I.cellphone, I.googleDrive)) { place = it; query = "" }
      Spacer(Modifier.height(12.dp))
    }
    SearchField(query, { query = it }, if (place == Place.Phone) "Tìm PDF trên điện thoại…" else "Tìm tệp…")

    AnimatedContent(place, label = "place", transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }) { where ->
      Column {
        if (where == Place.Drive) {
          DriveFiles(query, driveFolder) { driveFolder = it; query = "" }
          return@Column
        }
        if (where == Place.Phone) {
          PhoneFiles(query)
          return@Column
        }
        // Folders sit on top as a strip of cards; the filters below only narrow the files.
        val showFolders = folder == null && needle.isEmpty() && (folders.isNotEmpty() || files.isNotEmpty())
        if (showFolders) {
          GroupLabel(if (folders.isEmpty()) "Thư mục" else "Thư mục · ${folders.size}", first = true)
          Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NewFolderCard(newFolder)
            folders.forEach { (f, n) ->
              FolderCard(f.name, n, onOpen = { folderPath = f.path; query = ""; filter = Filter.All }) { menu = Menu.OfFolder(f, n) }
            }
          }
        }
        when {
          files.isEmpty() && needle.isEmpty() && showFolders -> T(
            "Các tệp bạn lưu ngoài thư mục sẽ hiện ở đây.", Type.small, c.sub, Modifier.fillMaxWidth().padding(top = 28.dp), align = TextAlign.Center,
          )
          files.isEmpty() && needle.isEmpty() -> Empty(
            { ToolArt("files", 112.dp) },
            if (folder != null) "Thư mục này trống" else "Chưa có tệp nào",
            if (folder != null) "Chuyển tệp vào thư mục này hoặc tạo PDF mới." else "PDF bạn gộp, tách, nén hoặc quét sẽ được lưu ở đây, để bạn chia sẻ lại sau.",
          ) {
            if (folder != null) Btn("Quay lại tất cả tệp", Modifier.fillMaxWidth(), kind = BtnKind.Secondary, onClick = goBack)
            else {
              Btn("Tạo thư mục", Modifier.fillMaxWidth(), I.folderPlusOutline, onClick = newFolder)
              Btn("Quét tài liệu", Modifier.fillMaxWidth(), I.cameraOutline, BtnKind.Secondary) { nav.scan() }
              Btn("Xem các công cụ", Modifier.fillMaxWidth(), kind = BtnKind.Secondary) { nav.show(Tab.Tools) }
            }
          }
          else -> {
            Row(
              Modifier.padding(top = if (showFolders) 22.dp else 12.dp, bottom = 14.dp).horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Filter.entries.forEach { f -> Chip(f.label, filter == f) { filter = f } }
            }
            if (filtered.isEmpty()) {
              T("Không có tệp nào khớp \"${needle.ifEmpty { filter.label }}\".", Type.body, c.sub, Modifier.fillMaxWidth().padding(top = 28.dp), align = TextAlign.Center)
            }
            byDay(filtered) { it.modified }.forEachIndexed { gi, (label, items) ->
              GroupLabel(label, first = gi == 0)
              Group {
                items.forEachIndexed { i, f ->
                  ListRow(
                    stem(f.name), first = i == 0, lead = { DocGlyph(kindOf(f.name)) }, marquee = true,
                    subtitle = "${fmtSize(f.size)} · ${fmtTime(f.modified)}", onClick = { openFile(f.path, f.name) }, onLongClick = { menu = Menu.OfFile(f) },
                  ) {
                    // Only files still waiting to upload get a cloud: "Synced" is the normal state.
                    if (Backup.waiting(f.path, f.name)) Icon(I.cloudUploadOutline, 17.dp, c.accent)
                    IconBtn(I.dotsVertical, color = c.sub, label = "Thêm") { menu = Menu.OfFile(f) }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  menu?.let { m ->
    ModalBottomSheet({ menu = null }, containerColor = c.surface) {
      Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 18.dp).navigationBarsPadding()) {
        val title = when (m) { is Menu.OfFile -> m.f.name; is Menu.OfFolder -> m.f.name }
        T(title, Type.bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), maxLines = 1)
        val pick = { run: () -> Unit -> { menu = null; run() } }
        when (m) {
          is Menu.OfFile -> {
            val f = m.f
            scans[f.name]?.let { id -> MenuRow(I.fileDocumentEditOutline, "Sửa bản quét", onClick = pick { nav.scan(id) }) }
            MenuRow(I.shareVariantOutline, "Chia sẻ", onClick = pick { share(f.path, f.name) })
            if (kindOf(f.name) == DocKind.Pdf) {
              MenuRow(I.fileWordOutline, "Lưu thành tệp Word", onClick = pick { exporting = f to true })
              MenuRow(I.fileImageOutline, "Lưu thành ảnh JPEG", onClick = pick { exporting = f to false })
            }
            MenuRow(I.pencilOutline, "Đổi tên", onClick = pick {
              prompt = PromptSpec("Đổi tên tệp", "Đổi tên \"${f.name}\"", "Tên tệp", stem(f.name), "Lưu") { relinkPdf(f.path, renameFile(f, it)) }
            })
            MenuRow(I.folderMoveOutline, "Chuyển vào thư mục", onClick = pick { moving = f })
            MenuRow(I.deleteOutline, "Xóa", color = c.accent, onClick = pick { deleteFile(f) })
          }
          is Menu.OfFolder -> {
            MenuRow(I.pencilOutline, "Đổi tên", onClick = pick {
              prompt = PromptSpec("Đổi tên thư mục", "Đổi tên \"${m.f.name}\"", "Tên thư mục", m.f.name, "Lưu") { renameFolder(m.f.path, it) }
            })
            MenuRow(I.deleteOutline, "Xóa", color = c.accent, onClick = pick { deleteFolder(m.f, m.count) })
          }
        }
      }
    }
  }

  prompt?.let { p -> PromptDialog(p.title, p.initial, p.subtitle, p.placeholder, p.submit, onClose = { prompt = null }, onSubmit = p.run) }
  confirm?.let { cf -> ConfirmDialog(cf.title, cf.message, "Xóa", danger = true, onDismiss = { confirm = null }, onConfirm = cf.run) }

  deleteChoice?.let { d ->
    AppDialog({ deleteChoice = null }, d.title, "${d.what} cũng có trong Google Drive của bạn.") {
      Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn("Chỉ xóa khỏi điện thoại", Modifier.fillMaxWidth(), I.cellphoneRemove, BtnKind.Secondary) { Backup.keepInDriveOnly(d.paths); d.remove(); deleteChoice = null }
        Btn("Xóa khỏi điện thoại và Drive", Modifier.fillMaxWidth(), I.deleteOutline, BtnKind.Danger) { (d.removeBoth ?: d.remove)(); deleteChoice = null }
        T(
          "Chỉ xóa khỏi điện thoại sẽ giữ bản trên Drive; bạn có thể tải lại từ Tệp → Google Drive. Xóa khỏi điện thoại và Drive sẽ chuyển bản trên Drive vào Thùng rác của Drive trong 30 ngày.",
          Type.small, c.sub, align = TextAlign.Center,
        )
      }
    }
  }

  moving?.let { f ->
    AppDialog({ moving = null }, "Chuyển tệp", f.name) {
      Column(Modifier.padding(top = 12.dp, bottom = 16.dp)) {
        if (folder != null) {
          MenuRow(I.homeOutline, "Tất cả tệp (gốc)", "Chuyển ra khỏi thư mục này", c.accent, trailing = { Icon(I.chevronRight, 20.dp, c.faint) }) { moveFileToRoot(f.path); moving = null }
        }
        val targets = folders.filter { it.first.path != folder?.path }
        targets.forEach { (t, n) ->
          Row(
            Modifier.fillMaxWidth().tap(pressed = c.sunken, shape = RoundedCornerShape(Radius.tile)) { moveFile(f.path, t.path); moving = null }.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            FolderGlyph(28.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
              T(t.name, Type.item)
              T(plural(n, "file"), Type.small, c.sub)
            }
            Icon(I.chevronRight, 20.dp, c.faint)
          }
        }
        if (targets.isEmpty() && folder == null) {
          T("Chưa có thư mục nào. Hãy tạo thư mục trước để sắp xếp tệp.", Type.small, c.sub, Modifier.fillMaxWidth().padding(vertical = 16.dp), align = TextAlign.Center)
        }
      }
      Btn("Hủy", Modifier.fillMaxWidth(), kind = BtnKind.Secondary) { moving = null }
    }
  }

  exporting?.let { (pdf, word) -> ExportDialog(pdf, word) { exporting = null } }
}

private val FOLDER_CARD = 128.dp

@Composable
private fun NewFolderCard(onClick: () -> Unit) {
  val shape = RoundedCornerShape(Radius.group)
  Column(
    Modifier.width(FOLDER_CARD).height(112.dp).tap(shape = shape, scaleTo = 0.95f, label = "Thư mục mới", onClick = onClick)
      .background(c.accentSoft, shape).padding(14.dp),
    verticalArrangement = Arrangement.SpaceBetween,
  ) {
    Box(Modifier.size(38.dp).background(c.surface, CircleShape), contentAlignment = Alignment.Center) { Icon(I.plus, 20.dp, c.accent) }
    T("Thư mục mới", Type.bold, c.accent, maxLines = 1)
  }
}

@Composable
private fun FolderCard(name: String, count: Int, onOpen: () -> Unit, onMore: () -> Unit) {
  val shape = RoundedCornerShape(Radius.group)
  Box(
    Modifier.width(FOLDER_CARD).height(112.dp).tap(shape = shape, scaleTo = 0.95f, label = "Mở $name", onLongClick = onMore, onClick = onOpen)
      .background(c.surface, shape).border(0.6.dp, c.lineStrong, shape),
  ) {
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
      FolderGlyph(40.dp)
      Column {
        T(name, Type.bold, maxLines = 1)
        T(plural(count, "file"), Type.small, c.sub, maxLines = 1)
      }
    }
    Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { IconBtn(I.dotsVertical, 18.dp, c.sub, "Thêm", onClick = onMore) }
  }
}

private class PromptSpec(val title: String, val subtitle: String, val placeholder: String, val initial: String, val submit: String, val run: (String) -> Unit)
private class ConfirmSpec(val title: String, val message: String, val run: () -> Unit)
private class DeleteChoice(val title: String, val what: String, val paths: List<String>, val removeBoth: (() -> Unit)? = null, val remove: () -> Unit)

// Files → Save as Word doc / Save as JPEG: converts, then offers where else the result should go.
@Composable
private fun ExportDialog(pdf: Saved, word: Boolean, onClose: () -> Unit) {
  val scope = rememberCoroutineScope()
  val nav = LocalNav.current
  var file by remember { mutableStateOf<Saved?>(null) }
  var failed by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf("") }
  var notes by remember { mutableStateOf(mapOf<String, String>()) }
  LaunchedEffect(pdf) {
    // Closing while it converts only hides the dialog: the file still lands in Files.
    try { file = withContext(Dispatchers.IO) { exportAs(pdf, word) } } catch (_: Exception) { failed = true }
  }
  val f = file
  when {
    failed -> AppDialog(onClose, "Không chuyển đổi được PDF này", "Có thể tệp đã đặt mật khẩu hoặc bị hỏng.") {
      Btn("OK", Modifier.fillMaxWidth().padding(top = 18.dp), kind = BtnKind.Secondary, onClick = onClose)
    }
    f == null -> AppDialog(onClose, if (word) "Đang lưu thành tệp Word…" else "Đang lưu thành ảnh JPEG…", "Mọi trang được chuyển đổi ngay trên điện thoại.") {
      Spinner(modifier = Modifier.padding(vertical = 20.dp).align(Alignment.CenterHorizontally))
    }
    else -> {
      val many = f.name.lowercase().endsWith(".zip")
      val drive = Backup.state
      val run = { key: String, fn: suspend () -> String? ->
        if (busy.isEmpty()) scope.launch {
          busy = key
          try { fn()?.let { notes = notes + (key to it) } } catch (e: Exception) { notes = notes + (key to (e.message ?: "Đã xảy ra lỗi.")) }
          busy = ""
        }
        Unit
      }
      AppDialog(onClose, if (word) "Đã lưu tệp Word" else if (many) "Đã lưu các ảnh JPEG" else "Đã lưu ảnh JPEG", "${f.name} · ${fmtSize(f.size)} · trong Tệp") {
        Column(Modifier.padding(top = 10.dp)) {
          val option = @Composable { key: String, glyph: Int, title: String, sub: String, fn: suspend () -> String? ->
            MenuRow(glyph, title, notes[key] ?: sub, trailing = { if (busy == key) Spinner() }) { run(key, fn) }
          }
          option("open", I.openInNew, "Mở", if (word) "Trong ứng dụng Word hoặc văn bản" else if (many) "Tệp ZIP chứa mỗi trang một ảnh JPEG" else "Trong thư viện ảnh của bạn") { openFile(f.path, f.name); null }
          option("device", I.cellphoneArrowDown, "Lưu vào thiết bị", "Chọn thư mục, như Downloads") { saveToDevice(f).takeIf { it.isNotEmpty() }?.let { "Đã lưu vào $it" } }
          option("drive", I.googleDrive, "Lưu vào Google Drive", if (drive.enabled) "Thư mục PDF Tools · ${drive.email}" else "Hãy bật đồng bộ Drive trong Cài đặt trước") {
            if (!drive.enabled) { onClose(); nav.show(Tab.Settings); null }
            else try { Backup.saveCopy(f, mimeOf(f.name)); "Đã tải lên thư mục PDF Tools trong Drive của bạn" } catch (e: Exception) { throw UserError(Backup.describe(e)) }
          }
          option("share", I.shareVariantOutline, "Chia sẻ", "Gửi tới ứng dụng hoặc người liên hệ") { share(f.path, f.name); null }
        }
      }
    }
  }
}

// Files → Google Drive: what's in the synced "PDF Tools" folder. Files only in Drive stay there until chosen: tap the
// download icon, or hold a file to select several (a whole day at once) and download them.
@Composable
private fun ColumnScope.DriveFiles(query: String, folder: String?, onOpenFolder: (String) -> Unit) {
  val nav = LocalNav.current
  val scope = rememberCoroutineScope()
  val state = Backup.state
  var error by remember { mutableStateOf("") }
  var busyId by remember { mutableStateOf("") }
  var picked by remember { mutableStateOf<Set<String>?>(null) } // selected file ids; null when not selecting
  var downloading by remember { mutableStateOf("") }
  var folderMenu by remember { mutableStateOf<String?>(null) }
  var renaming by remember { mutableStateOf<String?>(null) }
  var deleting by remember { mutableStateOf<String?>(null) }
  val data = Backup.cached()
  var filter by rememberSaveable { mutableStateOf(DriveFilter.All) }

  // Only the first visit fetches: after that every sync, which lists Drive anyway, leaves a fresh listing behind.
  LaunchedEffect(state.email, state.enabled) {
    if (!state.enabled || Backup.cached() != null) return@LaunchedEffect
    error = ""
    try { Backup.list() } catch (e: Exception) { error = Backup.describe(e) }
  }
  BackHandler(picked != null) { picked = null } // back leaves selection before it leaves a folder

  if (!state.enabled) {
    Empty({ Icon(I.googleDrive, 56.dp, c.faint) }, "Đồng bộ Google Drive đang tắt", "Bật để PDF và thư mục trên điện thoại giống với Google Drive của bạn.") {
      Btn("Bật trong Cài đặt", Modifier.fillMaxWidth(), I.cogOutline) { nav.show(Tab.Settings) }
    }
    return
  }

  val all = data?.files ?: emptyList()
  val chosen = all.filter { picked?.contains(it.id) == true }
  val toFetch = chosen.filter { !Backup.isOnPhone(it) }
  val download = { list: List<DriveFile> ->
    val todo = list.filter { !Backup.isOnPhone(it) }
    if (todo.isEmpty()) picked = null
    else scope.launch {
      error = ""
      val label = { done: Int -> if (todo.size > 1) "Đang tải ${minOf(done + 1, todo.size)}/${todo.size}…" else "Đang tải…" }
      downloading = label(0)
      try {
        Backup.downloadToPhone(todo) { downloading = label(it) }
        picked = null
      } catch (e: Exception) {
        error = Backup.describe(e)
      }
      downloading = ""
    }
    Unit
  }
  val act = { f: DriveFile, fn: suspend () -> Unit ->
    scope.launch {
      busyId = f.id
      error = ""
      try { fn() } catch (e: Exception) { error = Backup.describe(e) }
      busyId = ""
    }
    Unit
  }
  val toggle = { ids: List<String>, on: Boolean ->
    val next = (picked ?: emptySet()).toMutableSet().apply { if (on) addAll(ids) else removeAll(ids.toSet()) }
    picked = next.ifEmpty { null } // unticking the last one leaves selection
  }

  // The selection bar floats over the bottom of the screen, so it stays in reach however far the list is scrolled.
  Floating(picked != null) {
    val shape = RoundedCornerShape(Radius.card)
    Row(
      Modifier.fillMaxWidth().shadow(14.dp, shape).background(c.surface, shape).border(0.6.dp, c.lineStrong, shape).padding(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconBtn(I.close, label = "Hủy chọn") { picked = null }
      Column(Modifier.weight(1f).padding(start = 2.dp)) {
        T("Đã chọn ${plural(chosen.size, "file")}", Type.bold)
        T(
          downloading.ifEmpty {
            if (toFetch.isNotEmpty()) "${fmtSize(toFetch.sumOf { it.size })} cần tải${if (toFetch.size < chosen.size) " · ${chosen.size - toFetch.size} đã có trên máy" else ""}"
            else "Đã có trên điện thoại"
          },
          Type.small, c.sub, maxLines = 1,
        )
      }
      Btn("Tải xuống", icon = I.downloadOutline, busy = downloading.isNotEmpty(), enabled = toFetch.isNotEmpty(), height = 46.dp) { download(chosen) }
    }
  }

  val needle = query.trim().lowercase()
  // Searching looks everywhere (inside this folder when one is open); otherwise show just this level.
  val matches = if (needle.isNotEmpty()) all.filter { (folder == null || it.folder == folder) && fold("${it.folder}/${it.name}").contains(fold(needle)) }
  else all.filter { it.folder == (folder ?: "") }
  val shown = matches.filter {
    when (filter) {
      DriveFilter.All -> true
      DriveFilter.Recent -> System.currentTimeMillis() - it.modified < 7 * 86_400_000L
      DriveFilter.OnPhone -> Backup.isOnPhone(it)
      DriveFilter.DriveOnly -> !Backup.isOnPhone(it)
    }
  }
  val folders = if (folder == null && needle.isEmpty() && filter == DriveFilter.All) data?.folders ?: emptyList() else emptyList()
  val problem = error.ifEmpty { if (!Backup.syncing) state.error ?: "" else "" }

  Spacer(Modifier.height(12.dp))
  GroupLabel(state.email ?: "Google Drive", if (Backup.syncing) "Đang đồng bộ…" else "Đồng bộ ngay", if (Backup.syncing) null else ({ Backup.syncNow(); Unit }), first = true, caps = false)
  Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(0.6.dp).background(c.line)) // sets the account apart from the files under it
  if (problem.isNotEmpty()) T(problem, Type.small, c.accent, Modifier.padding(top = 4.dp, bottom = 4.dp))
  if (downloading.isNotEmpty() && picked == null) T(downloading, Type.small, c.sub, Modifier.padding(vertical = 4.dp))

  if (data != null) ChipRow(DriveFilter.entries.map { it to it.label }, filter, Modifier.padding(top = 12.dp, bottom = 4.dp)) { filter = it }
  when {
    data == null && problem.isEmpty() -> Spinner(c.accent, modifier = Modifier.padding(top = 40.dp).align(Alignment.CenterHorizontally))
    folders.isEmpty() && shown.isEmpty() && filter != DriveFilter.All && data != null -> T(
      "Không có tệp nào ở mục \"${filter.label}\".", Type.body, c.sub, Modifier.fillMaxWidth().padding(top = 28.dp), align = TextAlign.Center,
    )
    folders.isNotEmpty() || shown.isNotEmpty() -> {
      if (folders.isNotEmpty()) {
        GroupLabel("Thư mục")
        Group {
          folders.forEachIndexed { i, name ->
            ListRow(
              name, first = i == 0, lead = { FolderGlyph() }, subtitle = plural(all.count { it.folder == name }, "file"),
              onClick = { onOpenFolder(name) }, onLongClick = { folderMenu = name },
            ) {
              if (busyId == name) Spinner(c.accent, modifier = Modifier.padding(horizontal = 10.dp))
              else IconBtn(I.dotsVertical, color = c.sub, label = "Thêm") { folderMenu = name }
            }
          }
        }
      }
      byDay(shown) { it.modified }.forEachIndexed { gi, (label, items) ->
        val ids = items.map { it.id }
        val allOn = picked != null && ids.all { picked!!.contains(it) }
        GroupLabel(label, if (picked != null) (if (allOn) "Bỏ chọn" else "Chọn tất cả") else null, { toggle(ids, !allOn) })
        Group {
          items.forEachIndexed { i, f ->
            val here = Backup.isOnPhone(f)
            val on = picked?.contains(f.id) == true
            ListRow(
              stem(f.name), first = i == 0, marquee = true,
              lead = { if (picked != null) CheckMark(on) else DocGlyph() },
              subtitle = "${if (needle.isNotEmpty() && f.folder.isNotEmpty()) "${f.folder} · " else ""}${fmtSize(f.size)} · ${fmtTime(f.modified)}${if (here) " · Trên máy" else ""}",
              onClick = if (picked != null) ({ toggle(listOf(f.id), !on) }) else if (busyId.isNotEmpty()) null else ({ act(f) { Backup.fetch(f).let { openFile(it.path, it.name) } } }),
              onLongClick = { if (downloading.isEmpty()) toggle(listOf(f.id), !on) },
            ) {
              if (picked == null) {
                if (busyId == f.id) Spinner(c.accent, modifier = Modifier.padding(horizontal = 10.dp))
                else {
                  if (!here) IconBtn(I.downloadOutline, label = "Tải xuống") { if (downloading.isEmpty()) download(listOf(f)) }
                  IconBtn(I.shareVariantOutline, label = "Chia sẻ") { act(f) { Backup.fetch(f).let { share(it.path, it.name) } } }
                }
              }
            }
          }
        }
      }
    }
    data != null -> T(
      if (needle.isNotEmpty()) "Không có tệp Drive nào khớp \"${query.trim()}\"." else if (folder != null) "Thư mục này chưa có PDF nào trên Drive." else "Thư mục Drive của bạn chưa có PDF nào.",
      Type.body, c.sub, Modifier.fillMaxWidth().padding(top = 40.dp), align = TextAlign.Center,
    )
  }
  val folderAct = { name: String, fn: suspend () -> Unit ->
    scope.launch {
      busyId = name
      error = ""
      try { fn() } catch (e: Exception) { error = Backup.describe(e) }
      busyId = ""
    }
    Unit
  }
  folderMenu?.let { name ->
    val inside = all.filter { it.folder == name }
    val toGet = inside.filter { !Backup.isOnPhone(it) }
    ModalBottomSheet({ folderMenu = null }, containerColor = c.surface) {
      Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 18.dp).navigationBarsPadding()) {
        T(name, Type.bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), maxLines = 1)
        val pick = { run: () -> Unit -> { folderMenu = null; run() } }
        MenuRow(
          I.downloadOutline, "Tải tất cả",
          if (toGet.isEmpty()) "Đã có trên điện thoại" else "${plural(toGet.size, "file")} · ${fmtSize(toGet.sumOf { it.size })}",
          onClick = pick { if (downloading.isEmpty()) download(toGet) },
        )
        MenuRow(I.pencilOutline, "Đổi tên", onClick = pick { renaming = name })
        MenuRow(I.deleteOutline, "Xóa", color = c.accent, onClick = pick { deleting = name })
      }
    }
  }
  renaming?.let { name ->
    PromptDialog("Đổi tên thư mục", name, "Được đổi tên trên Drive và trên điện thoại.", "Tên thư mục", "Lưu", onClose = { renaming = null }) { typed ->
      folderAct(name) { Backup.renameFolder(name, typed) }
    }
  }
  deleting?.let { name ->
    ConfirmDialog(
      "Xóa thư mục?", "\"$name\" và ${plural(all.count { it.folder == name }, "file")} bên trong sẽ vào Thùng rác của Drive trong 30 ngày, và bị xóa khỏi điện thoại.",
      "Xóa", danger = true, requireTyping = "xác nhận", onDismiss = { deleting = null },
    ) { folderAct(name) { Backup.deleteFolder(name) } }
  }

  T(
    if (picked != null) "Chạm vào tệp để chọn hoặc bỏ chọn." else "Giữ một tệp để chọn nhiều tệp.",
    Type.small.copy(fontSize = Type.small.fontSize * 0.9f), c.faint, Modifier.fillMaxWidth().padding(top = 12.dp), align = TextAlign.Center,
  )
}
