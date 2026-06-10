package com.example.timepray.update

import android.content.Context

object UpdatePreferences {
    const val PREFS_NAME = "aura_prefs"
    const val KEY_AUTO_UPDATE_ENABLED = "auto_update_enabled"
    const val KEY_AUTO_UPDATE_HOUR = "auto_update_hour"
    const val KEY_AUTO_UPDATE_MINUTE = "auto_update_minute"

    fun isAutoUpdateEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_UPDATE_ENABLED, true)

    fun getCheckHour(context: Context): Int =
        prefs(context).getInt(KEY_AUTO_UPDATE_HOUR, 0).coerceIn(0, 23)

    fun getCheckMinute(context: Context): Int =
        prefs(context).getInt(KEY_AUTO_UPDATE_MINUTE, 0).coerceIn(0, 59)

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_UPDATE_ENABLED, enabled).apply()
    }

    fun setCheckTime(context: Context, hour: Int, minute: Int) {
        prefs(context).edit()
            .putInt(KEY_AUTO_UPDATE_HOUR, hour.coerceIn(0, 23))
            .putInt(KEY_AUTO_UPDATE_MINUTE, minute.coerceIn(0, 59))
            .apply()
    }

    fun formatCheckTime(context: Context): String =
        String.format("%02d:%02d", getCheckHour(context), getCheckMinute(context))

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
