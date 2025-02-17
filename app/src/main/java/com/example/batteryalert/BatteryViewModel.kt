package com.example.batteryalert

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import android.content.Context
import android.os.PowerManager

class BatteryViewModel(application: Application) : AndroidViewModel(application) {

    private val _estimatedCycles = MutableLiveData<Double>()

    private val _prediction = MutableLiveData<ImprovedBatteryCycleEstimator.ShutdownPrediction>()
    val prediction: LiveData<ImprovedBatteryCycleEstimator.ShutdownPrediction> get() = _prediction

    private val estimator = ImprovedBatteryCycleEstimator.getInstance(application)
    private val viewModelJob = SupervisorJob()
    private val viewModelScope = CoroutineScope(Dispatchers.IO + viewModelJob)

    // Track update frequency dynamically based on battery status
    private var updateInterval = DEFAULT_UPDATE_INTERVAL
    private var updateJob: Job? = null

    init {
        startPeriodicUpdates()
    }

    private fun startPeriodicUpdates() {
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            while (isActive) {
                updateBatteryData()
                // Sleep for the current interval
                delay(updateInterval)
            }
        }
    }

    private fun updateBatteryData() {
        _estimatedCycles.postValue(estimator.getEstimatedCycles())
        val currentPrediction = estimator.predictTimeToShutdown()
        _prediction.postValue(currentPrediction)
        adjustUpdateInterval(currentPrediction)
    }

    /**
     * Dynamically adjusts update frequency based on battery status and prediction confidence
     */
    private fun adjustUpdateInterval(
        currentPrediction: ImprovedBatteryCycleEstimator.ShutdownPrediction
    ) {
        val drainRate = estimator.getWeightedDrainRate()
        val powerManager = getApplication<Application>()
            .getSystemService(Context.POWER_SERVICE) as PowerManager
        val isPowerSaveMode = powerManager.isPowerSaveMode

        val newInterval = when {
            isPowerSaveMode -> {
                when {
                    currentPrediction.minutesLeft < 10.0 -> CRITICAL_UPDATE_INTERVAL
                    else -> DEFAULT_UPDATE_INTERVAL * 2  // Double interval in power save
                }
            }
            currentPrediction.minutesLeft < 10.0 -> CRITICAL_UPDATE_INTERVAL
            drainRate > 3.0 -> 2000L
            drainRate > 1.5 -> 3000L
            currentPrediction.confidence ==
                    ImprovedBatteryCycleEstimator.PredictionConfidence.LOW ->
                LOW_CONFIDENCE_UPDATE_INTERVAL
            else -> DEFAULT_UPDATE_INTERVAL
        }

        val safeInterval = newInterval.coerceAtLeast(500)
        if (safeInterval != updateInterval) {
            updateInterval = safeInterval
            updateJob?.cancel()
            startPeriodicUpdates()
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelJob.cancel()
        updateJob?.cancel()
    }

    companion object {
        private const val DEFAULT_UPDATE_INTERVAL = 5000L      // 5 seconds
        private const val CRITICAL_UPDATE_INTERVAL = 1000L     // 1 second
        private const val LOW_CONFIDENCE_UPDATE_INTERVAL = 2000L // 2 seconds
    }
}
