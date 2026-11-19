package com.sportshub.app

import android.app.Activity
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 纯 WebView 混合壳：
 * - 加载 assets/index.html，页面通过公开数据源联网拉取赛事；无网络或无源项目显示空态；
 * - 退后台暂停 WebView 与 JS 计时器，回前台恢复，降低功耗；
 * - 不引入额外原生桥接与第三方依赖，尽量减小安装包体积。
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
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean = false
        }

        webView.loadUrl("file:///android_asset/index.html")
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
