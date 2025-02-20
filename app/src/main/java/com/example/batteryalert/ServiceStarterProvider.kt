package com.example.batteryalert

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat

class ServiceStarterProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        try {
            Log.d("ServiceStarterProvider", "Starting service at app initialization")
            val context = context ?: return false

            // Start service if needed
            val prefs = context.getSharedPreferences("BatteryMonitorPrefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("isServiceRunning", false)) {
                val serviceIntent = Intent(context, BatteryMonitorService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
                AlarmScheduler.scheduleRepeatingAlarm(context)
            }

            return true
        } catch (e: Exception) {
            Log.e("ServiceStarterProvider", "Error starting service", e)
            return false
        }
    }

    // Required content provider methods (no-op implementations)
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}