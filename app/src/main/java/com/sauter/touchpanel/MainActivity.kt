package com.sauter.touchpanel

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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

        // 1. Giữ màn hình luôn sáng 24/7 cho thiết bị Kiosk Touchpanel
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 2. Kích hoạt chế độ Immersive Toàn màn hình (Ẩn Status Bar & Navigation Bar)
        applyKioskImmersiveMode()

        setContentView(R.layout.activity_main)

        initViews()
        setupWebView()

        // Bắt đầu nạp trang web Vision Center
        startLoading()

        // Khởi động bộ đếm giờ tự động làm mới phiên
        schedulePeriodicRefresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(periodicRefreshRunnable)
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

        // Bấm nút bánh răng ⚙ góc trên bên phải để mở cài đặt cấu hình ngay lập tức
        btnQuickSettings.setOnClickListener {
            showAdminSettingsDialog()
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

                // TRƯỜNG HỢP 1: Đang ở trang Đăng nhập -> Tự động điền & Submit
                if (url.contains("Account/Login", ignoreCase = true) || url.contains("login", ignoreCase = true)) {
                    statusText.text = getString(R.string.authenticating)
                    loadingOverlay.visibility = View.VISIBLE
                    injectAutoLoginScript()

                    // Chỉ mở màn hình nếu sau 10 giây vẫn không thể tự đăng nhập (để người dùng tự nhập)
                    handler.postDelayed({
                        if (webView.url?.contains("Login", ignoreCase = true) == true) {
                            loadingOverlay.visibility = View.GONE
                            webView.visibility = View.VISIBLE
                        }
                    }, 10000)
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
        webView.visibility = View.INVISIBLE
        loadingOverlay.visibility = View.VISIBLE
        errorLayout.visibility = View.GONE
        statusText.text = getString(R.string.connecting)

        val targetUrl = prefs.serverUrl.trim()
        webView.loadUrl(targetUrl)

        // Timeout an toàn: Tối đa 12 giây nếu không chuyển trang, luôn luôn hiển thị WebView
        handler.postDelayed({
            if (loadingOverlay.visibility == View.VISIBLE && errorLayout.visibility != View.VISIBLE) {
                loadingOverlay.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }
        }, 12000)
    }

    /**
     * Tự động đăng nhập lại khi hết phiên (Session Timeout / Unauthorized)
     */
    private fun triggerSilentReLogin(reason: String) {
        if (isReLoggingIn) return
        isReLoggingIn = true

        handler.post {
            statusText.text = "Phiên làm việc hết hạn. Đang tự động đăng nhập lại..."
            loadingOverlay.visibility = View.VISIBLE

            // Đảm bảo không bị treo màn hình chờ quá 8 giây
            handler.postDelayed({
                if (loadingOverlay.visibility == View.VISIBLE) {
                    loadingOverlay.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    isReLoggingIn = false
                }
            }, 8000)

            // Tải lại trang đăng nhập gốc để lấy session token mới
            val loginUrl = prefs.serverUrl.trim()
            isLoginInjected = false
            isRedirectedToGraphic = false
            webView.loadUrl(loginUrl)
        }
    }

    /**
     * Kiểm tra nội dung trang có chứa chữ Unauthorized hay không (bắt trường hợp server trả mã 200 nhưng nội dung là Unauthorized)
     */
    private fun checkAndRecoverFromUnauthorized() {
        val checkJs = """
            (function() {
                const text = document.body ? document.body.innerText.trim() : '';
                if (text === 'Unauthorized' || text.startsWith('Unauthorized') || document.title === 'Unauthorized') {
                    return true;
                }
                return false;
            })();
        """.trimIndent()

        webView.evaluateJavascript(checkJs) { result ->
            if (result == "true") {
                triggerSilentReLogin("Trang hiển thị chữ Unauthorized")
            }
        }
    }

    /**
     * Tự động inject mã JavaScript đóng popup Cookie, điền thông tin tài khoản và kích hoạt đăng nhập
     */
    private fun injectAutoLoginScript() {
        val user = prefs.username
        val pass = prefs.password

        val script = """
            (function() {
                // 1. Tự động click nút OK trên popup Cookie của Sauter Vision Center
                function dismissCookiePopup() {
                    const allButtons = Array.from(document.querySelectorAll('button, input[type="button"], a'));
                    const okBtn = allButtons.find(b => b.textContent && b.textContent.trim().toUpperCase() === 'OK');
                    if (okBtn) {
                        okBtn.click();
                        return true;
                    }
                    return false;
                }

                // 2. Tự động điền form đăng nhập và click Login
                function tryLogin() {
                    dismissCookiePopup();

                    const uInput = document.querySelector('input[type="text"]') ||
                                   document.querySelector('.login-form input[type="text"]') ||
                                   document.querySelector('input[name="Username"]') ||
                                   document.querySelector('input[name="username"]') ||
                                   document.querySelector('input[id*="user" i]');

                    const pInput = document.querySelector('input[type="password"]') ||
                                   document.querySelector('.login-form input[type="password"]') ||
                                   document.querySelector('input[name="Password"]') ||
                                   document.querySelector('input[name="password"]') ||
                                   document.querySelector('input[id*="pass" i]');

                    const btn = document.querySelector('.login-button') ||
                                document.querySelector('button[type="submit"]') ||
                                document.querySelector('input[type="submit"]') ||
                                document.querySelector('.btn-login') ||
                                Array.from(document.querySelectorAll('button')).find(b =>
                                    b.textContent && (b.textContent.trim().toLowerCase().includes('log') || b.textContent.trim().toLowerCase().includes('sign'))
                                );

                    if (uInput && pInput) {
                        function setReactValue(element, val) {
                            const lastVal = element.value;
                            element.value = val;
                            const event = new Event('input', { bubbles: true });
                            const tracker = element._valueTracker;
                            if (tracker) {
                                tracker.setValue(lastVal);
                            }
                            element.dispatchEvent(event);
                            element.dispatchEvent(new Event('change', { bubbles: true }));
                        }

                        // Điền Username & Password đồng bộ với React State
                        uInput.focus();
                        setReactValue(uInput, '$user');

                        pInput.focus();
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
     * Hộp thoại cấu hình URL, Link đồ họa BMS, Username, Password và Chu kỳ Refresh
     */
    private fun showAdminSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etServerUrl = dialogView.findViewById<EditText>(R.id.etServerUrl)
        val etGraphicUrl = dialogView.findViewById<EditText>(R.id.etGraphicUrl)
        val etUsername = dialogView.findViewById<EditText>(R.id.etUsername)
        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val etRefreshInterval = dialogView.findViewById<EditText>(R.id.etRefreshInterval)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)

        etServerUrl.setText(prefs.serverUrl)
        etGraphicUrl.setText(prefs.graphicUrl)
        etUsername.setText(prefs.username)
        etPassword.setText(prefs.password)
        etRefreshInterval.setText(prefs.refreshIntervalMinutes.toString())

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

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

            if (newServer.isNotEmpty()) prefs.serverUrl = newServer
            prefs.graphicUrl = newGraphic
            if (newUser.isNotEmpty()) prefs.username = newUser
            if (newPass.isNotEmpty()) prefs.password = newPass
            prefs.refreshIntervalMinutes = newInterval

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
     * Tự động phát hiện và kích hoạt chế độ Fullscreen đồ họa BMS bằng MutationObserver
     * Hoạt động đáp ứng (responsive) trên mọi kích thước màn hình mà không dùng toạ độ cố định
     */
    private fun triggerBmsGraphicFullscreen() {
        val fsScript = """
            (function() {
                function tryClickFullscreen() {
                    // 1. Tìm icon 4 mũi tên qua các class chuẩn của Sauter Vision Center
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

                    // 2. Kiểm tra bên trong iframe (nếu đồ họa BMS được render qua iframe)
                    const iframes = document.querySelectorAll('iframe');
                    for (let i = 0; i < iframes.length; i++) {
                        try {
                            const innerDoc = iframes[i].contentDocument || iframes[i].contentWindow.document;
                            const innerIcon = innerDoc.querySelector('.glyphicon-fullscreen, [class*="fullscreen" i]');
                            if (innerIcon) {
                                const innerBtn = innerIcon.closest('a, button, span, div') || innerIcon;
                                innerBtn.click();
                                return true;
                            }
                        } catch(e) {}
                    }
                    return false;
                }

                // Kỹ thuật 1: MutationObserver - Lập tức click ngay mili-giây đầu tiên khi icon đồ họa xuất hiện trong DOM
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

                    // Kỹ thuật 2: Polling dự phòng mỗi 500ms trong 10 giây đầu
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

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        }
    }
}
