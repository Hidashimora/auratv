package com.example.timepray.update

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GitHubUpdateChecker {

    private const val USER_AGENT = "TimePray-Updater"

    fun fetchLatestUpdate(owner: String, repo: String): UpdateInfo? {
        if (owner.isBlank() || repo.isBlank()) return null

        val release = httpGet("https://api.github.com/repos/$owner/$repo/releases/latest")
            ?.let(::JSONObject) ?: return null

        val assets = release.optJSONArray("assets") ?: JSONArray()
        val apkUrl = findAssetUrl(assets, ".apk") ?: return null
        val versionJsonUrl = findAssetUrl(assets, "version.json")

        val versionCode: Int
        val versionName: String

        if (versionJsonUrl != null) {
            val versionJson = httpGet(versionJsonUrl)?.let(::JSONObject) ?: return null
            versionCode = versionJson.optInt("versionCode", -1)
            versionName = versionJson.optString("versionName", release.optString("tag_name", ""))
            if (versionCode < 0) return null
        } else {
            val tag = release.optString("tag_name", "").removePrefix("v").trim()
            versionCode = tag.toIntOrNull() ?: return null
            versionName = tag
        }

        val notes = release.optString("body", "").trim()
        return UpdateInfo(versionCode, versionName, apkUrl, notes)
    }

    private fun findAssetUrl(assets: JSONArray, suffix: String): String? {
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            if (name.endsWith(suffix, ignoreCase = true)) {
                return asset.optString("browser_download_url", null)
            }
        }
        return null
    }

    private fun httpGet(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }

        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
