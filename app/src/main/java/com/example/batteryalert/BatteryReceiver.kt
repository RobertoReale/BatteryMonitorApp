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
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            estimator.updateBatteryStatus(batteryPct, temperature, voltage, isCharging)

            val prediction = estimator.predictTimeToShutdown()

            val needsMonitoring = batteryPct <= Constants.LOW_BATTERY_PERCENTAGE || voltage < Constants.LOW_VOLTAGE_THRESHOLD || prediction.minutesLeft < 10.0

            if (needsMonitoring) {
                context.startForegroundService(Intent(context, BatteryMonitorService::class.java))
            } else if (isCharging) {
                context.stopService(Intent(context, BatteryMonitorService::class.java))
            }
        }
    }
}
