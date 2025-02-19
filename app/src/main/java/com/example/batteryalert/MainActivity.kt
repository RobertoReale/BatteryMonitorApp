package com.example.batteryalert

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.android.material.card.MaterialCardView
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val viewModel: BatteryViewModel by viewModels()
    private lateinit var cycleCountTextView: TextView
    private lateinit var predictionTextView: TextView
    private lateinit var confidenceIndicator: MaterialCardView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        Log.d("MainActivity", "Permission result: $isGranted")
        if (isGranted) {
            Log.d("MainActivity", "Permission granted, setting up components")
            setupComponents()
            startMonitoringService()
        } else {
            Toast.makeText(this, "Permissions required for alerts", Toast.LENGTH_LONG).show()
        }
    }

    private val multiplePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d("MainActivity", "Permission results: $permissions")
        // If all permissions are granted
        if (permissions.all { it.value }) {
            Log.d("MainActivity", "All permissions granted, initializing app")
            setupComponents()
            startMonitoringService()
        } else {
            Log.d("MainActivity", "Some permissions denied")
            Toast.makeText(this, "Permissions required for alerts", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissions = arrayOf(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.WAKE_LOCK
            )

            val permissionsToRequest = permissions.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()

            if (permissionsToRequest.isNotEmpty()) {
                Log.d("MainActivity", "Requesting permissions: ${permissionsToRequest.joinToString()}")
                multiplePermissionLauncher.launch(permissionsToRequest)
            } else {
                Log.d("MainActivity", "All permissions already granted")
                setupComponents()
                startMonitoringService()
            }
        } else {
            Log.d("MainActivity", "No permissions needed for this Android version")
            setupComponents()
            startMonitoringService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            Log.d("MainActivity", "onCreate started")
            setContentView(R.layout.activity_main)

            createNotificationChannels()

            // Initialize AlarmScheduler
            AlarmScheduler.scheduleRepeatingAlarm(this)

            // Always initialize views
            initializeViews()
            Log.d("MainActivity", "Views initialized")

            // Check and request permissions if needed
            if (hasRequiredPermissions()) {
                Log.d("MainActivity", "Has required permissions, setting up components")
                setupComponents()
                // Automatically start monitoring if permissions are already granted
                startMonitoringService()
            } else {
                Log.d("MainActivity", "Requesting permissions")
                checkRequiredPermissions()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in onCreate", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Channel for normal notifications
        val regularChannel = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ID,
            Constants.NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableVibration(true)
            enableLights(true)
            description = "Regular battery monitoring alerts"
        }

        // Critical Notification Channel
        val criticalChannel = NotificationChannel(
            Constants.CRITICAL_NOTIFICATION_CHANNEL_ID,
            Constants.CRITICAL_NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            enableLights(true)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            description = "Critical battery alerts that need immediate attention"
        }

        notificationManager.createNotificationChannel(regularChannel)
        notificationManager.createNotificationChannel(criticalChannel)
    }

    private fun showPredictionHistory() {
        val predictionLearning = PredictionLearning.getInstance(this)
        val history = predictionLearning.getWarningHistory()

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Prediction History")

        val historyText = StringBuilder()
        history.forEach { outcome ->
            val warning = outcome.warning
            val timestamp = java.text.SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
                .format(Date(warning.startTime))

            historyText.append("📅 $timestamp\n")
            historyText.append("Predicted: ${warning.predictedMinutes.toInt()} min\n")
            historyText.append("Battery: ${warning.batteryLevel}%, ${warning.voltage}mV\n")

            when {
                outcome.wasCancelled ->
                    historyText.append("Result: ❌ False Alarm\n")
                outcome.actualShutdownTime != null -> {
                    val actualMinutes =
                        (outcome.actualShutdownTime - warning.startTime) / (1000.0 * 60.0)
                    historyText.append("Result: ⚡ Shutdown after ${actualMinutes.toInt()} min\n")
                }
                else ->
                    historyText.append("Result: ❓ Unknown\n")
            }
            historyText.append("\n")
        }

        if (history.isEmpty()) {
            historyText.append("No prediction history available yet.")
        }

        builder.setMessage(historyText.toString())
        builder.setPositiveButton("OK", null)
        builder.show()
    }

    // New method to encapsulate all component setup
    private fun setupComponents() {
        Log.d("MainActivity", "Setting up components")
        setupClickListeners()
        observeViewModel()
        setupWorkManager()
        showPredictionHistory()
    }

    // Add direct battery receiver registration
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("MainActivity", "Battery broadcast received")
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else -1
                Log.d("MainActivity", "Battery level: $batteryPct%")
            }
        }
    }

    private fun registerBatteryReceiver() {
        Log.d("MainActivity", "Registering battery receiver")
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering receiver", e)
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(android.Manifest.permission.WAKE_LOCK) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun initializeViews() {
        cycleCountTextView = findViewById(R.id.cycleCountTextView)
        predictionTextView = findViewById(R.id.minutesLeftTextView)
        confidenceIndicator = findViewById(R.id.confidenceIndicator)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
    }

    private fun setupClickListeners() {
        startButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED) {
                    startMonitoringService()
                } else {
                    requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                startMonitoringService()
            }
        }

        stopButton.setOnClickListener {
            stopMonitoringService()
        }

        findViewById<Button>(R.id.viewHistoryButton).setOnClickListener {
            showPredictionHistory()
        }
    }

    private fun observeViewModel() {
        Log.d("MainActivity", "Setting up ViewModel observers")
        viewModel.prediction.observe(this) { prediction ->
            Log.d("MainActivity", "Received prediction update: $prediction")
            val minutesLeft = prediction?.minutesLeft?.toInt() ?: -1

            val displayMinutes = when {
                minutesLeft <= 0 -> "N/A"
                minutesLeft > 1440 -> "> 24h"
                minutesLeft >= 60 -> "${minutesLeft / 60}h ${minutesLeft % 60}min"
                else -> "$minutesLeft min"
            }

            predictionTextView.text = getString(R.string.minutes_left_format, displayMinutes)
            updateConfidenceIndicator(prediction.confidence)
        }
    }

    private fun updateConfidenceIndicator(confidence: ImprovedBatteryCycleEstimator.PredictionConfidence) {
        val (backgroundColor, visibility) = when (confidence) {
            ImprovedBatteryCycleEstimator.PredictionConfidence.HIGH ->
                R.color.confidence_high to View.VISIBLE
            ImprovedBatteryCycleEstimator.PredictionConfidence.MEDIUM ->
                R.color.confidence_medium to View.VISIBLE
            ImprovedBatteryCycleEstimator.PredictionConfidence.LOW ->
                R.color.confidence_low to View.VISIBLE
            ImprovedBatteryCycleEstimator.PredictionConfidence.CHARGING ->
                R.color.confidence_charging to View.VISIBLE
            ImprovedBatteryCycleEstimator.PredictionConfidence.INSUFFICIENT_DATA ->
                R.color.confidence_insufficient to View.VISIBLE
        }

        confidenceIndicator.setCardBackgroundColor(
            ContextCompat.getColor(this, backgroundColor)
        )
        confidenceIndicator.visibility = visibility
    }

    private var isReceiverRegistered = false

    private fun startMonitoringService() {
        Log.d("MainActivity", "Starting monitoring service")
        val serviceIntent = Intent(this, BatteryMonitorService::class.java)
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            if (!isReceiverRegistered) {
                registerBatteryReceiver()
                isReceiverRegistered = true
            }
            updateButtonStates(isMonitoring = true)
            Log.d("MainActivity", "Service started successfully")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting service", e)
            Toast.makeText(this, "Error starting service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopMonitoringService() {
        Log.d("MainActivity", "Stopping monitoring service")
        try {
            stopService(Intent(this, BatteryMonitorService::class.java))
            if (isReceiverRegistered) {
                try {
                    unregisterReceiver(batteryReceiver)
                    isReceiverRegistered = false
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error unregistering receiver", e)
                }
            }
            updateButtonStates(isMonitoring = false)
            Log.d("MainActivity", "Service stopped successfully")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error stopping service", e)
            Toast.makeText(this, "Error stopping service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateButtonStates(isMonitoring: Boolean) {
        startButton.isEnabled = !isMonitoring
        stopButton.isEnabled = isMonitoring
    }

    private fun setupWorkManager() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val restartWorkerRequest = PeriodicWorkRequestBuilder<BatteryMonitorRestartWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORKER_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            restartWorkerRequest
        )
    }

    companion object {
        private const val WORKER_NAME = "BatteryMonitorRestart"
    }
}