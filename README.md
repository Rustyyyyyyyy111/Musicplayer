# Music Player (Android • Jetpack Compose • Media3 • TDLib)

> **Song ngữ / Bilingual Documentation:** Tiếng Việt • [English Summary](#english-overview)

Ứng dụng nghe nhạc cá nhân tiếng Việt (**100% vi-VN**) mang phong cách thiết kế **Apple Music & Liquid Glass**, kết hợp động cơ âm thanh **Media3 / ExoPlayer** với bộ xử lý DSP 10 băng tần tùy chỉnh, cơ chế **Dual-Player Crossfade**, **Telegram TDLib** (chế độ chỉ đọc + chống Flood-Wait), **YouTube** (NewPipeExtractor phát âm thanh thuần, không giới hạn thời lượng) và quản lý thư viện **Offline**.

---

## Tính năng chính (Tiếng Việt)

1. **Giao diện Apple Music & 3 Chế độ Hiển thị**
   - **Chế độ sáng (Light)**: Nền trắng/xám nhạt Apple (`#FFFFFF` / `#F5F5F7`), điểm nhấn đỏ `#FA243C`.
   - **Chế độ tối (Dark OLED)**: Nền đen sâu `#000000`, thẻ `#1C1C1E`.
   - **Liquid Glass**: Trích xuất bảng màu động bằng `Palette` từ ảnh bìa bài hát, hiệu ứng mờ `Modifier.blur`, thẻ kính mờ và chuyển màu mượt mà.
   - Điều hướng thích ứng: Bottom `NavigationBar` trên điện thoại và `NavigationRail` trên màn hình rộng/máy tính bảng, kèm `FloatingMiniPlayer` và màn hình `NowPlayingSheet` toàn màn hình.

2. **3 Nguồn Nhạc Đồng Bộ Trong Thư Viện (`[ Telegram | YouTube | Offline ]`)**
   - **Telegram (TDLib)**: Đăng nhập tài khoản cá nhân (SĐT + OTP 5 số + 2FA), hỗ trợ đa tài khoản, duyệt đủ 8 loại hội thoại, nhận diện **Forum Topics như Nghệ sĩ riêng biệt**, phát trực tiếp luồng progressive (`TelegramAudioDataSource`) với cơ chế **Local-First Room DB**, đồng bộ tăng dần (Delta Sync), hàng đợi giới hạn tần suất (Anti-Flood) và tự động khôi phục khi đứng luồng.
   - **YouTube (NewPipeExtractor)**: Tìm kiếm trực tiếp trên YouTube tiêu chuẩn (video/remix/cover/live mọi độ dài), giữ mục **Playlist đề xuất** & **Thể loại thịnh hành**, phát audio-only (OPUS/M4A) và tải nền qua WorkManager với thanh tiến trình `%` thời gian thực.
   - **Offline**: Lập chỉ mục tệp âm thanh qua `MediaStore` và thư mục `/sdcard/Music Player/` (`MP3`, `FLAC`, `WAV`, `M4A`, `AAC`, `ALAC`, `OGG`, `OPUS`, `APE`) không cần kết nối mạng.

3. **Trình Phát Đang Nghe (Now Playing), Chromecast & Menu "•••" Kiểu Apple Music**
   - **Chromecast & Thiết bị phát**: Khám phá thiết bị Chromecast qua `MediaRouter` + Media3 `CastPlayer`, hiển thị đầu ra Bluetooth / Tai nghe / Loa máy và tự động thông báo kèm lối tắt chuyển **Đầu ra âm thanh hệ thống** / **Bluetooth** khi chưa có Chromecast.
   - **Menu "•••" trên góc phải Now Playing**:
     - **Hẹn giờ (Sleep Timer)**: Bộ chọn Apple hiện đại với nút nhanh (`15 phút`, `30 phút`, `45 phút`, `1 tiếng`, `Hết bài hiện tại`), tùy chỉnh số phút hoặc số bài hát và đếm ngược trực tiếp.
     - **Thông tin bài hát**: Hiển thị đầy đủ siêu dữ liệu (`Tên bài hát`, `Nghệ sỹ`, `Album`, `Nghệ sỹ album`, `Thể loại`, `Năm`) và thông số tệp (`Đường dẫn file`, `Định dạng/Codec`, `Bitrate`, `Sample rate`, `Số kênh`, `Dung lượng file`).
     - **Chế độ ngủ (Sleep Mode)**: Bật/tắt **Giảm dần âm lượng 30 giây** trước khi tắt nhạc và **Khóa thao tác nhầm / Giao diện dịu mắt** (làm tối màn hình và nhấn giữ 1.5 giây để mở khóa).
     - **Sửa tag bài hát (Offline)**: Chỉnh sửa `Tên bài hát`, `Nghệ sỹ`, `Album`, `Nghệ sỹ album`, `Thể loại`, `Năm` — ghi trực tiếp vào tệp âm thanh (`MP3` ID3v2, `FLAC` VorbisComment, `M4A` MP4 atoms), đồng bộ `MediaStore` và Room DB ngay lập tức.

4. **Động cơ Âm thanh & Tiện ích Chuyên sâu**
   - **10-Band Biquad Equalizer (`BiquadEqAudioProcessor`)**: 31Hz–16kHz, Preamp `-12..+12 dB`, 9 Preset, Soft-Clipping Limiter, Auto-EQ theo 13 thể loại, chuẩn hóa âm lượng EBU R128.
   - **Dual-Player Crossfade (`DualPlayerCrossfadeController`)**: Tắt / 2s / 4s / 6s.
   - **Lời bài hát đồng bộ (.lrc + LRCLIB)**: Hỗ trợ chỉnh độ trễ (`±0.5s`), Romanization Hangul/Hiragana, tìm lời thủ công.
   - **Android Auto (`MusicLibraryService`)**, **Chế độ xe (Car Mode)**, **Phát hiện trùng lặp**, **Bộ nhớ đệm LRU**, **Proxy HTTP/SOCKS5**, **Widget màn hình chính (Glance)** và **Nhật ký hoạt động**.

---

## English Overview

**Music Player** is a Vietnamese-localized (`vi-VN`) Android audio player built with **Kotlin, Jetpack Compose, Material 3, Media3/ExoPlayer, Room, and TDLib**, featuring an **Apple Music / Liquid Glass** UI.

### Key Capabilities
- **Multi-Source Library**:
  - **Telegram (TDLib)**: Strict read-only account login (Phone + OTP + 2FA), multi-account slots, Forum Topics mapped as Artists, progressive streaming (`tg://`), anti-flood rate-limited delta sync cached in Room DB, and automatic stream stall recovery.
  - **Standard YouTube (NewPipeExtractor)**: Audio-only extraction (`OPUS`/`M4A`) for tracks of any duration, discovery playlists/genres, and WorkManager offline downloads with live `%` progress & cancellation.
  - **Offline Music**: `MediaStore` + `/sdcard/Music Player/` + SAF (`OpenDocumentTree`) indexing (`MP3`, `FLAC`, `WAV`, `M4A`, `AAC`, `ALAC`, `OGG`, `OPUS`, `APE`).
- **Chromecast & Audio Output Picker**: `MediaRouter` + Media3 `CastPlayer` discovery with real-time Bluetooth/Wired/Speaker listing and graceful fallback to Android System Audio Output Switcher / Bluetooth Settings when Google Play Services or Cast receivers are unavailable.
- **Now Playing `•••` Overflow Suite**:
  - **Sleep Timer (`Hẹn giờ`)**: Apple-style pill presets (`15m`, `30m`, `45m`, `1h`, `End of track`), custom minute/track count input, and live countdown status.
  - **Track Info (`Thông tin bài hát`)**: Full metadata & technical inspector (Album Artist, Genre, Year, File Path, Codec, Bitrate, Sample Rate, Channel Count, File Size) with safe fallbacks for online streams.
  - **Sleep Mode (`Chế độ ngủ`)**: Smooth **30-second volume fade-out** before sleep pause plus a dimmed nocturnal **Hold-to-Unlock** accidental-touch shield.
  - **Offline Tag Editor (`Sửa tag bài hát`)**: Direct container tag writing (`MP3` ID3v2, `FLAC` VorbisComment, `M4A` MP4 atoms) synced with `MediaStore`, Room DB, and the live player queue.
- **Audio DSP & Extras**: 10-band Biquad EQ, Dual-Player Crossfade, EBU R128 loudness normalization, synced `.lrc` / LRCLIB lyrics, Android Auto, Car Mode, HTTP/SOCKS5 proxy support, and Jetpack Glance widgets.

---

## Thư mục Lưu trữ / Unified Storage Directories

- `/sdcard/Music Player/Telegram/`
- `/sdcard/Music Player/YouTube/` *(tự động chuyển sang `Music/Music Player/YouTube/` hoặc SAF nếu chưa cấp quyền All Files Access / falls back cleanly under Android 11+ Scoped Storage)*
- `/sdcard/Music Player/Artwork/`
- `/sdcard/Music Player/Lyrics/`

---

## Cấu hình Secrets / Secrets Setup (AI Studio)

Thay vì hardcode khóa bí mật trong mã nguồn hoặc `local.properties`, hãy khai báo các biến sau trong **bảng Secrets của Google AI Studio** (hoặc `.env` dựa trên `.env.example`):

- `TELEGRAM_API_ID` — API ID lấy từ [my.telegram.org](https://my.telegram.org)
- `TELEGRAM_API_HASH` — API Hash lấy từ [my.telegram.org](https://my.telegram.org)
- `LASTFM_API_KEY` *(optional)* — Last.fm Scrobble API key
- `LASTFM_API_SECRET` *(optional)* — Last.fm Scrobble secret

---

## Kiến trúc `arm64-v8a` & Giới hạn / ABI Compatibility

Dự án cấu hình `ndk { abiFilters += "arm64-v8a" }` trong `app/build.gradle.kts` để tối ưu dung lượng APK và đóng gói thư viện gốc **TDLib (`libtdjni.so`)**.

- **ARM64 (`arm64-v8a`)**: Hỗ trợ đầy đủ mọi tính năng bao gồm Telegram TDLib gốc.
- **32-bit (`armeabi-v7a`) / `x86` / `x86_64` emulators**: Không có binary `libtdjni.so` tương ứng; các nguồn Offline và YouTube vẫn hoạt động bình thường nhưng Telegram TDLib sẽ báo không khả dụng trên kiến trúc không phải ARM64.

---

## Build & Kiểm thử / Build & Testing

```bash
# Chạy unit test & Robolectric UI test trên JVM / Run JVM & Robolectric tests
gradle :app:testDebugUnitTest

# Đóng gói APK Debug (arm64-v8a) / Build Debug APK
gradle :app:assembleDebug

# Đóng gói APK Release (yêu cầu biến môi trường keystore) / Build Release APK
gradle :app:assembleRelease
```
