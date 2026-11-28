# D Play (trước đây: Signage Player) (Android 9+)

Ứng dụng phát video/ảnh quảng cáo màn hình dọc 9:16 cho Android TV box thang máy, nối với AMS
(`ams.anio.vn`, gateway `gw-ams.anio.vn`). Mô tả chức năng: `Tai lieu mo ta - Signage Player Android.md (tên tệp tài liệu cũ)`.

## Build APK
Đẩy lên nhánh `main`: workflow `.github/workflows/build-apk.yml` build và đăng
`signage-player-debug.apk` vào Release **debug-latest** (và Artifacts).
Cài thử: `adb install -r signage-player-debug.apk`

### Chữ ký & cài đè (từ 1.1.1)
Mọi bản build ký bằng khóa CỐ ĐỊNH `app/anio-signage.jks` (alias `anio-signage`, SHA-256 chứng chỉ ghi ở `docs/YEU-CAU-BO-SUNG-AMS.md`) nên cài đè lên bản cũ không cần gỡ.
**Không đổi/xóa/tạo lại tệp khóa này**: đổi khóa = Android từ chối cài đè và máy phải gỡ app (mã thiết bị đổi).
Bản 1.1.0 trở về trước ký bằng khóa debug ngẫu nhiên của CI nên **một lần duy nhất** phải gỡ bản cũ rồi cài 1.1.1; từ đó mọi bản sau cài đè được.

### Mã thiết bị không đổi
Seed Ed25519 suy ra từ `ANDROID_ID` (theo khóa ký + thiết bị) và được lưu bền. `/v1/register` idempotent theo pubkey nên cùng seed luôn nhận lại đúng `device_id`; gỡ rồi cài lại cùng chữ ký vẫn ra mã cũ.

## Chế độ HOME (kiosk)
- App khai báo `CATEGORY_HOME`. Lần đầu bấm nút Home, chọn D Play và **Luôn dùng**; từ đó box khởi động và nút Home đều vào app.
- **Thoát app:** nhấn chuột phải (hoặc giữ nút Back trên điều khiển) 3 giây, nhập mật khẩu. App tạm tắt chế độ HOME để trả về launcher gốc; mở lại app từ icon sẽ bật lại.
- **Mở cài đặt:** giữ chuột trái 3 giây, nhấn đúp, hoặc nút OK. Mật khẩu mặc định `1234`, bắt buộc đổi lần đầu.
- **Quên mật khẩu:** vận hành viên gửi lệnh `reset_pin` trên ams.anio.vn (xem `docs/YEU-CAU-BO-SUNG-AMS.md`).

## Kết nối ams
Tự đăng ký (Ed25519, `kind=elevator`), heartbeat, manifest, proof_of_play theo `docs/DEVICE-PROTOCOL.md` của AMS.
Yêu cầu AMS cần bổ sung: `docs/YEU-CAU-BO-SUNG-AMS.md`.

## USB / LAN
- USB: thư mục `/ANIO/` ở gốc USB (tùy chọn `playlist.json`: `{"items":[{"file":"a.jpg","duration":10},{"file":"b.mp4"}]}`).
- LAN: Nguồn dữ liệu > Địa chỉ LAN, trỏ tới `playlist.json` (`{version, items:[{id,type,url,duration,checksum}]}`).

## Giao diện
Màu và bo góc theo ANIO Design System; chữ dùng font hệ thống sans-serif (Roboto trên Android).
Font San Francisco của Apple không được phép đóng gói vào app Android.

## Lệnh AMS đã hỗ trợ (từ 1.0.1)
`refresh_manifest`, `reboot`, `reset_pin`, `message` (dải thông báo/chữ chạy), `set_volume`, `set_orientation`. Id lệnh đã xử lý được lưu bền để lệnh giao lại không chạy lặp. Gói USB: giữ nguyên định dạng `ANIO/playlist.json`.

## Hiển thị nội dung theo web AMS (từ 1.1.0)
- Đọc `layout` / `zones` trong manifest (slot không có layout vẫn phát tuần tự như cũ). Dựng bố cục theo % màn hình, xoay dọc/ngang đúng; mỗi vùng video có ExoPlayer riêng, chỉ vùng video lớn nhất bật tiếng.
- Vùng hỗ trợ: video, ảnh, chữ chạy, văn bản, đồng hồ (giờ VN), thời tiết (Open-Meteo, cache 10 phút, offline dùng giá trị cuối), mã QR (zxing cho văn bản; ảnh QR/VietQR tải về từ URL web gửi xuống; ảnh nhúng dạng data URL), header/footer, chuyển cảnh none/fade/slide/zoom.
- Thông báo khẩn (`message`) phủ lên MỌI màn hình, kể cả màn chờ.
- Quyền: chỉ `INTERNET` (không cần vị trí: chế độ GPS dùng toạ độ web cấu hình và gửi xuống).
- Chế độ gỡ lỗi (Menu → "Chế độ gỡ lỗi"): nhãn nhỏ góc màn hình (slot, nội dung, vùng, trạng thái cache, phiên bản manifest, thiết bị), log ra Logcat (`adb logcat | grep Signage`) và gửi lên AMS qua `/v1/logs` mỗi 10 phút.
- Kiểm thử: `gradle :app:testDebugUnitTest` dùng manifest thật của gateway (`app/src/test/resources/manifest-layout.json`); chạy tự động trong CI.

