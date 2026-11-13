package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay

/**
 * 班牌悬浮助手 · 控制台
 *
 * 三条能力线：
 *  1) 开机自启（不依赖无障碍）：BOOT_COMPLETED 接收器 + 前台服务
 *  2) 开机压制：开机后一段时间内班牌一冒头就顶回桌面，等效"拦截它开机自启"
 *  3) 被关闭后拉起：AlarmManager 五分钟心跳 + 前台服务互相看护
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Toast.makeText(
                this,
                if (granted) "通知权限已授权，服务可稳定常驻" else "未授权通知，前台服务可能被系统限制",
                Toast.LENGTH_SHORT
            ).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestFirstLaunchPermissions()
        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HomeScreen(
                        onRequestOverlay = { openOverlaySettings() },
                        onRequestUsage = { openUsageAccessSettings() },
                        onRequestBattery = { requestIgnoreBatteryOptimizations() },
                        onRequestNotification = { requestNotificationPermission() },
                        onRequestAutoStart = { openAutoStartSettings(this) },
                        onOpenDetails = { AppActions.openAppDetails(this, packageName) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时把预置能力补齐（Android 11+ 开机广播需要一次真实启动）
        val prefs = InterceptConfig.prefs(this)
        if (prefs.getBoolean(InterceptConfig.KEY_GUARD_ENABLED, true) && !GuardianService.isRunning) {
            GuardianService.start(this)
        }
        WatchdogScheduler.ensureArmed(this)
    }

    private fun checkAndRequestFirstLaunchPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请允许「显示在其他应用上层」，悬浮控制台才能工作", Toast.LENGTH_LONG).show()
            openOverlaySettings()
        }
    }

    // ---------------- 权限跳转 ----------------

    private fun openOverlaySettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (ignored: Throwable) {
                Toast.makeText(this, "无法打开悬浮窗设置，请手动在系统设置中授权", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Toast.makeText(this, "无法打开「使用情况访问」设置", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power != null && power.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已在电池优化白名单中", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (ignored: Throwable) {
                Toast.makeText(this, "无法打开电池优化设置", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Toast.makeText(this, "当前系统无需申请通知权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAutoStartSettings(context: Context) {
        val intents = listOf(
            Intent().setComponent(
                android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            Intent().setComponent(
                android.content.ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.StartupNormalAppListActivity"
                )
            ),
            Intent().setComponent(
                android.content.ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
            ),
            Intent().setComponent(
                android.content.ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )
            ),
            Intent().setComponent(
                android.content.ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            ),
            Intent().setComponent(
                android.content.ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            ),
            Intent().setComponent(
                android.content.ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
            )
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (t: Throwable) {
                // 该厂商入口不存在，继续尝试下一个
            }
        }
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            Toast.makeText(context, "无法打开自启动管理，请手动在系统设置中允许", Toast.LENGTH_LONG).show()
        }
    }
}

data class AppItem(val name: String, val packageName: String)

private fun loadInstallableApps(context: Context): List<AppItem> {
    val pm = context.packageManager
    val apps = try {
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
    } catch (t: Throwable) {
        emptyList()
    }
    return apps.filter { app ->
        val isThirdParty = (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0
        val name = app.packageName.lowercase()
        isThirdParty ||
            name.contains("class") || name.contains("school") ||
            name.contains("brand") || name.contains("board")
    }.map { app ->
        val label = try {
            pm.getApplicationLabel(app).toString()
        } catch (t: Throwable) {
            app.packageName
        }
        AppItem(label, app.packageName)
    }.sortedBy { it.name.lowercase() }
}

private data class Snapshot(
    val overlay: Boolean,
    val usage: Boolean,
    val battery: Boolean,
    val notification: Boolean,
    val engineRunning: Boolean,
    val bubbleRunning: Boolean,
    val foreground: String,
    val total: Long,
    val logs: List<String>
)

@Composable
private fun HomeScreen(
    onRequestOverlay: () -> Unit,
    onRequestUsage: () -> Unit,
    onRequestBattery: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestAutoStart: () -> Unit,
    onOpenDetails: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { InterceptConfig.prefs(context) }

    var snapshot by remember { mutableStateOf(readSnapshot(context)) }
    var guardEnabled by remember { mutableStateOf(prefs.getBoolean(InterceptConfig.KEY_GUARD_ENABLED, true)) }
    var mode by remember { mutableStateOf(InterceptConfig.mode(context)) }
    var bootGuard by remember { mutableStateOf(prefs.getBoolean(InterceptConfig.KEY_BOOT_GUARD_ENABLED, true)) }
    var bootSeconds by remember { mutableStateOf(InterceptConfig.bootSuppressSeconds(context)) }
    var autoStart by remember { mutableStateOf(prefs.getBoolean(InterceptConfig.KEY_AUTO_START_ON_BOOT, true)) }
    var targetPackage by remember { mutableStateOf(InterceptConfig.targetPackage(context)) }
    var monitorText by remember { mutableStateOf(prefs.getString(InterceptConfig.KEY_MONITOR_PACKAGES, "").orEmpty()) }
    var iconHidden by remember { mutableStateOf(prefs.getBoolean(InterceptConfig.KEY_ICON_HIDDEN, false)) }
    var showPicker by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<AppItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = readSnapshot(context)
            delay(1200L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                )
            )
            .padding(20.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Column(
                modifier = Modifier
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.secondaryContainer
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                Text(
                    text = "班牌悬浮助手控制台",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "开机自启 · 冒头顶回 · 关掉再拉起（全程不用无障碍）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionCard(title = "权限清单") {
                PermissionRow("悬浮窗 / 显示在其他应用上层", snapshot.overlay, onRequestOverlay)
                Spacer(Modifier.height(8.dp))
                PermissionRow("使用情况访问（识别前台是不是班牌）", snapshot.usage, onRequestUsage)
                Spacer(Modifier.height(8.dp))
                PermissionRow("忽略电池优化（防后台被冻结）", snapshot.battery, onRequestBattery)
                Spacer(Modifier.height(8.dp))
                PermissionRow("通知（前台服务常驻凭证）", snapshot.notification, onRequestNotification)
                Spacer(Modifier.height(8.dp))
                PermissionRow("厂商自启动白名单（可选，双保险）", false, onRequestAutoStart, buttonLabel = "去设置", grantedLabel = "需手动确认")
            }

            SectionCard(title = "拦截引擎") {
                ToggleRow(
                    label = "总开关",
                    detail = if (guardEnabled) "发现班牌冒头就处理" else "已停止判定，班牌可正常使用",
                    checked = guardEnabled
                ) { value ->
                    guardEnabled = value
                    prefs.edit().putBoolean(InterceptConfig.KEY_GUARD_ENABLED, value).apply()
                    if (value) GuardianService.start(context) else {
                        CurtainOverlay.hide()
                        GuardianService.stop(context)
                    }
                    EventLog.add(if (value) "拦截已开启" else "拦截已关闭")
                }

                DividerLine()

                Text(
                    text = "处理方式",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    ModeChip(
                        label = "顶回桌面",
                        selected = mode == InterceptConfig.MODE_BOUNCE,
                        modifier = Modifier.weight(1f)
                    ) {
                        mode = InterceptConfig.MODE_BOUNCE
                        InterceptConfig.setInterceptMode(context, InterceptConfig.MODE_BOUNCE)
                    }
                    Spacer(Modifier.width(8.dp))
                    ModeChip(
                        label = "幕布遮挡",
                        selected = mode == InterceptConfig.MODE_CURTAIN,
                        modifier = Modifier.weight(1f)
                    ) {
                        mode = InterceptConfig.MODE_CURTAIN
                        InterceptConfig.setInterceptMode(context, InterceptConfig.MODE_CURTAIN)
                    }
                }

                DividerLine()

                ToggleRow(
                    label = "开机压制",
                    detail = if (bootGuard) "开机后 " + InterceptConfig.describeSeconds(bootSeconds.toLong()) + " 内班牌一冒头就顶回去" else "不做开机压制",
                    checked = bootGuard
                ) { value ->
                    bootGuard = value
                    prefs.edit().putBoolean(InterceptConfig.KEY_BOOT_GUARD_ENABLED, value).apply()
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "压制时长：" + InterceptConfig.describeSeconds(bootSeconds.toLong()),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = bootSeconds.toFloat(),
                    onValueChange = { bootSeconds = it.toInt() },
                    onValueChangeFinished = {
                        InterceptConfig.setBootSuppressSeconds(context, bootSeconds)
                    },
                    valueRange = 15f..600f,
                    enabled = bootGuard
                )

                DividerLine()

                Text(
                    text = "目标班牌包名",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = targetPackage,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(
                        onClick = {
                            installedApps = loadInstallableApps(context)
                            showPicker = true
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("从已安装应用选择", fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onOpenDetails() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Text("打开本应用详情", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = monitorText,
                    onValueChange = {
                        monitorText = it
                        prefs.edit().putString(InterceptConfig.KEY_MONITOR_PACKAGES, it).apply()
                    },
                    label = { Text("额外的监控包名（逗号分隔，可留空）", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }

            SectionCard(title = "开机启动（无无障碍）") {
                ToggleRow(
                    label = "开机自启悬浮控制台",
                    detail = if (autoStart) "开机后自动拉起气泡与守护引擎" else "开机后不自动启动",
                    checked = autoStart
                ) { value ->
                    autoStart = value
                    prefs.edit().putBoolean(InterceptConfig.KEY_AUTO_START_ON_BOOT, value).apply()
                }
                DividerLine()
                Text(
                    text = "说明：安卓 10 起后台启动需要「前台服务 + 开机广播」，本应用已按此实现；" +
                        "首次安装后请先手动打开一次本应用，否则部分机型会拦截开机广播。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard(title = "运行状态") {
                StatusRow("守护引擎", if (snapshot.engineRunning) "运行中" else "已停止", snapshot.engineRunning)
                StatusRow("悬浮窗服务", if (snapshot.bubbleRunning) "运行中" else "已停止", snapshot.bubbleRunning)
                StatusRow("当前前台应用", snapshot.foreground, snapshot.foreground == targetPackage)
                StatusRow("累计拦截次数", snapshot.total.toString(), true)

                DividerLine()
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            AppActions.goHome(context)
                            AppActions.killBackground(context, targetPackage)
                            InterceptConfig.addIntercept(context)
                            EventLog.add("手动：已把班牌顶回桌面")
                            Toast.makeText(context, "已归位", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("立即归位", fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (CurtainOverlay.isShowing) CurtainOverlay.hide()
                            else CurtainOverlay.show(context, "班牌已暂停使用", "手动幕布遮挡")
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Text(if (CurtainOverlay.isShowing) "撤掉幕布" else "盖上幕布", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            GuardianService.start(context)
                            WatchdogScheduler.ensureArmed(context)
                            Toast.makeText(context, "已重启守护引擎", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("重启引擎", fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            iconHidden = false
                            prefs.edit().putBoolean(InterceptConfig.KEY_ICON_HIDDEN, false).apply()
                            FloatingWindowService.start(context)
                            Toast.makeText(context, "气泡已恢复", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Text(if (iconHidden) "恢复气泡" else "重挂气泡", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }

            SectionCard(title = "最近事件") {
                if (snapshot.logs.isEmpty()) {
                    Text(
                        text = "暂无记录",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    snapshot.logs.forEach { line ->
                        Text(
                            text = line,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    EventLog.clear()
                    snapshot = readSnapshot(context)
                }) {
                    Text("清空记录", fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        Text(
            text = "提示：幕布遮挡后长按屏幕 5 秒即可解除；所有设置即时生效，无需重启应用。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp)
        )
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("选择目标应用", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().height(450.dp)
                ) {
                    LazyColumn {
                        items(installedApps.size) { index ->
                            val item = installedApps[index]
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        targetPackage = item.packageName
                                        InterceptConfig.setTargetPackage(context, item.packageName)
                                        showPicker = false
                                        EventLog.add("目标已切换：" + item.packageName)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = item.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = item.packageName,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            }
        )
    }
}

private fun readSnapshot(context: Context): Snapshot {
    val notificationEnabled = try {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    } catch (t: Throwable) {
        false
    }
    val batteryOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    } else {
        true
    }
    val overlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
    return Snapshot(
        overlay = overlay,
        usage = ForegroundWatcher.hasUsageAccess(context),
        battery = batteryOk,
        notification = notificationEnabled,
        engineRunning = GuardianService.isRunning,
        bubbleRunning = FloatingWindowService.isServiceRunning,
        foreground = GuardianService.lastSeenPackage ?: "未知",
        total = InterceptConfig.totalIntercepts(context),
        logs = EventLog.snapshot()
    )
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            DividerLine()
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun DividerLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    )
}

@Composable
private fun PermissionRow(
    label: String,
    isGranted: Boolean,
    onGrantClick: () -> Unit,
    buttonLabel: String = "去授权",
    grantedLabel: String = "已启用"
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (isGranted) grantedLabel else "未启用",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
        Button(
            onClick = onGrantClick,
            modifier = Modifier.height(32.dp),
            shape = RoundedCornerShape(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 2.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isGranted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(buttonLabel, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ToggleRow(label: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = detail,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StatusRow(label: String, value: String, highlight: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
