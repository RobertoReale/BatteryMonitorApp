package com.example.batteryalert

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
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

    private var lastBatteryLevel: Int = -1
    private var lastTemperature: Double = 0.0
    private var lastVoltage: Int = 0
    private var isCharging: Boolean = false

    @Volatile
    private var isCountdownActive = false
    private var countdownJob: Job? = null

    private lateinit var estimator: ImprovedBatteryCycleEstimator

    private lateinit var predictionLearning: PredictionLearning

    private val powerSaveModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                val isPowerSaveMode = powerManager.isPowerSaveMode
                Log.d("BatteryMonitorService", "Power saving mode changed to: $isPowerSaveMode")

                // Update battery status with new power save mode state
                if (lastBatteryLevel != -1) {
                    updateBatteryStatus(lastBatteryLevel, lastTemperature, lastVoltage, isCharging)
                }
            }
        }
    }


    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("BatteryMonitorService", "Task removed, ensuring service continues")

        // Persist that we detected the app being removed from recents
        getSharedPreferences("BatteryMonitorPrefs", Context.MODE_PRIVATE)
            .edit()
            .putLong("lastTaskRemovedTime", System.currentTimeMillis())
            .apply()

        // Reschedule our alarm
        AlarmScheduler.scheduleRepeatingAlarm(this)

        // Schedule a restart of our service with proper permission handling
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val restartIntent = Intent(this, BatteryMonitorService::class.java)
            val pendingIntent = PendingIntent.getService(
                this, 1, restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 1000,
                        pendingIntent
                    )
                } else {
                    // Fall back to inexact alarm
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 1000,
                        pendingIntent
                    )
                }
            } else {
                // For older Android versions
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 1000,
                    pendingIntent
                )
            }
        } catch (e: SecurityException) {
            Log.e("BatteryMonitorService", "Cannot schedule exact alarm", e)
            // Fall back to inexact alarm
            try {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val restartIntent = Intent(this, BatteryMonitorService::class.java)
                val pendingIntent = PendingIntent.getService(
                    this, 1, restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 1000,
                    pendingIntent
                )
            } catch (e2: Exception) {
                Log.e("BatteryMonitorService", "Failed to set fallback alarm", e2)
            }
        } catch (e: Exception) {
            Log.e("BatteryMonitorService", "Error in onTaskRemoved", e)
        }
    }

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

        getSharedPreferences("BatteryMonitorPrefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("isServiceRunning", true)
            .putLong("serviceLastAliveTimestamp", System.currentTimeMillis())
            .apply()

        return START_STICKY
    }

    private fun createHighPriorityNotificationChannel() {
        val channelId = Constants.CRITICAL_NOTIFICATION_CHANNEL_ID
        val channelName = Constants.CRITICAL_NOTIFICATION_CHANNEL_NAME

        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            enableLights(true)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            description = "Critical battery alerts"
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    // Add a periodic heartbeat
    private var heartbeatJob: Job? = null

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                // Update the timestamp to indicate that the service is still alive
                getSharedPreferences("BatteryMonitorPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("serviceLastAliveTimestamp", System.currentTimeMillis())
                    .apply()

                delay(2 * 60 * 1000) // Every 2 minutes
            }
        }
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

            createHighPriorityNotificationChannel()

            estimator = ImprovedBatteryCycleEstimator.getInstance(this)
            predictionLearning = PredictionLearning.getInstance(this)
            powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            createNotificationChannel()
            registerBatteryReceiver()

            // Register power save mode receiver
            registerReceiver(
                powerSaveModeReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            )

            // Log initial power save mode state
            val isPowerSaveMode = powerManager.isPowerSaveMode
            Log.d("BatteryMonitorService", "Initial power save mode state: $isPowerSaveMode")

            // Start the service in the foreground with an initial notification
            // Ensure notification is created before starting foreground
            val notification = buildNotification()
            Log.d("BatteryMonitorService", "Starting foreground service")
            startForeground(Constants.NOTIFICATION_ID, notification)
            startHeartbeat()
            Log.d("BatteryMonitorService", "Service started in foreground")

            // Start alarm as backup
            AlarmScheduler.scheduleRepeatingAlarm(this)

            // Request system exemption if possible (it is not decisive, it will be just an attempt)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                        // We do not require ignore batteryOptimization, but we try to improve resilience
                        Log.d("BatteryMonitorService", "Note: App is not exempt from battery optimizations")
                    }
                } catch (e: Exception) {
                    Log.e("BatteryMonitorService", "Error checking battery optimization status", e)
                }
            }

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
        charging: Boolean
    ) {
        try {
            // Store latest values
            lastBatteryLevel = batteryPct
            lastTemperature = temperature
            lastVoltage = voltage
            isCharging = charging

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isPowerSaveMode = powerManager.isPowerSaveMode

            Log.d("BatteryMonitorService",
                "Battery update - Level: $batteryPct%, Voltage: $voltage, " +
                        "Temp: $temperature, Charging: $charging, " +
                        "Power Saving: $isPowerSaveMode")

            estimator.updateBatteryStatus(
                batteryPct,
                temperature,
                voltage,
                charging,
                isPowerSaveMode
            )

            if (voltage <= Constants.MIN_VOLTAGE || batteryPct <= 1) {
                Log.d("BatteryMonitorService", "Critical battery condition detected")
                predictionLearning.recordActualShutdown()

                // Immediately write to SharedPreferences
                getSharedPreferences("BatteryMonitorPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("shutdownOccurred", true)
                    .apply()
            }

            val prediction = estimator.predictTimeToShutdown()
            updateNotificationWithPrediction(prediction)

            checkShutdownConditions(prediction, voltage, batteryPct, temperature, charging)

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

                    updateCountdownNotification(secondsLeft)

                    if (secondsLeft == 0) {
                        predictionLearning.recordActualShutdown()  // Add this line
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

    private fun updateCountdownNotification(secondsLeft: Int) {
        val title = "⚠️ CRITICAL: SHUTDOWN IMMINENT ⚠️"
        val message = String.format(
            Locale.getDefault(),
            "Device will shutdown in %d seconds! Save your work NOW!",
            secondsLeft
        )

        val notification = NotificationCompat.Builder(this, Constants.CRITICAL_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(getPendingIntent(), true) // Full screen intent per Android 10+
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(Constants.CRITICAL_NOTIFICATION_ID, notification)
    }

    private fun getPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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

            // Release wake lock if held
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

            // Unregister receivers
            try {
                unregisterReceiver(batteryReceiver)
            } catch (e: Exception) {
                Log.e("BatteryMonitorService", "Error unregistering battery receiver", e)
            }

            try {
                unregisterReceiver(powerSaveModeReceiver)
            } catch (e: Exception) {
                Log.e("BatteryMonitorService", "Error unregistering power save receiver", e)
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
            .setContentText("Monitoring battery status...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }
}
