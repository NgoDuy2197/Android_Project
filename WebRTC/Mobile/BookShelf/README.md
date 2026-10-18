# BookShelf — Tủ sách đọc PDF & TXT

Ứng dụng Android (Kotlin, Android Views) để đọc sách **PDF** và **văn bản thuần (TXT)**, với giao diện tủ sách gỗ, đọc thành tiếng, dịch sách, tạo sách nói MP3 và trích xuất chữ từ PDF. Giao diện mặc định là **Tiếng Việt** (có English, 中文, 日本語).

- Package: `com.dsoft.bookshelf` · minSdk 24 · targetSdk/compileSdk 34 · Java/Kotlin 17
- Build: chạy `build_apk.bat` → tạo `BookShelf.apk` ở thư mục gốc (và copy vào `WebRTC\__Result`).
  - `build_apk.bat install` → build + `adb install`; `build_apk.bat clean` → dọn.
  - Cần JDK của Android Studio (`C:\Program Files\Android\Android Studio\jbr`), script tự tìm.
- Unit test (JVM): `gradlew testDebugUnitTest` (tách câu, chia lô dịch, sổ sách tiến độ job, giải mã văn bản, mã hóa MP3).

## 1. Tủ sách (màn hình chính)
- Sách hiển thị như **bìa sách đứng trên kệ gỗ**: vách gỗ có vân, tấm kệ có mặt trên, cạnh trước có vân + mắt gỗ, bóng đổ phía dưới; bìa sách có gáy 3D, khối giấy bên phải, bo góc, bóng đổ và lớp bóng kính. Tự chia số cột theo chiều rộng màn hình (điện thoại / máy tính bảng / xoay ngang). Có biến thể **sáng và tối**; luôn có vài kệ trống để trông như tủ thật.
- Tên sách được in trên cạnh kệ ngay dưới mỗi cuốn.
- **Bìa**: PDF → render trang 1 bằng `PdfRenderer` (nền tô trắng trước khi render để không bị **đen**), lưu `cover.png`. TXT hoặc PDF không render được → bìa vẽ tự động (sách vải màu tươi theo tên, khung vàng, huy hiệu **“?”** lớn, tên sách). Sách nói MP3 → bìa hình **đĩa CD/vinyl**.
- **Nhãn loại ở góc bìa**: mỗi bìa có các thẻ nhỏ **bám sát mép trái**, xếp **theo chiều dọc** (mỗi thẻ một dòng): **PDF / TXT / MP3**, và bản dịch có thêm thẻ ngôn ngữ đích (ví dụ **VI**). Thẻ dạng nhãn bo tròn ôm mép trái.
- Bìa được vẽ với **nền trắng đục bên dưới** và **không còn lớp phủ tối** đè lên ảnh, nên ảnh trang 1 PDF và ảnh bìa tùy chọn hiển thị đúng độ sáng (sửa lỗi bìa bị “đen xì / phủ lớp đen”).
- **Ảnh bìa tùy chọn** cho từng sách (chọn ảnh qua SAF, được thu nhỏ và copy vào bộ nhớ app); có thể quay lại bìa mặc định.
- **Thanh tiến độ + phần trăm** trên mỗi bìa (✓ khi đọc xong).
- Thanh tiêu đề: 🔍 tìm/lọc theo tên, sắp xếp (đọc gần đây / tên / mới thêm), tiến trình (jobs), cài đặt và nút **“+”** lớn để thêm sách.
- Thanh **mini-player** ở dưới khi đang đọc thành tiếng (lùi / phát-tạm dừng / tới / dừng).
- Mở được file PDF/TXT từ ứng dụng khác (“Mở bằng BookShelf”).

