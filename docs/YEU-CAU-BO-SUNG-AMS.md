# Yêu cầu bổ sung trên AMS cho Signage Player (thang máy 9:16)

Gửi: nhóm AMS (ams.anio.vn, gw-ams.anio.vn). Ngày: 2026-11-20.
Căn cứ: `docs/DEVICE-PROTOCOL.md` và `docs/openapi/ams-device-gateway.yaml` trong repo AMS-shorf.

## 1. App đã làm theo giao thức hiện có (không cần AMS đổi)

Signage Player (`vn.anio.signage`) tự kết nối, không cần người nhập gì trên màn hình:

1. Lần đầu sinh khóa Ed25519, gọi `POST /v1/register` với `kind=elevator`, `model`, `os`, `app_version`.
2. Ký mọi yêu cầu `/v1/*` (X-Device-Id, X-Timestamp, X-Nonce, X-Signature), tự bù lệch đồng hồ khi gặp `stale_timestamp`.
3. `POST /v1/heartbeat` theo `next_interval` của server; `GET /v1/manifest?etag=` khi `manifest_etag` đổi, tải creative và kiểm `sha256`.
4. Gửi `proof_of_play` theo lô qua `POST /v1/events` (5 phút một lần hoặc đủ 20 sự kiện).
5. Nhận lệnh `refresh_manifest`, `reboot` (khởi động lại app), `reset_pin`, `message`, `set_volume`, `set_orientation` (từ bản 1.0.1) và ack qua `POST /v1/commands/ack`.
6. Kiểm tra mất mạng bằng `GET /health` mỗi 15 giây (3 lần lỗi thì chuyển sang USB/LAN, 2 lần đạt thì quay lại).

## 2. Cần AMS bổ sung (theo mức ưu tiên)

### P0: chặn việc chạy thật

| # | Yêu cầu | Lý do |
|---|---|---|
| 1 | **Cấp quyền thiết bị thang máy không cần mã 6 số.** Trang Thiết bị hiển thị máy mới đăng ký (`kind=elevator`, `trust_level=0`, có `device_id`, model, thời điểm đăng ký) và nút **Duyệt/Cấp quyền** (đặt `trust_level>=1`), gán vào một `screen` và `venue`. | Tài liệu nêu player thang máy được vận hành viên cấp qua admin nhưng chưa thấy màn hình và API làm việc đó. Màn hình chờ của app hiện mã thiết bị để vận hành viên đối chiếu. |
| 2 | **Lệnh `reset_pin`** (admin enqueue, có `idempotency_key`, TTL ngắn): app xóa mật khẩu đã đặt, về `1234` và bắt buộc đổi lại ở lần vào cài đặt kế tiếp. Thêm nút **Cấp lại mật khẩu** ở ngăn chi tiết thiết bị, ghi audit log. | Yêu cầu "cấp lại mật khẩu trên ams.anio.vn". App đã xử lý lệnh này; AMS cần tạo lệnh. |
| 3 | **Tính `proof_of_play` của thang máy là hợp lệ** kể cả khi `trust_level=0` hoặc `1`, hoặc nêu rõ quy tắc. Hiện tài liệu chỉ nói thiết bị `trust_level=0` bị đánh `valid=0` và không định nghĩa hiệu lực của `proof_of_play`. | Báo cáo phát cho nhãn hàng thang máy dựa vào sự kiện này. |
| 4 | **Manifest theo độ phân giải màn hình:** cho phép `register` (hoặc `heartbeat`) nhận `px_w`, `px_h`, `orientation`, `region` để chọn nhóm `elevator_<w>x<h>` tự động, thay vì chờ vận hành viên gắn thiết bị vào màn hình. App gửi sẵn `region` nếu đã cấu hình. | Thang máy chỉ nhận bản creative đúng độ phân giải. |

### P1: cần cho đủ tính năng trong tài liệu mô tả app

