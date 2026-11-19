package com.sauter.touchpanel

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Bộ quản lý Disk Cache chọn lọc (Selective Asset Caching) cho Sauter moduWeb Unity & Vision Center.
 * - Chỉ cache tài nguyên tĩnh: .js/.css/.png/.svg/.woff2, GetSVOResources.ashx?ResourceType=Image,
 *   /assets/i18n/*.json?hash=... và /api/Common/Resources/Get...?version=...
 * - Không bao giờ cache HTML, trang Login, SignalR hay API số liệu BACnet.
 */
class StaticAssetCacheManager(context: Context) {

    companion object {
        private const val TAG = "StaticAssetCache"
        private const val CACHE_DIR_NAME = "bms_static_cache"
        private const val MAX_CACHE_BYTES = 150L * 1024L * 1024L // 150 MB
        private const val TTL_DEFAULT_MS = 24L * 60L * 60L * 1000L // 24 giờ cho ảnh/CSS không hash
        private const val TTL_HASHED_MS = 7L * 24L * 60L * 60L * 1000L // 7 ngày cho file có hash/version
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 12000

        private val STATIC_EXTENSIONS = setOf(
            "svg", "png", "jpg", "jpeg", "gif", "webp", "ico",
            "css", "js", "woff", "woff2", "ttf"
        )

        private val HASHED_BUNDLE_REGEX = Regex(".*\\.[0-9a-fA-F]{12,}\\.(js|css|woff2)$")

        private val trustAllSslContext: SSLContext by lazy {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, SecureRandom())
            }
        }
        private val trustAllHostnameVerifier = HostnameVerifier { _, _ -> true }
    }

    private val cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply {
        if (!exists()) mkdirs()
    }
    private val inFlightKeys = ConcurrentHashMap<String, Any>()

    /**
     * Xử lý request từ WebViewClient.shouldInterceptRequest().
     * Trả null nếu là request động hoặc khi có lỗi để WebView xử lý bình thường.
     */
    fun intercept(request: WebResourceRequest?): WebResourceResponse? {
        if (request == null) return null
        if (!request.method.equals("GET", ignoreCase = true)) return null

        val uri = request.url ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null

        val rule = classify(uri) ?: return null
        val cacheKey = buildCacheKey(uri, rule)
        val dataFile = File(cacheDir, "$cacheKey.dat")
        val metaFile = File(cacheDir, "$cacheKey.meta")

        // 1. Cache Hit (còn hạn TTL)
        if (dataFile.exists() && metaFile.exists() && dataFile.length() > 0) {
            val ageMs = System.currentTimeMillis() - dataFile.lastModified()
            if (ageMs <= rule.ttlMs) {
                val meta = readMeta(metaFile)
                if (meta != null && !meta.mimeType.startsWith("text/html", ignoreCase = true)) {
                    try {
                        dataFile.setLastModified(System.currentTimeMillis()) // cập nhật LRU
                        return buildResponse(meta.mimeType, meta.encoding, FileInputStream(dataFile))
                    } catch (e: Exception) {
                        Log.w(TAG, "Không đọc được file cache ${dataFile.name}", e)
                    }
                }
            }
        }

        // 2. Cache Miss: đồng bộ tải và ghi atomic, tránh tải trùng lặp cùng 1 URL
        val lock = inFlightKeys.computeIfAbsent(cacheKey) { Any() }
        synchronized(lock) {
            // Double-check sau khi qua lock
            if (dataFile.exists() && metaFile.exists() && dataFile.length() > 0) {
                val ageMs = System.currentTimeMillis() - dataFile.lastModified()
                if (ageMs <= rule.ttlMs) {
                    val meta = readMeta(metaFile)
                    if (meta != null && !meta.mimeType.startsWith("text/html", ignoreCase = true)) {
                        try {
                            return buildResponse(meta.mimeType, meta.encoding, FileInputStream(dataFile))
                        } catch (_: Exception) {}
                    }
                }
            }

            return fetchAndCache(uri, request, dataFile, metaFile, rule)
        }
    }

    /**
     * Xóa sạch toàn bộ cache đồ họa khi kỹ thuật viên yêu cầu hoặc khi server nâng cấp giao diện
     */
    fun clearAll(): Int {
        var count = 0
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.delete()) count++
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi xóa cache đồ họa", e)
        }
        return count
    }

    /**
     * Tính tổng dung lượng cache hiện tại (MB)
     */
    fun getCacheSizeMB(): Double {
        var total = 0L
        cacheDir.listFiles()?.forEach { total += it.length() }
        return total / (1024.0 * 1024.0)
    }

    private data class CacheRule(val ttlMs: Long, val cacheKeySeed: String)
    private data class CacheMeta(val mimeType: String, val encoding: String)

    private fun classify(uri: Uri): CacheRule? {
        val path = uri.path ?: return null
        val lowerPath = path.lowercase()
        val query = uri.query ?: ""
        val lowerQuery = query.lowercase()

        // Tuyệt đối bỏ qua đường dẫn động / Login / SignalR / API số liệu
        if (lowerPath.contains("login") ||
            lowerPath.contains("signalr") ||
            lowerPath.contains("/api/bacnet/") ||
            lowerPath.contains("/api/svo/") ||
            lowerPath.contains("/api/users/") ||
            lowerPath.contains("/api/status/") ||
            lowerPath.contains("/api/system/") ||
            lowerPath.contains("/api/datalogger/") ||
            lowerPath.contains("/api/common/business")
        ) {
            return null
        }

        // Rule 2: Vision Center GetSVOResources.ashx?ResourceType=Image
        if (lowerPath.endsWith("/getsvoresources.ashx")) {
            val resType = uri.getQueryParameter("ResourceType") ?: ""
            if (resType.equals("Image", ignoreCase = true)) {
                val resKey = uri.getQueryParameter("ResourceKey") ?: ""
                val configId = uri.getQueryParameter("ConfigId") ?: ""
                if (resKey.isNotEmpty()) {
                    return CacheRule(TTL_DEFAULT_MS, "svo_img|$resKey|$configId")
                }
            }
            return null
        }

        // Rule 3: Resource đa ngôn ngữ / tĩnh có gắn hash hoặc version
        if (lowerPath.startsWith("/assets/i18n/") && lowerPath.endsWith(".json") && !uri.getQueryParameter("hash").isNullOrEmpty()) {
            return CacheRule(TTL_HASHED_MS, "${uri.host}$path?hash=${uri.getQueryParameter("hash")}")
        }
        if (lowerPath.contains("/api/common/resources/get") && !uri.getQueryParameter("version").isNullOrEmpty()) {
            val region = uri.getQueryParameter("regioncode") ?: ""
            val ver = uri.getQueryParameter("version") ?: ""
            return CacheRule(TTL_HASHED_MS, "${uri.host}$path?region=$region&version=$ver")
        }

        // Bỏ qua các endpoint /api/ khác
        if (lowerPath.contains("/api/")) return null

        // Rule 1: Asset tĩnh chuẩn (.png, .svg, .js, .css, .woff2...)
        val lastDot = lowerPath.lastIndexOf('.')
        if (lastDot <= 0 || lastDot == lowerPath.length - 1) return null
        val ext = lowerPath.substring(lastDot + 1)
        if (ext !in STATIC_EXTENSIONS) return null

        // Bỏ qua version.json hoặc các json có timestamp ?t=
        if (ext == "json") return null

        val ttl = if (HASHED_BUNDLE_REGEX.matches(lowerPath) || lowerQuery.contains("v=") || lowerQuery.contains("hash=")) {
            TTL_HASHED_MS
        } else {
            TTL_DEFAULT_MS
        }
        return CacheRule(ttl, "${uri.host}$path")
    }

    private fun fetchAndCache(
        uri: Uri,
        request: WebResourceRequest,
        dataFile: File,
        metaFile: File,
        rule: CacheRule
    ): WebResourceResponse? {
        var conn: HttpURLConnection? = null
        val tmpFile = File(cacheDir, "${dataFile.name}.${System.nanoTime()}.tmp")
        try {
            val urlStr = uri.toString()
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"

                if (this is HttpsURLConnection) {
                    sslSocketFactory = trustAllSslContext.socketFactory
                    hostnameVerifier = trustAllHostnameVerifier
                }

                // Gắn Cookie của WebView để không bị 401/302 với tài nguyên Protected
                val cookies = CookieManager.getInstance().getCookie(urlStr)
                if (!cookies.isNullOrEmpty()) {
                    setRequestProperty("Cookie", cookies)
                }

                request.requestHeaders?.forEach { (k, v) ->
                    if (!k.equals("Cookie", ignoreCase = true) &&
                        !k.equals("Accept-Encoding", ignoreCase = true)
                    ) {
                        setRequestProperty(k, v)
                    }
                }
            }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return null // Để WebView tự xử lý (vd: 401/302/304)
            }

            val rawContentType = conn.contentType ?: guessMimeType(uri.path ?: "")
            val mimeType = rawContentType.substringBefore(";").trim().ifEmpty { guessMimeType(uri.path ?: "") }
            val encoding = parseEncoding(rawContentType)

            // Chống cache nhầm trang Login HTML hoặc trang báo lỗi
            if (mimeType.equals("text/html", ignoreCase = true)) {
                return null
            }

            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) {
                return null
            }

            // Ghi atomic ra file .tmp rồi mới renameTo()
            FileOutputStream(tmpFile).use { fos ->
                fos.write(bytes)
                fos.flush()
            }
            if (dataFile.exists()) dataFile.delete()
            if (!tmpFile.renameTo(dataFile)) {
                tmpFile.delete()
            } else {
                writeMeta(metaFile, mimeType, encoding)
                enforceCacheLimit()
            }

            return buildResponse(mimeType, encoding, ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi tải asset ${uri.path}: ${e.message}")
            tmpFile.delete()
            return null
        } finally {
            conn?.disconnect()
        }
    }

    private fun buildResponse(mimeType: String, encoding: String, stream: java.io.InputStream): WebResourceResponse {
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "public, max-age=86400"
        )
        return WebResourceResponse(mimeType, encoding, 200, "OK", headers, stream)
    }

    private fun writeMeta(metaFile: File, mimeType: String, encoding: String) {
        try {
            metaFile.writeText("$mimeType\n$encoding")
        } catch (_: Exception) {}
    }

    private fun readMeta(metaFile: File): CacheMeta? {
        return try {
            val lines = metaFile.readLines()
            if (lines.isEmpty()) null
            else CacheMeta(lines[0].trim(), lines.getOrElse(1) { "UTF-8" }.trim())
        } catch (_: Exception) {
            null
        }
    }

    private fun enforceCacheLimit() {
        try {
            val files = cacheDir.listFiles() ?: return
            var totalSize = files.sumOf { it.length() }
            if (totalSize <= MAX_CACHE_BYTES) return

            val dataFiles = files.filter { it.name.endsWith(".dat") }.sortedBy { it.lastModified() }
            for (df in dataFiles) {
                if (totalSize <= MAX_CACHE_BYTES * 8 / 10) break // dọn xuống còn 80%
                val mf = File(cacheDir, df.name.removeSuffix(".dat") + ".meta")
                totalSize -= df.length()
                df.delete()
                if (mf.exists()) {
                    totalSize -= mf.length()
                    mf.delete()
                }
            }
        } catch (_: Exception) {}
    }

    private fun buildCacheKey(uri: Uri, rule: CacheRule): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rule.cacheKeySeed.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun parseEncoding(contentType: String): String {
        val parts = contentType.split(";")
        for (p in parts) {
            val trimmed = p.trim()
            if (trimmed.startsWith("charset=", ignoreCase = true)) {
                return trimmed.substringAfter("=").trim().ifEmpty { "UTF-8" }
            }
        }
        return "UTF-8"
    }

    private fun guessMimeType(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase()) {
            "js" -> "application/javascript"
            "css" -> "text/css"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }
}
