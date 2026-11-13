package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * 无 root、无无障碍条件下的"尽可能暴力"的操作原语。
 *
 * 说明：普通应用无法调用 am force-stop，也无法阻止别的应用收到开机广播。
 * 因此这里只提供两件真实可做的事：
 *   1) 把当前前台置为桌面（"归位"），让对方应用离开屏幕；
 *   2) 调用 killBackgroundProcesses 让它在后台被回收（系统可能重启它）。
 */
object AppActions {

    private const val TAG = "AppActions"

    /** 把前台切回桌面。 */
    fun goHome(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "goHome failed", t)
            false
        }
    }

    /** 让系统回收目标应用的后台进程（需要 KILL_BACKGROUND_PROCESSES，普通权限，无需 root）。 */
    fun killBackground(context: Context, packageName: String): Boolean {
        if (!InterceptConfig.isValidPackageName(packageName)) return false
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return false
        return try {
            manager.killBackgroundProcesses(packageName)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "killBackgroundProcesses failed", t)
            false
        }
    }

    /** 打开系统的"应用信息"页，用户可在此手动点【强行停止】。 */
    fun openAppDetails(context: Context, packageName: String): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:" + packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "openAppDetails failed", t)
            false
        }
    }

    fun isInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (t: Throwable) {
            false
        }
    }

    @Suppress("DEPRECATION")
    fun appLabel(context: Context, packageName: String): String? {
        return try {
            val info: ApplicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (t: Throwable) {
            null
        }
    }
}
