# Companion Backend Service — Kênh Telegram Công Khai

Dịch vụ backend mẫu (Node.js + GramJS MTProto chính thức) cho phép ứng dụng Music Player đồng bộ và phát nhạc từ các kênh Telegram công khai (`@username` / `t.me/...`) mà **không yêu cầu người dùng cuối đăng nhập tài khoản Telegram cá nhân trên điện thoại**.

## Endpoint hỗ trợ
1. `GET /v1/telegram/public/resolve?username=<channel>`
   - Trả về metadata công khai của kênh (`username`, `title`, `avatarUrl`, `channelId`).
2. `GET /v1/telegram/public/channels/<username>/audio?cursor=<messageId>&limit=50`
   - Phân trang các bài hát (MP3, FLAC, ALAC, WAV, M4A/AAC, OPUS/OGG) trong kênh với `nextCursor`.
3. `GET /v1/telegram/public/channels/<username>/stream/<messageId>`
   - Stream trực tiếp với HTTP `Range` (`206 Partial Content`) để ExoPlayer / Media3 tua mượt mà và tải xuống ngoại tuyến.

## Cấu hình & Chạy cục bộ / VPS

```bash
cd companion-backend
npm install

export TELEGRAM_API_ID=123456
export TELEGRAM_API_HASH=your_api_hash
# (Tuỳ chọn) Chuỗi StringSession hoặc Bot Token nếu triển khai production
export TELEGRAM_STRING_SESSION=""
# (Tuỳ chọn nhưng khuyến nghị) Bearer token cho ứng dụng Android
export BACKEND_AUTH_TOKEN=replace_with_strong_random_secret
export PORT=8787

npm start
```

Sau khi triển khai (HTTPS reverse proxy hoặc mạng LAN / `http://10.0.2.2:8787` khi thử trên Android Emulator):
1. Mở ứng dụng Music Player → **Cài đặt** → **Kênh Telegram công khai**.
2. Bật công tắc **Đồng bộ kênh công khai**, chọn chế độ **Backend API**.
3. Nhập **URL Backend** và **Bearer Token** (nếu đã đặt `BACKEND_AUTH_TOKEN`), nhấn **Lưu cấu hình**.
4. Nhập `@channel_username` hoặc `https://t.me/channel_username` và nhấn **Thêm kênh**.