| # | Yêu cầu | Chi tiết đề xuất |
|---|---|---|
| 5 | **Chữ header/footer** trong manifest | Thêm `header`, `footer` (chuỗi, rỗng thì ẩn) ở cấp manifest và cấp `schedule[]`. App đã đọc sẵn các trường này (cấp slot ghi đè cấp manifest). |
| 6 | **Hotline/Zalo** hiện ở màn hình logo | Thêm `hotline` vào phản hồi `heartbeat` (hoặc manifest). App đã đọc sẵn và lưu để hiện cả khi mất mạng. |
| 7 | **Thời lượng ảnh và kiểu hiển thị** | `creatives[].duration_s` luôn có giá trị với ảnh (app mặc định 10 giây nếu thiếu); thêm `fit` = `fit` hoặc `cover` (mặc định `cover`). |
| 8 | **Quy tắc xoay vòng** | Chốt cách phát theo `priority` và tần suất (hiện là "gợi ý chưa thuộc giao thức"). App đang phát tuần tự theo `priority` tăng dần, lặp vô hạn. |
| 9 | **Lệnh bổ sung** | `set_volume` (`percent` 0-100), `set_orientation` (`landscape`/`portrait`), `message` (chữ chạy tạm thời). App bỏ qua lệnh lạ và vẫn ack. **Đã hỗ trợ từ app 1.0.1**: `message` hiện dải đỏ giữa màn hình (chữ dài > 40 ký tự thì chạy ngang, ngắn thì hiện tĩnh), tự tắt sau `duration_s` (mặc định 15 giây), tin mới thay tin cũ; tham số sai thì ack `failed`. |
| 10 | **Cập nhật app từ xa** | `heartbeat` trả `min_app_version` (đã có) kèm `app_update_url` và `app_update_sha256` để box không có kho ứng dụng tự cập nhật. |

### P2: vận hành

| # | Yêu cầu | Chi tiết đề xuất |
|---|---|---|
| 11 | **Nhật ký thiết bị** | Thân heartbeat tối đa 2 KB không đủ cho log. Cần `POST /v1/logs` (tối đa 8 KB, giới hạn tần suất) để app gửi 7 ngày nhật ký (lỗi phát, đổi nguồn, mất mạng, rút USB). |
| 12 | **Hiển thị trạng thái trên admin** | Cột nguồn đang phát (ams/USB/LAN), phiên bản app, dung lượng cache, quyền thiết bị. |
| 13 | **Phát đồng bộ nhiều màn** | Chưa cần ở bản này; nếu cần, đề xuất `sync_group` và mốc thời gian bắt đầu chung trong manifest. |

## 3. Câu hỏi đã được xác nhận (2026-11-21)

- Gateway production là `https://gw-ams.anio.vn`: **đúng**. App dùng làm mặc định (`Prefs.gwBase`).
- Đăng ký lại với cùng khóa công khai: **luôn nhận lại cùng `device_id`**. App dựa vào điều này khi xóa dữ liệu hoặc khi gặp `unknown_device`.
- Lệnh giao lại do ack chậm: **AMS đảm bảo `id` lệnh không đổi** (bắt buộc). App bỏ qua id đã xử lý để `reset_pin`/`message` không chạy hai lần; danh sách 100 id gần nhất được lưu bền (SharedPreferences) nên vẫn đúng sau khi app khởi động lại.

## 4. Cập nhật 1.1.0 (2026-11-22): app hiển thị theo bố cục web
- App đọc `layout.zones` từ `/v1/manifest` (tương thích cũ nếu thiếu), dựng bố cục theo %, xoay theo `orientation`.
- AMS không cần đổi: app tự dựng URL VietQR nếu thiếu `url`, dùng toạ độ `weather` do web gửi (không cần quyền vị trí).
- Log gỡ lỗi gửi `/v1/logs` (≤8KB/≤100 dòng, 60 lần/giờ) chỉ khi bật chế độ gỡ lỗi trên máy.
- Chưa kiểm trên thiết bị thật; CI chạy unit test với manifest thật.


## Ký APK & danh tính thiết bị (1.1.1)
- Khóa ký cố định: `app/anio-signage.jks` (RSA 2048, hiệu lực 100 năm). SHA-256 chứng chỉ: `05:67:D3:DD:64:C2:00:D2:16:D7:75:C0:83:F4:16:01:04:28:AB:12:0F:64:0B:50:09:7B:5A:98:0E:F2:93:62` (xác minh APK bằng `apksigner verify --print-certs`).
- Gỡ-một-lần: máy đang cài 1.1.0 trở xuống (khóa debug ngẫu nhiên) phải gỡ rồi cài 1.1.1; các bản sau cài đè thẳng.
- Mã thiết bị: seed Ed25519 = HMAC-SHA256(ANDROID_ID, applicationId); AMS đăng ký idempotent theo pubkey nên `device_id` không đổi khi cài lại cùng chữ ký.
- Yêu cầu phía AMS: URL media trong manifest phải tải được (gateway mới tự phục vụ `GET /media/*` khi không cấu hình URL công khai); thông báo khẩn chỉ gửi tới thiết bị đã duyệt (quyền >= 1).
