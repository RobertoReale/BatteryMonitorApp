package com.example.batteryalert

import android.content.Intent
import android.content.pm.PackageManager
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
        if (isGranted) {
            startMonitoringService()
        } else {
            Toast.makeText(this, "Permissions required for alerts", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.WAKE_LOCK) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.POST_NOTIFICATIONS,
                        android.Manifest.permission.WAKE_LOCK
                    ),
                    PERMISSION_REQUEST_CODE
                )
                return
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)

            // Initialize views first
            initializeViews()

            // Check permissions before doing anything else
            checkRequiredPermissions()

            // Only setup other components if permissions are granted
            if (hasRequiredPermissions()) {
                setupClickListeners()
                observeViewModel()
                setupWorkManager()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in onCreate", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
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
    }

    private fun observeViewModel() {
        viewModel.prediction.observe(this) { prediction ->
            val minutesLeft = prediction?.minutesLeft?.toInt() ?: -1

            val displayMinutes = when {
                minutesLeft <= 0 -> "N/A"
                minutesLeft > 1440 -> "> 24h"
                minutesLeft >= 60 -> "${minutesLeft / 60}h ${minutesLeft % 60}min" // Convert to hours and minutes
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

    private fun startMonitoringService() {
        val serviceIntent = Intent(this, BatteryMonitorService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        updateButtonStates(isMonitoring = true)
    }

    private fun stopMonitoringService() {
        stopService(Intent(this, BatteryMonitorService::class.java))
        updateButtonStates(isMonitoring = false)
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
        private const val PERMISSION_REQUEST_CODE = 123
    }
}