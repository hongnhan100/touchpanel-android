package com.sauter.touchpanel

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
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
    private lateinit var secretAdminButton: View

    private lateinit var prefs: AppPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var isRedirectedToGraphic = false
    private var isLoginInjected = false

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
        setupSecretAdminHotspot()

        // Bắt đầu nạp trang web Vision Center
        startLoading()
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

    private fun initViews() {
        webView = findViewById(R.id.webView)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        statusText = findViewById(R.id.statusText)
        errorLayout = findViewById(R.id.errorLayout)
        errorDetailText = findViewById(R.id.errorDetailText)
        btnRetry = findViewById(R.id.btnRetry)
        secretAdminButton = findViewById(R.id.secretAdminButton)

        btnRetry.setOnClickListener {
            errorLayout.visibility = View.GONE
            loadingOverlay.visibility = View.VISIBLE
            statusText.text = getString(R.string.connecting)
            startLoading()
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

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Nếu đang điều hướng tới trang login, chuẩn bị sẵn sàng inject
                if (url?.contains("Account/Login", ignoreCase = true) == true) {
                    isLoginInjected = false
                    loadingOverlay.visibility = View.VISIBLE
                    statusText.text = getString(R.string.authenticating)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                cookieManager.flush()

                if (url == null) return

                // TRƯỜNG HỢP 1: Đang ở trang Đăng nhập -> Tự động điền & Submit
                if (url.contains("Account/Login", ignoreCase = true)) {
                    if (!isLoginInjected) {
                        isLoginInjected = true
                        statusText.text = getString(R.string.authenticating)
                        injectAutoLoginScript()
                    }
                }
                // TRƯỜNG HỢP 2: Đã vào trong hệ thống (Đăng nhập thành công)
                else {
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
                }
            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                showNetworkError(description ?: "Không thể kết nối")
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 200) >= 400) {
                    // Nếu lỗi ở trang chính
                    if (errorResponse?.statusCode == 404 && request.url.toString().contains("Account/Login")) {
                        showNetworkError("Lỗi đường dẫn máy chủ (HTTP 404)")
                    }
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                // Trong môi trường BMS nội bộ, cho phép chấp nhận chứng chỉ tự ký (Self-signed)
                handler?.proceed()
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

        // Nếu chưa cấu hình mật khẩu, mở hộp thoại cài đặt
        if (prefs.password.isEmpty()) {
            showAdminSettingsDialog()
            return
        }

        val targetUrl = prefs.serverUrl.trim()
        webView.loadUrl(targetUrl)
    }

    /**
     * Tự động inject mã JavaScript điền thông tin tài khoản và kích hoạt click đăng nhập
     */
    private fun injectAutoLoginScript() {
        val user = prefs.username
        val pass = prefs.password

        val script = """
            (function() {
                function tryLogin() {
                    const uInput = document.querySelector('input[type="text"]') ||
                                   document.querySelector('.login-form input[type="text"]') ||
                                   document.querySelector('input[name="Username"]') ||
                                   document.querySelector('input[name="username"]');

                    const pInput = document.querySelector('input[type="password"]') ||
                                   document.querySelector('.login-form input[type="password"]') ||
                                   document.querySelector('input[name="Password"]') ||
                                   document.querySelector('input[name="password"]');

                    const btn = document.querySelector('.login-button') ||
                                document.querySelector('button[type="submit"]') ||
                                document.querySelector('input[type="submit"]') ||
                                document.querySelector('.btn-login');

                    if (uInput && pInput) {
                        // 1. Điền Username
                        uInput.focus();
                        uInput.value = '$user';
                        uInput.dispatchEvent(new Event('input', { bubbles: true }));
                        uInput.dispatchEvent(new Event('change', { bubbles: true }));

                        // 2. Điền Password
                        pInput.focus();
                        pInput.value = '$pass';
                        pInput.dispatchEvent(new Event('input', { bubbles: true }));
                        pInput.dispatchEvent(new Event('change', { bubbles: true }));

                        // 3. Click nút đăng nhập
                        setTimeout(function() {
                            if (btn) {
                                btn.click();
                            } else {
                                const form = uInput.closest('form');
                                if (form) form.submit();
                            }
                        }, 400);
                        return true;
                    }
                    return false;
                }

                // Thử ngay lập tức
                if (!tryLogin()) {
                    // Nếu React/Redux chưa render xong, thử lại sau 500ms
                    setTimeout(tryLogin, 500);
                    setTimeout(tryLogin, 1200);
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    private fun showNetworkError(detail: String) {
        webView.visibility = View.INVISIBLE
        loadingOverlay.visibility = View.GONE
        errorLayout.visibility = View.VISIBLE
        errorDetailText.text = "Lỗi kết nối: $detail\nĐang tự động thử lại sau 10 giây..."

        // Tự động thử kết nối lại sau 10 giây
        handler.postDelayed({
            if (errorLayout.visibility == View.VISIBLE) {
                startLoading()
            }
        }, 10000)
    }

    /**
     * Vùng cảm ứng ẩn ở góc trên bên phải: Nhấn giữ 5 giây để mở màn hình cài đặt
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupSecretAdminHotspot() {
        val longPressRunnable = Runnable {
            showAdminSettingsDialog()
        }

        secretAdminButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handler.postDelayed(longPressRunnable, 5000) // Nhấn giữ 5 giây
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Hộp thoại cấu hình URL, Link đồ họa BMS, Username và Password
     */
    private fun showAdminSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etServerUrl = dialogView.findViewById<EditText>(R.id.etServerUrl)
        val etGraphicUrl = dialogView.findViewById<EditText>(R.id.etGraphicUrl)
        val etUsername = dialogView.findViewById<EditText>(R.id.etUsername)
        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)

        etServerUrl.setText(prefs.serverUrl)
        etGraphicUrl.setText(prefs.graphicUrl)
        etUsername.setText(prefs.username)
        etPassword.setText(prefs.password)

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

            if (newServer.isNotEmpty()) prefs.serverUrl = newServer
            prefs.graphicUrl = newGraphic
            if (newUser.isNotEmpty()) prefs.username = newUser
            if (newPass.isNotEmpty()) prefs.password = newPass

            dialog.dismiss()
            applyKioskImmersiveMode()

            // Xóa cookie cũ và tải lại trang với cấu hình mới
            CookieManager.getInstance().removeAllCookies(null)
            startLoading()
        }

        dialog.show()
    }

    override fun onBackPressed() {
        // Hỗ trợ nút Back để quay lại trang web trước đó trong WebView thay vì thoát app
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            // Không làm gì để tránh người dùng thoát ứng dụng Kiosk
        }
    }
}
