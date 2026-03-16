package com.example.timepray


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_STARTED_FROM_BOOT = "com.example.timepray.extra.STARTED_FROM_BOOT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {

                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    putExtra(EXTRA_STARTED_FROM_BOOT, true)
                    // создаём новый таск, очищаем старый
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }

                try {
                    context.startActivity(launchIntent)
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Не удалось запустить MainActivity после загрузки", e)
                }
            }
        }
    }
}
