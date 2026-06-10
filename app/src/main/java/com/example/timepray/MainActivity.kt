package com.example.timepray


import android.annotation.SuppressLint
import android.graphics.Bitmap
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
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.content.edit
import androidx.core.net.toUri
import com.example.timepray.update.ApkUpdateManager
import com.example.timepray.update.UpdatePreferences
import com.example.timepray.update.UpdateScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_FORCE_UPDATE_CHECK = "com.example.timepray.extra.FORCE_UPDATE_CHECK"

        private const val PREFS_NAME = "aura_prefs"
        private const val KEY_LAST_URL = "last_success_url"
        private const val KEY_LAST_UPDATE_CHECK_DAY = "last_update_check_day"
        private const val KEY_LOADED_VERSION_CODE = "loaded_version_code"
        private const val OFFLINE_MESSAGE = "Нет подключения к интернету\nОжидание сети..."
        @Suppress("SpellCheckingInspection")
        private const val PAGE_FIX_JS = """
            (function() {
                if (navigator.serviceWorker) {
                    navigator.serviceWorker.getRegistrations().then(function(regs) {
                        regs.forEach(function(r) { r.unregister(); });
                    });
                }
                var h = window.innerHeight || document.documentElement.clientHeight || 1080;
                if (document.body) document.body.style.minHeight = h + 'px';
                var main = document.querySelector('main');
                if (main) {
                    main.style.height = h + 'px';
                    main.style.minHeight = h + 'px';
                }
            })();
        """
        private val PAGE_FIX_DELAYS_MS = longArrayOf(300L, 1000L, 2000L, 4000L)
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
    private var settingsTimeEditing = false
    private var mainFrameHttpErrorRetried = false

    private enum class SettingsMenuItem { WIFI, UPDATE, AUTO_UPDATE, AUTO_UPDATE_TIME, WEBVIEW_UPDATE }

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
                if (lastUrl.isNotBlank()) loadWebPage(lastUrl)
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
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = true
            allowContentAccess = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
            @Suppress("DEPRECATION")
            setNeedInitialFocus(false)
        }
        webView.setBackgroundColor(0xFF0c192a.toInt())
        webView.clearCache(true)

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
            UpdateScheduler.applyFromPrefs(this)
        }
        handleUpdateCheckIntent(intent)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                // Service Worker на TV WebView кэширует ошибки и отдаёт пустые ответы — сайт белеет.
                val path = request?.url?.path.orEmpty()
                if (path == "/sw.js" || path.endsWith("/sw.js")) {
                    return WebResourceResponse(
                        "application/javascript",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) = Unit

            override fun onPageFinished(view: WebView?, url: String?) {
                mainFrameHttpErrorRetried = false
                if (isOnline) hideOffline() else showOffline()
                url?.let {
                    lastUrl = it
                    if (isSameSiteAsHome(it)) saveLastSuccessfulUrl(it)
                }
                view?.let { applyPageFixes(it) }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame != true) return
                val code = errorResponse?.statusCode ?: return
                if (code >= 400 && !mainFrameHttpErrorRetried) {
                    mainFrameHttpErrorRetried = true
                    runOnUiThread { loadWebPage(homeUrl(), forceNetwork = true) }
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                view?.reload()
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
                if (request?.isForMainFrame != true) return
                handleMainFrameLoadError(error?.errorCode ?: return, request.url?.toString())
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return
                handleMainFrameLoadError(errorCode, failingUrl)
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                // Сайты на ТВ иногда с самоподписанными сертификатами; без proceed страница не откроется.
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    settingsMenuVisible && settingsTimeEditing -> {
                        settingsTimeEditing = false
                        updateSettingsMenuText()
                    }
                    settingsMenuVisible -> hideSettingsMenu()
                    wifiMenuVisible -> hideWifiMenu()
                    customVideoView != null -> myChromeClient.onHideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })

        lastUrl = homeUrl()
        updateOnlineState()
        if (isOnline) {
            loadWebPage(lastUrl, WebSettings.LOAD_NO_CACHE, forceNetwork = true)
        } else {
            loadCachedLastPage()
        }
    }

    private fun homeUrl(): String = getString(R.string.app_website_url).trim()

    private fun isSameSiteAsHome(url: String): Boolean {
        return try {
            url.toUri().host.equals(homeUrl().toUri().host, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun handleMainFrameLoadError(code: Int, failedUrl: String?) {
        val isConnectivityError = code == WebViewClient.ERROR_HOST_LOOKUP ||
                code == WebViewClient.ERROR_CONNECT ||
                code == WebViewClient.ERROR_TIMEOUT ||
                code == WebViewClient.ERROR_IO

        if (isConnectivityError || !isOnline) {
            showOffline()
            if (lastUrl.isNotBlank() && failedUrl != lastUrl) {
                loadCachedLastPage()
            }
        } else {
            runOnUiThread {
                showPageError(getString(R.string.page_load_error, code))
            }
        }
    }

    private fun browserUserAgent(): String = getString(R.string.app_webview_user_agent)

    private fun browserHeaders(bypassCache: Boolean = false): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to browserUserAgent(),
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
        )
        if (bypassCache) {
            headers["Cache-Control"] = "no-cache"
            headers["Pragma"] = "no-cache"
        }
        return headers
    }

    private fun shouldBypassCacheForLoad(): Boolean {
        val loadedVersion = prefs.getInt(KEY_LOADED_VERSION_CODE, -1)
        if (loadedVersion == BuildConfig.VERSION_CODE) return false
        prefs.edit { putInt(KEY_LOADED_VERSION_CODE, BuildConfig.VERSION_CODE) }
        return true
    }

    private fun loadWebPage(
        url: String,
        cacheMode: Int = WebSettings.LOAD_DEFAULT,
        forceNetwork: Boolean = false
    ) {
        webView.settings.userAgentString = browserUserAgent()
        webView.settings.cacheMode = cacheMode
        val bypass = forceNetwork || shouldBypassCacheForLoad()
        webView.loadUrl(url, browserHeaders(bypass))
    }

    private fun applyPageFixes(view: WebView) {
        view.evaluateJavascript(PAGE_FIX_JS, null)
        PAGE_FIX_DELAYS_MS.forEach { delay ->
            handler.postDelayed({ view.evaluateJavascript(PAGE_FIX_JS, null) }, delay)
        }
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
            prefs.edit { putString(KEY_LAST_URL, url) }
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

    private fun visibleSettingsMenuItems(): List<SettingsMenuItem> {
        val items = mutableListOf(
            SettingsMenuItem.WIFI,
            SettingsMenuItem.UPDATE,
            SettingsMenuItem.AUTO_UPDATE
        )
        if (UpdatePreferences.isAutoUpdateEnabled(this)) {
            items.add(SettingsMenuItem.AUTO_UPDATE_TIME)
        }
        items.add(SettingsMenuItem.WEBVIEW_UPDATE)
        return items
    }

    private fun cycleSettingsMenu(forward: Boolean) {
        val items = visibleSettingsMenuItems()
        val currentIndex = items.indexOf(settingsMenuSelection).coerceAtLeast(0)
        val nextIndex = if (forward) {
            (currentIndex + 1) % items.size
        } else {
            (currentIndex - 1 + items.size) % items.size
        }
        settingsMenuSelection = items[nextIndex]
        updateSettingsMenuText()
    }

    private fun menuLine(item: SettingsMenuItem, label: String): String =
        if (settingsMenuSelection == item) "▶  $label" else "    $label"

    private fun updateSettingsMenuText() {
        val autoEnabled = UpdatePreferences.isAutoUpdateEnabled(this)
        val autoLabel = if (autoEnabled) "Автообновление: Вкл" else "Автообновление: Выкл"
        val timeLabel = if (settingsTimeEditing) {
            "Время: ${UpdatePreferences.formatCheckTime(this)} (изменение)"
        } else {
            "Время: ${UpdatePreferences.formatCheckTime(this)}"
        }
        val webViewLine = getString(
            R.string.webview_menu_line,
            WebViewSupport.describeCurrentProvider(this)
        )

        settingsMenuText.text = buildString {
            appendLine("Меню")
            appendLine()
            appendLine(menuLine(SettingsMenuItem.WIFI, "Wi‑Fi"))
            appendLine(menuLine(SettingsMenuItem.UPDATE, "Проверить обновление"))
            appendLine(menuLine(SettingsMenuItem.AUTO_UPDATE, autoLabel))
            if (autoEnabled) {
                appendLine(menuLine(SettingsMenuItem.AUTO_UPDATE_TIME, timeLabel))
            }
            appendLine(menuLine(SettingsMenuItem.WEBVIEW_UPDATE, webViewLine))
            appendLine()
            append(
                if (settingsTimeEditing) {
                    "←→ часы   ↑↓ минуты   OK — сохранить   Назад — отмена"
                } else {
                    "↑↓ — выбор   OK — действие   Назад — закрыть"
                }
            )
        }
    }

    private fun showSettingsMenu() {
        if (settingsMenuVisible) return
        settingsMenuVisible = true
        settingsTimeEditing = false
        settingsMenuSelection = SettingsMenuItem.WIFI
        updateSettingsMenuText()
        settingsMenuOverlay.isVisible = true
        settingsMenuOverlay.requestFocus()
    }

    private fun hideSettingsMenu() {
        if (!settingsMenuVisible) return
        settingsMenuVisible = false
        settingsTimeEditing = false
        settingsMenuOverlay.isVisible = false
        webView.requestFocus()
    }

    private fun applyAutoUpdateSchedule() {
        if (!isUpdateConfigured()) return
        UpdateScheduler.applyFromPrefs(this)
    }

    private fun adjustAutoUpdateHour(delta: Int) {
        val hour = (UpdatePreferences.getCheckHour(this) + delta + 24) % 24
        UpdatePreferences.setCheckTime(this, hour, UpdatePreferences.getCheckMinute(this))
        updateSettingsMenuText()
    }

    private fun adjustAutoUpdateMinute(delta: Int) {
        val minute = (UpdatePreferences.getCheckMinute(this) + delta + 60) % 60
        UpdatePreferences.setCheckTime(this, UpdatePreferences.getCheckHour(this), minute)
        updateSettingsMenuText()
    }

    private fun saveAutoUpdateTime() {
        settingsTimeEditing = false
        applyAutoUpdateSchedule()
        updateSettingsMenuText()
        Toast.makeText(this, "Время проверки: ${UpdatePreferences.formatCheckTime(this)}", Toast.LENGTH_SHORT).show()
    }

    private fun toggleAutoUpdate() {
        val enabled = !UpdatePreferences.isAutoUpdateEnabled(this)
        UpdatePreferences.setAutoUpdateEnabled(this, enabled)
        settingsTimeEditing = false
        if (!enabled && settingsMenuSelection == SettingsMenuItem.AUTO_UPDATE_TIME) {
            settingsMenuSelection = SettingsMenuItem.AUTO_UPDATE
        }
        applyAutoUpdateSchedule()
        updateSettingsMenuText()
        val message = if (enabled) {
            "Автообновление включено (${UpdatePreferences.formatCheckTime(this)})"
        } else {
            "Автообновление выключено"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun activateSettingsMenuSelection() {
        when (settingsMenuSelection) {
            SettingsMenuItem.WIFI -> {
                hideSettingsMenu()
                showWifiMenu()
            }
            SettingsMenuItem.UPDATE -> {
                hideSettingsMenu()
                checkForAppUpdate()
            }
            SettingsMenuItem.AUTO_UPDATE -> toggleAutoUpdate()
            SettingsMenuItem.AUTO_UPDATE_TIME -> {
                settingsTimeEditing = true
                updateSettingsMenuText()
            }
            SettingsMenuItem.WEBVIEW_UPDATE -> {
                hideSettingsMenu()
                WebViewSupport.openUpdater(this)
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

    private fun markUpdateCheckedToday() {
        prefs.edit { putString(KEY_LAST_UPDATE_CHECK_DAY, todayKey()) }
    }

    private fun handleUpdateCheckIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_FORCE_UPDATE_CHECK, false) == true) {
            handler.post { checkForAppUpdate() }
        }
    }

    private fun checkForAppUpdate() {
        if (!isUpdateConfigured() || !isOnline || updateOverlayVisible) return

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
        showUpdateOverlay(getString(R.string.update_check_in_progress))

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
            if (settingsTimeEditing) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        adjustAutoUpdateHour(-1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        adjustAutoUpdateHour(1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        adjustAutoUpdateMinute(1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        adjustAutoUpdateMinute(-1)
                        return true
                    }
                    in listOf(
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER,
                        KeyEvent.KEYCODE_BUTTON_A
                    ) -> {
                        saveAutoUpdateTime()
                        return true
                    }
                    KeyEvent.KEYCODE_ESCAPE -> {
                        settingsTimeEditing = false
                        updateSettingsMenuText()
                        return true
                    }
                }
            } else {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        cycleSettingsMenu(forward = false)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        cycleSettingsMenu(forward = true)
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
                    KeyEvent.KEYCODE_ESCAPE -> {
                        hideSettingsMenu()
                        return true
                    }
                }
            }
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
            checkForAppUpdate()
            return true
        }

        if (isConfirmKey(event.keyCode) && wifiMenuVisible) {
            openWifiSettings()
            return true
        }

        return super.dispatchKeyEvent(event)
    }

}
