package com.example.batteryalert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.Locale

class BatteryMonitorService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var isCountdownActive = false
    private var countdownJob: Job? = null

    private lateinit var estimator: ImprovedBatteryCycleEstimator

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                val temperatureTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

                val batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else -1
                val temperature = if (temperatureTenths != -1) temperatureTenths / 10.0 else -1.0
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                updateBatteryStatus(batteryPct, temperature, voltage, isCharging)
            }
        }
    }

    override fun onCreate() {
        try {
            super.onCreate()
            estimator = ImprovedBatteryCycleEstimator.getInstance(this)
            powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            createNotificationChannel()
            registerBatteryReceiver()

            // Start the service in the foreground with an initial notification
            startForeground(Constants.NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e("BatteryMonitorService", "Error in onCreate", e)
            stopSelf()
        }
    }

    private fun updateBatteryStatus(
        batteryPct: Int,
        temperature: Double,
        voltage: Int,
        isCharging: Boolean
    ) {
        try {
            estimator.updateBatteryStatus(batteryPct, temperature, voltage, isCharging)

            val prediction = estimator.predictTimeToShutdown()
            updateNotificationWithPrediction(prediction)

            checkShutdownConditions(prediction, voltage, batteryPct, temperature, isCharging)
        } catch (e: Exception) {
            Log.e("BatteryMonitorService", "Error updating battery status", e)
        }
    }

    private fun checkShutdownConditions(
        prediction: ImprovedBatteryCycleEstimator.ShutdownPrediction,
        voltage: Int,
        batteryPct: Int,
        temperature: Double,
        isCharging: Boolean
    ) {
        if (isCountdownActive) return

        // If it's charging or battery recovers, we can stop the service
        stopServiceIfSafe(batteryPct, voltage, isCharging)

        // If the service is still running after that check, see if we should do countdown
        val adjustedLowVoltageThreshold = if (temperature > Constants.HIGH_TEMPERATURE_THRESHOLD) {
            Constants.LOW_VOLTAGE_THRESHOLD + 200
        } else {
            Constants.LOW_VOLTAGE_THRESHOLD
        }

        val shouldStartCountdown = when {
            prediction.confidence != ImprovedBatteryCycleEstimator.PredictionConfidence.CHARGING &&
                    prediction.minutesLeft < 10.0 -> true
            voltage <= Constants.MIN_VOLTAGE -> true
            voltage < adjustedLowVoltageThreshold && batteryPct <= Constants.LOW_BATTERY_PERCENTAGE -> true
            else -> false
        }

        if (shouldStartCountdown) {
            startShutdownCountdown()
        }
    }

    /**
     * If battery is above thresholds or device is charging, we no longer need
     * the foreground service. This helps save resources.
     */
    private fun stopServiceIfSafe(
        batteryPct: Int,
        voltage: Int,
        isCharging: Boolean
    ) {
        val batterySafe = batteryPct > Constants.LOW_BATTERY_PERCENTAGE && voltage >= Constants.LOW_VOLTAGE_THRESHOLD
        val prediction = estimator.predictTimeToShutdown()

        if (isCharging || batterySafe || prediction.confidence == ImprovedBatteryCycleEstimator.PredictionConfidence.HIGH) {
            Log.d("BatteryMonitorService", "Battery is stable, stopping service.")
            stopSelf()
        }
    }

    private fun startShutdownCountdown() {
        isCountdownActive = true
        countdownJob?.cancel()

        // Acquire the partial WakeLock only for this critical countdown
        if (wakeLock?.isHeld != true) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BatteryAlert:ShutdownMonitorCountdown"
            ).apply {
                acquire(10*60*1000L /*10 minutes*/) // acquire with no specific timeout, we'll release once we’re done
            }
        }

        countdownJob = serviceScope.launch {
            var secondsLeft = 30

            while (secondsLeft >= 0 && isActive) {
                val urgencyLevel = when {
                    secondsLeft <= 5 -> NotificationManager.IMPORTANCE_HIGH
                    secondsLeft <= 15 -> NotificationManager.IMPORTANCE_DEFAULT
                    else -> NotificationManager.IMPORTANCE_LOW
                }

                updateCountdownNotification(secondsLeft, urgencyLevel)

                if (secondsLeft == 0) {
                    updateNotification(
                        "Device Shutting Down",
                        "Shutdown imminent - please save your work immediately",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                    // Optionally call stopSelf() if you want the service gone afterwards:
                    // stopSelf()
                    break
                }

                delay(1000)
                secondsLeft--
            }
            isCountdownActive = false

            // Release the WakeLock once the countdown ends
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
    }

    private fun updateCountdownNotification(secondsLeft: Int, importance: Int) {
        val title = "Imminent Shutdown Warning"
        val message = String.format(
            Locale.getDefault(),
            "CRITICAL: Device shutting down in %d seconds!",
            secondsLeft
        )
        updateNotification(title, message, importance)
    }

    private var lastNotificationText: String? = null

    private fun updateNotificationWithPrediction(prediction: ImprovedBatteryCycleEstimator.ShutdownPrediction) {
        val newMessage = when {
            prediction.confidence == ImprovedBatteryCycleEstimator.PredictionConfidence.CHARGING -> "Device is charging"
            prediction.confidence == ImprovedBatteryCycleEstimator.PredictionConfidence.INSUFFICIENT_DATA -> "Gathering battery data..."
            prediction.minutesLeft == Double.POSITIVE_INFINITY -> "Battery level stable"
            else -> String.format(Locale.getDefault(), "%.1f minutes until shutdown (%s)", prediction.minutesLeft, prediction.confidence.toString().lowercase())
        }

        // Avoid redundant updates
        if (newMessage == lastNotificationText) return
        lastNotificationText = newMessage

        updateNotification("Battery Monitor Active", newMessage, NotificationManager.IMPORTANCE_LOW)
    }

    private fun updateNotification(title: String, text: String, importance: Int) {
        val notification = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(
                when (importance) {
                    NotificationManager.IMPORTANCE_HIGH -> NotificationCompat.PRIORITY_HIGH
                    NotificationManager.IMPORTANCE_DEFAULT -> NotificationCompat.PRIORITY_DEFAULT
                    else -> NotificationCompat.PRIORITY_LOW
                }
            )
            .setOnlyAlertOnce(importance != NotificationManager.IMPORTANCE_HIGH)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(Constants.NOTIFICATION_ID, notification)
    }

    private fun registerBatteryReceiver() {
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ID,
            Constants.NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            enableLights(true)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        countdownJob?.cancel()

        wakeLock?.let {
            if (it.isHeld) it.release()
        }

        unregisterReceiver(batteryReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Battery Monitor Active")
            .setContentText("Initializing battery monitoring...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
