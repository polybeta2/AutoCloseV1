package com.example

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 看门狗：复刻电子班牌自身的自保手段。
 *
 * 用 AlarmManager 每 5 分钟做一次自检，即使被 ROM 或用户从最近任务里划掉，
 * 也能在几分钟内把守护服务 / 悬浮窗重新拉起来。
 */
object WatchdogScheduler {

    private const val REQUEST_CODE = 8831

    fun ensureArmed(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            manager.cancel(pending)
            val interval = AlarmManager.INTERVAL_FIFTEEN_MINUTES / 3
            manager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 60_000L,
                interval,
                pending
            )
        } catch (t: Throwable) {
            EventLog.add("看门狗闹钟设置失败：" + t.javaClass.simpleName)
        }
    }

    fun disarm(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            manager.cancel(pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT))
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun pendingIntent(context: Context, extraFlags: Int): PendingIntent {
        val flags = extraFlags or PendingIntent.FLAG_IMMUTABLE
        val intent = Intent(context, WatchdogReceiver::class.java).apply {
            action = WatchdogReceiver.ACTION_TICK
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}

/** 看门狗广播：只做一件事——确认两个服务还活着。 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = InterceptConfig.prefs(context)
        val guardEnabled = prefs.getBoolean(InterceptConfig.KEY_GUARD_ENABLED, true)
        val autoStart = prefs.getBoolean(InterceptConfig.KEY_AUTO_START_ON_BOOT, true)

        if (guardEnabled && !GuardianService.isRunning) {
            EventLog.add("看门狗：重新拉起守护服务")
            GuardianService.start(context, 0)
        }
        if (autoStart && !FloatingWindowService.isServiceRunning) {
            FloatingWindowService.start(context)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            WatchdogScheduler.ensureArmed(context)
        }
    }

    companion object {
        const val ACTION_TICK = "com.example.action.WATCHDOG_TICK"
    }
}
