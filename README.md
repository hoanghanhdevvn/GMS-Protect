# GMS Protect v1.0.5

**Tiếng Việt** | [English](#english)

> ### 📥 Tải GMS Protect
> **[⬇️ Tải APK v1.0.5]([https://github.com/hoanghanhdevvn/GMS-Protect/releases/download/v1.0.5/GMS_Protect_v1.0.5.apk](https://github.com/hoanghanhdevvn/GMS-Protect/releases/download/v1.0.5/GMS-Protect-v1.0.5.apk))** · [Xem bản phát hành v1.0.5](https://github.com/hoanghanhdevvn/GMS-Protect/releases/tag/v1.0.5) · [Tất cả phiên bản](https://github.com/hoanghanhdevvn/GMS-Protect/releases)
>
> APK nằm trong **Assets** của GitHub Releases, không nằm trong thư mục mã nguồn. **Link tải trực tiếp yêu cầu APK được đăng đúng tên `GMS_Protect_v1.0.5.apk`.**

## Giới thiệu

**GMS Protect** giúp Google Play services hoạt động ổn định hơn trong nền, hỗ trợ cải thiện khả năng nhận thông báo FCM trên **Xiaomi HyperOS / MIUI**, đặc biệt là ROM nội địa Trung Quốc.

Ứng dụng tận dụng các cơ chế bảo vệ của Xiaomi, **không cần root**. Hai bộ theo dõi danh sách sử dụng sự kiện hệ thống thay vì kiểm tra liên tục bằng vòng lặp.

## Điểm mới ở v1.0.5

- **Bảo vệ từng ứng dụng:** thêm ứng dụng cần bảo vệ, bật/tắt riêng và xem trạng thái từng mục. Google Play services luôn là mục bảo vệ đặc biệt.
- **Cải tiến màn Thêm ứng dụng:** hướng dẫn quyền **Access list of installed apps** trên HyperOS; xử lý trường hợp hệ thống chỉ trả về danh sách rất ít ứng dụng.
- **Giao diện mới, Việt/Anh:** màn thiết lập 3 quyền trực quan, icon thống nhất, trạng thái rõ ràng, các mục Cài đặt và chi tiết bảo vệ gọn hơn.
- **Nhật ký sự kiện:** ghi lại các thao tác và kết quả quan trọng để dễ theo dõi, kiểm tra lỗi.
- **Kiểm tra tương thích:** phân biệt trạng thái đã xác minh, đang chờ và lỗi; chỉ đánh dấu thành công khi có bằng chứng phù hợp.

## GMS Protect hoạt động như thế nào?

| Cơ chế | Chức năng |
| --- | --- |
| **MILLET Protection** | Giữ GMS, CH Play và GMS Protect trong danh sách **Không hạn chế** của Xiaomi, không xóa các mục có sẵn. |
| **MiPush / Greezer** | Gửi yêu cầu làm mới bảo vệ cho GMS và ứng dụng được chọn; việc gửi thành công **không đồng nghĩa** Xiaomi đã xác nhận áp dụng. |
| **Game Allowlist** | Trên ROM hỗ trợ, duy trì GMS, GMS Protect và ứng dụng đã bật trong danh sách bảo vệ bổ sung. |
| **Tự phục hồi theo sự kiện** | Dùng JobScheduler để theo dõi thay đổi danh sách và hỗ trợ thiết lập lại sau khi khởi động máy. Không dùng dịch vụ foreground thường trú chỉ để theo dõi hai danh sách. |

## Cách sử dụng

1. **[Tải APK](https://github.com/hoanghanhdevvn/GMS-Protect/releases/tag/v1.0.5)** và cài đặt trên điện thoại.
2. Mở ứng dụng, làm theo hướng dẫn **Tự khởi động**, **Sửa đổi cài đặt hệ thống** và **Pin**.
3. Vào phần **Ứng dụng bảo vệ → Thêm ứng dụng** nếu muốn bảo vệ thêm ứng dụng khác. Cấp quyền **Access list of installed apps** nếu HyperOS yêu cầu.
4. Bật các tùy chọn bảo vệ phù hợp và xem **Kiểm tra tương thích / Nhật ký sự kiện** để theo dõi.

**Lưu ý:** Các ứng dụng được thêm thủ công có thể vẫn cần tự đặt **Pin → Không hạn chế** trong cài đặt Xiaomi; GMS Protect không tự đưa tất cả ứng dụng này vào danh sách MILLET.

## Giới hạn & mã nguồn

- Hiệu quả phụ thuộc phiên bản ROM, cơ chế Doze và chính sách chạy nền. **Không cam kết thông báo hoạt động 100%** hoặc không bao giờ bị đóng băng.
- Một số phiên bản Android mới có thể hạn chế cài APK dùng target SDK cũ; không nên hạ mức bảo mật thiết bị để cài đặt.
- Source Java/Android trong repo dành cho người muốn kiểm tra hoặc tự build. **APK cài đặt lấy tại Releases**; không đưa keystore hay thông tin ký ứng dụng lên GitHub.
- Dự án độc lập, không liên kết chính thức với Google hoặc Xiaomi.

---

## English

> **📥 Download:** **[APK v1.0.5](https://github.com/hoanghanhdevvn/GMS-Protect/releases/download/v1.0.5/GMS_Protect_v1.0.5.apk)** · [v1.0.5 release](https://github.com/hoanghanhdevvn/GMS-Protect/releases/tag/v1.0.5) · [All releases](https://github.com/hoanghanhdevvn/GMS-Protect/releases)
>
> The direct APK link works after an asset named `GMS_Protect_v1.0.5.apk` is uploaded to the release. Otherwise, open the release and download the APK under **Assets**.

**GMS Protect** helps improve Google Play services background availability and FCM notification reliability on compatible **Xiaomi HyperOS / MIUI** devices, especially China ROMs. **No root required.**

### What's new in v1.0.5

- **Per-app protection:** add apps, enable/disable their protection individually and inspect status; GMS remains a built-in protected entry.
- **Improved app picker:** guidance for Xiaomi's **Access list of installed apps** permission and handling of unexpectedly restricted package lists.
- **Refreshed bilingual UI:** a clearer three-permission setup, consistent icons, compact Settings and protection details.
- **Event journal and compatibility checks:** inspect activity, pending checks, confirmed results and errors.

### Protection mechanisms

- **MILLET:** keeps Google Play services, Play Store and GMS Protect in Xiaomi's no-restriction list while preserving existing entries.
- **MiPush / Greezer:** sends best-effort protection refresh requests; a successful send is **not** confirmation from Xiaomi.
- **Game Allowlist:** maintains GMS, GMS Protect and enabled apps on supported ROMs.
- **Event-driven recovery:** JobScheduler observes relevant list changes; boot events can restore setup. No permanent foreground service is used just to poll the two lists.

### Getting started

1. [Download and install the APK](https://github.com/hoanghanhdevvn/GMS-Protect/releases/tag/v1.0.5).
2. Follow the **Autostart**, **Modify system settings** and **Battery** setup guidance.
3. Add optional apps and grant **Access list of installed apps** if HyperOS prompts for it. Additional apps may still require manual **No restrictions** battery settings.
4. Review **Compatibility** and the **Event journal** when needed.

**Limitations:** Results vary by device and ROM; Doze and independent FCM factors can still delay notifications. **100% push delivery is not guaranteed.** Some modern Android versions restrict installation of older-targeted APKs. Source code is in this repository; installable APKs are in **Releases**. Never publish signing keys.

*Independent utility — not affiliated with Google or Xiaomi.*
