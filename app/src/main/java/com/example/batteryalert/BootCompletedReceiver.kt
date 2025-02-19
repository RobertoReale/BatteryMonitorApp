package com.example.batteryalert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("BootCompletedReceiver", "Received action: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d("BootCompletedReceiver", "Device booted or app updated, starting service")
                val prefs = context.getSharedPreferences("BatteryMonitorPrefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean("shutdownOccurred", false)) {
                    Log.d("BootCompletedReceiver", "Phone shut down previously. Restoring data.")
                    PredictionLearning.getInstance(context).recordActualShutdown()

                    // Clear shutdown flag after restoring
                    prefs.edit().putBoolean("shutdownOccurred", false).apply()
                }
                val serviceIntent = Intent(context, BatteryMonitorService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)

                // Also program an alarm for security
                AlarmScheduler.scheduleRepeatingAlarm(context)
            }
        }
    }
}