## 2. Thêm sách
- `ACTION_OPEN_DOCUMENT`, chọn nhiều tệp, nhận `application/pdf` và `text/plain`.
- Mỗi tệp được **copy vào bộ nhớ riêng của app** (`filesDir/books/<id>/book.pdf|book.txt`) nên vẫn đọc được mà không cần quyền URI lâu dài.
- Tên sách = tên tệp (bỏ đuôi), **đổi tên** được.
- TXT được chuẩn hóa sang UTF-8 khi nhập (nhận diện BOM UTF-8/UTF-16, UTF-16 không BOM, fallback windows-1258).

## 3. Menu “⋮” của mỗi cuốn sách
Nhấn nút ⋮ ở góc bìa (hoặc nhấn giữ bìa): Mở sách · Nghe tiếp · Đổi tên · Đổi ảnh bìa / Dùng lại bìa mặc định · Trích xuất văn bản (PDF → sách TXT mới) · Dịch sách… · Tạo sách nói MP3… · **Chia sẻ sách** · Xem tiến trình/tác vụ · Xóa sách.
- **Chia sẻ sách**: gửi chính tệp sách (PDF / TXT / MP3) qua `ACTION_SEND` bằng URI `content://` của FileProvider, chọn ứng dụng nhận (Zalo, Gmail, Drive…).
- Sách nói MP3 trên tủ có menu riêng: **Phát** (mở trình phát nhạc hệ thống) · Đổi tên · Chia sẻ · Xóa.

## 4. Đọc PDF
- Cuộn dọc liên tục; mỗi trang render bằng `PdfRenderer` trên luồng nền theo chiều rộng màn hình; cache LRU; chỉ render các trang gần vị trí đang xem.
- **Phóng to**: chụm 2 ngón (1×–6×) và **nhấn đúp** để xoay vòng 1× → 2× → 3.5× → 1×; kéo để di chuyển khi đang phóng to. Nhấn 1 lần để ẩn/hiện thanh công cụ.
- **Nút lật trang** ở **góc dưới-trái** (vừa tầm ngón cái): **trang trước / trang sau** (xếp dọc), ẩn/hiện cùng thanh công cụ. Độ **mờ của nút chỉnh được 0–100%** trong Cài đặt (mặc định 40%); đặt 0% để ẩn hẳn.
- **Lưu đúng vị trí đọc** (chỉ số trang + vị trí trong trang) liên tục khi cuộn và khi tạm dừng; mở lại là về đúng chỗ ngay.
- Hộp thoại **Đến trang**, chỉ báo trang **“12 / 300”**.
- **Chế độ đêm** cho PDF (đảo màu bằng `ColorMatrix`).
- Nút nghe (đọc văn bản đã trích xuất), menu: trích xuất / dịch / MP3.

## 5. Đọc TXT
- Văn bản chia thành đoạn trong `RecyclerView` (không dùng một TextView khổng lồ) → mở được tệp rất lớn.
- Tùy chỉnh **cỡ chữ, giãn dòng, font (có chân/không chân), lề**, và giao diện Sáng/Tối/Sepia ngay trong sách (nút “Aa”).
- Lưu vị trí theo **offset ký tự**, khôi phục chính xác đến dòng.
- **Đến vị trí** theo phần trăm (thanh kéo hoặc nhập số).
- **Nhấn giữ một đoạn** → “Đọc từ đây” (bắt đầu đọc từ câu tại chỗ chạm), “Đánh dấu nghe từ đây”, “Sao chép đoạn”.
- **Tô sáng** đoạn đang được đọc và tự cuộn theo giọng đọc (trừ khi bạn vừa tự cuộn).