## Chẩn đoán nhận nội dung (từ 1.1.1)
- **Màn chờ** chỉ hiện MỘT dòng trung tính: "Đã kết nối với hệ thống quản trị" / "Đang kết nối hệ thống quản trị…" / "Chưa kết nối được hệ thống quản trị" (không mã lỗi, không mã thiết bị). Lý do chi tiết (chưa duyệt, mã HTTP, tải tệp x/y...) và mã thiết bị chỉ hiện khi bật **Chế độ gỡ lỗi**.
- **Menu → Thông tin thiết bị**: mã thiết bị, quyền tin cậy/đăng ký, địa chỉ máy chủ và nút **Sao chép mã thiết bị**.
- **Menu → Chế độ gỡ lỗi: BẬT** hiện bảng: địa chỉ gateway, mã thiết bị + quyền (quyền 0 = chưa duyệt trên web), nhóm manifest, lần đăng ký/heartbeat/manifest gần nhất kèm mã HTTP, 4 lệnh gần đây, nguồn đang phát, lỗi gần nhất.
- **Menu → "Đồng bộ ngay"**: hỏi AMS (lệnh, thông báo) và tải lại manifest tức thì.
- Chu kỳ hỏi AMS bị chặn trần 30 giây (máy chủ mặc định 300–600 giây) nên thông báo khẩn tới trong tối đa ~30 giây; đăng ký lại sau lỗi 30 giây (trước 15 phút).
- Điều kiện phía web: thiết bị phải được **duyệt** (quyền >= 1) mới nhận thông báo khẩn; chương trình phải **xuất bản**, nội dung đã duyệt, đích là đúng thiết bị/khu vực.

## Bản 1.1.2
- **Lỗi "Tải manifest lỗi mã 401"**: Cloudflare đổi ETag thành dạng yếu `W/"…"`; app ký đường dẫn thô trong khi OkHttp mã hóa `"`→`%22` nên chữ ký lệch. Nay app ký đúng URL đã mã hóa và chuẩn hóa ETag; nếu vẫn 401 sẽ thử lại không ETag và đăng ký lại, hiển thị lý do trong chế độ gỡ lỗi.
- **Âm lượng**: nếu hệ điều hành không cho đổi (TV box volume cố định/HDMI) app hạ âm lượng qua trình phát (ExoPlayer) và báo về web `applied_percent` + `mode` (`system`|`player`).
- **Realtime**: app giữ long-poll `GET /v1/commands/wait` (≤25 s) nên lệnh/tin khẩn tới thiết bị trong ~2 s; heartbeat vẫn là dự phòng. Cần gateway mới.


## Danh sách phát và lượt xem theo mục (1.1.5)

- Vùng media phát `playlist[]` của manifest (docs/PLAYLIST.md bên AMS): video/ảnh/chữ/link, tuần tự, lặp (`loop`), nạp trước video kế (2 ExoPlayer luân phiên), bỏ qua mục lỗi (ghi log `/v1/logs`), mọi mục lỗi thì màn chờ 8 giây rồi thử lại. Vùng cũ chỉ có `creative_ids` vẫn phát (đọc thành danh sách, id `lg-<vùng>-<creative>`).
- `item_impression`: mỗi mục hiển thị ổn định >= 1 giây ghi một bản ghi `log_id` (UUIDv7). Hàng đợi bền `event-queue.json` (chung với `proof_of_play`), gửi `POST /v1/events` mỗi 5 phút hoặc đủ 50 bản ghi, tối đa 50/lô. Chỉ xóa khi máy chủ trả 2xx; 401/lệch giờ/429/5xx/mất mạng thì giữ và lùi mũ; 413 và 400/422 thì chia đôi lô để cô lập sự kiện hỏng (sự kiện đơn lẻ bị từ chối mới bị bỏ sau 5 lần). Giờ gửi = giờ máy + độ lệch đồng hồ mới nhất lúc gửi. Dữ liệu `events.jsonl` của 1.1.4 được nhập tự động.
- Kiểm thử chấp nhận tay: xem `docs/NGHIEM-THU-1.1.5.md`.
