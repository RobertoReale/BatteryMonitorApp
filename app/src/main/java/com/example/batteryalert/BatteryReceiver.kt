package com.example.batteryalert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log

class BatteryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) // in mV
            val temperatureTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) // tenths of °C
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val temperature = if (temperatureTenths != -1) temperatureTenths / 10.0 else -1.0

            // Voltage check first
            if (voltage < Constants.MIN_VOLTAGE || voltage > Constants.MAX_VOLTAGE) {
                Log.w("BatteryReceiver", "Voltage ($voltage mV) out of valid range.")
                return
            }

            val estimator = ImprovedBatteryCycleEstimator.getInstance(context)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            estimator.updateBatteryStatus(batteryPct, temperature, voltage, isCharging)

            val prediction = estimator.predictTimeToShutdown()

            val needsMonitoring = batteryPct <= Constants.LOW_BATTERY_PERCENTAGE ||
                    voltage < Constants.LOW_VOLTAGE_THRESHOLD ||
                    prediction.minutesLeft < 10.0

            // Instead of checking running services (deprecated), use SharedPreferences to track service state
            val prefs = context.getSharedPreferences("BatteryMonitorPrefs", Context.MODE_PRIVATE)
            val isServiceRunning = prefs.getBoolean("isServiceRunning", false)

            if (needsMonitoring && !isServiceRunning) {
                Log.d("BatteryReceiver", "Starting BatteryMonitorService - " +
                        "batteryPct: $batteryPct%, voltage: $voltage, " +
                        "prediction: ${prediction.minutesLeft} minutes")
                context.startForegroundService(Intent(context, BatteryMonitorService::class.java))
                prefs.edit().putBoolean("isServiceRunning", true).apply()
            } else if (isCharging && isServiceRunning) {
                Log.d("BatteryReceiver", "Stopping BatteryMonitorService - device is charging")
                context.stopService(Intent(context, BatteryMonitorService::class.java))
                prefs.edit().putBoolean("isServiceRunning", false).apply()
            }
        }
    }
}
