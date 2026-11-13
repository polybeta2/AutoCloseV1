package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机与覆盖安装的唯一入口。
 *
 * 普通应用没法定向拦截别人的开机广播，所以这里的策略是"抢跑"：
 * 开机后立刻拉起守护引擎并开一个【开机压制窗口】，
 * 窗口内班牌只要冒头就被顶回桌面（或盖上幕布），效果等同于"拦截了它的开机自启"。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val prefs = InterceptConfig.prefs(context)
        EventLog.add("系统广播：" + action)

        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            EventLog.add("已覆盖安装，重新武装看门狗")
            WatchdogScheduler.ensureArmed(context)
        }

        val guardEnabled = prefs.getBoolean(InterceptConfig.KEY_GUARD_ENABLED, true)
        val bootGuardEnabled = prefs.getBoolean(InterceptConfig.KEY_BOOT_GUARD_ENABLED, true)
        val suppressSeconds = if (guardEnabled && bootGuardEnabled) {
            InterceptConfig.bootSuppressSeconds(context)
        } else {
            0
        }

        if (guardEnabled) {
            GuardianService.start(context, suppressSeconds)
        }
        if (prefs.getBoolean(InterceptConfig.KEY_AUTO_START_ON_BOOT, true)) {
            FloatingWindowService.start(context)
        }
        WatchdogScheduler.ensureArmed(context)
    }
}
