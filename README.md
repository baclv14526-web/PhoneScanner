# Quét Số Gọi — App quét số điện thoại VN bằng camera

## Cách hoạt động
1. Mở app, camera chạy nền, khung xanh gợi ý vị trí đưa số điện thoại vào.
2. App dùng ML Kit (OCR chạy offline trên máy) đọc chữ trong khung hình.
3. Khi cùng một số điện thoại hợp lệ (dạng VN: 03x/05x/07x/08x/09x-xxxxxxx,
   hoặc +84...) xuất hiện ổn định 3 khung hình liên tiếp, app dừng quét và
   hiện số lên để bạn xác nhận.
4. Bấm **Gọi** để thực hiện cuộc gọi, hoặc **Quét lại** nếu đọc sai.

---

## ⚠️ Thiết lập ký số (BẮT BUỘC làm 1 lần trước khi push)

Vì repo này là **public**, không thể commit file keystore/mật khẩu thật vào
code (ai cũng thấy). Workflow CI ký APK bằng thông tin lấy từ **GitHub
Secrets** — bạn cần tạo keystore và khai báo secrets 1 lần duy nhất, sau đó
mọi lần push đều tự động build ra APK đã ký, không cần lặp lại bước này.

### Bước 1 — Tạo keystore (chạy trên máy tính của bạn, cần cài Java/JDK)

```bash
keytool -genkeypair -v \
  -keystore release.keystore.jks \
  -alias quetsogoi \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "MAT_KHAU_KEYSTORE_CUA_BAN" \
  -keypass "MAT_KHAU_KEY_CUA_BAN"
```

Lệnh sẽ hỏi vài thông tin (tên, tổ chức, quốc gia...) — điền gì cũng được,
không ảnh hưởng chức năng. **Lưu file `release.keystore.jks` này lại an
toàn (ví dụ trong Password Manager hoặc ổ cứng riêng) — mất file này thì về
sau không update được app nữa mà phải gỡ cài đặt bản cũ đi cài lại từ đầu.**

Tuyệt đối **không commit file `.jks` này vào Git**.

### Bước 2 — Encode keystore sang base64

```bash
base64 -i release.keystore.jks -o keystore_base64.txt
```
(Trên Linux/Mac; trên Windows PowerShell dùng:
`[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore.jks")) | Out-File keystore_base64.txt`)

### Bước 3 — Khai báo 4 Secrets trên GitHub

Vào repo trên GitHub → **Settings → Secrets and variables → Actions →
New repository secret**, tạo lần lượt 4 secret sau:

| Tên Secret | Giá trị |
|---|---|
| `KEYSTORE_BASE64` | Toàn bộ nội dung file `keystore_base64.txt` ở Bước 2 |
| `KEYSTORE_PASSWORD` | Mật khẩu keystore bạn đặt ở Bước 1 (`-storepass`) |
| `KEY_ALIAS` | `quetsogoi` (hoặc alias bạn đặt ở `-alias`) |
| `KEY_PASSWORD` | Mật khẩu key bạn đặt ở Bước 1 (`-keypass`) |

Xong bước này, **xoá file `keystore_base64.txt` khỏi máy** (không cần giữ,
chỉ cần giữ file `.jks` gốc).

### Bước 4 — Push code

```bash
git push origin main
```

Workflow tự chạy, build APK ký release, và đăng lên tab **Releases** của
repo (link cố định, không hết hạn).

---

## Cách tải & cài trên điện thoại (sau khi đã push thành công)

1. Vào tab **Releases** của repo trên GitHub (hoặc mở thẳng link
   `https://github.com/<user>/<repo>/releases/latest`).
2. Trên điện thoại, bấm vào file `.apk` để tải về.
3. Mở file `.apk` vừa tải → nếu lần đầu, Android sẽ hỏi "Cho phép cài đặt
   từ nguồn này" (cho trình duyệt bạn đang dùng, ví dụ Chrome/Samsung
   Internet) → bấm **Cho phép** → **Cài đặt**.
4. Các lần sau, mỗi lần bạn push code mới, chỉ cần vào lại link Releases,
   tải bản mới nhất, Android sẽ **tự động cập nhật đè** lên bản cũ (nhờ
   cùng chữ ký + versionCode tăng dần theo số lần build).

⚠️ Nếu trước đó bạn từng cài bản build debug (chưa ký release, theo hướng
dẫn cũ) — bản đó dùng debug key khác chữ ký, Android sẽ báo lỗi
"Ứng dụng chưa được cài đặt" khi cài đè. Cách xử lý: gỡ cài đặt bản debug
cũ đi rồi cài bản release mới.

---

## Chạy thử trên máy (Android Studio)
1. Mở thư mục này bằng Android Studio (Ladybug trở lên khuyến nghị vì dùng AGP 8.9.1).
2. Android Studio sẽ tự tạo `gradlew` + `gradle-wrapper.jar` khi Sync lần đầu.
3. Kết nối điện thoại Samsung A23 5G qua USB, bật Chế độ nhà phát triển +
   Gỡ lỗi USB, bấm Run (build debug bình thường, không cần keystore).

## Vì sao build trên CI nhanh dần theo thời gian
- `gradle/actions/setup-gradle` tự cache `~/.gradle/caches` (dependency đã
  tải: CameraX, ML Kit...) và cache resolution giữa các lần chạy dựa trên
  GitHub Actions cache backend — lần build đầu chậm (~3-5 phút tải
  dependency), các lần sau nhanh hơn đáng kể vì tái sử dụng cache.
- `gradle.properties` đã bật `org.gradle.caching=true` (build cache) và
  `org.gradle.parallel=true` (biên dịch song song các module).
- Workflow chỉ chạy khi push lên `main`, không chạy lại nếu chỉ sửa
  README hay các file ngoài code (có thể tự thêm điều kiện `paths:` nếu
  muốn tối ưu thêm).

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
