# GMS Protect

**Hỗ trợ ổn định thông báo GMS/FCM trên Xiaomi HyperOS ROM nội địa.**

📥 **[Tải GMS Protect tại GitHub Releases](https://github.com/hoanghanhdevvn/GMS-Protect/releases/latest)**

## Tính năng

- **MILLET Protection:** Tự thêm Google Play services, Play Store  `MILLET_NO_RESTRICT_APP` mà không ảnh hưởng ứng dụng khác.
- **Tự khôi phục:** Theo dõi thay đổi danh sách MILLET bằng JobScheduler và kiểm tra lại khi cần.
- **Tự chạy sau khởi động:** Thiết lập bảo vệ sau khi điện thoại khởi động lại mà không cần mở app thủ công (khi AutoStart được cho phép).
- **Greezer / MiPush Protection:** Tích hợp cơ chế gửi yêu cầu bảo vệ thông qua HyperOS.
- **FCM:** Kiểm tra kết nối và hỗ trợ khôi phục khi phát hiện mất kết nối.
- **Tiết kiệm pin:** Không cần dịch vụ chạy nền thường trực ở chế độ mặc định.
- **Không Root, không cần ADB để vận hành.** Hỗ trợ tiếng Việt và tiếng Anh.

## Hướng dẫn sử dụng

1. Tải APK trong mục **Releases** và cài đặt.
2. Mở GMS Protect, cấp quyền **AutoStart** và **Modify System Settings**.
3. Chọn **Mở cài đặt pin** và cho phép miễn tối ưu pin theo hướng dẫn.
4. Kiểm tra trạng thái trong mục **Xác minh tương thích**.

Sau khi thiết lập, ứng dụng sẽ tự kiểm tra và duy trì cấu hình MILLET theo các sự kiện được hệ thống cho phép.

## Cài đặt bằng ADB (nếu cần)

Trên một số phiên bản Android mới, APK có thể bị chặn cài trực tiếp do `targetSdkVersion 22`. Khi gặp trường hợp này, kết nối điện thoại với máy tính, bật **USB debugging** và chạy:

```bat
adb install --bypass-low-target-sdk-block GMS-Protect-v1.0.3.apk
```

ADB chỉ hỗ trợ **bước cài đặt nếu cần**, không phải yêu cầu để GMS Protect hoạt động sau khi cài. Tùy Android/ROM, lệnh có thể không vượt qua được mọi hạn chế cài đặt.

## Lưu ý

- Tính tương thích và hiệu quả của Greezer/MiPush phụ thuộc từng phiên bản HyperOS.
- Chế độ Aurogon nâng cao **tắt mặc định**; khi bật, ứng dụng sẽ dùng foreground service và lịch đánh thức định kỳ.

---

**Developer:** [hoanghanhdevvn](https://github.com/hoanghanhdevvn)  
**GMS Protect Lite:** [GitHub](https://github.com/hoanghanhdevvn/GMS-Protect-Lite)