## 6. Cài đặt
- Giao diện: **Sáng / Tối / Sepia / Pastel / Hệ thống** (Pastel = bảng màu tím-oải hương dịu cho cả tủ sách và giao diện).
- Ngôn ngữ: **Tiếng Việt (mặc định)**, English, 中文, 日本語 — đổi ngay trong app (bọc `Context` bằng locale trong `attachBaseContext` rồi `recreate`). Chuỗi nằm trong `values/`, `values-en`, `values-zh`, `values-ja`.
- **Tự mở sách đang đọc khi mở app** (bật mặc định): mở cuốn cuối cùng tại đúng vị trí.
- Mặc định khi đọc: cỡ chữ, giãn dòng, lề, font, giữ màn hình sáng, chế độ đêm PDF.
- **Lọc ánh sáng xanh chỉ trong app**: lớp phủ màu hổ phách bán trong suốt đặt trên nội dung mỗi Activity (không phải overlay hệ thống, không cần quyền), chỉnh **cường độ** và **độ ấm**. **Giảm độ sáng trong app** qua `window.attributes.screenBrightness`.
- Giọng đọc (TTS) và dịch (xem dưới), chọn kiểu OCR (Tự động / Latin / 中文 / 日本語).

## 7. Dịch sách
- Lấy văn bản: TXT dùng trực tiếp; PDF dùng văn bản đã trích xuất — nếu chưa có, app **tự xếp hàng một tác vụ trích xuất trước**.
- Chia văn bản thành **lô** tại ranh giới đoạn/câu (ML Kit ~3000 ký tự, Google ~1800, Gemini ~4000) và dịch từng lô.
- **Lưu tiến độ sau mỗi lô** vào `filesDir/jobs/<id>.json` (sách, công cụ, ngôn ngữ nguồn/đích, lô hiện tại, tổng số, sách kết quả, độ dài file kết quả, trạng thái, lỗi cuối, thời gian) — ghi nguyên tử (tmp + rename).
- Kết quả **ghi dần** vào một sách TXT mới, ví dụ “<tên sách> (Tiếng Việt)”. Sách dịch là **một mục riêng trên tủ sách** với nhãn góc ngôn ngữ đích (ví dụ **VI**).
- Lỗi (hết hạn mức, mất mạng…) → tác vụ **tạm dừng kèm thông báo lỗi**; nhấn **“Tiếp tục”** để chạy tiếp từ lô chưa dịch (file kết quả được cắt về độ dài đã lưu nên không bị trùng).
- **Tải mô hình ML Kit (không bao giờ tự chờ tải)**: khi chưa có mô hình cho ngôn ngữ cần dịch, tác vụ **tạm dừng ngay** với thông báo **“Chưa có mô hình dịch”** và nút **“Tải mô hình”** — không đứng chờ tải. Nhấn **“Tải mô hình”** mở một popup hiển thị tiến trình tải (và báo lỗi nếu thất bại); popup có ô **“Cho phép tải bằng dữ liệu di động (3G/4G)”** (mặc định chỉ Wi-Fi). Tải xong, nhấn **“Tiếp tục”** để dịch tiếp. Hộp thoại dịch cũng có sẵn ô cho phép dữ liệu di động.
- Công cụ:
  - **(a) Google ML Kit trên máy** (mặc định): miễn phí, không giới hạn, offline sau khi tải mô hình (~30MB/ngôn ngữ); hiển thị trạng thái tải mô hình, tùy chọn **chỉ tải qua Wi-Fi**; quản lý/tải/xóa mô hình trong Cài đặt. Dịch từng dòng để giữ xuống dòng.
  - **(b) Google Dịch online** (`translate.googleapis.com/translate_a/single?client=gtx…`): ghi rõ là **không chính thức, bị giới hạn tốc độ**; lô nhỏ, nghỉ 1.2 giây giữa các lô, **chờ lùi** khi gặp HTTP 429 (8s → 24s → 72s) rồi tạm dừng.
  - **(c) Gemini API** với key của bạn (`gemini-2.5-flash-lite`, REST `generateContent`, header `x-goog-api-key`); HTTP 429 → tạm dừng.
- Ngôn ngữ: vi, en, zh, ja, ko, fr, de, es, ru, th, id; nguồn có **Tự nhận diện** (ML Kit Language ID với ML Kit, `sl=auto` với Google, Gemini tự nhận).

