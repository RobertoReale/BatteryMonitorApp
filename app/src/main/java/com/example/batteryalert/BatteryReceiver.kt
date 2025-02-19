package com.example.batteryalert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log

class BatteryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("BatteryReceiver", "Action received: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BATTERY_LOW -> {
                Log.d("BatteryReceiver", "Battery is critically low!")
                forciblyStartMonitorService(context)
                return
            }
            Intent.ACTION_BATTERY_CHANGED -> {
                // Continua con il codice esistente
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                // Assicurati che il servizio sia in esecuzione quando scollegato dall'alimentazione
                forciblyStartMonitorService(context)
                return
            }
            else -> return
        }

        // Codice esistente per ACTION_BATTERY_CHANGED...
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

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isPowerSaveMode = powerManager.isPowerSaveMode

        val estimator = ImprovedBatteryCycleEstimator.getInstance(context)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        estimator.updateBatteryStatus(batteryPct, temperature, voltage, isCharging, isPowerSaveMode)

        val prediction = estimator.predictTimeToShutdown()

        // Modifica le soglie per essere più aggressive
        val needsMonitoring = batteryPct <= Constants.LOW_BATTERY_PERCENTAGE + 10 ||  // Aumenta la soglia a 30%
                voltage < Constants.LOW_VOLTAGE_THRESHOLD + 200 ||  // Aumenta la soglia a 3400
                prediction.minutesLeft < 20.0  // Aumenta la finestra a 20 minuti

        // Verifica lo stato del servizio e avvialo se necessario
        val prefs = context.getSharedPreferences("BatteryMonitorPrefs", Context.MODE_PRIVATE)
        val isServiceRunning = prefs.getBoolean("isServiceRunning", false)
        val lastAliveTimestamp = prefs.getLong("serviceLastAliveTimestamp", 0)
        val serviceUnresponsive = System.currentTimeMillis() - lastAliveTimestamp > 5 * 60 * 1000

        if ((needsMonitoring && (!isServiceRunning || serviceUnresponsive))) {
            Log.d("BatteryReceiver", "Starting BatteryMonitorService - " +
                    "batteryPct: $batteryPct%, voltage: $voltage, " +
                    "prediction: ${prediction.minutesLeft} minutes")
            context.startForegroundService(Intent(context, BatteryMonitorService::class.java))

            // Assicurati che l'alarm sia programmato
            AlarmScheduler.scheduleRepeatingAlarm(context)

            prefs.edit().putBoolean("isServiceRunning", true).apply()
        } else if (isCharging && isServiceRunning && batteryPct > 50) {
            // Stop only if charging AND battery is above 50%
            Log.d("BatteryReceiver", "Stopping BatteryMonitorService - device is charging with good battery level")
            context.stopService(Intent(context, BatteryMonitorService::class.java))
            prefs.edit().putBoolean("isServiceRunning", false).apply()
        }
    }

    private fun forciblyStartMonitorService(context: Context) {
        try {
            val serviceIntent = Intent(context, BatteryMonitorService::class.java)
            serviceIntent.putExtra("CRITICAL_START", true)
            context.startForegroundService(serviceIntent)

            // Programma l'allarme per sicurezza
            AlarmScheduler.scheduleRepeatingAlarm(context)

            // Aggiorna lo stato
            context.getSharedPreferences("BatteryMonitorPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("isServiceRunning", true)
                .apply()

            Log.d("BatteryReceiver", "Forcibly started monitor service due to critical battery")
        } catch (e: Exception) {
            Log.e("BatteryReceiver", "Failed to start service", e)
        }
    }
}