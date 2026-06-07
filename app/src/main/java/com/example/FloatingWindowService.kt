package com.example

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.roundToInt

class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindowService"
        private const val NOTIFICATION_ID = 8829
        private const val CHANNEL_ID = "floating_service_pkg"
        
        var isServiceRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var sharedPrefs: SharedPreferences
    
    // Root container added to WindowManager
    private var rootContainer: FrameLayout? = null

    // Track position in float mode
    private var lastX = 150
    private var lastY = 300
    private var isCurrentlyExpanded = false

    // Real-time automatic UI status refresher
    private val uiHandler = Handler(Looper.getMainLooper())
    private var uiUpdateRunnable: Runnable? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "bubble_opacity" || key == "icon_hidden") {
            if (!isCurrentlyExpanded && isServiceRunning) {
                uiHandler.post {
                    switchToCollapsedView()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sharedPrefs = getSharedPreferences("class_brand_prefs", Context.MODE_PRIVATE)
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefListener)
        
        // Setup Foreground Service notification
        startServiceForeground()
        
        // Setup base layout container and attach to WindowManager
        setupRootAndAttach()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        if (::sharedPrefs.isInitialized) {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        }
        stopUiTimer()
        removeWindow()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startServiceForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val title = "班牌悬浮助手正在运行"
            val channel = NotificationChannel(
                CHANNEL_ID,
                "班牌辅助后台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保证悬浮窗后台不被系统结束"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText("点击可打开管理界面进行配置")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun Int.dp(): Int {
        return (this * resources.displayMetrics.density).roundToInt()
    }

    private fun setupRootAndAttach() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Cannot draw overlays, permission missing!")
            stopSelf()
            return
        }

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Initially setup layout params for collapsed bubble (64dp * 64dp size roughly)
        layoutParams = WindowManager.LayoutParams(
            64.dp(),
            64.dp(),
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastX
            y = lastY
        }

        rootContainer = FrameLayout(this)
        
        try {
            windowManager.addView(rootContainer, layoutParams)
            Log.d(TAG, "Root frame successfully attached to WindowManager")
            
            // Render first initial state (collapsed)
            switchToCollapsedView()
        } catch (e: Exception) {
            Log.e(TAG, "Error adding base floating container to window manager", e)
            stopSelf()
        }
    }

    private fun removeWindow() {
        rootContainer?.let { root ->
            try {
                root.removeAllViews()
                windowManager.removeView(root)
                Log.d(TAG, "Root frame removed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing root float frame", e)
            }
        }
        rootContainer = null
    }

    // Switch WindowManager Layout and Component into COMPACT BUBBLE
    private fun switchToCollapsedView() {
        stopUiTimer()
        val root = rootContainer ?: return
        root.removeAllViews()

        isCurrentlyExpanded = false

        // Correct size parameters for bubble mode
        layoutParams.width = 64.dp()
        layoutParams.height = 64.dp()
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = lastX
        layoutParams.y = lastY
        try {
            windowManager.updateViewLayout(root, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying collapsed constraints", e)
        }

        // Create Bubble Layout
        val isIconHidden = sharedPrefs.getBoolean("icon_hidden", false)
        val bubbleOpacity = sharedPrefs.getFloat("bubble_opacity", 1.0f)

        val bubble = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(60.dp(), 60.dp(), Gravity.CENTER)
            if (isIconHidden) {
                alpha = 0f
            } else {
                alpha = bubbleOpacity
                val bg = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.parseColor("#333333"), Color.parseColor("#555555"))
                ).apply {
                    shape = GradientDrawable.OVAL
                }
                background = bg
                elevation = 10f
            }
        }

        // Add elegant character to bubble
        val gearView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            text = "✦"
            textColor = Color.parseColor("#D4AF37")
            textSize = 28f
        }
        bubble.addView(gearView)

        // Add drag & click physics control
        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f
            private var startY = 0f
            private var initialX = 0
            private var initialY = 0
            private var clickStartTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        clickStartTime = System.currentTimeMillis()
                        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).start()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startX
                        val dy = event.rawY - startY
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        
                        // Protect limits
                        if (layoutParams.x < 0) layoutParams.x = 0
                        if (layoutParams.y < 0) layoutParams.y = 0
                        
                        try {
                            windowManager.updateViewLayout(root, layoutParams)
                        } catch (e: Exception) {
                            Log.e(TAG, "Drag dynamic layout update fail", e)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                        lastX = layoutParams.x
                        lastY = layoutParams.y
                        
                        val duration = System.currentTimeMillis() - clickStartTime
                        val dx = event.rawX - startX
                        val dy = event.rawY - startY
                        val dist = Math.sqrt((dx * dx + dy * dy).toDouble())
                        
                        // Standard micro touch detection: click when move threshold is low
                        if (dist < 8 && duration < 200) {
                            // Expand!
                            switchToExpandedView()
                        }
                        return true
                    }
                }
                return false
            }
        })

        root.addView(bubble)
    }

    // Switch WindowManager Layout and Component into PREMIUM EXPANDED CARD PANEL
    private fun switchToExpandedView() {
        val root = rootContainer ?: return
        root.removeAllViews()

        isCurrentlyExpanded = true

        // Apply Layout Params to display large card at display center, focusing keys standard dialog behavior
        layoutParams.width = 290.dp()
        layoutParams.height = 300.dp()
        layoutParams.gravity = Gravity.CENTER
        layoutParams.x = 0
        layoutParams.y = 0
        try {
            windowManager.updateViewLayout(root, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying expanded constraints", e)
        }

        // Entire outer dialog shape Card
        val containerLayout = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            
            val cardBg = GradientDrawable().apply {
                setColor(Color.parseColor("#121212"))
                cornerRadius = 20.dp().toFloat()
                setStroke(2.dp(), Color.parseColor("#333333"))
            }
            background = cardBg
            elevation = 16f
        }

        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isScrollbarFadingEnabled = false
        }

        val cardLayout = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
        }

        // 1. Gorgeous Title bar Header (Concentric pastel teal gradient)
        val headerBar = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())

            val headerBg = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#1E1E1E"), Color.parseColor("#121212"))
            ).apply {
                val r = 18.dp().toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            background = headerBg
        }

        val headerTxt = TextView(this).apply {
            text = "班牌辅助控制面板"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            textColor = Color.parseColor("#D4AF37")
        }
        headerBar.addView(headerTxt)
        cardLayout.addView(headerBar)

        // 2. Preview Panel section (Dynamic Text Area)
        val previewBox = LinearLayout(this).apply {
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(14.dp(), 12.dp(), 14.dp(), 10.dp())
            }
            layoutParams = params
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())

            val wrapBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A"))
                cornerRadius = 10.dp().toFloat()
                setStroke(1.dp(), Color.parseColor("#2C2C2C"))
            }
            background = wrapBg
        }

        val packageTitle = TextView(this).apply {
            text = "目标班牌包名:"
            textSize = 11f
            textColor = Color.parseColor("#9E9E9E")
        }
        previewBox.addView(packageTitle)

        // Package specific string view
        val targetPkg = sharedPrefs.getString("target_package", "test.ndk.com.ndktest2") ?: "test.ndk.com.ndktest2"
        val packageContent = TextView(this).apply {
            text = targetPkg
            textSize = 12f
            textColor = Color.parseColor("#E0E0E0")
            typeface = Typeface.MONOSPACE
            setPadding(0, 2.dp(), 0, 6.dp())
        }
        previewBox.addView(packageContent)

        // Division border line
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dp()).apply {
                setMargins(0, 2.dp(), 0, 6.dp())
            }
            setBackgroundColor(Color.parseColor("#333333"))
        }
        previewBox.addView(divider)

        // App Status real-time preview label view references
        val statusPreviewLabel = TextView(this).apply {
            text = "正在检测系统与应用状态..."
            textSize = 12f
            textColor = Color.parseColor("#D4AF37")
            typeface = Typeface.DEFAULT_BOLD
        }
        previewBox.addView(statusPreviewLabel)

        cardLayout.addView(previewBox)

        // Opacity Slider inside FloatingWindow
        val opacityContainer = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(14.dp(), 0, 14.dp(), 10.dp()) }
            orientation = LinearLayout.VERTICAL
        }
        val opacityLabel = TextView(this).apply {
            text = "图标透明度: ${(sharedPrefs.getFloat("bubble_opacity", 1.0f) * 100).toInt()}%"
            textSize = 12f
            textColor = Color.parseColor("#9E9E9E")
            typeface = Typeface.DEFAULT_BOLD
            setPadding(4.dp(), 0, 0, 4.dp())
        }
        opacityContainer.addView(opacityLabel)
        
        val opacitySeek = android.widget.SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            max = 90
            progress = ((sharedPrefs.getFloat("bubble_opacity", 1.0f) * 100).toInt() - 10).coerceIn(0, 90)
            
            setOnSeekBarChangeListener(object: android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val newOpacity = (progress + 10) / 100f
                        sharedPrefs.edit().putFloat("bubble_opacity", newOpacity).apply()
                        opacityLabel.text = "图标透明度: ${progress + 10}%"
                    }
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        opacityContainer.addView(opacitySeek)
        cardLayout.addView(opacityContainer)

        // 3. Command Action Buttons setup (Vertical container)
        val btnContainer = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(14.dp(), 0, 14.dp(), 14.dp())
            }
            orientation = LinearLayout.VERTICAL
        }

        // RED BUTTON: "🛑 强行结束进程"
        val btnKill = Button(this).apply {
            val p = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                46.dp()
            )
            p.setMargins(0, 0, 0, 8.dp())
            layoutParams = p
            text = "强行结束进程"
            textColor = Color.WHITE
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            
            // Premium custom drawable shapes for responsive feedback
            val defaultShape = GradientDrawable().apply {
                setColor(Color.parseColor("#D32F2F"))
                cornerRadius = 10.dp().toFloat()
            }
            background = defaultShape
            
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.75f
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1.0f
                        if (event.action == MotionEvent.ACTION_UP) {
                            handleForceKill()
                        }
                    }
                }
                true
            }
        }
        btnContainer.addView(btnKill)

        // GREEN BUTTON: "🏠 返回主屏幕"
        val btnGoHome = Button(this).apply {
            val p = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                46.dp()
            )
            p.setMargins(0, 0, 0, 8.dp())
            layoutParams = p
            text = "返回主屏幕"
            textColor = Color.parseColor("#121212")
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD

            val defaultShape = GradientDrawable().apply {
                setColor(Color.parseColor("#D4AF37"))
                cornerRadius = 10.dp().toFloat()
            }
            background = defaultShape

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.75f
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1.0f
                        if (event.action == MotionEvent.ACTION_UP) {
                            handleGoHome { switchToCollapsedView() }
                        }
                    }
                }
                true
            }
        }
        btnContainer.addView(btnGoHome)

        // HIDE ICON TOGGLE BUTTON
        val isHidden = sharedPrefs.getBoolean("icon_hidden", false)
        val btnHideIcon = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                46.dp()
            ).apply { setMargins(0, 0, 0, 8.dp()) }
            text = if (isHidden) "显示悬浮窗图标" else "隐藏悬浮窗图标"
            textColor = Color.parseColor("#E0E0E0")
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD

            val defaultShape = GradientDrawable().apply {
                setColor(Color.parseColor("#2C2C2C"))
                cornerRadius = 10.dp().toFloat()
                setStroke(1.dp(), Color.parseColor("#424242"))
            }
            background = defaultShape

            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    val currentHidden = sharedPrefs.getBoolean("icon_hidden", false)
                    sharedPrefs.edit().putBoolean("icon_hidden", !currentHidden).apply()
                    text = if (!currentHidden) "显示悬浮窗图标" else "隐藏悬浮窗图标"
                    Toast.makeText(this@FloatingWindowService, if(!currentHidden) "已隐藏，点击原位置仍可唤醒" else "已取消隐藏", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
        btnContainer.addView(btnHideIcon)

        // LIGHT GREY BUTTON: "✕ 返回悬浮窗"
        val btnMinimize = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                44.dp()
            )
            text = "返回悬浮窗"
            textColor = Color.parseColor("#B0BEC5")
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD

            val defaultShape = GradientDrawable().apply {
                setColor(Color.parseColor("#263238"))
                cornerRadius = 10.dp().toFloat()
                setStroke(1.dp(), Color.parseColor("#37474F"))
            }
            background = defaultShape

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.75f
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1.0f
                        if (event.action == MotionEvent.ACTION_UP) {
                            switchToCollapsedView()
                        }
                    }
                }
                true
            }
        }
        btnContainer.addView(btnMinimize)

        cardLayout.addView(btnContainer)
        scrollView.addView(cardLayout)
        containerLayout.addView(scrollView)
        root.addView(containerLayout)

        // Start dynamic update cycle to refresh the labels (Target app, active screen and permission warning alerts)
        startUiTimer(statusPreviewLabel)
    }

    private fun startUiTimer(labelView: TextView) {
        stopUiTimer()
        
        uiUpdateRunnable = object : Runnable {
            override fun run() {
                val targetPkg = sharedPrefs.getString("target_package", "test.ndk.com.ndktest2") ?: "test.ndk.com.ndktest2"
                val lastActive = MyAccessibilityService.lastActiveNonSystemPackage
                val onHome = MyAccessibilityService.isCurrentlyOnLauncher
                
                // Double check accessibility via 100% reliable system checker
                val asPermitted = MyAccessibilityService.checkAccessibilityPermission(this@FloatingWindowService)

                if (!asPermitted) {
                    labelView.text = "运行提示：请开启无障碍服务"
                    labelView.setTextColor(Color.parseColor("#D32F2F"))
                } else if (onHome) {
                    labelView.text = "状态：当前已处于系统桌面"
                    labelView.setTextColor(Color.parseColor("#D4AF37"))
                } else {
                    val appLabel = getAppNameFromPackage(this@FloatingWindowService, lastActive)
                    labelView.text = "下方运行中：$appLabel"
                    labelView.setTextColor(Color.parseColor("#D4AF37"))
                }

                uiHandler.postDelayed(this, 1500) // update every 1.5 seconds smoothly
            }
        }
        
        uiUpdateRunnable?.let { uiHandler.post(it) }
    }

    private fun stopUiTimer() {
        uiUpdateRunnable?.let {
            uiHandler.removeCallbacks(it)
        }
        uiUpdateRunnable = null
    }

    private fun getAppNameFromPackage(context: Context, packageName: String?): String {
        if (packageName == null || packageName.isEmpty()) return "系统组件 / 其它应用"
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            return pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            // Simply display short package name suffix if application label lookup fails
            val parts = packageName.split(".")
            if (parts.size > 1) {
                return parts.takeLast(2).joinToString(".")
            }
            return packageName
        }
    }

    private fun handleForceKill() {
        val targetPkg = sharedPrefs.getString("target_package", "test.ndk.com.ndktest2") ?: "test.ndk.com.ndktest2"
        val lastActivePkg = MyAccessibilityService.lastActiveNonSystemPackage

        val asPermitted = MyAccessibilityService.checkAccessibilityPermission(this)
        if (!asPermitted) {
            Toast.makeText(this, "检测到无障碍不可用。\n正在跳转应用详情页，请手动点击【强行停止】", Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$targetPkg")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Fail to open app details", e)
            }
            return
        }

        val onHome = MyAccessibilityService.isCurrentlyOnLauncher
        if (onHome) {
            Toast.makeText(this, "当前处于桌面，没有需要结束的应用", Toast.LENGTH_SHORT).show()
            return
        }

        // Check running state via activity manager running app processes query
        val isTargetRunning = isAppRunning(targetPkg)

        if (isTargetRunning) {
            MyAccessibilityService.startForceStopSequence(this, targetPkg)
            Toast.makeText(this, "准备强行结束电子班牌进程...", Toast.LENGTH_SHORT).show()
        } else if (lastActivePkg != null && lastActivePkg.isNotEmpty()) {
            MyAccessibilityService.startForceStopSequence(this, lastActivePkg)
            Toast.makeText(this, "电子班牌未运行，准备结束下方应用进程...", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未有匹配的可关闭进程", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleGoHome(onCollapse: () -> Unit) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            Toast.makeText(this, "已返回主屏幕", Toast.LENGTH_SHORT).show()
            onCollapse()
        } catch (e: Exception) {
            Log.e(TAG, "Fail to launch home activity", e)
        }
    }

    private fun isAppRunning(packageName: String): Boolean {
        try {
            // Simple query in running systems
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningProcesses = am.runningAppProcesses
            if (runningProcesses != null) {
                for (procInfo in runningProcesses) {
                    if (procInfo.processName == packageName) {
                        return true
                    }
                    procInfo.pkgList?.forEach { pkg ->
                        if (pkg == packageName) return true
                    }
                }
            }
            
            // Check fallback trackers in accessibility state
            if (MyAccessibilityService.lastActiveNonSystemPackage == packageName) {
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if app is running", e)
        }
        return false
    }

    // Set utility TextView text color clearly
    private var TextView.textColor: Int
        get() = currentTextColor
        set(value) = setTextColor(value)
}
