<h1 align="center">PDF Tools (bản tiếng Việt)</h1>

<p align="center">
  24 công cụ PDF miễn phí và máy quét tài liệu cho Android.<br>
  Mọi thứ chạy ngay trên điện thoại. Không quảng cáo, không đăng ký, không theo dõi.
</p>

<p align="center">
  <a href="https://github.com/trunghuynhvan0310/PDFTools/releases/tag/latest"><b>⬇ Tải APK mới nhất</b></a>
  &nbsp;·&nbsp;
  <a href="https://github.com/trunghuynhvan0310/PDFTools/actions">Các lần build</a>
</p>

> Đây là bản fork của [sammyZi/pdf-tools](https://github.com/sammyZi/pdf-tools) (giấy phép MIT), với các thay đổi:
> - Việt hóa toàn bộ giao diện.
> - Tắt `allowBackup` để Android không tự sao lưu PDF của bạn.
> - Thêm GitHub Actions để tự build APK.

## Tải về

Mở [trang bản phát hành mới nhất](https://github.com/trunghuynhvan0310/PDFTools/releases/tag/latest) và tải `app-release.apk` (bản 64-bit arm64, phù hợp với hầu hết điện thoại đời 2017 trở lại đây).

- Cần Android 7.0 trở lên.
- Trình xem PDF tích hợp chỉ chạy trên Android 12 có bản cập nhật hệ thống mới nhất; các máy khác sẽ mở PDF bằng ứng dụng PDF của máy.
- APK ký bằng khóa debug, khác với bản gốc của tác giả và bản trên Google Play. Hãy gỡ bản cũ trước khi cài, và sao lưu tệp quan trọng vì dữ liệu trong app sẽ mất.

## Tính năng

### Quét
- Máy quét bằng camera tự phát hiện cạnh trang, chụp tự động hoặc thủ công, đèn flash, camera trước hoặc sau.
- Các chế độ: tài liệu, sách (2 trang mỗi ảnh), thẻ ID (hai mặt trên một trang), danh thiếp, bảng trắng.
- Cắt, xoay, bộ lọc (màu tự động, chữ sáng, thang xám, bảng trắng), độ sáng và độ tương phản.
- Cọ làm sạch và chú thích với màu tùy chọn, kể cả lấy màu ngay từ trang.
- Bản quét đã lưu tìm kiếm được: OCR trên máy thêm một lớp chữ ẩn để bạn tìm kiếm và sao chép.

### 24 công cụ
| Nhóm | Công cụ |
|---|---|
| Gộp và tách | Gộp · Trộn trang xen kẽ · Tách · Tách theo chữ · Tách theo dấu trang · Tách đôi · Tách theo dung lượng · PDF sang ZIP |
| Sắp xếp trang | Sắp xếp trang (đổi thứ tự, xoay, xóa) · Xoay · Lật · Nhiều trang trên một tờ · Cắt và đổi cỡ |
| Điền và đánh dấu | Điền và ký (chữ ký vẽ, lưu lại) · Hình mờ · Đánh số trang · Che thông tin · Sửa thông tin PDF |
| Bảo vệ và nén | Nén (theo mức, hoặc theo dung lượng) · OCR · Đặt mật khẩu · Gỡ mật khẩu |
| Tự động hóa | Quy trình tự động: nối nhiều công cụ và chạy trên một hoặc nhiều PDF |

Ghim các công cụ hay dùng lên đầu Trang chủ. Mọi công cụ đều xem trước được kết quả trước khi lưu.

### Đọc
- Trình xem tích hợp: cuộn và thu phóng mượt, chọn và sao chép chữ, tìm kiếm có đánh dấu, liên kết, hình thu nhỏ trang, đi tới trang. PDF có mật khẩu mở bằng mật khẩu.

### Tệp và Google Drive
- Thư mục, tìm kiếm, đổi tên, chuyển, chia sẻ, lưu bản sao vào bất kỳ thư mục nào hoặc dưới dạng Word hay JPEG.
- Đồng bộ hai chiều (tùy chọn) với thư mục "PDF Tools" trong Google Drive của bạn (quyền `drive.file`: ứng dụng chỉ thấy những gì nó tạo ra). Cần tự tạo Android OAuth client trong Google Cloud, xem mục Build.

## Quyền riêng tư

Mọi công cụ, máy quét và OCR chạy trên thiết bị. Tệp chỉ rời khỏi điện thoại nếu bạn bật đồng bộ Google Drive, và chỉ tới Drive của chính bạn. Không quảng cáo, không phân tích, không tài khoản.

## Điểm riêng của bản tiếng Việt

- Font giao diện là Be Vietnam Pro, hiển thị đủ mọi chữ tiếng Việt.
- Chữ điền vào PDF, hình mờ, số trang và lớp chữ OCR dùng font Be Vietnam Pro nhúng trong PDF, nên giữ nguyên dấu thanh và tìm kiếm được.
- Tìm kiếm không phân biệt dấu: gõ "hop dong" vẫn ra "Hợp đồng". Tên tệp và thư mục sắp xếp theo thứ tự tiếng Việt.
- Nén PDF theo dung lượng (ví dụ 1, 2, 5 MB) để nộp lên cổng dịch vụ công, thuế điện tử, BHXH.

## Công nghệ

| | |
|---|---|
| Ngôn ngữ và giao diện | Kotlin, Jetpack Compose, Material 3, Navigation Compose |
| Xử lý PDF | [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android), Android `PdfRenderer` |
| Trình xem | [Jetpack PDF](https://developer.android.com/jetpack/androidx/releases/pdf) (`androidx.pdf`) |
| Máy quét | CameraX, OpenCV với mô hình DocAligner đi kèm, ZXing (mã QR) |
| OCR | ML Kit Text Recognition (trên thiết bị) |
| Đồng bộ | Google Identity authorization, Drive REST API v3 |
| Làm mờ | [Haze](https://github.com/chrisbanes/haze) |

## Build

**Trên GitHub:** vào tab **Actions** → **Build APK** → **Run workflow** (tick `all_abis` nếu cần đủ các ABI). Mỗi lần push lên `main` cũng tự build, và APK được đưa lên [bản phát hành `latest`](https://github.com/trunghuynhvan0310/PDFTools/releases/tag/latest).

**Trên máy:** cần JDK 17 và Android SDK (compile SDK 36 với SDK extension 19), hoặc Android Studio.

```bash
./gradlew :app:assembleDebug        # APK debug ở app/build/outputs/apk/debug
./gradlew :app:assembleRelease      # APK release ở app/build/outputs/apk/release
```

Nếu không có khóa upload riêng, bản release được ký bằng khóa debug.

**Ký bằng khóa riêng trên GitHub:** tạo 4 secret trong Settings → Secrets and variables → Actions: `UPLOAD_STORE_BASE64` (nội dung base64 của file `.jks`), `UPLOAD_STORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`. Có đủ secret thì workflow tự ký bằng khóa của bạn; thiếu thì dùng khóa debug. Đừng bao giờ commit file `.jks`. Đồng bộ Google Drive cần Android OAuth client cho package name và SHA-1 chữ ký của bạn trong Google Cloud.

## Cấu trúc dự án

```
app/src/main/java/com/sammyi57/pdftools/
  Theme.kt, Kit.kt          bảng màu, kiểu chữ và thành phần dùng chung
  Tools.kt                  danh mục công cụ
  Pdf.kt, PdfRender.kt      bộ xử lý PDF
  ToolScreen.kt, PageEditors.kt
  Scanner.kt, Review.kt, ScanModel.kt, DocCamera.kt, DocVision.kt
  Viewer.kt                 trình xem trong ứng dụng
  Storage.kt, Files.kt, Backup.kt
```

## Ghi công

Dự án gốc: [sammyZi/pdf-tools](https://github.com/sammyZi/pdf-tools). Xây dựng với Jetpack Compose, Jetpack PDF, CameraX, PdfBox-Android, OpenCV, ML Kit và mô hình DocAligner, cùng nhiều dự án khác. Xem [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) để biết đầy đủ các dự án và giấy phép.

## Giấy phép

[MIT](LICENSE) © 2026 SammyZi
