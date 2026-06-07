package com.example

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppItem(val name: String, val packageName: String)

class MainActivity : ComponentActivity() {

    private lateinit var sharedPrefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sharedPrefs = getSharedPreferences("class_brand_prefs", Context.MODE_PRIVATE)

        // Guide overlay permission on first startup automatically
        checkAndRequestFirstLaunchPermissions()

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    MainConfigScreen(
                        modifier = Modifier.padding(innerPadding),
                        onOpenOverlaySettings = { openOverlaySettings() },
                        onOpenAccessibilitySettings = { openAccessibilitySettings() },
                        onOpenAutoStartSettings = { openAutoStartSettings(this) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Force redraw or sync states if needed when user returns from settings pages
    }

    private fun checkAndRequestFirstLaunchPermissions() {
        // Overlay permission is critical for showing float window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "需要“在其他应用上显示”权限来开启悬浮窗", Toast.LENGTH_LONG).show()
            openOverlaySettings()
        }
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    startActivity(intent)
                } catch (err: Exception) {
                    Toast.makeText(this, "无法打开悬浮窗设置，请手动开启", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "请在列表中找到并开启「班牌悬浮助手」", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开无障碍设置，请在系统设置中手动开启", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAutoStartSettings(context: Context) {
        val intentList = listOf(
            Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.StartupNormalAppListActivity"),
            Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            Intent().setClassName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
        )
        for (intent in intentList) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                Toast.makeText(context, "已打开设备专属自启动选项，请授予自启动权限", Toast.LENGTH_SHORT).show()
                return
            } catch (e: Exception) {
                // Try next
            }
        }
        // Fallback to standard app settings Info or standard settings Activity
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Toast.makeText(context, "已打开自启动/电池优化设置，请在其中开启自启动", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "All auto start intents failed", e)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainConfigScreen(
    modifier: Modifier = Modifier,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAutoStartSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sharedPrefs = remember { context.getSharedPreferences("class_brand_prefs", Context.MODE_PRIVATE) }

    // Read stored config values
    var targetPackage by remember { mutableStateOf(sharedPrefs.getString("target_package", "test.ndk.com.ndktest2") ?: "test.ndk.com.ndktest2") }
    var autoStartBoot by remember { mutableStateOf(sharedPrefs.getBoolean("auto_start_on_boot", true)) }
    var bubbleOpacity by remember { mutableFloatStateOf(sharedPrefs.getFloat("bubble_opacity", 1.0f)) }
    var hasAutoStarted by remember { mutableStateOf(false) }
    
    // Permission dynamic states
    var isOverlayGranted by remember { mutableStateOf(true) }
    var isAccessibilityActive by remember { mutableStateOf(MyAccessibilityService.checkAccessibilityPermission(context)) }
    var isServiceRunningState by remember { mutableStateOf(FloatingWindowService.isServiceRunning) }

    // App List Picker state
    var showAppPickerDialog by remember { mutableStateOf(false) }
    var installedAppsList by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(false) }

