# BoxStation 📡

**BoxStation** là ứng dụng Android chuyên dụng biến Android TV & FPT Play Box thành **Mini NAS chia sẻ ổ cứng USB** và **Cổng cài đặt APK / Khởi động lại Box từ xa** thông qua giao diện Web hiện đại, không cần phụ thuộc vào ADB hay cáp kết nối.

---

## 🌟 Tính Năng Nổi Bật

1. **Mini NAS Chia Sẻ Ổ Cứng USB (Cổng 8888):**
   - Tự động nhận diện mọi ổ cứng cắm qua cổng USB (`/storage/XXXX-XXXX`) và bộ nhớ trong (`/sdcard`).
   - Giao diện Web Dark Mode tối ưu cho cả điện thoại và máy tính: duyệt file, tạo thư mục, tải file lên/xuống, xóa file.
   - **HTTP 206 Partial Content (Range Streaming):** Hỗ trợ tua / seek video 4K/1080p và âm thanh mượt mà trên trình duyệt, Smart TV, VLC, Kodi, PotPlayer, Infuse.

2. **Cài Đặt File APK Từ Xa (`/install`):**
   - Kéo thả hoặc chọn file `.apk` từ trình duyệt điện thoại/laptop để cài trực tiếp lên TV.
   - Hỗ trợ cài đặt ngầm qua shell (`pm install`) hoặc tự động kích hoạt hộp thoại cài đặt hệ thống trên màn hình TV thông qua `FileProvider`.

3. **Khởi Động Lại Box Từ Xa (`/api/reboot`):**
   - Gửi lệnh Reboot Box chỉ bằng 1 nút bấm trên giao diện Web khi Box bị treo hoặc cần khởi động lại.

4. **Dịch Vụ Chạy Ngầm 24/7:**
   - Hoạt động ổn định với Foreground Service (`FOREGROUND_SERVICE_DATA_SYNC`), `WAKE_LOCK` và tự khởi động cùng hệ thống (`RECEIVE_BOOT_COMPLETED`).

---

## 📱 Cài Đặt & Sử Dụng

1. Tải file APK mới nhất: [boxStation-v1.0.0.apk](https://github.com/hongson117/boxStation/releases/latest)
2. Mở ứng dụng trên TV, cấp quyền **Truy cập mọi tệp (Manage External Storage)** khi được nhắc.
3. Mở trình duyệt trên điện thoại hoặc máy tính truy cập:
   - **Mạng nội bộ:** `http://<IP-BOX>:8888`
   - **Từ xa (Tailscale):** `http://<IP-TAILSCALE>:8888`
