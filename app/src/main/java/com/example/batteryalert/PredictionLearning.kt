package com.example.batteryalert

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tracks and learns from shutdown prediction accuracy to improve future predictions
 */
class PredictionLearning private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var activeWarning: ShutdownWarning? = null
    private var predictionAdjustment: Double = 1.0 // Multiplier for prediction times

    data class ShutdownWarning(
        val startTime: Long,
        val predictedMinutes: Double,
        val voltage: Int,
        val temperature: Double,
        val batteryLevel: Int
    )

    data class WarningOutcome(
        val warning: ShutdownWarning,
        val actualShutdownTime: Long?,
        val wasCancelled: Boolean
    )

    companion object {
        private const val PREFS_NAME = "PredictionLearningPrefs"
        private const val KEY_PREDICTION_HISTORY = "predictionHistory"
        private const val KEY_PREDICTION_ADJUSTMENT = "predictionAdjustment"
        private const val MAX_HISTORY_SIZE = 50
        private const val MIN_ADJUSTMENT = 0.5
        private const val MAX_ADJUSTMENT = 2.0
        private const val LEARNING_RATE = 0.1

        @Volatile
        private var instance: PredictionLearning? = null

        fun getInstance(context: Context): PredictionLearning {
            return instance ?: synchronized(this) {
                instance ?: PredictionLearning(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        predictionAdjustment = prefs.getFloat(KEY_PREDICTION_ADJUSTMENT, 1.0f).toDouble()
    }

    /**
     * Record when a shutdown warning starts
     */
    fun recordWarningStart(
        predictedMinutes: Double,
        voltage: Int,
        temperature: Double,
        batteryLevel: Int
    ) {
        activeWarning = ShutdownWarning(
            System.currentTimeMillis(),
            predictedMinutes,
            voltage,
            temperature,
            batteryLevel
        )
    }

    /**
     * Record when a warning was cancelled (false positive)
     */
    fun recordWarningCancelled() {
        activeWarning?.let { warning ->
            val outcome = WarningOutcome(
                warning = warning,
                actualShutdownTime = null,
                wasCancelled = true
            )
            saveOutcome(outcome)
            updatePredictionAdjustment(outcome)
            activeWarning = null
        }
    }

    /**
     * Record actual shutdown time
     */
    fun recordActualShutdown() {
        activeWarning?.let { warning ->
            val outcome = WarningOutcome(
                warning = warning,
                actualShutdownTime = System.currentTimeMillis(),
                wasCancelled = false
            )
            saveOutcome(outcome)
            updatePredictionAdjustment(outcome)
            activeWarning = null
        }
    }

    /**
     * Get the current adjustment factor for predictions
     */
    fun getPredictionAdjustment(): Double = predictionAdjustment

    private fun updatePredictionAdjustment(outcome: WarningOutcome) {
        val warning = outcome.warning

        if (outcome.wasCancelled) {
            // If cancelled, our prediction was too early - increase the adjustment
            predictionAdjustment *= (1 + LEARNING_RATE)
        } else {
            outcome.actualShutdownTime?.let { shutdownTime ->
                // Calculate how accurate our prediction was
                val actualMinutes = (shutdownTime - warning.startTime) / (1000.0 * 60.0)
                val ratio = actualMinutes / warning.predictedMinutes

                // Adjust our prediction factor towards the actual ratio
                predictionAdjustment = predictionAdjustment * (1 - LEARNING_RATE) +
                        (ratio * LEARNING_RATE)
            }
        }

        // Ensure adjustment stays within reasonable bounds
        predictionAdjustment = predictionAdjustment.coerceIn(MIN_ADJUSTMENT, MAX_ADJUSTMENT)

        // Persist the updated adjustment
        prefs.edit().putFloat(KEY_PREDICTION_ADJUSTMENT, predictionAdjustment.toFloat()).apply()
    }

    private fun saveOutcome(outcome: WarningOutcome) {
        val history = getWarningHistory().toMutableList()
        history.add(outcome)

        // Keep history size bounded
        while (history.size > MAX_HISTORY_SIZE) {
            history.removeAt(0)
        }

        val jsonArray = JSONArray()
        for (item in history) {
            val json = JSONObject().apply {
                put("startTime", item.warning.startTime)
                put("predictedMinutes", item.warning.predictedMinutes)
                put("voltage", item.warning.voltage)
                put("temperature", item.warning.temperature)
                put("batteryLevel", item.warning.batteryLevel)
                put("actualShutdownTime", item.actualShutdownTime ?: JSONObject.NULL)
                put("wasCancelled", item.wasCancelled)
            }
            jsonArray.put(json)
        }

        prefs.edit().putString(KEY_PREDICTION_HISTORY, jsonArray.toString()).apply()
    }

    private fun getWarningHistory(): List<WarningOutcome> {
        val jsonString = prefs.getString(KEY_PREDICTION_HISTORY, "[]") ?: "[]"
        val jsonArray = JSONArray(jsonString)
        val history = mutableListOf<WarningOutcome>()

        for (i in 0 until jsonArray.length()) {
            val json = jsonArray.getJSONObject(i)
            val warning = ShutdownWarning(
                startTime = json.getLong("startTime"),
                predictedMinutes = json.getDouble("predictedMinutes"),
                voltage = json.getInt("voltage"),
                temperature = json.getDouble("temperature"),
                batteryLevel = json.getInt("batteryLevel")
            )
            val actualShutdownTime = if (json.isNull("actualShutdownTime"))
                null
            else
                json.getLong("actualShutdownTime")

            history.add(
                WarningOutcome(
                    warning = warning,
                    actualShutdownTime = actualShutdownTime,
                    wasCancelled = json.getBoolean("wasCancelled")
                )
            )
        }
        return history
    }
}