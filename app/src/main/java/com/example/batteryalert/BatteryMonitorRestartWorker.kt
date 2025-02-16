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
