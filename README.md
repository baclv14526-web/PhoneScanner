# Quét Số Gọi — App quét số điện thoại VN bằng camera

## Cách hoạt động
1. Mở app, camera chạy nền, khung xanh gợi ý vị trí đưa số điện thoại vào.
2. App dùng ML Kit (OCR chạy offline trên máy) đọc chữ trong khung hình.
3. Khi cùng một số điện thoại hợp lệ (dạng VN: 03x/05x/07x/08x/09x-xxxxxxx,
   hoặc +84...) xuất hiện ổn định 3 khung hình liên tiếp, app dừng quét và
   hiện số lên để bạn xác nhận.
4. Bấm **Gọi** để thực hiện cuộc gọi, hoặc **Quét lại** nếu đọc sai.

---

## ⚠️ Thiết lập (KHÔNG cần cài Java) — chỉ làm 1 lần

Vì repo này là **public**, không thể commit file keystore/mật khẩu thật vào
code. Nhưng bạn không cần tự tạo keystore bằng `keytool` trên máy — **workflow
sẽ tự làm việc đó ngay trên GitHub Actions** (runner của GitHub có sẵn Java),
rồi tự lưu lại keystore đó vào GitHub Secrets để các lần build sau dùng lại
(giữ nguyên chữ ký, không bị lỗi khi cài đè bản mới lên bản cũ).

Việc duy nhất bạn cần làm: tạo 1 **Personal Access Token (PAT)** để cho phép
workflow được quyền tự ghi secret vào chính repo của bạn — thao tác này chỉ
là click chuột trên trang web GitHub, không cần Java, không cần cài gì.

### Bước 1 — Tạo Personal Access Token

1. Vào **github.com → bấm avatar góc phải trên → Settings**.
2. Kéo xuống cuối menu bên trái, chọn **Developer settings**.
3. Chọn **Personal access tokens → Tokens (classic) → Generate new token
   (classic)**.
4. Đặt tên bất kỳ (vd: `quetsogoi-ci`), thời hạn (Expiration) chọn **7 ngày**
   là đủ (chỉ cần dùng 1 lần cho lần build đầu tiên).
5. Tick chọn scope **`repo`** (tick vào ô `repo` ở đầu, nó sẽ tự tick hết
   các ô con bên trong).
6. Bấm **Generate token** ở cuối trang → **copy đoạn token hiện ra ngay**
   (dạng `ghp_xxxxxxxxxxxx...`) — trang này chỉ hiện 1 lần duy nhất, rời
   trang là mất, phải tạo token mới nếu quên copy.

### Bước 2 — Khai báo token đó làm Secret trong repo

1. Vào repo trên GitHub → **Settings → Secrets and variables → Actions →
   New repository secret**.
2. Đặt tên secret là **`GH_PAT`**, dán token vừa copy vào ô Value → **Add secret**.

### Bước 3 — Push code

```bash
git push origin main
```

Lần chạy đầu tiên, workflow sẽ:
1. Tự tạo keystore bằng `keytool` ngay trên máy chủ GitHub Actions.
2. Tự sinh mật khẩu ngẫu nhiên an toàn cho keystore.
3. Tự lưu keystore + mật khẩu vào 4 secret của repo (`KEYSTORE_BASE64`,
   `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) bằng chính `GH_PAT` bạn
   vừa thêm.
4. Dùng luôn keystore đó để ký APK và đăng lên GitHub Releases.

**Từ lần push thứ 2 trở đi**, workflow thấy đã có sẵn 4 secret keystore nên
sẽ **dùng lại y nguyên**, không tạo mới — nhờ vậy chữ ký không đổi giữa các
lần build, cài đè bản mới lên bản cũ luôn thành công.

### Bước 4 (khuyến nghị, không bắt buộc) — Thu hồi PAT sau khi build đầu tiên thành công

Sau khi thấy lần build đầu tiên chạy xong và đã có APK trên tab Releases,
bạn có thể xoá secret `GH_PAT` hoặc thu hồi token đó (**Settings → Developer
settings → Personal access tokens → Delete**), vì từ giờ workflow không cần
tới nó nữa (đã có sẵn keystore trong secrets rồi). Đây là bước bảo mật thêm,
không làm thì token cũng tự hết hạn sau 7 ngày như đã đặt ở Bước 1.

**Lưu ý bảo mật:** keystore được tự sinh và lưu dưới dạng GitHub Secrets —
những secret này chỉ có thể *ghi*, không ai (kể cả chủ repo) xem lại được
giá trị qua giao diện web hay API sau khi đã lưu, chỉ workflow mới đọc được
lúc chạy. Nếu file này lỡ bị mất do repo bị xoá secrets, bạn sẽ phải gỡ cài
đặt app cũ trên điện thoại rồi cài lại bản ký bằng keystore mới.

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
