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
import android.net.*
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.example.timepray.update.ApkUpdateManager
import com.example.timepray.update.UpdateScheduler
import com.example.timepray.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_FORCE_UPDATE_CHECK = "com.example.timepray.extra.FORCE_UPDATE_CHECK"

        private const val PREFS_NAME = "aura_prefs"
        private const val KEY_LAST_URL = "last_success_url"
        private const val KEY_LAST_UPDATE_CHECK_DAY = "last_update_check_day"
        private const val OFFLINE_MESSAGE = "Нет подключения к интернету\nОжидание сети..."
    }

    private lateinit var cm: ConnectivityManager
    private var isOnline = false
    private var lastUrl = ""
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private lateinit var webView: WebView
    private lateinit var myChromeClient: WebChromeClient
    private var customVideoView: View? = null

    private lateinit var offlineText: TextView
    private lateinit var wifiMenuOverlay: FrameLayout
    private lateinit var wifiMenuText: TextView
    private var wifiMenuVisible = false

    private lateinit var apkUpdateManager: ApkUpdateManager
    private lateinit var updateOverlay: FrameLayout
    private lateinit var updateOverlayText: TextView
    private var updateOverlayVisible = false

    private lateinit var settingsMenuOverlay: FrameLayout
    private lateinit var settingsMenuText: TextView
    private var settingsMenuVisible = false
    private var settingsMenuSelection = SettingsMenuItem.WIFI

    private enum class SettingsMenuItem { WIFI, UPDATE }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateOnlineState()
            if (isOnline) reloadWhenOnline()
        }

        override fun onLost(network: Network) {
            updateOnlineState()
            loadCachedLastPage()
        }

        override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
            updateOnlineState()
            if (isOnline) reloadWhenOnline() else loadCachedLastPage()
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
            isOnline = isLegacyNetworkConnected()
        }

        runOnUiThread {
            if (isOnline) hideOffline() else showOffline()
            if (wifiMenuVisible) updateWifiMenuText()
        }
    }


    private var reloadPending = false
    private fun reloadWhenOnline() {
        if (reloadPending) return
        reloadPending = true
        handler.postDelayed({
            if (isOnline) {
                webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
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
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = getString(R.string.app_webview_user_agent)
            loadsImagesAutomatically = true
        }
        webView.setBackgroundColor(0xFF000000.toInt())

        // Куки
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        offlineText = TextView(this).apply {
            text = OFFLINE_MESSAGE
            gravity = Gravity.CENTER
            textSize = 18f
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            isVisible = false
        }
        val rootContainer = webView.parent as? FrameLayout
        rootContainer?.addView(
            offlineText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        wifiMenuText = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(48, 48, 48, 48)
        }
        wifiMenuOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xE6000000.toInt())
            isVisible = false
            isFocusable = true
            isFocusableInTouchMode = true
            addView(
                wifiMenuText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        rootContainer?.addView(
            wifiMenuOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        updateOverlayText = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(48, 48, 48, 48)
        }
        updateOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xE6000000.toInt())
            isVisible = false
            addView(
                updateOverlayText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        rootContainer?.addView(
            updateOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        settingsMenuText = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(48, 48, 48, 48)
        }
        settingsMenuOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xE6000000.toInt())
            isVisible = false
            isFocusable = true
            isFocusableInTouchMode = true
            addView(
                settingsMenuText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        rootContainer?.addView(
            settingsMenuOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        apkUpdateManager = ApkUpdateManager(
            this,
            getString(R.string.update_github_owner),
            getString(R.string.update_github_repo),
            BuildConfig.VERSION_CODE
        )
        if (isUpdateConfigured()) {
            UpdateScheduler.scheduleDailyMidnightCheck(this)
        }
        handleUpdateCheckIntent(intent)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) = Unit

            override fun onPageFinished(view: WebView?, url: String?) {
                if (isOnline) hideOffline() else showOffline()
                url?.let {
                    lastUrl = it
                    if (isSameSiteAsHome(it)) saveLastSuccessfulUrl(it)
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame != true) return
                val code = errorResponse?.statusCode ?: return
                if (code >= 400) {
                    runOnUiThread { showPageError("Сайт недоступен (ошибка $code)") }
                }
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
                    val failedUrl = request.url?.toString()
                    showOffline()
                    if (!lastUrl.isNullOrBlank() && failedUrl != lastUrl) {
                        loadCachedLastPage()
                    }
                } else {
                    runOnUiThread {
                        showPageError("Не удалось загрузить страницу (код $code)")
                    }
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
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

                val container = webView.parent as? FrameLayout
                container?.addView(view, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
                webView.visibility = View.GONE
                setImmersiveMode(true)
            }

            override fun onHideCustomView() {
                val container = webView.parent as? FrameLayout
                if (customVideoView != null) {
                    container?.removeView(customVideoView)
                    customVideoView = null
                }
                webView.visibility = View.VISIBLE
                setImmersiveMode(false)
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
                if (settingsMenuVisible) {
                    hideSettingsMenu()
                } else if (wifiMenuVisible) {
                    hideWifiMenu()
                } else if (customVideoView != null) {
                    // НЕ вызываем webView.webChromeClient?.onHideCustomView() — это и ломало сборку на API<26
                    myChromeClient.onHideCustomView()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        // D-pad прокрутка (OK/Enter обрабатываются в dispatchKeyEvent)
        webView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || wifiMenuVisible || settingsMenuVisible) {
                return@setOnKeyListener false
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { webView.pageDown(true); true }
                KeyEvent.KEYCODE_DPAD_UP -> { webView.pageUp(true); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { webView.scrollBy(-200, 0); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { webView.scrollBy(200, 0); true }
                else -> false
            }
        }

        // Загружаем сайт из config.xml (сохранённый URL только если тот же домен)
        lastUrl = resolveStartUrl()
        updateOnlineState()
        if (isOnline) {
            loadWebPage(lastUrl, WebSettings.LOAD_DEFAULT)
        } else {
            loadCachedLastPage()
        }
    }

    private fun homeUrl(): String = getString(R.string.app_website_url).trim()

    private fun resolveStartUrl(): String {
        val home = homeUrl()
        val saved = prefs.getString(KEY_LAST_URL, null)?.takeIf { it.isNotBlank() } ?: return home
        return if (isSameSiteAsHome(saved)) saved else home
    }

    private fun isSameSiteAsHome(url: String): Boolean {
        return try {
            Uri.parse(url).host.equals(Uri.parse(homeUrl()).host, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun loadWebPage(url: String, cacheMode: Int) {
        webView.settings.cacheMode = cacheMode
        webView.loadUrl(url)
    }
    @Suppress("DEPRECATION")
    private fun isLegacyNetworkConnected(): Boolean {
        val ni = cm.activeNetworkInfo
        return ni != null && ni.isConnected
    }

    private fun setImmersiveMode(enabled: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showOffline() {
        runOnUiThread { offlineText.isVisible = true }
    }

    private fun hideOffline() {
        runOnUiThread {
            offlineText.text = OFFLINE_MESSAGE
            offlineText.isVisible = false
        }
    }

    private fun showPageError(message: String) {
        offlineText.text = message
        showOffline()
    }

    private fun saveLastSuccessfulUrl(url: String) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            prefs.edit().putString(KEY_LAST_URL, url).apply()
        }
    }

    private fun loadCachedLastPage() {
        runOnUiThread {
            val targetUrl = if (lastUrl.isNotBlank()) lastUrl else homeUrl()
            loadWebPage(targetUrl, WebSettings.LOAD_CACHE_ELSE_NETWORK)
            showOffline()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateCheckIntent(intent)
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
    private fun updateWifiMenuText() {
        val status = if (isOnline) "Подключено к интернету" else "Нет подключения к интернету"
        wifiMenuText.text = buildString {
            appendLine("Управление Wi‑Fi")
            appendLine()
            appendLine(status)
            appendLine()
            appendLine("Ctrl+Q / OK — открыть настройки сети")
            append("Esc / Назад — закрыть")
        }
    }

    private fun showWifiMenu() {
        if (wifiMenuVisible) return
        wifiMenuVisible = true
        updateWifiMenuText()
        wifiMenuOverlay.isVisible = true
        wifiMenuOverlay.requestFocus()
        openWifiSettings()
    }

    private fun hideWifiMenu() {
        if (!wifiMenuVisible) return
        wifiMenuVisible = false
        wifiMenuOverlay.isVisible = false
        webView.requestFocus()
    }

    private fun updateSettingsMenuText() {
        val wifiLine = if (settingsMenuSelection == SettingsMenuItem.WIFI) "▶  Wi‑Fi" else "    Wi‑Fi"
        val updateLine = if (settingsMenuSelection == SettingsMenuItem.UPDATE) "▶  Обновление" else "    Обновление"
        settingsMenuText.text = buildString {
            appendLine("Меню")
            appendLine()
            appendLine(wifiLine)
            appendLine(updateLine)
            appendLine()
            append("↑↓ — выбор   OK — открыть   Назад — закрыть")
        }
    }

    private fun showSettingsMenu() {
        if (settingsMenuVisible) return
        settingsMenuVisible = true
        settingsMenuSelection = SettingsMenuItem.WIFI
        updateSettingsMenuText()
        settingsMenuOverlay.isVisible = true
        settingsMenuOverlay.requestFocus()
    }

    private fun hideSettingsMenu() {
        if (!settingsMenuVisible) return
        settingsMenuVisible = false
        settingsMenuOverlay.isVisible = false
        webView.requestFocus()
    }

    private fun activateSettingsMenuSelection() {
        when (settingsMenuSelection) {
            SettingsMenuItem.WIFI -> {
                hideSettingsMenu()
                showWifiMenu()
            }
            SettingsMenuItem.UPDATE -> {
                hideSettingsMenu()
                checkForAppUpdate(force = true)
            }
        }
    }

    private fun isSettingsKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == KeyEvent.KEYCODE_MENU
    }

    private fun isConfirmKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                keyCode == KeyEvent.KEYCODE_BUTTON_A
    }

    private fun isCloseKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_ESCAPE ||
                keyCode == KeyEvent.KEYCODE_BACK
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

    private fun isUpdateConfigured(): Boolean {
        val owner = getString(R.string.update_github_owner)
        return owner.isNotBlank() && !owner.startsWith("YOUR_")
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun wasUpdateCheckedToday(): Boolean =
        prefs.getString(KEY_LAST_UPDATE_CHECK_DAY, "") == todayKey()

    private fun markUpdateCheckedToday() {
        prefs.edit().putString(KEY_LAST_UPDATE_CHECK_DAY, todayKey()).apply()
    }

    private fun handleUpdateCheckIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_FORCE_UPDATE_CHECK, false) == true) {
            handler.post { checkForAppUpdate(force = true) }
        }
    }

    private fun checkForAppUpdate(force: Boolean = false) {
        if (!isUpdateConfigured() || !isOnline || updateOverlayVisible) return
        if (!force && wasUpdateCheckedToday()) return

        if (!apkUpdateManager.canInstallPackages()) {
            apkUpdateManager.createInstallPermissionIntent()?.let { startActivity(it) }
            Toast.makeText(
                this,
                "Разрешите установку приложений для автообновления",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        markUpdateCheckedToday()
        showUpdateOverlay("Проверка обновлений...")

        apkUpdateManager.checkAndInstall(
            onStatus = { status ->
                runOnUiThread {
                    updateOverlayText.text = status
                    if (status == "Установлена актуальная версия") {
                        handler.postDelayed({ hideUpdateOverlay() }, 2000)
                    }
                }
            },
            onProgress = { progress ->
                runOnUiThread { updateOverlayText.text = "Загрузка обновления...\n$progress%" }
            },
            onError = { error ->
                runOnUiThread {
                    updateOverlayText.text = error
                    handler.postDelayed({ hideUpdateOverlay() }, 3000)
                }
            },
            onInstallStarted = {
                runOnUiThread {
                    updateOverlayText.text = "Подтвердите установку на экране"
                }
            }
        )
    }

    private fun showUpdateOverlay(text: String) {
        updateOverlayVisible = true
        updateOverlayText.text = text
        updateOverlay.isVisible = true
    }

    private fun hideUpdateOverlay() {
        updateOverlayVisible = false
        updateOverlay.isVisible = false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        if (settingsMenuVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    settingsMenuSelection = SettingsMenuItem.WIFI
                    updateSettingsMenuText()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    settingsMenuSelection = SettingsMenuItem.UPDATE
                    updateSettingsMenuText()
                    return true
                }
                in listOf(
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_BUTTON_A
                ) -> {
                    activateSettingsMenuSelection()
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    hideSettingsMenu()
                    return true
                }
            }
        }

        if (isCloseKey(event.keyCode)) {
            if (settingsMenuVisible) {
                hideSettingsMenu()
                return true
            }
            if (wifiMenuVisible) {
                hideWifiMenu()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (isSettingsKey(event.keyCode)) {
            showSettingsMenu()
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_Q && event.isCtrlPressed) {
            showWifiMenu()
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_W && event.isCtrlPressed) {
            checkForAppUpdate(force = true)
            return true
        }

        if (isConfirmKey(event.keyCode) && wifiMenuVisible) {
            openWifiSettings()
            return true
        }

        return super.dispatchKeyEvent(event)
    }

}
