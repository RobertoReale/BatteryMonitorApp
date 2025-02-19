package com.example.batteryalert

/**
 * Constants used for battery monitoring thresholds, intervals, and notification details.
 */
object Constants {
    // Voltage in milli-volts (mV)
    const val MIN_VOLTAGE = 3000
    const val MAX_VOLTAGE = 4500
    const val LOW_VOLTAGE_THRESHOLD = 3200

    // Battery percentage threshold for low battery conditions
    const val LOW_BATTERY_PERCENTAGE = 20

    // Temperature threshold in Celsius
    const val HIGH_TEMPERATURE_THRESHOLD = 45

    // Notification channel and IDs
    const val NOTIFICATION_CHANNEL_ID = "battery_monitor_channel"
    const val NOTIFICATION_CHANNEL_NAME = "Battery Monitor Alerts"
    const val NOTIFICATION_ID = 1

    // Critical notification channel
    const val CRITICAL_NOTIFICATION_CHANNEL_ID = "battery_critical_channel"
    const val CRITICAL_NOTIFICATION_CHANNEL_NAME = "Critical Battery Alerts"
    const val CRITICAL_NOTIFICATION_ID = 2
}