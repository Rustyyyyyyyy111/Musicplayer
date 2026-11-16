# Music Player (Android • Jetpack Compose • Media3 • TDLib)

Ứng dụng nghe nhạc cá nhân tiếng Việt (**vi-VN**) mang phong cách thiết kế **Apple Music**, kết hợp động cơ âm thanh **Media3 / ExoPlayer** với bộ xử lý DSP 10 băng tần tùy chỉnh, cơ chế **Dual-Player Crossfade**, **Telegram TDLib** (chế độ chỉ đọc nghiêm ngặt), **YouTube Music** (InnerTube / NewPipeExtractor) và quản lý thư viện **Offline**.

---

## Tính năng chính

1. **Giao diện Apple Music & 3 Chế độ Hiển thị**
   - **Chế độ sáng (Light)**: Nền trắng/xám nhạt Apple (`#FFFFFF` / `#F5F5F7`), điểm nhấn đỏ `#FA243C`.
   - **Chế độ tối (Dark OLED)**: Nền đen sâu `#000000`, thẻ `#1C1C1E`.
   - **Liquid Glass**: Trích xuất bảng màu động bằng `Palette` từ ảnh bìa bài hát, hiệu ứng mờ `Modifier.blur`, thẻ kính mờ và chuyển màu mượt mà.
   - Điều hướng thích ứng: Bottom `NavigationBar` trên điện thoại và `NavigationRail` trên màn hình rộng/máy tính bảng, kèm `FloatingMiniPlayer` và màn hình `NowPlayingSheet` toàn màn hình.

2. **3 Nguồn Nhạc Đồng Bộ Trong Thư Viện (`[ Telegram | YouTube Music | Offline ]`)**
   - **Telegram (TDLib)**: Đăng nhập tài khoản cá nhân (SĐT + OTP 5 số + 2FA), hỗ trợ đa tài khoản, duyệt đủ 8 loại hội thoại, nhận diện **Forum Topics như Nghệ sĩ riêng biệt**, phát trực tiếp luồng progressive (`TelegramAudioDataSource`) và tải về nghe offline.
   - **YouTube Music**: Tìm kiếm bài hát, nghệ sĩ, album, playlist qua tầng InnerTube/NewPipeExtractor và tải xuống thư mục thống nhất.
   - **Offline**: Lập chỉ mục tệp âm thanh qua `MediaStore` và thư mục `/sdcard/Music Player/` (MP3, FLAC, WAV, M4A, AAC, ALAC, OGG, OPUS, APE) không cần kết nối mạng.

3. **Thư mục Lưu trữ Thống nhất**
   - `/sdcard/Music Player/Telegram/`
   - `/sdcard/Music Player/YouTube/`
   - `/sdcard/Music Player/Artwork/`
   - `/sdcard/Music Player/Lyrics/`
   - Hỗ trợ chọn thư mục SAF (`OpenDocumentTree`) mà không cần quyền `MANAGE_EXTERNAL_STORAGE`.

4. **Động cơ Âm thanh & Tiện ích Chuyên sâu**
   - **10-Band Biquad Equalizer (`BiquadEqAudioProcessor`)**: 31Hz–16kHz, Preamp `-12..+12 dB`, 9 Preset, Soft-Clipping Limiter, Auto-EQ theo 13 thể loại, chuẩn hóa âm lượng EBU R128.
   - **Dual-Player Crossfade (`DualPlayerCrossfadeController`)**: Tắt / 2s / 4s / 6s.
   - **Lời bài hát đồng bộ (.lrc + LRCLIB)**: Hỗ trợ chỉnh độ trễ (`±0.5s`), Romanization Hangul/Hiragana, tìm lời thủ công.
   - **Android Auto (`MusicLibraryService`)** & **Chế độ xe (Car Mode)** tương phản cao.
   - **Phát hiện trùng lặp**, **Bộ nhớ đệm LRU** (bảo vệ tuyệt đối bài đã tải offline), **Proxy HTTP/SOCKS5**, **Scrobble Last.fm / ListenBrainz**, **AI Smart Mix**, **Nhập/Xuất M3U**, và **Nhật ký hoạt động** đã lọc dữ liệu nhạy cảm.

---

## Cấu hình Secrets (AI Studio)

Thay vì hardcode khóa bí mật trong mã nguồn hoặc `local.properties`, hãy khai báo các biến sau trong **bảng Secrets của Google AI Studio** (xem `.env.example`):

- `TELEGRAM_API_ID` — API ID lấy từ [my.telegram.org](https://my.telegram.org)
- `TELEGRAM_API_HASH` — API Hash lấy từ [my.telegram.org](https://my.telegram.org)
- `LASTFM_API_KEY` *(tùy chọn)* — Khóa Last.fm Scrobble
- `LASTFM_API_SECRET` *(tùy chọn)* — Secret Last.fm Scrobble

---

## Kiến trúc `arm64-v8a` & Giới hạn tương thích

Dự án cấu hình `ndk { abiFilters += "arm64-v8a" }` trong `app/build.gradle.kts` để tối ưu kích thước APK và đóng gói thư viện gốc **TDLib (`libtdjni.so`)**.

**Giới hạn cần lưu ý:**
- APK chỉ chứa mã máy **ARM64 (`arm64-v8a`)**.
- **Không tương thích** với thiết bị Android 32-bit (`armeabi-v7a`) hoặc trình giả lập kiến trúc **`x86` / `x86_64`** không có lớp dịch ARM64 (trên giả lập x86, ứng dụng vẫn khởi chạy và phát Offline/YouTube Music nhưng TDLib gốc sẽ thông báo không khả dụng nếu thiếu `libtdjni.so` phù hợp ABI).
- Kiểm thử thực tế Telegram TDLib cần thiết bị hoặc giả lập **ARM64 (`arm64-v8a`)**.

---

## Hướng dẫn Build & Kiểm thử

```bash
# Chạy unit test & Robolectric UI test trên JVM
gradle :app:testDebugUnitTest

# Đóng gói APK Debug (arm64-v8a)
gradle :app:assembleDebug

# Đóng gói APK Release (yêu cầu biến môi trường keystore)
gradle :app:assembleRelease
```
