package com.sauter.touchpanel

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var errorLayout: LinearLayout
    private lateinit var errorDetailText: TextView
    private lateinit var btnRetry: Button
    private lateinit var btnQuickSettings: Button

    private lateinit var prefs: AppPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var isRedirectedToGraphic = false
    private var isLoginInjected = false
    private var isReLoggingIn = false
    private var isNetworkAvailable = false

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Bộ hẹn giờ định kỳ làm mới phiên làm việc (mặc định 15 phút)
    private val periodicRefreshRunnable = object : Runnable {
        override fun run() {
            triggerSilentReLogin("Đến chu kỳ làm mới phiên (Session Keep-Alive)")
            val intervalMs = (prefs.refreshIntervalMinutes.coerceAtLeast(5)) * 60 * 1000L
            handler.postDelayed(this, intervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences(this)

        // 1. Giữ màn hình luôn sáng 24/7 cho thiết bị Kiosk Touchpanel ONERugged P10R
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 2. Kích hoạt chế độ Immersive Toàn màn hình (Ẩn Status Bar & Navigation Bar)
        applyKioskImmersiveMode()

        // 3. Khóa phím Back theo chuẩn Android 12 để chống thoát Kiosk
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                }
                // Chặn thoát ứng dụng khi hết lịch sử web
            }
        })

        setContentView(R.layout.activity_main)

        initViews()
        setupWebView()

        // 4. Đăng ký giám sát mạng cổng RJ45 Ethernet / Wifi
        setupNetworkMonitoring()

        // 5. Khởi động bộ đếm giờ tự động làm mới phiên
        schedulePeriodicRefresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(periodicRefreshRunnable)
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyKioskImmersiveMode()
        }
    }

    private fun applyKioskImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    isNetworkAvailable = true
                    if (errorLayout.visibility == View.VISIBLE || loadingOverlay.visibility == View.VISIBLE) {
                        statusText.text = getString(R.string.connecting)
                        startLoading()
                    }
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    isNetworkAvailable = false
                }
            }
        }

        try {
            networkCallback?.let { connectivityManager?.registerNetworkCallback(request, it) }
        } catch (_: Exception) {}

        // Kiểm tra nhanh kết nối hiện tại khi vừa mở app
        val activeNet = connectivityManager?.activeNetwork
        val caps = connectivityManager?.getNetworkCapabilities(activeNet)
        isNetworkAvailable = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        if (isNetworkAvailable) {
            startLoading()
        } else {
            statusText.text = "Đang chờ kết nối cổng mạng RJ45 / BMS..."
            loadingOverlay.visibility = View.VISIBLE
            // Vẫn thử tải sau 3 giây đề phòng mạng Ethernet đang đàm phán IP DHCP
            handler.postDelayed({
                if (loadingOverlay.visibility == View.VISIBLE) {
                    startLoading()
                }
            }, 3000)
        }
    }

    private fun schedulePeriodicRefresh() {
        handler.removeCallbacks(periodicRefreshRunnable)
        val intervalMs = (prefs.refreshIntervalMinutes.coerceAtLeast(5)) * 60 * 1000L
        handler.postDelayed(periodicRefreshRunnable, intervalMs)
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        statusText = findViewById(R.id.statusText)
        errorLayout = findViewById(R.id.errorLayout)
        errorDetailText = findViewById(R.id.errorDetailText)
        btnRetry = findViewById(R.id.btnRetry)
        btnQuickSettings = findViewById(R.id.btnQuickSettings)

        btnRetry.setOnClickListener {
            errorLayout.visibility = View.GONE
            loadingOverlay.visibility = View.VISIBLE
            statusText.text = getString(R.string.connecting)
            startLoading()
        }

        // Bấm nút bánh răng ⚙ góc trên bên phải: yêu cầu nhập mã PIN Kỹ thuật viên
        btnQuickSettings.setOnClickListener {
            promptAdminPinAndOpenSettings()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.setSupportZoom(false)
        settings.mediaPlaybackRequiresUserGesture = false

        // User-Agent chuẩn desktop để hệ thống Vision Center hiển thị đầy đủ tính năng đồ họa
        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 SauterTouchpanel/Android"

        // Cấu hình Cookie bền vững
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // Hỗ trợ HTML5 Fullscreen API cho WebChromeClient
        WebView.setWebContentsDebuggingEnabled(true)
        webView.webChromeClient = object : WebChromeClient() {
            private var customView: View? = null
            private var customViewCallback: CustomViewCallback? = null

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                super.onShowCustomView(view, callback)
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                (window.decorView as android.widget.FrameLayout).addView(
                    view,
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                applyKioskImmersiveMode()
            }

            override fun onHideCustomView() {
                super.onHideCustomView()
                if (customView == null) return
                (window.decorView as android.widget.FrameLayout).removeView(customView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                applyKioskImmersiveMode()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url?.contains("Account/Login", ignoreCase = true) == true) {
                    isLoginInjected = false
                    statusText.text = getString(R.string.authenticating)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                cookieManager.flush()

                if (url == null) return

                // TRƯỜNG HỢP 1: Đang ở trang Đăng nhập -> Tự động điền & Submit (Hiển thị trực tiếp cho người dùng thấy)
                if (url.contains("Account/Login", ignoreCase = true) || url.contains("login", ignoreCase = true)) {
                    loadingOverlay.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    injectAutoLoginScript()
                }
                // TRƯỜNG HỢP 2: Đã vào trong hệ thống (Đăng nhập thành công)
                else {
                    isReLoggingIn = false

                    // Kiểm tra xem trang có bị lỗi "Unauthorized" hay không
                    checkAndRecoverFromUnauthorized()

                    val targetGraphicUrl = prefs.graphicUrl.trim()

                    // Nếu người dùng có cấu hình Link BMS Graphic và chưa chuyển hướng tới đó:
                    if (targetGraphicUrl.isNotEmpty() && !isRedirectedToGraphic) {
                        if (url != targetGraphicUrl) {
                            isRedirectedToGraphic = true
                            statusText.text = "Đang chuyển hướng vào sơ đồ đồ họa BMS..."
                            handler.postDelayed({
                                webView.loadUrl(targetGraphicUrl)
                            }, 500)
                            return
                        }
                    }

                    // Hiển thị giao diện chính, ẩn màn hình loading
                    loadingOverlay.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    errorLayout.visibility = View.GONE

                    // Tự động kích hoạt nút Fullscreen của khung đồ họa BMS (icon 4 mũi tên)
                    handler.postDelayed({
                        triggerBmsGraphicFullscreen()
                    }, 1200)
                }
            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                showNetworkError(description ?: "Không thể kết nối")
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                val statusCode = errorResponse?.statusCode ?: 200

                // BẮT LỖI 401 UNAUTHORIZED HOẶC 403 FORBIDDEN KHI HẾT PHIÊN TRÊN MÀN HÌNH CHÍNH
                if (statusCode == 401 || statusCode == 403) {
                    if (request?.isForMainFrame == true) {
                        triggerSilentReLogin("Phát hiện lỗi HTTP $statusCode Unauthorized")
                        return
                    }
                }

                if (request?.isForMainFrame == true && statusCode >= 400) {
                    if (statusCode == 404 && request.url.toString().contains("Account/Login")) {
                        showNetworkError("Lỗi đường dẫn máy chủ (HTTP 404)")
                    }
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                // Hỗ trợ trạm Sauter nội bộ dùng chứng chỉ tự ký (Self-signed)
                handler?.proceed()
            }

            // Hỗ trợ HTTP Basic Auth (nếu chạy với Sauter moduWeb Unity)
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                val user = prefs.username
                val pass = prefs.password
                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    handler?.proceed(user, pass)
                } else {
                    super.onReceivedHttpAuthRequest(view, handler, host, realm)
                }
            }
        }
    }

    private fun startLoading() {
        isRedirectedToGraphic = false
        isLoginInjected = false
        webView.visibility = View.VISIBLE
        loadingOverlay.visibility = View.GONE
        errorLayout.visibility = View.GONE

        val targetUrl = prefs.serverUrl.trim()
        webView.loadUrl(targetUrl)
    }

    /**
     * Tự động đăng nhập lại khi hết phiên (Session Timeout / Unauthorized)
     */
    private fun triggerSilentReLogin(reason: String) {
        if (isReLoggingIn) return
        isReLoggingIn = true

        handler.post {
            val loginUrl = prefs.serverUrl.trim()
            isLoginInjected = false
            isRedirectedToGraphic = false
            webView.loadUrl(loginUrl)
        }
    }

    /**
     * Kiểm tra nội dung trang có chứa chữ Unauthorized hay không
     */
    private fun checkAndRecoverFromUnauthorized() {
        val checkScript = """
            (function() {
                const bodyText = document.body ? (document.body.innerText || '') : '';
                if (bodyText.includes('Unauthorized') || bodyText.includes('401') || bodyText.includes('Access Denied')) {
                    return 'UNAUTHORIZED_FOUND';
                }
                return 'OK';
            })();
        """.trimIndent()

        webView.evaluateJavascript(checkScript) { result ->
            if (result != null && result.contains("UNAUTHORIZED_FOUND")) {
                triggerSilentReLogin("Nội dung trang chứa thông báo Unauthorized")
            }
        }
    }

    /**
     * Tự động điền tài khoản, mật khẩu và submit form đăng nhập React/Sauter
     */
    private fun injectAutoLoginScript() {
        if (isLoginInjected) return
        isLoginInjected = true

        val user = prefs.username.replace("'", "\\'")
        val pass = prefs.password.replace("'", "\\'")

        val script = """
            (function() {
                function dismissCookiePopup() {
                    const buttons = Array.from(document.querySelectorAll('button, a'));
                    for (let btn of buttons) {
                        const t = btn.innerText.toLowerCase();
                        if (t.includes('accept') || t.includes('đồng ý') || t.includes('ok') || t.includes('i agree')) {
                            btn.click();
                            break;
                        }
                    }
                }

                function setReactValue(input, value) {
                    if (!input) return;
                    input.focus();
                    const nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
                    nativeInputValueSetter.call(input, value);
                    input.dispatchEvent(new Event('input', { bubbles: true }));
                    input.dispatchEvent(new Event('change', { bubbles: true }));
                }

                function tryLogin() {
                    let uInput = document.getElementById('UserName') ||
                                 document.getElementById('username') ||
                                 document.querySelector('input[name="UserName"]') ||
                                 document.querySelector('input[name="username"]') ||
                                 document.querySelector('input[type="text"]');

                    let pInput = document.getElementById('Password') ||
                                 document.getElementById('password') ||
                                 document.querySelector('input[name="Password"]') ||
                                 document.querySelector('input[name="password"]') ||
                                 document.querySelector('input[type="password"]');

                    let btn = document.getElementById('btnLogin') ||
                              document.querySelector('button[type="submit"]') ||
                              document.querySelector('input[type="submit"]') ||
                              document.querySelector('button.btn-primary');

                    if (uInput && pInput) {
                        setReactValue(uInput, '$user');
                        setReactValue(pInput, '$pass');

                        // Tick chọn ô "Keep me logged in"
                        const keepMe = document.querySelector('input[type="checkbox"]');
                        if (keepMe && !keepMe.checked) {
                            keepMe.click();
                        }

                        // Click nút đăng nhập
                        setTimeout(function() {
                            if (btn) {
                                btn.click();
                            } else {
                                const form = uInput.closest('form');
                                if (form) form.submit();
                            }
                        }, 500);
                        return true;
                    }
                    return false;
                }

                dismissCookiePopup();
                setTimeout(dismissCookiePopup, 300);
                setTimeout(tryLogin, 600);
                setTimeout(tryLogin, 1200);
                setTimeout(tryLogin, 2500);
                setTimeout(tryLogin, 4000);
                setTimeout(tryLogin, 6000);
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    private fun showNetworkError(detail: String) {
        webView.visibility = View.INVISIBLE
        loadingOverlay.visibility = View.GONE
        errorLayout.visibility = View.VISIBLE
        errorDetailText.text = "Lỗi kết nối: $detail\nĐang tự động thử lại sau 10 giây..."

        handler.postDelayed({
            if (errorLayout.visibility == View.VISIBLE) {
                startLoading()
            }
        }, 10000)
    }

    /**
     * Hộp thoại yêu cầu mã PIN Kỹ thuật viên trước khi vào Cài đặt
     */
    private fun promptAdminPinAndOpenSettings() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Nhập mã PIN bảo mật"
            setPadding(40, 40, 40, 40)
        }

        AlertDialog.Builder(this)
            .setTitle("Xác thực Kỹ thuật viên")
            .setMessage("Vui lòng nhập mã PIN bảo mật để truy cập cấu hình:")
            .setView(input)
            .setPositiveButton("Xác nhận") { _, _ ->
                val enteredPin = input.text.toString().trim()
                if (enteredPin == prefs.adminPin) {
                    showAdminSettingsDialog()
                } else {
                    Toast.makeText(this, "Mã PIN không chính xác!", Toast.LENGTH_SHORT).show()
                    applyKioskImmersiveMode()
                }
            }
            .setNegativeButton("Hủy") { dialog, _ ->
                dialog.dismiss()
                applyKioskImmersiveMode()
            }
            .setOnDismissListener {
                applyKioskImmersiveMode()
            }
            .show()
    }

    /**
     * Hộp thoại cấu hình URL, Link đồ họa BMS, Username, Password, Chu kỳ Refresh và Cài đặt Android
     */
    private fun showAdminSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etServerUrl = dialogView.findViewById<EditText>(R.id.etServerUrl)
        val etGraphicUrl = dialogView.findViewById<EditText>(R.id.etGraphicUrl)
        val etUsername = dialogView.findViewById<EditText>(R.id.etUsername)
        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val etRefreshInterval = dialogView.findViewById<EditText>(R.id.etRefreshInterval)
        val etAdminPin = dialogView.findViewById<EditText>(R.id.etAdminPin)
        val btnOpenAndroidSettings = dialogView.findViewById<Button>(R.id.btnOpenAndroidSettings)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)

        etServerUrl.setText(prefs.serverUrl)
        etGraphicUrl.setText(prefs.graphicUrl)
        etUsername.setText(prefs.username)
        etPassword.setText(prefs.password)
        etRefreshInterval.setText(prefs.refreshIntervalMinutes.toString())
        etAdminPin.setText(prefs.adminPin)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        // Nút mở trực tiếp Cài đặt Android để kỹ thuật viên cấu hình IP LAN RJ45 hoặc Wifi trên P10R
        btnOpenAndroidSettings.setOnClickListener {
            dialog.dismiss()
            openAndroidSystemSettings()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
            applyKioskImmersiveMode()
        }

        btnSave.setOnClickListener {
            val newServer = etServerUrl.text.toString().trim()
            val newGraphic = etGraphicUrl.text.toString().trim()
            val newUser = etUsername.text.toString().trim()
            val newPass = etPassword.text.toString().trim()
            val newInterval = etRefreshInterval.text.toString().trim().toIntOrNull() ?: 15
            val newPin = etAdminPin.text.toString().trim()

            if (newServer.isNotEmpty()) prefs.serverUrl = newServer
            prefs.graphicUrl = newGraphic
            if (newUser.isNotEmpty()) prefs.username = newUser
            if (newPass.isNotEmpty()) prefs.password = newPass
            prefs.refreshIntervalMinutes = newInterval
            if (newPin.isNotEmpty()) prefs.adminPin = newPin

            dialog.dismiss()
            applyKioskImmersiveMode()

            // Cập nhật lại lịch đếm giờ refresh
            schedulePeriodicRefresh()

            // Tải lại trang với cấu hình mới
            startLoading()
        }

        dialog.show()
    }

    /**
     * Mở Cài đặt Hệ thống Android để kỹ thuật viên cấu hình IP tĩnh mạng LAN RJ45 hoặc Wifi
     */
    private fun openAndroidSystemSettings() {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val wirelessIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(wirelessIntent)
            } catch (e2: Exception) {
                Toast.makeText(this, "Không thể mở Cài đặt Android", Toast.LENGTH_SHORT).show()
                applyKioskImmersiveMode()
            }
        }
    }

    /**
     * Tự động phát hiện và kích hoạt chế độ Fullscreen đồ họa BMS bằng MutationObserver
     */
    private fun triggerBmsGraphicFullscreen() {
        val fsScript = """
            (function() {
                function tryClickFullscreen() {
                    const icon = document.querySelector('.glyphicon-fullscreen') ||
                                 document.querySelector('[class*="glyphicon-fullscreen"]') ||
                                 document.querySelector('.fa-expand') ||
                                 document.querySelector('.icon-fullscreen') ||
                                 document.querySelector('[title*="Full" i]') ||
                                 document.querySelector('[aria-label*="Full" i]');

                    if (icon) {
                        const btn = icon.closest('a, button, span, div') || icon;
                        btn.click();
                        return true;
                    }

                    const iframes = document.querySelectorAll('iframe');
                    for (let i = 0; i < iframes.length; i++) {
                        try {
                            const innerDoc = iframes[i].contentDocument || iframes[i].contentWindow.document;
                            const innerIcon = innerDoc.querySelector('.glyphicon-fullscreen') ||
                                              innerDoc.querySelector('.fa-expand') ||
                                              innerDoc.querySelector('[title*="Full" i]');
                            if (innerIcon) {
                                const innerBtn = innerIcon.closest('a, button, span, div') || innerIcon;
                                innerBtn.click();
                                return true;
                            }
                        } catch(e) {}
                    }
                    return false;
                }

                if (!tryClickFullscreen()) {
                    const observer = new MutationObserver(function(mutations, obs) {
                        if (tryClickFullscreen()) {
                            obs.disconnect();
                        }
                    });

                    observer.observe(document.body || document.documentElement, {
                        childList: true,
                        subtree: true
                    });

                    let attempts = 0;
                    const timer = setInterval(function() {
                        attempts++;
                        if (tryClickFullscreen() || attempts > 20) {
                            clearInterval(timer);
                            observer.disconnect();
                        }
                    }, 500);
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(fsScript, null)
    }
}
