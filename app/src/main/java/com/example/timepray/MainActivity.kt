package com.example.timepray


import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.provider.Settings
import android.widget.Toast
import android.os.Build
import java.net.URLEncoder
import android.net.*
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.webkit.WebResourceError
import android.widget.TextView
import androidx.core.view.isVisible


class MainActivity : AppCompatActivity() {
    private lateinit var cm: ConnectivityManager
    private var isOnline = false
    private var lastUrl = ""
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var webView: WebView
    private lateinit var myChromeClient: WebChromeClient
    private var customVideoView: View? = null
    private var originalSystemUiVisibility: Int = 0

    private lateinit var offlineText: TextView

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateOnlineState()
            if (isOnline) reloadWhenOnline()
        }

        override fun onLost(network: Network) {
            updateOnlineState()
            showOffline()
        }

        override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
            updateOnlineState()
            if (isOnline) reloadWhenOnline() else showOffline()
        }
    }

    private fun updateOnlineState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val an = cm.activeNetwork
            val caps = an?.let { cm.getNetworkCapabilities(it) }
            // На ряде Android TV VALIDATED может не выставляться стабильно, даже при доступном интернете.
            // Для UI-состояния считаем онлайн по наличию INTERNET у активной сети.
            isOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            @Suppress("DEPRECATION")
            val ni = cm.activeNetworkInfo
            isOnline = ni != null && ni.isConnected
        }

        runOnUiThread {
            if (isOnline) hideOffline() else showOffline()
        }
    }


    private var reloadPending = false
    private fun reloadWhenOnline() {
        if (reloadPending) return
        reloadPending = true
        handler.postDelayed({
            if (isOnline) {
                if (lastUrl.isNotBlank()) webView.loadUrl(lastUrl)
                else webView.reload()
            }
            reloadPending = false
        }, 800)
    }



    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        webView = findViewById(R.id.web)
        webView.isFocusable = true
        webView.requestFocus()

        // Базовые настройки для современных сайтов
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString + " AndroidTVWebView"
        }

        // Куки
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        offlineText = TextView(this).apply {
            text = "Нет подключения к интернету\nОжидание сети..."
            gravity = Gravity.CENTER
            textSize = 18f
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            isVisible = false
        }
        (webView.parent as? FrameLayout)?.addView(
            offlineText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                lastUrl = url
                view?.loadUrl(url)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                url?.let { lastUrl = it }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                hideOffline()
                url?.let { lastUrl = it }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // Не реагируем на ошибки вторичных ресурсов (js/css/img) как на "нет интернета".
                if (request?.isForMainFrame != true) return

                val code = error?.errorCode
                val isConnectivityError = code == ERROR_HOST_LOOKUP ||
                        code == ERROR_CONNECT ||
                        code == ERROR_TIMEOUT ||
                        code == ERROR_IO

                if (isConnectivityError || !isOnline) {
                    showOffline()
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                super.onReceivedSslError(view, handler, error)
            }
        }

        // Сохраняем ChromeClient в переменную, чтобы обходить API 26 getWebChromeClient()
        myChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customVideoView != null || view == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customVideoView = view
                originalSystemUiVisibility = window.decorView.systemUiVisibility

                val container = webView.parent as? FrameLayout
                container?.addView(view, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
                webView.visibility = View.GONE

                // Иммерсив для видео
                window.decorView.systemUiVisibility =
                    (View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }

            override fun onHideCustomView() {
                val container = webView.parent as? FrameLayout
                if (customVideoView != null) {
                    container?.removeView(customVideoView)
                    customVideoView = null
                }
                webView.visibility = View.VISIBLE
                window.decorView.systemUiVisibility = originalSystemUiVisibility
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                // Разрешаем только запрошенные ресурсы (WebRTC и т.п.)
                request?.grant(request.resources)
            }
        }
        webView.webChromeClient = myChromeClient

        // Обработка "Назад": либо выходим из фулл-скрина, либо назад в истории, либо закрываем
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customVideoView != null) {
                    // НЕ вызываем webView.webChromeClient?.onHideCustomView() — это и ломало сборку на API<26
                    myChromeClient.onHideCustomView()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        // D-pad прокрутка
        webView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    openWifiSettings()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> { webView.pageDown(true); true }
                KeyEvent.KEYCODE_DPAD_UP -> { webView.pageUp(true); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { webView.scrollBy(-200, 0); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { webView.scrollBy(200, 0); true }
                else -> false
            }
        }

        // Загружаем сайт
        lastUrl = getString(R.string.app_website_url)
        updateOnlineState()
        if (isOnline) webView.loadUrl(lastUrl) else showOffline()
        // Загружаем ваш сайт
//        val device = "AndroidTV"
//        val url = getString(R.string.app_website_url) //+ "?device=111"//${URLEncoder.encode(device, "UTF-8")}
//        webView.loadUrl(url)
    }
    private fun showOffline() {
        runOnUiThread { offlineText.isVisible = true }
    }

    private fun hideOffline() {
        runOnUiThread { offlineText.isVisible = false }
    }

    override fun onStart() {
        super.onStart()
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(req, networkCallback)
        updateOnlineState()
    }

    override fun onStop() {
        super.onStop()
        try { cm.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
    }

    // На случай, если где-то нужно гарантировано нормализовать URL
    private fun WebView.loadUrlSafe(url: String) {
        val uri = Uri.parse(url)
        loadUrl(uri.toString())
    }
    private fun openWifiSettings() {
        try {
            // Для Android 10+ (API 29): панель подключения не выходя из приложения
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
                return
            }
        } catch (_: ActivityNotFoundException) { /* падаем на запасные варианты ниже */ }

        // Запасные варианты для любых API / прошивок ТВ
        val fallbacks = listOf(
            Intent(Settings.ACTION_WIFI_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (intent in fallbacks) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) { /* попробуем следующий */ }
        }

        Toast.makeText(this, "Не удалось открыть настройки сети на этом устройстве", Toast.LENGTH_SHORT).show()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,   // OK на пульте
                KeyEvent.KEYCODE_ENTER,         // Enter на клавиатуре/некоторых пультах
                KeyEvent.KEYCODE_NUMPAD_ENTER,  // Enter на numpad
                KeyEvent.KEYCODE_BUTTON_A       // «A» на геймпадах (часто = подтверждение)
                    -> {
                    openWifiSettings()
                    return true // поглощаем событие, чтобы WebView не «кликал» по ссылкам
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

}