## 8. Đọc thành tiếng (TTS)
- `TextToSpeech`, ưu tiên engine Google `com.google.android.tts`. Cài đặt: chọn **giọng/ngôn ngữ** từ `tts.voices`, **tốc độ**, **cao độ**, nghe thử.
- Văn bản được tách câu theo `. ! ? 。 ！ ？ …` và xuống dòng, gộp ~3 câu (≤ 400 ký tự) mỗi lượt, xếp hàng bằng `QUEUE_ADD` với utterance id.
- **Lưu vị trí nghe** (offset ký tự của đoạn đang đọc) theo từng sách → “Nghe tiếp” tiếp tục đúng chỗ. “Đọc từ đây” trong trình đọc TXT đặt lại dấu.
- Chạy trong **foreground service `mediaPlayback`** với thông báo kiểu media (lùi / phát-tạm dừng / tới / dừng) + MediaSession, giữ wake lock → vẫn đọc khi tắt màn hình. Xử lý audio focus (tạm dừng khi có cuộc gọi/ứng dụng khác phát).
- Nếu 3 đoạn liên tiếp bị lỗi (ví dụ giọng “cần mạng” khi offline) thì tạm dừng thay vì nhảy hết sách.
- Với PDF: đọc văn bản đã trích xuất.

## 9. Tạo sách nói MP3
- Mỗi đoạn (≤ 8 câu, ≤ 1500 ký tự) được tổng hợp bằng `TextToSpeech.synthesizeToFile` (WAV/PCM 16-bit).
- Mã hóa **MP3 thật** bằng **jump3r 1.0.5** (bản Java thuần của LAME, Maven Central `de.sciss:jump3r`). App dùng trực tiếp API cấp thấp `de.sciss.jump3r.mp3.*` vì lớp `LameEncoder` cấp cao phụ thuộc `javax.sound` (Android không có). Đã có unit test JVM mã hóa sóng sin thành MP3 hợp lệ. **Không cần fallback AAC/M4A** (chưa kiểm chứng trên thiết bị thật).
- **Một file MP3 duy nhất**: các khung MP3 của từng đoạn được **nối vào cuối file** (MP3 nối được). CBR 48/64/96/128 kbps (chọn khi tạo).
- **Chống treo**: mỗi đoạn tổng hợp có **giới hạn 3 lần thử**; nếu lỗi, bộ đọc được khởi tạo lại và thử lại, mỗi lần chờ tối đa 90 giây. Sau 3 lần vẫn lỗi thì tác vụ chuyển **ERROR kèm thông báo** (có thể Tiếp tục), không bao giờ đứng im vô hạn. Thanh tiến trình cập nhật “Đang đọc đoạn X / Y”.
- Tác vụ nền có thể tiếp tục, **lưu tiến độ** sau mỗi đoạn (chỉ số đoạn, đường dẫn, độ dài file, trạng thái, lỗi). Dừng giữa chừng (lỗi / app bị tắt) → hôm sau nhấn **“Tiếp tục”**, chạy tiếp từ đoạn kế tiếp, không làm lại phần đã xong. Giọng đọc được ghi nhớ trong tác vụ để cả file cùng một giọng.
- Trong khi tạo: file nằm ở `Android/data/com.dsoft.bookshelf/files/Music/BookShelf/`. Khi xong: copy vào **Music/BookShelf** qua MediaStore (Android 10+); nút **Mở kết quả** và **Chia sẻ**.
- Khi hoàn tất, sách nói trở thành **một mục riêng trên tủ sách** với **bìa hình đĩa CD** và nhãn **MP3**; chạm vào để **phát** bằng trình phát nhạc hệ thống.

