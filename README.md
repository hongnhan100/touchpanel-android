# Sauter Touchpanel Kiosk - Android 12

Ứng dụng Kiosk Touchpanel chuyên dụng thay thế phần mềm Sauter Touchpanel Windows, chạy tối ưu trên các thiết bị màn hình cảm ứng Android 11/12/13/14.

---

## 1. Tính năng nổi bật

* **Tự động Đăng nhập (Auto-Login)**: Tự động điền tài khoản người dùng và kích hoạt đăng nhập ngầm khi gặp trang `Account/Login`.
* **Vào thẳng trang Đồ họa BMS (Direct Graphic Navigation)**: Sau khi đăng nhập thành công, ứng dụng tự động điều hướng trực tiếp vào link sơ đồ BMS / Graphic Page đã cấu hình.
* **Màn hình chờ Splash chuyên nghiệp**: Che giấu toàn bộ quá trình submit đăng nhập, chỉ hiển thị giao diện khi hệ thống đã vào thẳng màn hình vận hành.
* **Kiosk Immersive Toàn màn hình**: Ẩn triệt để thanh Status Bar và Navigation Bar, chống thoát ứng dụng.
* **Giữ sáng 24/7**: Cấu hình `FLAG_KEEP_SCREEN_ON` giúp màn hình không bao giờ bị tắt khi vận hành.
* **Tự động khôi phục kết nối (Auto Reconnect)**: Tự động thử lại mỗi 10 giây khi mất mạng hoặc server bảo trì.
* **Hộp thoại Cài đặt Ẩn (Hidden Admin Menu)**: Nhấn giữ 5 giây vào **góc trên bên phải màn hình** để mở menu đổi URL, Graphic URL, Username, Password.

---

## 2. Cấu trúc Dự án

```
android-touchpanel/
├── app/
│   ├── src/main/
│   │   ├── java/com/sauter/touchpanel/
│   │   │   ├── MainActivity.kt        # Logic Kiosk, Auto-Login, Redirect Graphic, Reconnect
│   │   │   └── AppPreferences.kt      # Quản lý lưu trữ URL, tài khoản an toàn
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   ├── activity_main.xml  # Layout chính (WebView, Splash Overlay, Error Screen)
│   │   │   │   └── dialog_settings.xml# Hộp thoại cấu hình URL và link BMS Graphic
│   │   │   └── values/
│   │   │       ├── colors.xml         # Màu sắc thương hiệu Sauter Blue
│   │   │       ├── strings.xml
│   │   │       └── themes.xml         # Theme Fullscreen Kiosk
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
└── settings.gradle
```

---

## 3. Cách mở và Build trong Android Studio

1. Mở **Android Studio** -> Chọn **Open Project** -> Trỏ tới thư mục:
   `d:\1-Learn\1-AI\touchPanel\android-touchpanel`
2. Chờ Gradle đồng bộ (Sync) xong.
3. Cắm thiết bị Android 12 (hoặc mở máy ảo Android) và bấm nút **Run (Shift + F10)** để cài đặt APK lên máy.

---

## 4. Cách cấu hình Thiết bị Kiosk Dedicated (Tùy chọn)

Để biến tablet Android 12 thành thiết bị Kiosk chuyên dụng (ngăn người dùng tắt app hoặc về Home):
```bash
# Đặt app làm ứng dụng mặc định
adb shell dpm set-device-owner com.sauter.touchpanel/.AdminReceiver
```
