package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_USER_PRESENT ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d(TAG, "Device booted or user present! Action received: ${intent.action}")
            
            val sharedPrefs = context.getSharedPreferences("class_brand_prefs", Context.MODE_PRIVATE)
            val isAutoStartEnabled = sharedPrefs.getBoolean("auto_start_on_boot", true)
            
            if (isAutoStartEnabled && !FloatingWindowService.isServiceRunning) {
                try {
                    val serviceIntent = Intent(context, FloatingWindowService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.d(TAG, "FloatingWindowService started successfully on boot/user_present.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to auto-start FloatingWindowService", e)
                }
            } else {
                Log.d(TAG, "Auto-start is disabled or service already running.")
            }
        }
    }
}
