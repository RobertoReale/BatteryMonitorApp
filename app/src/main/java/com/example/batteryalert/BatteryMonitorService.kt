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

    private lateinit var predictionLearning: PredictionLearning

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("BatteryMonitorService", "Battery broadcast received in service")
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                val temperatureTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

                val batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else -1
                val temperature = if (temperatureTenths != -1) temperatureTenths / 10.0 else -1.0
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                Log.d("BatteryMonitorService", "Battery update - Level: $batteryPct%, " +
                        "Voltage: $voltage, Temp: $temperature, Charging: $isCharging")

                updateBatteryStatus(batteryPct, temperature, voltage, isCharging)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BatteryMonitorService", "Service started via onStartCommand")
        return START_STICKY
    }

    override fun onCreate() {
        try {
            super.onCreate()
            Log.d("BatteryMonitorService", "onCreate started")

            // Mark service as running
            getSharedPreferences("BatteryMonitorPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("isServiceRunning", true)
                .apply()

            estimator = ImprovedBatteryCycleEstimator.getInstance(this)
            predictionLearning = PredictionLearning.getInstance(this)
            powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            createNotificationChannel()
            registerBatteryReceiver()

            // Start the service in the foreground with an initial notification
            // Ensure notification is created before starting foreground
            val notification = buildNotification()
            Log.d("BatteryMonitorService", "Starting foreground service")
            startForeground(Constants.NOTIFICATION_ID, notification)
            Log.d("BatteryMonitorService", "Service started in foreground")
        } catch (e: Exception) {
            Log.e("BatteryMonitorService", "Error in onCreate", e)
            getSharedPreferences("BatteryMonitorPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("isServiceRunning", false)
                .apply()
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
            Log.d("BatteryMonitorService", "Updating battery status")
            estimator.updateBatteryStatus(batteryPct, temperature, voltage, isCharging)

            // Add these lines to detect critical battery conditions
            if (voltage <= Constants.MIN_VOLTAGE || batteryPct <= 1) {
                Log.d("BatteryMonitorService", "Critical battery condition detected")
                predictionLearning.recordActualShutdown()
            }

            val prediction = estimator.predictTimeToShutdown()
            Log.d("BatteryMonitorService", "Prediction: $prediction")
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
        val batterySafe = batteryPct > Constants.LOW_BATTERY_PERCENTAGE &&
                voltage >= Constants.LOW_VOLTAGE_THRESHOLD
        val prediction = estimator.predictTimeToShutdown()

        if (isCharging || batterySafe ||
            prediction.confidence == ImprovedBatteryCycleEstimator.PredictionConfidence.HIGH) {
            if (isCountdownActive) {
                predictionLearning.recordWarningCancelled()
            }
            Log.d("BatteryMonitorService", "Battery is stable, stopping service.")
            stopSelf()
        }
    }

    private fun startShutdownCountdown() {
        isCountdownActive = true
        countdownJob?.cancel()

        // Record the start of the warning
        val prediction = estimator.predictTimeToShutdown()
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val voltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val temperatureTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val temperature = temperatureTenths / 10.0
        val level = batteryIntent?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) level * 100 / scale else -1
        } ?: -1

        predictionLearning.recordWarningStart(
            prediction.minutesLeft,
            voltage,
            temperature,
            level
        )

        var acquiredWakeLock = false
        try {
            // Acquire the partial WakeLock only for this critical countdown
            if (wakeLock?.isHeld != true) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "BatteryAlert:ShutdownMonitorCountdown"
                ).apply {
                    acquire(10*60*1000L /*10 minutes*/)
                }
                acquiredWakeLock = true
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
                        break
                    }

                    delay(1000)
                    secondsLeft--
                }
                isCountdownActive = false
            }
        } catch (e: Exception) {
            Log.e("BatteryMonitorService", "Error in shutdown countdown", e)
            isCountdownActive = false
            throw e // Re-throw to ensure we don't swallow the exception
        } finally {
            // Only release if we acquired it in this method
            if (acquiredWakeLock && wakeLock?.isHeld == true) {
                try {
                    wakeLock?.release()
                } catch (e: Exception) {
                    Log.e("BatteryMonitorService", "Error releasing WakeLock", e)
                }
                wakeLock = null
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
        Log.d("BatteryMonitorService", "Registering battery receiver in service")
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
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
        try {
            super.onDestroy()
            serviceJob.cancel()
            countdownJob?.cancel()

            wakeLock?.let {
                try {
                    if (it.isHeld) {
                        it.release()
                    } else {
                        // No action needed if lock isn't held
                        Log.d("BatteryMonitorService", "WakeLock not held, no need to release")
                    }
                } catch (e: Exception) {
                    Log.e("BatteryMonitorService", "Error releasing WakeLock", e)
                }
            }

            try {
                unregisterReceiver(batteryReceiver)
            } catch (e: Exception) {
                Log.e("BatteryMonitorService", "Error unregistering receiver", e)
            }

            // Mark service as not running
            getSharedPreferences("BatteryMonitorPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("isServiceRunning", false)
                .apply()

        } catch (e: Exception) {
            Log.e("BatteryMonitorService", "Error in onDestroy", e)
        }
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
