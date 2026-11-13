package com.sauter.touchpanel

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sauter_touchpanel_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_GRAPHIC_URL = "graphic_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_REFRESH_INTERVAL = "refresh_interval"

        // 1. Link trang chủ / Đăng nhập của Sauter Vision Center
        const val DEFAULT_SERVER_URL = "https://demo.sauter.com.vn/VisionCenter"

        // 2. Link ĐỒ HỌA BMS (GRAPHIC): Điền link sơ đồ BMS bạn muốn vào thẳng sau khi login ở đây
        const val DEFAULT_GRAPHIC_URL = ""

        // 3. Tài khoản và Mật khẩu tự động đăng nhập
        const val DEFAULT_USERNAME = "Administrator"
        const val DEFAULT_PASSWORD = ""

        // 4. Chu kỳ tự động làm mới phiên làm việc (phút) - Mặc định 15 phút
        const val DEFAULT_REFRESH_INTERVAL = 15
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim()).apply()

    var graphicUrl: String
        get() = prefs.getString(KEY_GRAPHIC_URL, DEFAULT_GRAPHIC_URL) ?: DEFAULT_GRAPHIC_URL
        set(value) = prefs.edit().putString(KEY_GRAPHIC_URL, value.trim()).apply()

    var username: String
        get() = prefs.getString(KEY_USERNAME, DEFAULT_USERNAME) ?: DEFAULT_USERNAME
        set(value) = prefs.edit().putString(KEY_USERNAME, value.trim()).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
        set(value) = prefs.edit().putString(KEY_PASSWORD, value.trim()).apply()

    var refreshIntervalMinutes: Int
        get() = prefs.getInt(KEY_REFRESH_INTERVAL, DEFAULT_REFRESH_INTERVAL)
        set(value) = prefs.edit().putInt(KEY_REFRESH_INTERVAL, value).apply()
}
