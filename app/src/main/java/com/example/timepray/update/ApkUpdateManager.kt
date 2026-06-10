package com.example.timepray.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class ApkUpdateManager(
    private val context: Context,
    private val githubOwner: String,
    private val githubRepo: String,
    private val currentVersionCode: Int
) {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile
    private var running = false

    fun checkAndInstall(
        onStatus: (String) -> Unit,
        onProgress: (Int) -> Unit,
        onError: (String) -> Unit,
        onInstallStarted: () -> Unit
    ) {
        if (running) return
        running = true

        executor.execute {
            try {
                onStatus("Проверка обновлений...")
                val update = GitHubUpdateChecker.fetchLatestUpdate(githubOwner, githubRepo)
                    ?: run {
                        onError("Обновления не найдены")
                        return@execute
                    }

                if (update.versionCode <= currentVersionCode) {
                    onStatus("Установлена актуальная версия")
                    return@execute
                }

                onStatus("Загрузка версии ${update.versionName}...")
                val apkFile = downloadApk(update.apkUrl, onProgress)
                    ?: run {
                        onError("Не удалось скачать обновление")
                        return@execute
                    }

                onStatus("Установка...")
                onInstallStarted()
                installApk(apkFile)
            } catch (e: Exception) {
                onError(e.message ?: "Ошибка обновления")
            } finally {
                running = false
            }
        }
    }

    fun canInstallPackages(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                context.packageManager.canRequestPackageInstalls()
    }

    fun createInstallPermissionIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun downloadApk(url: String, onProgress: (Int) -> Unit): File? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "TimePray-Updater")
        }

        return try {
            if (connection.responseCode !in 200..299) return null

            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updatesDir, "update.apk")

            connection.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            onProgress(100)
            apkFile
        } finally {
            connection.disconnect()
        }
    }

    private fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
