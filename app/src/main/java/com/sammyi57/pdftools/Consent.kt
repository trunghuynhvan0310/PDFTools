package com.sammyi57.pdftools

import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// Shown once, before anything else, on a fresh install, and again from Settings to read back. One plain screen that
// fits without scrolling: what the app does with your files, then a tick for each document.

// The contact address shown in the privacy policy (and on its web page, built from this file by scripts/build_policy_page.py).
private const val CONTACT = "https://github.com/trunghuynhvan0310/PDFTools/issues"

private val POINTS = listOf(
  I.cellphoneLock to "Mọi công cụ chạy ngay trên điện thoại",
  I.accountOffOutline to "Không tài khoản, không theo dõi, không quảng cáo",
  I.googleDrive to "Sao lưu Google Drive là tùy chọn",
  I.wifiOff to "Hoạt động không cần mạng",
)

private val PRIVACY = """1. Chúng tôi thu thập gì
Không gì cả. PDF Tools không có tài khoản, không phân tích, không quảng cáo và không báo cáo lỗi. Chúng tôi không thu thập, nhận hay lưu tài liệu, tên, địa chỉ email hoặc bất kỳ mã định danh nào của bạn.

2. Tệp của bạn nằm ở đâu
Mọi công cụ chạy hoàn toàn trên thiết bị này. PDF đã lưu được giữ trong bộ nhớ riêng của ứng dụng, các ứng dụng khác không đọc được. Chúng không bao giờ được gửi cho chúng tôi, vì không có nơi nào để gửi.

3. Sao lưu Google Drive
Tắt trừ khi bạn bật. Khi bật, ứng dụng đăng nhập vào tài khoản Google bạn chọn và sao chép PDF đã lưu lên Google Drive của chính bạn. Các tệp đó đi thẳng từ điện thoại này tới Google. Bạn có thể ngắt kết nối bất cứ lúc nào trong Cài đặt, việc này thu hồi quyền truy cập của ứng dụng vào tài khoản của bạn. Cách Google xử lý tệp trong Drive của bạn tuân theo chính sách quyền riêng tư của Google.

4. Quyền truy cập
Camera chỉ được dùng khi bạn đang quét tài liệu. Ảnh chỉ được dùng khi bạn chọn một ảnh để chuyển thành PDF hoặc để đặt chữ ký. Cả hai đều không chạy nền, và ứng dụng hỏi lần đầu khi cần từng quyền.

5. Đọc chữ từ bản quét
Việc làm cho PDF quét có thể tìm kiếm dùng một mô hình nhận dạng chữ tích hợp trong ứng dụng. Ảnh trang và chữ đọc được không bao giờ rời khỏi thiết bị, và không cần kết nối mạng.

6. Trẻ em
Ứng dụng không hướng tới trẻ em dưới 13 tuổi, và không thu thập dữ liệu của bất kỳ ai ở mọi độ tuổi.

7. Thay đổi
Nếu chính sách này thay đổi theo hướng quan trọng, ứng dụng sẽ hiển thị lại cho bạn trước khi bạn tiếp tục.

8. Liên hệ
Câu hỏi về chính sách này: $CONTACT"""

private val TERMS = """1. Ứng dụng
PDF Tools được cung cấp miễn phí, nguyên trạng, để dùng trên thiết bị của chính bạn.

2. Tệp của bạn do bạn chịu trách nhiệm
Ứng dụng thay đổi các tài liệu bạn chọn, và một số thay đổi không thể hoàn tác: che thông tin xóa nội dung vĩnh viễn, mật khẩu khóa tệp, cắt xén bỏ một phần trang. Hãy giữ bản sao riêng của bất cứ thứ gì bạn không thể để mất. Chúng tôi không có bản sao và không thể khôi phục giúp bạn.

3. Sử dụng chấp nhận được
Không dùng ứng dụng để sửa tài liệu nhằm lừa dối bất kỳ ai, giả mạo chữ ký, hoặc cho bất kỳ việc gì trái pháp luật nơi bạn sống. Việc đặt ảnh chữ ký lên tài liệu không phải là tuyên bố về giá trị pháp lý của tài liệu đó — một PDF đã ký có ràng buộc ai hay không phụ thuộc vào luật địa phương và các bên liên quan.

4. Không bảo hành
Ứng dụng không kèm bất kỳ bảo hành nào. Việc phát hiện trang, nhận dạng chữ và nén vốn chỉ mang tính tương đối, và công cụ nào cũng có thể có lỗi. Hãy kiểm tra kết quả trước khi dựa vào nó.

5. Giới hạn trách nhiệm
Trong phạm vi pháp luật cho phép, chúng tôi không chịu trách nhiệm về dữ liệu bị mất, lợi nhuận bị mất, hay bất kỳ tổn thất gián tiếp hoặc hệ quả nào phát sinh từ việc bạn dùng ứng dụng.

6. Dịch vụ bên thứ ba
Sao lưu Google Drive là tùy chọn và tuân theo điều khoản dịch vụ riêng của Google.

7. Thay đổi
Nếu các điều khoản này thay đổi, ứng dụng sẽ yêu cầu bạn chấp nhận lại. Tiếp tục sử dụng nghĩa là bạn chấp nhận phiên bản được hiển thị."""

private val DOCS = mapOf("privacy" to ("Chính sách quyền riêng tư" to PRIVACY), "terms" to ("Điều khoản & Điều kiện" to TERMS))

