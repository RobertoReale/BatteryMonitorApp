package com.example.batteryalert

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp

class ImprovedBatteryCycleEstimator private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var cumulativeDischarge: Double = prefs.getFloat(KEY_CUMULATIVE_DISCHARGE, 0f).toDouble()
    private var estimatedCycles: Double = prefs.getFloat(KEY_ESTIMATED_CYCLES, 0f).toDouble()
    private var previousBatteryLevel: Int = prefs.getInt(KEY_PREVIOUS_LEVEL, -1)

    // New fields for improved prediction
    private var lastTemperature: Double = 0.0
    private var lastVoltage: Int = 0
    private var weightedDrainRate: Double = 0.0
    private val drainRateWindow = mutableListOf<DrainRateRecord>()

    data class BatteryRecord(
        val timestamp: Long,
        val batteryLevel: Int,
        val temperature: Double,
        val voltage: Int,
        val isCharging: Boolean
    )

    data class DrainRateRecord(
        val drainRate: Double,
        val temperature: Double,
        val timestamp: Long
    )

    companion object {
        private const val PREFS_NAME = "BatteryCyclePrefs"
        private const val KEY_CUMULATIVE_DISCHARGE = "cumulativeDischarge"
        private const val KEY_ESTIMATED_CYCLES = "estimatedCycles"
        private const val KEY_PREVIOUS_LEVEL = "previousBatteryLevel"
        private const val KEY_BATTERY_HISTORY = "batteryHistoryJson"
        private const val DRAIN_RATE_WINDOW_SIZE = 20
        private const val SMOOTHING_FACTOR = 0.2
        private const val MAX_HISTORY_SIZE = 200
        private const val RECENT_WEIGHT = 2.0
        private const val TEMPERATURE_WEIGHT = 1.5

        @Volatile
        private var instance: ImprovedBatteryCycleEstimator? = null

        fun getInstance(context: Context): ImprovedBatteryCycleEstimator {
            return instance ?: synchronized(this) {
                instance ?: ImprovedBatteryCycleEstimator(context.applicationContext).also { instance = it }
            }
        }
    }

    fun updateBatteryStatus(
        currentLevel: Int,
        temperature: Double,
        voltage: Int,
        isCharging: Boolean
    ) {
        if (isCharging) {
            // Reset drain rate calculations when charging
            drainRateWindow.clear()
            weightedDrainRate = 0.0
        }

        val now = System.currentTimeMillis()

        // Update cycle estimation
        if (previousBatteryLevel != -1 && currentLevel < previousBatteryLevel) {
            val discharge = (previousBatteryLevel - currentLevel).toDouble()
            cumulativeDischarge += discharge
            val newCycleEstimate = cumulativeDischarge / 100.0
            estimatedCycles = SMOOTHING_FACTOR * newCycleEstimate + (1 - SMOOTHING_FACTOR) * estimatedCycles
        }

        // Update battery history with enhanced data
        val newRecord = BatteryRecord(now, currentLevel, temperature, voltage, isCharging)
        val history = getBatteryHistory().toMutableList()
        history.add(newRecord)
        if (history.size > MAX_HISTORY_SIZE) {
            history.removeAt(0)
        }
        saveBatteryHistory(history)

        // Update drain rate calculations
        if (previousBatteryLevel != -1) {
            calculateDrainRate(currentLevel, temperature, now)
        }

        previousBatteryLevel = currentLevel
        lastTemperature = temperature
        lastVoltage = voltage
        persistState(isCharging)
    }

    private fun calculateDrainRate(currentLevel: Int, temperature: Double, timestamp: Long) {
        val history = getBatteryHistory()
        if (history.size < 2) return

        val recentHistory = history.takeLast(10)
        val drainRate = calculateWeightedDrainRate(recentHistory, temperature)

        drainRateWindow.add(DrainRateRecord(drainRate, temperature, timestamp))
        if (drainRateWindow.size > DRAIN_RATE_WINDOW_SIZE) {
            drainRateWindow.removeAt(0)
        }

        // Log the change in battery level for debugging
        println("Battery Level Changed to: $currentLevel")

        weightedDrainRate = calculateTemperatureAdjustedDrainRate()
    }

    private fun calculateWeightedDrainRate(
        history: List<BatteryRecord>,
        currentTemp: Double
    ): Double {
        if (history.size < 2) return 0.0

        var totalWeight = 0.0
        var weightedSum = 0.0

        for (i in 1 until history.size) {
            val prev = history[i - 1]
            val curr = history[i]

            val timeDiff = (curr.timestamp - prev.timestamp) / 60000.0 // Convert to minutes
            if (timeDiff <= 0 || prev.batteryLevel <= curr.batteryLevel) continue // Ensure valid calculations

            val levelDiff = prev.batteryLevel - curr.batteryLevel
            val instantRate = levelDiff / timeDiff

            val recencyWeight = exp(-0.1 * (history.size - i))
            val tempInfluence = 1.0 + abs(currentTemp - prev.temperature) * 0.02
            val weight = recencyWeight * tempInfluence

            weightedSum += instantRate * weight
            totalWeight += weight
        }

        return if (totalWeight > 0) weightedSum / totalWeight else 0.0
    }

    private fun calculateTemperatureAdjustedDrainRate(): Double {
        if (drainRateWindow.isEmpty()) return 0.0

        var totalWeight = 0.0
        var weightedSum = 0.0

        drainRateWindow.forEachIndexed { index, record ->
            val recencyWeight = RECENT_WEIGHT * (index + 1) / drainRateWindow.size
            val temperatureWeight = TEMPERATURE_WEIGHT * (1 + abs(record.temperature - 25.0) * 0.02)
            val weight = recencyWeight * temperatureWeight

            weightedSum += record.drainRate * weight
            totalWeight += weight
        }

        return if (totalWeight > 0) weightedSum / totalWeight else 0.0
    }

    fun getEstimatedCycles(): Double {
        return estimatedCycles
    }

    private fun getBatteryDrainRate(voltage: Int): Double {
        return when {
            voltage > 4000 -> 0.5  // Slow drain at high charge
            voltage > 3700 -> 1.2  // Moderate drain
            voltage > 3400 -> 2.5  // Faster drain as battery weakens
            voltage > 3200 -> 5.0  // Critical fast drain near shutdown
            else -> 10.0  // Almost dead battery drains very fast
        }
    }

    fun predictTimeToShutdown(): ShutdownPrediction {
        val history = getBatteryHistory()
        if (history.isEmpty()) {
            return ShutdownPrediction(Double.POSITIVE_INFINITY, PredictionConfidence.INSUFFICIENT_DATA)
        }

        val currentRecord = history.last()
        if (currentRecord.isCharging) {
            return ShutdownPrediction(Double.POSITIVE_INFINITY, PredictionConfidence.CHARGING)
        }

        // Adjust drain rate based on actual voltage instead of linear rate
        val adjustedDrainRate = getBatteryDrainRate(currentRecord.voltage)
        var minutesLeft = currentRecord.batteryLevel / adjustedDrainRate

        // Prevent overly optimistic estimates
        minutesLeft = minutesLeft.coerceAtMost(1440.0)

        val confidence = when {
            drainRateWindow.size < 5 -> PredictionConfidence.LOW
            drainRateWindow.size < 10 -> PredictionConfidence.MEDIUM
            else -> PredictionConfidence.HIGH
        }

        return ShutdownPrediction(minutesLeft, confidence)
    }

    data class ShutdownPrediction(
        val minutesLeft: Double,
        val confidence: PredictionConfidence
    )

    enum class PredictionConfidence {
        INSUFFICIENT_DATA,
        LOW,
        MEDIUM,
        HIGH,
        CHARGING
    }

    // Existing helper methods for persistence...
    private fun persistState(isCharging: Boolean) {
        prefs.edit().apply {
            putFloat(KEY_CUMULATIVE_DISCHARGE, cumulativeDischarge.toFloat())
            putFloat(KEY_ESTIMATED_CYCLES, estimatedCycles.toFloat())
            putInt(KEY_PREVIOUS_LEVEL, previousBatteryLevel)
            putBoolean("IS_CHARGING", isCharging)  // Save charging state
            apply()
        }
    }

    private fun getBatteryHistory(): List<BatteryRecord> {
        val jsonString = prefs.getString(KEY_BATTERY_HISTORY, "[]") ?: "[]"
        return parseJsonToHistory(jsonString)
    }

    private fun saveBatteryHistory(history: List<BatteryRecord>) {
        val jsonString = historyToJson(history)
        prefs.edit().putString(KEY_BATTERY_HISTORY, jsonString).apply()
    }

    private fun parseJsonToHistory(jsonString: String): List<BatteryRecord> {
        val list = mutableListOf<BatteryRecord>()
        val jsonArray = JSONArray(jsonString)
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            list.add(
                BatteryRecord(
                    obj.getLong("timestamp"),
                    obj.getInt("batteryLevel"),
                    obj.getDouble("temperature"),
                    obj.getInt("voltage"),
                    obj.getBoolean("isCharging")
                )
            )
        }
        return list
    }

    private fun historyToJson(history: List<BatteryRecord>): String {
        val jsonArray = JSONArray()
        for (record in history) {
            val obj = JSONObject().apply {
                put("timestamp", record.timestamp)
                put("batteryLevel", record.batteryLevel)
                put("temperature", record.temperature)
                put("voltage", record.voltage)
                put("isCharging", record.isCharging)
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    fun getWeightedDrainRate(): Double {
        return weightedDrainRate
    }
}