## 10. Trích xuất văn bản PDF → sách TXT mới
- Ưu tiên lớp chữ có sẵn bằng **pdfbox-android** (`PDFTextStripper`, từng trang).
- Trang không có chữ → render bằng `PdfRenderer` (~1700px) và **OCR bằng ML Kit** (Latin; tự thử thêm Trung + Nhật nếu kết quả Latin quá ít; hoặc chọn cố định trong Cài đặt). Dùng bản Play-services (mô hình do Google Play services tải, APK nhỏ).
- Tác vụ có thể tiếp tục, tiến độ theo từng trang; trang lỗi được bỏ qua và đếm “bỏ qua N”.
- **Nối dòng bị ngắt**: PDF thường xuống dòng giữa câu; khi trích xuất, một dấu xuống dòng **không** đứng sau dấu kết câu (`. ! ? : ; … 。 ！ ？` và dấu đóng ngoặc/nháy) sẽ được thay bằng **dấu cách** (nối liền dòng), chỉ giữ xuống dòng khi hết câu hoặc trước dòng trống; các dấu cách thừa được gộp lại. (Có unit test cho bộ nối dòng này.)

## 11. Màn hình Tiến trình
- Liệt kê mọi tác vụ (trích xuất / dịch / MP3) với thanh tiến độ, “Trang/Lô/Đoạn X / Y”, trạng thái (đang chạy, đang chờ, tạm dừng, lỗi kèm thông báo, hoàn tất, đã hủy) và nút **Tiếp tục / Tạm dừng / Hủy / Mở kết quả** (+ Chia sẻ, Xóa khỏi danh sách).
- Cập nhật **trực tiếp** khi đang chạy. Mở từ biểu tượng đồng hồ trên tủ sách hoặc từ ⋮ của từng sách (lọc theo sách).
- Tác vụ chạy trong **foreground service `dataSync`** có thông báo tiến độ (nút Tạm dừng) + wake lock → tiếp tục khi tắt màn hình. Các tác vụ chạy lần lượt theo thứ tự tạo.

## Lưu trữ & độ bền
- `filesDir/library.json` (danh mục), `filesDir/books/<id>/` (sách, `cover.png`, `custom_cover.jpg`, `state.json` = vị trí PDF/TXT/nghe + tiến độ), `filesDir/jobs/*.json`.
- Mọi file JSON ghi **nguyên tử** (ghi `.tmp`, fsync, rename). Dữ liệu kết quả được append + fsync; độ dài đã xác nhận được lưu trong job để cắt bỏ phần ghi dở khi tiếp tục.
- Sau khi tiến trình bị hệ thống giết, tác vụ “đang chạy” được chuyển thành “tạm dừng — bị gián đoạn”, giữ nguyên tiến độ.
- Luồng chính không bị chặn: coroutines (`kotlinx-coroutines-android`) và executor cho render/IO.
- Xin quyền `POST_NOTIFICATIONS` trên Android 13+. Khai báo foreground service `mediaPlayback` và `dataSync` cùng quyền tương ứng.

## Thư viện
- AndroidX: core-ktx 1.13.1, recyclerview 1.3.2, media 1.7.0
- kotlinx-coroutines-android 1.8.1
- com.tom-roush:pdfbox-android 2.0.27.0
- com.google.mlkit:translate 17.0.3, com.google.mlkit:language-id 17.0.6
- play-services-mlkit-text-recognition 19.0.1 (+ chinese 16.0.1, japanese 16.0.1)
- de.sciss:jump3r 1.0.5 (LGPL)

## Giới hạn đã biết
- Phóng to PDF dùng phép scale của view nên trang ở mức zoom cao hơi mờ (bitmap render ở độ rộng màn hình).
- Cổng Google Dịch online không chính thức có thể bị chặn bất cứ lúc nào.
- Tệp TXT mã hóa cũ ngoài UTF-8/UTF-16/windows-1258 (ví dụ GBK, Shift-JIS, VNI) có thể hiển thị sai.
- APK chỉ chứa thư viện native cho `arm64-v8a` và `armeabi-v7a` (điện thoại/máy tính bảng thật); không chạy trên emulator x86 trừ khi bỏ `abiFilters` trong `app/build.gradle.kts`. APK debug khoảng 40MB (phần lớn là thư viện dịch ML Kit).
- Chưa được kiểm thử trên thiết bị thật (chỉ build + unit test JVM).
