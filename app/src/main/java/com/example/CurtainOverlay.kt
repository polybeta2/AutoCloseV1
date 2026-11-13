package com.example

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 全屏"幕布"悬浮层：拦截模式的第二种实现。
 *
 * 它不结束任何进程，只是在目标应用之上盖一层不透明窗口并吞掉所有触摸，
 * 于是班牌即使被自动拉起也停在"不可用"的画面上。
 * 管理员长按 5 秒可解除遮挡（同时关闭拦截开关）。
 */
object CurtainOverlay {

    private const val RELEASE_HOLD_MS = 5000L

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var root: View? = null
    private var titleView: TextView? = null
    private var subtitleView: TextView? = null
    private var releaseRunnable: Runnable? = null

    @Volatile
    var isShowing: Boolean = false
        private set

    fun show(context: Context, title: String, subtitle: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return
        }
        if (isShowing) {
            titleView?.text = title
            subtitleView?.text = subtitle
            return
        }
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val panel = buildPanel(context, title, subtitle)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        try {
            manager.addView(panel, params)
        } catch (t: Throwable) {
            EventLog.add("幕布挂载失败：" + t.javaClass.simpleName)
            return
        }
        windowManager = manager
        root = panel
        isShowing = true
        armReleaseGesture(context, panel)
    }

    fun hide() {
        val manager = windowManager ?: return
        val view = root ?: return
        releaseRunnable?.let { handler.removeCallbacks(it) }
        releaseRunnable = null
        try {
            manager.removeViewImmediate(view)
        } catch (t: Throwable) {
            // 已被系统移除，忽略
        }
        windowManager = null
        root = null
        titleView = null
        subtitleView = null
        isShowing = false
    }

    private fun armReleaseGesture(context: Context, panel: View) {
        val runnable = Runnable {
            hide()
            InterceptConfig.prefs(context).edit()
                .putBoolean(InterceptConfig.KEY_GUARD_ENABLED, false)
                .apply()
            EventLog.add("幕布已被管理员长按解除，拦截开关已关闭")
            Toast.makeText(context.applicationContext, "拦截已关闭（长按解锁）", Toast.LENGTH_LONG).show()
        }
        releaseRunnable = runnable
        panel.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> handler.postDelayed(runnable, RELEASE_HOLD_MS)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    handler.removeCallbacks(runnable)
            }
            true
        }
    }

    private fun buildPanel(context: Context, title: String, subtitle: String): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F20B0B0B"))
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        val mark = TextView(context).apply {
            text = "✦"
            textSize = 64f
            setTextColor(Color.parseColor("#D4AF37"))
            gravity = Gravity.CENTER
        }

        val titleText = TextView(context).apply {
            text = title
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F5F5F5"))
            gravity = Gravity.CENTER
        }

        val subtitleText = TextView(context).apply {
            text = subtitle
            textSize = 15f
            setTextColor(Color.parseColor("#9E9E9E"))
            gravity = Gravity.CENTER
        }

        val hint = TextView(context).apply {
            text = "长按屏幕 5 秒可解除遮挡"
            textSize = 12f
            setTextColor(Color.parseColor("#5E5E5E"))
            gravity = Gravity.CENTER
        }

        container.addView(mark)
        container.addView(titleText)
        container.addView(subtitleText)
        container.addView(hint)
        (container.getChildAt(1) as TextView).setPadding(0, dp(16), 0, dp(8))
        (container.getChildAt(2) as TextView).setPadding(0, 0, 0, dp(24))

        titleView = titleText
        subtitleView = subtitleText
        return container
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
