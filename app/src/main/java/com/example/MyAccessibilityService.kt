package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClassBrandHelperAS"
        
        // Static references to track state and allow service communication
        var isRunning = false
            private set

        var lastActiveNonSystemPackage: String? = null
            private set

        var isCurrentlyOnLauncher = false
            private set

        // Service coordinates targeting
        var targetPackageToStop: String? = null
        var isStopInProgress = false
        private var clickedForceStopInThisSession = false
        
        // Launcher packages caching
        private var launcherPackages = emptySet<String>()

        fun checkAccessibilityPermission(context: Context): Boolean {
            if (isRunning) return true
            try {
                val expectedComponentName = android.content.ComponentName(context, MyAccessibilityService::class.java)
                val enabledServicesSetting = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
                colonSplitter.setString(enabledServicesSetting)
                while (colonSplitter.hasNext()) {
                    val componentNameString = colonSplitter.next()
                    val enabledService = android.content.ComponentName.unflattenFromString(componentNameString)
                    if (enabledService != null && enabledService == expectedComponentName) {
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking accessibility permission", e)
            }
            return false
        }

        fun startForceStopSequence(context: Context, packageName: String) {
            targetPackageToStop = packageName
            isStopInProgress = true
            clickedForceStopInThisSession = false
            
            // Launch standard settings page for application details
            try {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                context.startActivity(intent)
                Log.d(TAG, "Launched Application Info settings for package: $packageName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch application settings Details", e)
                isStopInProgress = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        updateLauncherPackages()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.d(TAG, "Accessibility Service Connected")
        
        // --- Boot Fallback Auto-Start ---
        val sharedPrefs = getSharedPreferences("class_brand_prefs", Context.MODE_PRIVATE)
        val isAutoStartEnabled = sharedPrefs.getBoolean("auto_start_on_boot", true)
        if (isAutoStartEnabled && checkAccessibilityPermission(this)) {
            try {
                val serviceIntent = Intent(this, FloatingWindowService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Log.d(TAG, "FloatingWindowService started securely by AccessibilityService.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-start FloatingWindowService", e)
            }
        }
        // --------------------------------
        
        // Update service info configuration
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        serviceInfo = info
    }

    override fun onInterrupt() {
        isRunning = false
        Log.w(TAG, "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }

    private fun updateLauncherPackages() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            launcherPackages = resolveInfos.map { it.activityInfo.packageName }.toSet()
            Log.d(TAG, "Discovered launcher packages: $launcherPackages")
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching launcher packages", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkgName = event.packageName?.toString() ?: ""
        if (pkgName.isEmpty()) return

        // 1. Maintain active package state
        if (launcherPackages.isEmpty()) {
            updateLauncherPackages()
        }

        if (launcherPackages.contains(pkgName)) {
            isCurrentlyOnLauncher = true
            Log.d(TAG, "User currently on home screen launcher: $pkgName")
        } else if (pkgName != packageName && pkgName != "com.android.settings" && !pkgName.contains("system")) {
            isCurrentlyOnLauncher = false
            lastActiveNonSystemPackage = pkgName
            Log.d(TAG, "Tracked last active app under float window: $pkgName")
        }

        // 2. Automated clicking if force close sequence is in progress
        if (isStopInProgress && targetPackageToStop != null) {
            val rootNode = rootInActiveWindow ?: return

            // If we are on Settings screen, look for Force Stop
            if (pkgName == "com.android.settings" || pkgName.contains("settings")) {
                // Look for "Force Stop" button
                val forceStopTexts = listOf(
                    "强行停止", "强制停止", "强行结束", "结束运行",
                    "Force stop", "Force Stop", "FORCE STOP"
                )
                
                // Try to find the button
                val clickedForceStop = performClickOnNodeWithTexts(rootNode, forceStopTexts)
                if (clickedForceStop) {
                    Log.d(TAG, "Successfully clicked 'Force Stop' button")
                    clickedForceStopInThisSession = true
                }
            }
            
            // If we already clicked Force Stop, look for the "OK" confirmation button next
            if (clickedForceStopInThisSession) {
                // Confirmation dialog prompt button texts
                val okTexts = listOf(
                    "确定", "确认", "是", "强行停止", "强制停止",
                    "OK", "Ok", "ok", "YES", "Yes", "yes", "FORCE STOP"
                )
                // We searches for matching button and clicks
                val clickedOk = performClickOnNodeWithTexts(rootNode, okTexts)
                if (clickedOk) {
                    Log.d(TAG, "Successfully clicked confirmation dialog 'OK'")
                    
                    // Sequence done! Post back-press or exit details
                    isStopInProgress = false
                    targetPackageToStop = null
                    clickedForceStopInThisSession = false
                    
                    // Exit setting screen after 600ms delay to give system time to apply stop change
                    val service = this
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        service.performGlobalAction(GLOBAL_ACTION_BACK)
                        Log.d(TAG, "Triggered Back Action to return to app screen")
                    }, 600)
                }
            }
            
            rootNode.recycle()
        }
    }

    private fun performClickOnNodeWithTexts(nodeInfo: AccessibilityNodeInfo?, targetTexts: List<String>): Boolean {
        if (nodeInfo == null) return false

        val text = nodeInfo.text?.toString() ?: ""
        val contentDesc = nodeInfo.contentDescription?.toString() ?: ""
        val className = nodeInfo.className?.toString() ?: ""

        val matchesText = targetTexts.any { target ->
            // Match complete text or contains text
            text.trim().equals(target, ignoreCase = true) || 
            contentDesc.trim().equals(target, ignoreCase = true) ||
            (text.isNotEmpty() && text.contains(target, ignoreCase = true))
        }

        // Must be a clickable button or clickable container
        if (matchesText && (className.contains("Button", ignoreCase = true) || nodeInfo.isClickable)) {
            val success = nodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (success) {
                return true
            }
        }

        // Traverse children
        for (i in 0 until nodeInfo.childCount) {
            val child = nodeInfo.getChild(i) ?: continue
            val found = performClickOnNodeWithTexts(child, targetTexts)
            child.recycle()
            if (found) return true
        }

        return false
    }
}