    // Double-secure lifecycle aware states sync: whenever MainActivity is resumed (brought back),
    // update all critical status instantly so that permission grants take visual effect immediately!
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isOverlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(context)
                } else {
                    true
                }
                isAccessibilityActive = MyAccessibilityService.checkAccessibilityPermission(context)
                isServiceRunningState = FloatingWindowService.isServiceRunning
                
                if (!hasAutoStarted && isOverlayGranted && !FloatingWindowService.isServiceRunning) {
                    hasAutoStarted = true
                    try {
                        val intent = Intent(context, FloatingWindowService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                        isServiceRunningState = true
                    } catch (e: Exception) { }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Helper to query apps
    fun fetchApps() {
        if (installedAppsList.isNotEmpty()) {
            showAppPickerDialog = true
            return
        }
        isLoadingApps = true
        showAppPickerDialog = true
        scope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val filteredList = apps.filter {
                // Filter items to show user custom apps mostly
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                it.packageName.contains("class", ignoreCase = true) ||
                it.packageName.contains("school", ignoreCase = true) ||
                it.packageName.contains("brand", ignoreCase = true) ||
                it.packageName.contains("board", ignoreCase = true)
            }.map {
                val name = pm.getApplicationLabel(it).toString()
                AppItem(name, it.packageName)
            }.sortedBy { it.name }

            withContext(Dispatchers.Main) {
                installedAppsList = filteredList
                isLoadingApps = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                )
            )
            .padding(20.dp)
    ) {
        // App Launcher header
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.secondaryContainer
                            )
                        )
                    )
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "班牌悬浮助手控制台",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 0.5.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "专为各版本安卓定制的极客管理利器",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Permissions status panel
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "核心系统权限检测与设置",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // Float Window overlay permission
                        PermissionCheckRow(
                            label = "显示在其他应用上方 (悬浮窗)",
                            isGranted = isOverlayGranted,
                            onGrantClick = {
                                onOpenOverlaySettings()
                                Toast.makeText(context, "返回后请手动重新打开此页刷新状态", Toast.LENGTH_SHORT).show()
                            }
                        )

                        // Accessibility service active check
                        PermissionCheckRow(
                            label = "无障碍服务 (用于快捷结束进程)",
                            isGranted = isAccessibilityActive,
                            onGrantClick = onOpenAccessibilitySettings
                        )

                        // Autostart permission guidance
                        PermissionCheckRow(
                            label = "设备专有自启动项 (开机自动加载)",
                            isGranted = true, // We show self optimization settings
                            buttonLabel = "去配置",
                            onGrantClick = onOpenAutoStartSettings
                        )
                    }
                }
            }

            // 2. Class Brand Target Setup Panel
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "自定义目标应用设置",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Text(
                            text = "当点击悬浮窗“强行结束进程”时，默认全力优先寻找并结束下方指定的“电子班牌”包名。如果没有运行，则会自动强退当时打开的下方前台应用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = targetPackage,
                            onValueChange = {
                                targetPackage = it
                                sharedPrefs.edit().putString("target_package", it).apply()
                            },
                            label = { Text("电子班牌软件包名 (Package Name)") },
                            trailingIcon = {
                                IconButton(onClick = { fetchApps() }) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Select App Package Icon"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        Button(
                            onClick = { fetchApps() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.List, contentDescription = "App List Icon")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("或从安装的应用列表中选择")
                        }
                    }
                }
            }

            // 3. Float window Controls Panel
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "悬浮窗与开机启动控管",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // Toggle autostart boot config
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "开机自启动",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "在设备(电子班牌)开机启动时自动加载悬浮窗",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = autoStartBoot,
                                onCheckedChange = {
                                    autoStartBoot = it
                                    sharedPrefs.edit().putBoolean("auto_start_on_boot", it).apply()
                                    Toast.makeText(context, if (it) "已开启开机自启动配置" else "已禁用开机自启动配置", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Start / Stop service switch
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "运行悬浮窗服务",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isServiceRunningState) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "手动开启、关闭前台常驻悬浮窗",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = isServiceRunningState,
                                onCheckedChange = { checked ->
                                    val intent = Intent(context, FloatingWindowService::class.java)
                                    if (checked) {
                                        // Request overlay check before starting
                                        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                            Settings.canDrawOverlays(context)
                                        } else {
                                            true
                                        }

                                        if (!hasOverlay) {
                                            Toast.makeText(context, "请先授予上方悬浮屏显示权限！", Toast.LENGTH_LONG).show()
                                            onOpenOverlaySettings()
                                        } else {
                                            try {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    context.startForegroundService(intent)
                                                } else {
                                                    context.startService(intent)
                                                }
                                                isServiceRunningState = true
                                                Toast.makeText(context, "悬浮窗已开启", Toast.LENGTH_SHORT).show()
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "开启失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    } else {
                                        context.stopService(intent)
                                        isServiceRunningState = false
                                        Toast.makeText(context, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Bubble opacity slider
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "悬浮窗默认状态透明度: ${(bubbleOpacity * 100).toInt()}%",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Slider(
                                value = bubbleOpacity,
                                onValueChange = { 
                                    bubbleOpacity = it 
                                    sharedPrefs.edit().putFloat("bubble_opacity", it).apply()
                                },
                                valueRange = 0.1f..1.0f
                            )
                        }
                    }
                }
            }
            
            // 4. Testing Control Panel (Compatible for High Android versions)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "兼容测试与手动控制",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Text(
                            text = "若设备在此Android版本使用悬浮窗受限(例如反复停止)，您可以关闭悬浮窗，并使用下方的按钮进行测试或直接控制，功能与悬浮窗完全相同。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { handleForceKillFromActivity(context, targetPackage) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("强行结束", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }

                            Button(
                                onClick = { handleGoHomeFromActivity(context) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                    contentColor = MaterialTheme.colorScheme.onSecondary
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("返回桌面", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }

        // Quick tip guide
        Text(
            text = "配置完成后，您可以放心关闭此主界面，助手将常驻于悬浮窗并在后台持续护航。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
    }

    // App Picker Dialog structure
    if (showAppPickerDialog) {
        Dialog(onDismissRequest = { showAppPickerDialog = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(450.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "选择您的班牌应用",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    if (isLoadingApps) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(installedAppsList) { app ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            targetPackage = app.packageName
                                            sharedPrefs
                                                .edit()
                                                .putString("target_package", app.packageName)
                                                .apply()
                                            showAppPickerDialog = false
                                            Toast
                                                .makeText(
                                                    context,
                                                    "目标已设定为：「${app.name}」",
                                                    Toast.LENGTH_SHORT
                                                )
                                                .show()
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (targetPackage == app.packageName) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        }
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = app.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = app.packageName,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    TextButton(
                        onClick = { showAppPickerDialog = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("取消")
                    }
                }
            }
        }
    }
}

fun handleForceKillFromActivity(context: Context, targetPackage: String) {
    val asRunning = MyAccessibilityService.checkAccessibilityPermission(context)
    if (!asRunning) {
        Toast.makeText(context, "检测到无障碍不可用。\n正在跳转应用详情页，请手动点击【强行停止】", Toast.LENGTH_LONG).show()
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$targetPackage")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开应用详情", Toast.LENGTH_SHORT).show()
        }
        return
    }

    val onHome = MyAccessibilityService.isCurrentlyOnLauncher
    if (onHome) {
        Toast.makeText(context, "当前已经处于桌面状态", Toast.LENGTH_SHORT).show()
    }

    MyAccessibilityService.startForceStopSequence(context, targetPackage)
    Toast.makeText(context, "已触发结束应用流程...", Toast.LENGTH_SHORT).show()
}

fun handleGoHomeFromActivity(context: Context) {
    try {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        Toast.makeText(context, "已返回主屏幕", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "返回桌面失败", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun PermissionCheckRow(
    label: String,
    isGranted: Boolean,
    buttonLabel: String = "去授权",
    onGrantClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (isGranted) "已启用" else "未启用",
                fontSize = 11.sp,
                color = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold
            )
        }
        
        Button(
            onClick = onGrantClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isGranted) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                contentColor = if (isGranted) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onPrimary
                }
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
            modifier = Modifier.height(32.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = if (isGranted) "已设置" else buttonLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
