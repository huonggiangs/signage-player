# Signage Player (Android 9+)

Ứng dụng phát video/ảnh quảng cáo màn hình dọc 9:16 cho Android TV box thang máy, nối với AMS
(`ams.anio.vn`, gateway `gw-ams.anio.vn`). Mô tả chức năng: `Tai lieu mo ta - Signage Player Android.md`.

## Build APK
Đẩy lên nhánh `main`: workflow `.github/workflows/build-apk.yml` build và đăng
`signage-player-debug.apk` vào Release **debug-latest** (và Artifacts).
Cài thử: `adb install -r signage-player-debug.apk`

## Chế độ HOME (kiosk)
- App khai báo `CATEGORY_HOME`. Lần đầu bấm nút Home, chọn Signage Player và **Luôn dùng**; từ đó box khởi động và nút Home đều vào app.
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
