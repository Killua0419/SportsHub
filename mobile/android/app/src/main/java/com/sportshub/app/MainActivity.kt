package com.sportshub.app

import android.app.Activity
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * 纯 WebView 混合壳（内嵌原生本地代理）：
 * - 加载 assets/index.html；页面把数据请求拼到 http://127.0.0.1:8787/proxy/ 路径；
 * - shouldInterceptRequest 拦截回环代理地址，由 ProxyRouter 在原生层代为请求上游公开源并回包，
 *   手机端无需 Node 运行时、不依赖外部服务器；无网络或无源项目显示空态。
 * - 退后台暂停 WebView 与 JS 计时器，回前台恢复，降低功耗；
 * - 不引入额外第三方依赖，尽量减小安装包体积。
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        val s: WebSettings = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.setAllowFileAccessFromFileURLs(true)
        s.setAllowUniversalAccessFromFileURLs(true)
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mediaPlaybackRequiresUserGesture = true
        s.builtInZoomControls = false
        s.displayZoomControls = false
        /* 公开数据源边缘会拦截带 WebView(wv) 标识的 UA，改为普通移动 Chrome UA */
        s.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.234 Mobile Safari/537.36"

        // 降耗：拦截 console 输出，避免日志通道开销
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return null
                val host = uri.host ?: return null
                // 仅拦截本地代理回环地址（默认端口 8787）；其余资源放行走系统网络栈
                val isLoopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
                if (!isLoopback) return null
                if (uri.port != -1 && uri.port != 8787) return null

                val res = ProxyRouter.route(uri)
                val mime = res.contentType.substringBefore(';').trim().ifBlank { "application/json" }
                val reason = reasonPhrase(res.status)
                val headers = HashMap(res.headers)
                val stream = ByteArrayInputStream(res.body.toByteArray(StandardCharsets.UTF_8))
                return WebResourceResponse(mime, "UTF-8", res.status, reason, headers, stream)
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun reasonPhrase(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        502 -> "Bad Gateway"
        else -> "Error"
    }

    override fun onPause() {
        super.onPause()
        runCatching { webView.onPause() }
        runCatching { webView.pauseTimers() }
    }

    override fun onResume() {
        super.onResume()
        runCatching { webView.resumeTimers() }
        runCatching { webView.onResume() }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        runCatching { webView.destroy() }
        super.onDestroy()
    }
}
