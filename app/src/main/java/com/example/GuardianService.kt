package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * 守护引擎（无 root、无无障碍）。
 *
 * 每 [InterceptConfig.DEFAULT_TICK_INTERVAL_MS] 毫秒做一次判定：
 *   1) 用 UsageStatsManager 找出当前前台包名；
 *   2) 如果命中"需要压制的包"（班牌本体 / 用户附加的监控包），立刻把它顶下去：
 *      bounce 模式 → 发送 HOME 归位；curtain 模式 → 盖上全屏幕布；
 *      两种模式都会顺带 killBackgroundProcesses（尽力而为）；
 *   3) 离开后仍保持 [InterceptConfig.DEFAULT_GUARD_COOLDOWN_MS] 的"余温"，
 *      以对抗"自动监听服务"式的反复拉起。
 *
 * 开机后的一段时间（开机压制窗口）里同样会持续执行上述逻辑，
 * 这就是"拦截班牌开机自启"的实际实现：无法阻止对方收到 BOOT_COMPLETED，
 * 但可以让它每次冒头都被立刻顶回桌面 / 盖上幕布。
 */
class GuardianService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private var lastDetectedPackage: String? = null
    private var lastDetectedAt = 0L
    private var lastKillAt = 0L
    private var lastLogAt = 0L
    private var tickCount = 0L
    private var notificationText: String = "守护中"

    private val ticker = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (t: Throwable) {
                EventLog.add("守护循环异常：" + t.javaClass.simpleName)
            }
            handler.postDelayed(this, InterceptConfig.tickIntervalMs(this@GuardianService))
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = InterceptConfig.prefs(this)
        startForegroundCompat()
        isRunning = true
        EventLog.add("守护服务已启动")
        handler.postDelayed(ticker, 800L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val bootSeconds = intent?.getIntExtra(EXTRA_BOOT_SUPPRESS_SECONDS, 0) ?: 0
        if (bootSeconds > 0) {
            val until = System.currentTimeMillis() + bootSeconds * 1000L
            InterceptConfig.prefs(this).edit()
                .putLong(InterceptConfig.KEY_SUPPRESS_UNTIL, until)
                .apply()
            EventLog.add("开机压制窗口已激活：" + InterceptConfig.describeSeconds(bootSeconds.toLong()))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        EventLog.add("任务被移除，看门狗接管")
        WatchdogScheduler.ensureArmed(this)
    }

    override fun onDestroy() {
        isRunning = false
        CurtainOverlay.hide()
        handler.removeCallbacks(ticker)
        EventLog.add("守护服务已停止")
        if (InterceptConfig.guardEnabled(this)) {
            WatchdogScheduler.ensureArmed(this)
        }
        super.onDestroy()
    }

    // ---------------- 核心循环 ----------------

    private fun tick() {
        tickCount++
        if (!prefs.getBoolean(InterceptConfig.KEY_GUARD_ENABLED, true)) {
            CurtainOverlay.hide()
            if (tickCount % 20L == 0L) pushNotification("拦截已关闭")
            return
        }

        val guards = InterceptConfig.guardPackages(this)
        if (guards.isEmpty()) {
            CurtainOverlay.hide()
            return
        }

        val now = System.currentTimeMillis()
        val usageGranted = ForegroundWatcher.hasUsageAccess(this)
        val foreground = if (usageGranted) ForegroundWatcher.lastForegroundPackage(this) else null

        var detected: String? = null
        if (foreground != null && guards.contains(foreground)) {
            detected = foreground
        } else if (foreground == null) {
            detected = guards.firstOrNull { ForegroundWatcher.isForegroundByImportance(this, it) }
        }

        if (detected != null) {
            lastDetectedPackage = detected
            lastDetectedAt = now
            lastSeenPackage = detected
        }

        val bootWindow = InterceptConfig.isWithinWindow(
            now,
            prefs.getLong(InterceptConfig.KEY_SUPPRESS_UNTIL, 0L)
        )
        val stillHot = lastDetectedPackage != null && now - lastDetectedAt <= InterceptConfig.cooldownMs(this)
        val target = detected ?: if (stillHot) lastDetectedPackage else null

        if (target != null) {
            act(target, bootWindow)
            return
        }

        if (bootWindow && !usageGranted && tickCount % 4L == 0L) {
            // 没有用法统计权限时的开机兜底：低频归位，赌它落在目标应用刚启动的瞬间
            AppActions.goHome(this)
            InterceptConfig.addIntercept(this)
            pushNotification("开机压制中（缺少用法统计权限，降级归位）")
            return
        }

        CurtainOverlay.hide()
        if (tickCount % 30L == 0L) pushNotification("待命中")
    }

    private fun act(packageName: String, bootWindow: Boolean) {
        val mode = InterceptConfig.mode(this)
        if (InterceptConfig.MODE_CURTAIN == mode) {
            val label = AppActions.appLabel(this, packageName) ?: packageName
            CurtainOverlay.show(this, "班牌已暂停使用", "已拦截：" + label)
        } else {
            AppActions.goHome(this)
        }

        val now = System.currentTimeMillis()
        if (now - lastKillAt > 1500L) {
            lastKillAt = now
            AppActions.killBackground(this, packageName)
        }

        InterceptConfig.addIntercept(this)
        if (now - lastLogAt > 5000L) {
            lastLogAt = now
            val suffix = if (bootWindow) "（开机压制窗口内）" else ""
            EventLog.add("已压制 " + packageName + suffix)
        }
        pushNotification(if (bootWindow) "开机压制中：" + packageName else "已拦截：" + packageName)
    }

    // ---------------- 通知 ----------------

    private fun startForegroundCompat() {
        val notification = buildNotification(notificationText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun pushNotification(text: String) {
        if (text == notificationText) return
        notificationText = text
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (t: Throwable) {
            // 通知被系统折叠时忽略
        }
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("班牌悬浮助手 · 守护引擎")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "守护引擎", NotificationManager.IMPORTANCE_LOW).apply {
            description = "拦截班牌自动运行与自动拉起"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "guardian_service_channel"
        const val NOTIFICATION_ID = 8830
        const val EXTRA_BOOT_SUPPRESS_SECONDS = "boot_suppress_seconds"

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 最近一次被判定为"目标在前台"的包名，供界面展示。 */
        @Volatile
        var lastSeenPackage: String? = null

        fun start(context: Context, bootSuppressSeconds: Int = 0) {
            val intent = Intent(context, GuardianService::class.java).apply {
                putExtra(EXTRA_BOOT_SUPPRESS_SECONDS, bootSuppressSeconds)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                EventLog.add("守护服务启动失败：" + t.javaClass.simpleName)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, GuardianService::class.java))
            } catch (t: Throwable) {
                // ignore
            }
        }
    }
}
