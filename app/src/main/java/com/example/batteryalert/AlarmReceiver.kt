package com.example.batteryalert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AlarmReceiver", "Alarm triggered, checking service")

        // Check if the service is running
        val prefs = context.getSharedPreferences("BatteryMonitorPrefs", Context.MODE_PRIVATE)
        val isServiceRunning = prefs.getBoolean("isServiceRunning", false)
        val lastAliveTimestamp = prefs.getLong("serviceLastAliveTimestamp", 0)
        val currentTime = System.currentTimeMillis()

        // If the service is not running or has not updated the timestamp in the last 5 minutes
        if (!isServiceRunning || (currentTime - lastAliveTimestamp) > 5 * 60 * 1000) {
            Log.d("AlarmReceiver", "Service not running or unresponsive, restarting")
            val serviceIntent = Intent(context, BatteryMonitorService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        // Reschedule next alarm
        AlarmScheduler.scheduleRepeatingAlarm(context)
    }
}