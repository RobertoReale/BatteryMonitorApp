package com.example.batteryalert

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters


/**
 * BatteryMonitorRestartWorker restarts BatteryMonitorService if it terminates unexpectedly.
 *
 * Implements a fallback mechanism using WorkManager.
 */
class BatteryMonitorRestartWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    override fun doWork(): Result {
        return try {
            val prefs = applicationContext.getSharedPreferences("BatteryMonitorPrefs", Context.MODE_PRIVATE)

            // Check if a shutdown occurred and restore history
            if (prefs.getBoolean("shutdownOccurred", false)) {
                Log.d("BatteryMonitorRestartWorker", "Restoring shutdown record.")
                PredictionLearning.getInstance(applicationContext).recordActualShutdown()

                // Clear the shutdown flag after restoring
                prefs.edit().putBoolean("shutdownOccurred", false).apply()
            }

            Log.d("BatteryMonitorRestartWorker", "Restarting BatteryMonitorService...")
            val serviceIntent = Intent(applicationContext, BatteryMonitorService::class.java)
            ContextCompat.startForegroundService(applicationContext, serviceIntent)

            Result.success()
        } catch (e: Exception) {
            Log.e("BatteryMonitorRestartWorker", "Failed to restart service", e)
            Result.failure()
        }
    }
}
