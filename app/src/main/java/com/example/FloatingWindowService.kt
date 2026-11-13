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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * 前台常驻的悬浮控制面板。
 *
 * 折叠态是一个可拖动的金色气泡，点开是控制面板：
 * 立即归位 / 幕布开关 / 拦截开关 / 透明度 / 返回桌面 / 隐藏图标。
 * 它只负责"下命令"，真正的判定循环在 [GuardianService] 里。
 */
class FloatingWindowService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences

    private var currentView: View? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var expanded = false
    private var overlayAlpha = 1.0f

    private var statusView: TextView? = null
    private var curtainButton: Button? = null
    private var guardButton: Button? = null

    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = InterceptConfig.prefs(this)
        overlayAlpha = prefs.getFloat(InterceptConfig.KEY_BUBBLE_OPACITY, 1.0f).coerceIn(0.1f, 1.0f)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startServiceForeground()
        isServiceRunning = true
        EventLog.add("悬浮窗服务已启动")
        if (prefs.getBoolean(InterceptConfig.KEY_ICON_HIDDEN, false)) {
            EventLog.add("图标处于隐藏状态（可从主界面恢复）")
        } else {
            showBubble()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isServiceRunning = false
        handler.removeCallbacksAndMessages(null)
        removeCurrentView()
        EventLog.add("悬浮窗服务已停止")
        super.onDestroy()
    }

    // ---------------- 视图 ----------------

    private fun removeCurrentView() {
        val view = currentView ?: return
        try {
            windowManager.removeViewImmediate(view)
        } catch (t: Throwable) {
            // 已被系统移除
        }
        currentView = null
        currentParams = null
        statusView = null
        curtainButton = null
        guardButton = null
    }

    private fun showBubble() {
        removeCurrentView()
        expanded = false
        val size = dp(64)
        val bubble = TextView(this).apply {
            text = "✦"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#121212"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D4AF37"))
            }
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_BUBBLE_X, 24)
            y = prefs.getInt(KEY_BUBBLE_Y, 240)
        }
        bubble.alpha = overlayAlpha
        attachBubbleTouch(bubble, params)
        addView(bubble, params)
    }

    private fun showPanel() {
        removeCurrentView()
        val panel = buildPanel()
        val params = WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        addView(panel, params)
        expanded = true
        refreshStatus()
        handler.removeCallbacks(statusTicker)
        handler.post(statusTicker)
    }

    private fun addView(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.addView(view, params)
            currentView = view
            currentParams = params
        } catch (t: Throwable) {
            EventLog.add("悬浮窗挂载失败：" + t.javaClass.simpleName)
        }
    }

    private fun attachBubbleTouch(bubble: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var downAt = 0L
        var dragged = false

        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    downAt = System.currentTimeMillis()
                    dragged = false
                    bubble.alpha = 1.0f
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) dragged = true
                    params.x = startX + dx
                    params.y = startY + dy
                    try {
                        windowManager.updateViewLayout(bubble, params)
                    } catch (t: Throwable) {
                        // ignore
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragged && System.currentTimeMillis() - downAt < 250L) {
                        showPanel()
                    } else {
                        bubble.alpha = overlayAlpha
                        prefs.edit()
                            .putInt(KEY_BUBBLE_X, params.x)
                            .putInt(KEY_BUBBLE_Y, params.y)
                            .apply()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun buildPanel(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#F2141414"))
                setStroke(dp(1), Color.parseColor("#33D4AF37"))
            }
        }

        val title = TextView(this).apply {
            text = "班牌悬浮助手 · 控制台"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#D4AF37"))
        }

        val target = TextView(this).apply {
            text = "目标：" + InterceptConfig.targetPackage(this@FloatingWindowService)
            textSize = 11f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, dp(4), 0, dp(8))
        }

        val status = TextView(this).apply {
            text = statusLine()
            textSize = 11f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, 0, 0, dp(10))
        }
        statusView = status

        container.addView(title)
        container.addView(target)
        container.addView(status)

        container.addView(actionButton("立即归位（顶掉班牌）", "#D32F2F", "#FFFFFF") {
            bringTargetDown()
        })
        container.addView(actionButton("返回主屏幕", "#D4AF37", "#121212") {
            AppActions.goHome(this)
        })

        val curtain = actionButton("幕布：关闭", "#37474F", "#CFD8DC") {
            toggleCurtain()
        }
        curtainButton = curtain
        container.addView(curtain)

        val guard = actionButton("拦截：开启", "#37474F", "#CFD8DC") {
            toggleGuard()
        }
        guardButton = guard
        container.addView(guard)

        val alphaLabel = TextView(this).apply {
            text = "气泡透明度：" + (overlayAlpha * 100).toInt().toString() + "%"
            textSize = 11f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, dp(10), 0, 0)
        }
        container.addView(alphaLabel)

        val seekBar = SeekBar(this).apply {
            max = 90
            progress = ((overlayAlpha - 0.1f) * 100).toInt().coerceIn(0, 90)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    overlayAlpha = 0.1f + value / 100f
                    alphaLabel.text = "气泡透明度：" + (value + 10).toString() + "%"
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    prefs.edit().putFloat(InterceptConfig.KEY_BUBBLE_OPACITY, overlayAlpha).apply()
                }
            })
        }
        container.addView(seekBar)

        container.addView(actionButton("隐藏气泡（保留后台服务）", "#263238", "#B0BEC5") {
            prefs.edit().putBoolean(InterceptConfig.KEY_ICON_HIDDEN, true).apply()
            removeCurrentView()
            EventLog.add("气泡已隐藏")
        })
        container.addView(actionButton("收起面板", "#1C1C1C", "#9E9E9E") {
            handler.removeCallbacks(statusTicker)
            showBubble()
        })

        container.setOnClickListener { }
        return container
    }

    private fun actionButton(label: String, bgColor: String, foreground: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.parseColor(foreground))
            this.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor(bgColor))
            }
            val layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            )
            layoutParams.topMargin = dp(6)
            this.layoutParams = layoutParams
            setOnClickListener { onClick() }
        }
    }

    // ---------------- 行为 ----------------

    private fun bringTargetDown() {
        val target = InterceptConfig.targetPackage(this)
        AppActions.goHome(this)
        val killed = AppActions.killBackground(this, target)
        InterceptConfig.addIntercept(this)
        EventLog.add(if (killed) "已归位并尝试回收 " + target else "已归位 " + target)
        refreshStatus()
    }

    private fun toggleCurtain() {
        if (CurtainOverlay.isShowing) {
            CurtainOverlay.hide()
        } else {
            CurtainOverlay.show(this, "班牌已暂停使用", "手动幕布遮挡")
        }
        refreshStatus()
    }

    private fun toggleGuard() {
        val enabled = !prefs.getBoolean(InterceptConfig.KEY_GUARD_ENABLED, true)
        prefs.edit().putBoolean(InterceptConfig.KEY_GUARD_ENABLED, enabled).apply()
        if (enabled) {
            GuardianService.start(this)
        } else {
            CurtainOverlay.hide()
            GuardianService.stop(this)
        }
        EventLog.add(if (enabled) "拦截已开启" else "拦截已关闭")
        refreshStatus()
    }

    private fun statusLine(): String {
        val guard = if (prefs.getBoolean(InterceptConfig.KEY_GUARD_ENABLED, true)) "拦截 开" else "拦截 关"
        val engine = if (GuardianService.isRunning) "引擎 运行" else "引擎 停止"
        val total = InterceptConfig.totalIntercepts(this)
        return guard + " · " + engine + " · 累计拦截 " + total
    }

    private fun refreshStatus() {
        statusView?.text = statusLine()
        curtainButton?.text = if (CurtainOverlay.isShowing) "幕布：开启" else "幕布：关闭"
        guardButton?.text = if (prefs.getBoolean(InterceptConfig.KEY_GUARD_ENABLED, true)) "拦截：开启" else "拦截：关闭"
    }

    // ---------------- 通知 ----------------

    private fun startServiceForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "班牌辅助后台服务", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "保持悬浮控制台常驻"
                    }
                )
            }
        }
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
            .setContentTitle("班牌悬浮助手")
            .setContentText("悬浮控制台运行中")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .build()
    }

    // ---------------- 工具 ----------------

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    companion object {
        const val NOTIFICATION_ID = 8829
        const val CHANNEL_ID = "floating_service_pkg"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                EventLog.add("悬浮窗启动失败：" + t.javaClass.simpleName)
            }
        }
    }
}
