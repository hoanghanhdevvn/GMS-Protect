# GMS Protect Full v1.0.4

**VI** | [English](#english)

## Tiếng Việt

**GMS Protect** hỗ trợ duy trì Google Play Services (GMS) và khả năng nhận thông báo FCM trên một số bản Xiaomi HyperOS / MIUI, đặc biệt ROM China. Ứng dụng khai thác các cơ chế bảo vệ sẵn có của hệ thống, không đòi hỏi root hay chạy dịch vụ GAME_WATCH liên tục.

### Điểm mới trong v1.0.4

- **Bảo vệ nền nâng cao (tùy chọn):** thêm và duy trì cả `com.google.android.gms` lẫn `com.hanh.gmsprotect` trong `Settings.System["com.miui.game.allowlist"]` trên máy hỗ trợ.
- **Tự phục hồi theo sự kiện:** `JobScheduler.TriggerContentUri` theo dõi thay đổi danh sách; khi job được thực thi và phát hiện thiếu mục, ứng dụng bổ sung mà không ghi đè các package còn lại.
- **Đánh thức khi app không mở:** hỗ trợ JobService được hệ thống gọi lại sau khi tiến trình trước đã kết thúc (phụ thuộc hạn chế nền/Doze của từng ROM).
- **Kiểm tra tương thích tự động:** có thể bật bảo vệ ngay, các mục PASS được tích lũy từ trạng thái và callback thực tế. PASS không phải cam kết không bao giờ bị freeze.
- **Giao diện gọn, Việt/Anh:** card trạng thái và Bảo vệ nền nâng cao được tối ưu, phần chi tiết/log nằm sau nút mở rộng.
- Giữ các cơ chế đã có: **MILLET_NO_RESTRICT_APP**, **MILLET_WATCH**, **MiPush/Greezer**, kiểm tra và yêu cầu FCM reconnect.

### Sử dụng

1. Cài APK từ **GitHub Releases** và cấp quyền **Sửa đổi cài đặt hệ thống** khi app hướng dẫn.
2. Hoàn thành hướng dẫn pin/Tự khởi động phù hợp với ROM của máy.
3. Bật **Bảo vệ nền nâng cao** nếu tương thích; tính năng này mặc định tắt.
4. Xem **Kiểm tra tương thích** để theo dõi bằng chứng tự xác minh trong quá trình sử dụng.

### Giới hạn và lưu ý

- `targetSdk=22`, `minSdk=22`, `compileSdk=35`, `versionCode=8`, `versionName=1.0.4`.
- Hiệu quả phụ thuộc ROM: ghi được whitelist **không đồng nghĩa** Greezer luôn miễn đóng băng; Doze vẫn có thể trì hoãn JobScheduler và mạng/FCM vẫn có thể mất kết nối vì nguyên nhân riêng.
- Trên một số ROM mới, Android có thể hạn chế cài ứng dụng có target SDK cũ. **Không khuyến khích hạ bảo mật thiết bị** để vượt giới hạn.
- Dự án không sửa file hệ thống, không bảo đảm 100% thông báo và không can thiệp root.

### Mã nguồn & APK

Kho mã nguồn công khai giữ cấu trúc như bản Lite: `README.md` và `src/main/`. Đây là **source code của ứng dụng**; để build, đặt `src/main/` vào `app/src/main/` của một project Android Studio cấu hình tương thích (`applicationId=com.hanh.gmsprotect`, JDK 17, Gradle 8.10.2). **APK phải lấy ở Releases**, không lưu trong nhánh source. Không đưa file ký, mật khẩu, token lên kho.

---

## English

**GMS Protect** helps Google Play services remain eligible to run in the background on compatible Xiaomi HyperOS / MIUI firmware, particularly China ROMs. No root or permanently running GAME_WATCH service is required.

### What's new in v1.0.4

- **Enhanced Background Protection (opt-in):** maintains `com.google.android.gms` and `com.hanh.gmsprotect` in the Xiaomi Game Allowlist where supported.
- **Event-driven recovery:** `JobScheduler.TriggerContentUri` observes list changes and merges missing entries without erasing other packages.
- **Process restart support:** a system-dispatched JobService can run after the prior app process has exited, subject to ROM/Doze restrictions.
- **Passive compatibility evidence:** protection can be enabled immediately; PASS indicators are earned through actual state and system callbacks.
- **Compact bilingual UI:** cleaner status/protection cards, optional details and activity logs.
- Retains **MILLET_NO_RESTRICT_APP**, **MILLET_WATCH**, **MiPush/Greezer**, and FCM connectivity tools.

### Getting started

1. Install the APK from **GitHub Releases**, and grant **Modify system settings** as requested.
2. Follow the ROM-specific battery/autostart setup instructions.
3. Optionally enable **Enhanced Background Protection**.
4. Check the Compatibility screen as real-world evidence accumulates.

### Limitations

- `targetSdk=22`, `minSdk=22`, `compileSdk=35`, `versionCode=8`, `versionName=1.0.4`.
- Allowlist write success does **not** prove that every freeze path is blocked. Doze may delay jobs, and FCM connectivity is governed by independent factors. No 100% notification guarantee.
- Some recent Android versions restrict installation of apps targeting old SDK levels. Do not weaken device security to bypass installation protections.

### Sources and releases

This repository follows the Lite layout: `README.md` and `src/main/`. To build, integrate `src/main/` into a compatible Android Studio app project using package ID `com.hanh.gmsprotect`, JDK 17 and Gradle 8.10.2. Obtain installable APKs from **GitHub Releases**. Never upload signing keys, credentials or tokens.
