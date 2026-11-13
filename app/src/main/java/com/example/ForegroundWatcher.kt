package com.example

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

/**
 * 前台应用探测器：完全不需要无障碍服务。
 *
 * 首选 UsageStatsManager 的 ACTIVITY_RESUMED / MOVE_TO_FOREGROUND 事件（需要"使用情况访问"权限），
 * 退而求其次用 ActivityManager 的进程重要级别（部分 ROM 仍然可用），
 * 两者都拿不到时返回 null，由调用方决定是否放行。
 */
object ForegroundWatcher {

    /** 是否已获得"使用情况访问"（PACKAGE_USAGE_STATS）权限。 */
    @Suppress("DEPRECATION")
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
        } catch (t: Throwable) {
            return false
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 最近 [lookbackMs] 毫秒内最后一个切到前台的应用包名。 */
    fun lastForegroundPackage(context: Context, lookbackMs: Long = 4500L): String? {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val end = System.currentTimeMillis()
        val events = try {
            manager.queryEvents(end - lookbackMs, end)
        } catch (t: Throwable) {
            null
        } ?: return null

        val event = UsageEvents.Event()
        var found: String? = null
        var stamp = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = event.eventType
            val isForegroundEvent = type == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type == UsageEvents.Event.ACTIVITY_RESUMED)
            if (!isForegroundEvent) continue
            if (event.timeStamp < stamp) continue
            val name = event.packageName
            if (!name.isNullOrEmpty()) {
                found = name
                stamp = event.timeStamp
            }
        }
        return found
    }

    /** 退化方案：按进程重要级别判断包名是否处于前台。部分 ROM 会限制该接口，失败即返回 false。 */
    @Suppress("DEPRECATION")
    fun isForegroundByImportance(context: Context, packageName: String): Boolean {
        val processes = runningProcesses(context) ?: return false
        return processes.any {
            it.processName == packageName &&
                it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    /** 该包是否还有存活进程（不区分前后台）。 */
    @Suppress("DEPRECATION")
    fun isProcessAlive(context: Context, packageName: String): Boolean {
        val processes = runningProcesses(context) ?: return false
        return processes.any {
            it.processName == packageName || it.processName.startsWith(packageName + ":")
        }
    }

    /** 当前桌面包名集合（用于判断"已经回到桌面"）。 */
    fun homePackages(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0L)
                )
            } else {
                context.packageManager.queryIntentActivities(intent, 0)
            }
        } catch (t: Throwable) {
            emptyList()
        }
        return resolved.mapNotNull { it.activityInfo?.packageName }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun runningProcesses(context: Context): List<ActivityManager.RunningAppProcessInfo>? {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        return try {
            manager.runningAppProcesses
        } catch (t: Throwable) {
            null
        }
    }
}
