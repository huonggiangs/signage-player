# Nghiệm thu D Play 1.1.5 (làm tay trên thiết bị thật)

Chưa chạy được trên màn thang máy V2419A và AMS thật từ môi trường build; các bước dưới là kiểm tra bắt buộc.

1. Cài đè: `adb install -r signage-player-debug.apk` (Release `debug-latest`). Mã thiết bị không đổi; app vẫn "Đã kết nối".
2. Cài đặt > bật **Chế độ gỡ lỗi**.
3. Trên AMS tạo chương trình phát sóng có vùng playlist: 1 video + 1 ảnh + 1 chữ + (tuỳ chọn) 1 mục link hỏng; gửi tới màn V2419A.
4. Quan sát: các mục chạy tuần tự rồi lặp; mục hỏng bị bỏ qua (dòng gỡ lỗi hiện lỗi, `/v1/logs` có dòng cảnh báo); ngắt hết mạng/xóa tệp thì hiện "Đang chờ nội dung…" 8 giây rồi thử lại.
5. Chờ 5 phút (hoặc đủ 50 lượt), mở AMS `/history` (báo cáo lượt xem theo mục): thấy số lượt từng mục, `device` đúng.
6. Thử lệch giờ: chỉnh đồng hồ máy lệch > 5 phút, để phát 10 phút, chỉnh lại. Lượt xem vẫn lên đủ (1.1.4 mất cả lô).
7. Tắt mạng 10 phút rồi bật lại: số lượt tăng bù, không trùng (khử trùng theo `log_id`).
8. Khởi động lại thiết bị khi còn bản ghi chưa gửi: sau khi lên mạng vẫn gửi đủ.
