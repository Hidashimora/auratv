package com.example.timepray.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.timepray.MainActivity

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        try {
            context.startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e("UpdateReceiver", "Не удалось перезапустить приложение после обновления", e)
        }
    }
}
