package com.example.timepray

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.WebView
import android.widget.Toast
import androidx.core.net.toUri

object WebViewSupport {

    fun describeCurrentProvider(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return context.getString(R.string.webview_unknown_legacy)
        }
        val pkg = WebView.getCurrentWebViewPackage() ?: return context.getString(R.string.webview_not_found)
        return "${pkg.versionName ?: "?"} — ${pkg.packageName}"
    }

    fun openUpdater(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(context, R.string.webview_update_legacy, Toast.LENGTH_LONG).show()
            return
        }
        val pkg = WebView.getCurrentWebViewPackage()
        if (pkg == null) {
            Toast.makeText(context, R.string.webview_not_found, Toast.LENGTH_LONG).show()
            return
        }
        val packageName = pkg.packageName
        val marketIntent = Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri())
        val webIntent = Intent(
            Intent.ACTION_VIEW,
            "https://play.google.com/store/apps/details?id=$packageName".toUri()
        )
        try {
            context.startActivity(marketIntent)
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(webIntent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(
                    context,
                    context.getString(R.string.webview_update_failed, packageName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
