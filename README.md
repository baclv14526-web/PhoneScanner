# Quét Số Gọi — App quét số điện thoại VN bằng camera

## Cách hoạt động
1. Mở app, camera chạy nền, khung xanh gợi ý vị trí đưa số điện thoại vào.
2. App dùng ML Kit (OCR chạy offline trên máy) đọc chữ trong khung hình.
3. Khi cùng một số điện thoại hợp lệ (dạng VN: 03x/05x/07x/08x/09x-xxxxxxx,
   hoặc +84...) xuất hiện ổn định 3 khung hình liên tiếp, app dừng quét và
   hiện số lên để bạn xác nhận.
4. Bấm **Gọi** để thực hiện cuộc gọi, hoặc **Quét lại** nếu đọc sai.

---

## Build & cài đặt — không cần thiết lập gì cả

```bash
git push origin main
```

Xong. Workflow tự động:
1. Tự tạo keystore + mật khẩu ngẫu nhiên ngay trên máy chủ GitHub Actions
   (không cần bạn cài Java, không cần secret nào).
2. Ký APK bằng keystore vừa tạo.
3. Đăng APK lên tab **Releases** của repo tại link cố định:
   `https://github.com/<user>/<repo>/releases/latest`

## Cách cài lên điện thoại
1. Vào link Releases ở trên bằng trình duyệt điện thoại.
2. Tải file `.apk`, mở ra để cài (lần đầu Android sẽ hỏi "Cho phép cài đặt
   từ nguồn này" — bấm Cho phép).

⚠️ **Lưu ý quan trọng:** mỗi lần build, workflow tạo keystore **mới hoàn
toàn** (không lưu lại giữa các lần chạy), nên chữ ký APK **đổi khác nhau
mỗi lần build**. Vì Android chỉ cho cài đè bản mới khi cùng chữ ký với bản
cũ, nên mỗi lần bạn tải bản build mới về, cần **gỡ cài đặt bản cũ trước**
rồi mới cài bản mới (không cài đè trực tiếp được). Đây là đánh đổi để đổi
lấy việc không cần thiết lập bất kỳ secret/token nào.

*(Nếu sau này bạn muốn giữ nguyên 1 chữ ký cố định để cài đè được giữa các
lần build — đổi lại phải làm thêm vài bước thiết lập 1 lần — cứ nói mình
làm lại theo hướng đó.)*

---

## Chạy thử trên máy (Android Studio)
1. Mở thư mục này bằng Android Studio (Ladybug trở lên khuyến nghị vì dùng AGP 8.9.1).
2. Android Studio sẽ tự tạo `gradlew` + `gradle-wrapper.jar` khi Sync lần đầu.
3. Kết nối điện thoại Samsung A23 5G qua USB, bật Chế độ nhà phát triển +
   Gỡ lỗi USB, bấm Run (build debug bình thường, không cần keystore).

## Cấu hình đã pin cứng
| Thành phần | Version |
|---|---|
| Gradle | 8.14 |
| Android Gradle Plugin | 8.9.1 |
| Kotlin | 2.1.0 |
| Java | 17 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| CameraX | 1.4.0 |
| ML Kit Text Recognition | 16.0.1 |

## Các điểm cần lưu ý khi test trên Samsung A23 5G (One UI)
- Lần đầu mở app, ML Kit cần internet để tải model OCR về máy (chỉ 1 lần,
  sau đó chạy offline hoàn toàn).
- Nếu Samsung hiện cảnh báo "App yêu cầu quyền Gọi điện", đây là quyền
  `CALL_PHONE` bắt buộc để app tự thực hiện cuộc gọi sau khi bạn xác nhận —
  cần bấm Cho phép thì tính năng Gọi mới hoạt động.

## Có thể mở rộng sau
- Thêm rung/âm thanh khi phát hiện số để không cần nhìn màn hình xác nhận.
- Cho phép chỉnh sửa tay số vừa nhận diện trước khi gọi (phòng khi OCR đọc
  thiếu 1 chữ số).
- Lưu lịch sử các số đã quét.
