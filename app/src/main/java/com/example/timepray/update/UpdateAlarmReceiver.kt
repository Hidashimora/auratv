package com.example.timepray.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.timepray.MainActivity

class UpdateAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        UpdateScheduler.applyFromPrefs(context)
        if (!UpdatePreferences.isAutoUpdateEnabled(context)) return

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_FORCE_UPDATE_CHECK, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(launchIntent)
    }
}