/** First launch: a short tour, then the terms. Reading the terms again from Settings goes straight to them. */
@Composable
fun Consent(onAccept: (() -> Unit)? = null, onClose: (() -> Unit)? = null) {
  var intro by rememberSaveable { mutableStateOf(onAccept != null) }
  if (intro) Onboarding { intro = false } else ConsentBody(onAccept, onClose)
}

@Composable
private fun ConsentBody(onAccept: (() -> Unit)?, onClose: (() -> Unit)?) {
  var doc by remember { mutableStateOf<String?>(null) }
  var privacy by remember { mutableStateOf(false) }
  var terms by remember { mutableStateOf(false) }
  if (onClose != null) BackHandler(doc == null, onClose)

  Box(Modifier.fillMaxSize().background(c.bg)) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
      if (onClose != null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
          IconBtn(I.close, 24.dp, label = "Đóng", onClick = onClose)
        }
      }
      Column(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
      ) {
        val pop = remember { Animatable(0.6f) }
        LaunchedEffect(Unit) { pop.animateTo(1f, spring(0.5f, Spring.StiffnessLow)) }
        Box(
          Modifier.size(84.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .clip(RoundedCornerShape(26.dp)).glow(c.hero, 0.85f, 1f),
          contentAlignment = Alignment.Center,
        ) { Icon(I.filePdfBox, 46.dp, Color.White) }
        T("PDF Tools", Type.largeTitle, modifier = Modifier.padding(top = 18.dp))
        T("Tài liệu của bạn không bao giờ rời khỏi điện thoại này.", Type.body, c.sub, Modifier.padding(top = 4.dp), align = TextAlign.Center)
        Column(Modifier.fillMaxWidth().padding(top = 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
          POINTS.forEachIndexed { i, (glyph, label) -> Stagger(i) { Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(c.sunken, RoundedCornerShape(Radius.tile)), contentAlignment = Alignment.Center) { Icon(glyph, 18.dp, c.sub) }
            Spacer(Modifier.size(12.dp))
            T(label, Type.body.copy(fontSize = Type.body.fontSize * 0.97f))
          } } }
        }
      }
      Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
        if (onAccept != null) {
          Tick(privacy, "Tôi đã đọc", "Chính sách quyền riêng tư", { privacy = !privacy }) { doc = "privacy" }
          Tick(terms, "Tôi đồng ý với", "Điều khoản & Điều kiện", { terms = !terms }) { doc = "terms" }
          Btn("Tiếp tục", Modifier.fillMaxWidth().padding(top = 14.dp), enabled = privacy && terms, onClick = onAccept)
        } else {
          T("Chính sách quyền riêng tư", Type.item, c.accent, Modifier.tap { doc = "privacy" }.padding(vertical = 11.dp))
          T("Điều khoản & Điều kiện", Type.item, c.accent, Modifier.tap { doc = "terms" }.padding(vertical = 11.dp))
        }
        T("Cập nhật lần cuối: ${Terms.UPDATED}", Type.small, c.faint, Modifier.fillMaxWidth().padding(top = 14.dp), align = TextAlign.Center)
      }
    }

    AnimatedVisibility(doc != null, enter = slideInVertically(tween(380)) { it }, exit = slideOutVertically(tween(300)) { it }) {
      val shown = remember { mutableStateOf(doc) }
      if (doc != null) shown.value = doc
      val (title, body) = DOCS.getValue(shown.value ?: "privacy")
      BackHandler { doc = null }
      Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          T(title, Type.title.copy(fontSize = Type.title.fontSize * 0.75f), modifier = Modifier.weight(1f))
          IconBtn(I.close, 24.dp, label = "Đóng") { doc = null }
        }
        T(body, Type.small.copy(fontSize = Type.small.fontSize * 1.07f, lineHeight = Type.small.lineHeight * 1.15f), c.sub,
          Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 40.dp))
      }
    }
  }
}

@Composable
private fun Tick(on: Boolean, before: String, link: String, toggle: () -> Unit, open: () -> Unit) {
  Row(Modifier.fillMaxWidth().tap(scaleTo = 0.98f, onClick = toggle).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
    val box = RoundedCornerShape(7.dp)
    Box(
      Modifier.size(22.dp).background(if (on) c.accent else Color.Transparent, box).border(2.dp, if (on) c.accent else c.lineStrong, box),
      contentAlignment = Alignment.Center,
    ) { if (on) Icon(I.check, 15.dp, Color.White) }
    Spacer(Modifier.size(12.dp))
    T("$before ", Type.small.copy(fontSize = Type.small.fontSize * 1.07f), c.sub)
    T(link, Type.small.copy(fontSize = Type.small.fontSize * 1.07f, fontWeight = Type.item.fontWeight), c.accent, Modifier.tap(scaleTo = 0.94f, onClick = open))
  }
}

/**
 * Fades and lifts its content in, a beat after the one before it. Plays once per screen: coming back to a screen (from a
 * tool, or another tab) shows it as it was, instead of blank while every section fades in again.
 */
@Composable
fun Stagger(index: Int, content: @Composable () -> Unit) {
  var played by rememberSaveable { mutableStateOf(false) }
  val t = remember { Animatable(if (played) 1f else 0f) }
  LaunchedEffect(Unit) {
    if (played) return@LaunchedEffect
    played = true
    delay(60L * index.coerceAtMost(8))
    t.animateTo(1f, tween(380))
  }
  Box(Modifier.graphicsLayer { alpha = t.value; translationY = (1 - t.value) * 24f }) { content() }
}
