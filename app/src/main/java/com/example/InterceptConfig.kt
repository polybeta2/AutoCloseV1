package com.example

import android.content.Context
import android.content.SharedPreferences

/**
 * 全项目配置项的唯一出口。
 *
 * 这里只放"键名 + 纯逻辑"，不依赖任何 Android 组件，
 * 因此可以直接在 JVM 单元测试里验证（见 app/src/test/.../InterceptConfigTest.kt）。
 */
object InterceptConfig {

    const val PREFS_NAME = "class_brand_prefs"

    // ---------- 目标应用 ----------
    const val KEY_TARGET_PACKAGE = "target_package"
    const val DEFAULT_TARGET_PACKAGE = "test.ndk.com.ndktest2"

    /** 附加监控包名，逗号/分号/空格分隔。用于同时压制"自动监听服务"之类的拉起者。 */
    const val KEY_MONITOR_PACKAGES = "monitor_packages"

    // ---------- 守护引擎 ----------
    const val KEY_GUARD_ENABLED = "guard_enabled"
    const val KEY_INTERCEPT_MODE = "intercept_mode"
    const val MODE_BOUNCE = "bounce"
    const val MODE_CURTAIN = "curtain"
    const val KEY_GUARD_COOLDOWN_MS = "guard_cooldown_ms"
    const val DEFAULT_GUARD_COOLDOWN_MS = 4000L
    const val KEY_TICK_INTERVAL_MS = "tick_interval_ms"
    const val DEFAULT_TICK_INTERVAL_MS = 800L

    // ---------- 开机压制 ----------
    const val KEY_BOOT_GUARD_ENABLED = "boot_guard_enabled"
    const val KEY_BOOT_SUPPRESS_SECONDS = "boot_suppress_seconds"
    const val DEFAULT_BOOT_SUPPRESS_SECONDS = 90
    const val KEY_SUPPRESS_UNTIL = "suppress_until"

    // ---------- 悬浮窗 ----------
    const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    const val KEY_BUBBLE_OPACITY = "bubble_opacity"
    const val KEY_ICON_HIDDEN = "icon_hidden"
    const val KEY_TOTAL_INTERCEPTS = "total_intercepts"

    private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------- 写入 ----------

    fun setTargetPackage(context: Context, packageName: String) {
        val value = if (isValidPackageName(packageName)) packageName else DEFAULT_TARGET_PACKAGE
        prefs(context).edit().putString(KEY_TARGET_PACKAGE, value).apply()
    }

    fun setInterceptMode(context: Context, mode: String) {
        val value = if (mode == MODE_CURTAIN) MODE_CURTAIN else MODE_BOUNCE
        prefs(context).edit().putString(KEY_INTERCEPT_MODE, value).apply()
    }

    fun setBootSuppressSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_BOOT_SUPPRESS_SECONDS, seconds.coerceIn(0, 1800)).apply()
    }

    fun setGuardEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GUARD_ENABLED, enabled).apply()
    }

    // ---------- 读取 ----------

    fun targetPackage(context: Context): String {
        val raw = prefs(context).getString(KEY_TARGET_PACKAGE, DEFAULT_TARGET_PACKAGE)?.trim()
        return if (raw.isNullOrEmpty()) DEFAULT_TARGET_PACKAGE else raw
    }

    fun mode(context: Context): String =
        prefs(context).getString(KEY_INTERCEPT_MODE, MODE_BOUNCE) ?: MODE_BOUNCE

    fun guardEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_GUARD_ENABLED, true)

    fun bootGuardEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_BOOT_GUARD_ENABLED, true)

    fun autoStartOnBoot(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_START_ON_BOOT, true)

    fun tickIntervalMs(context: Context): Long =
        prefs(context).getLong(KEY_TICK_INTERVAL_MS, DEFAULT_TICK_INTERVAL_MS).coerceIn(300L, 5_000L)

    fun cooldownMs(context: Context): Long =
        prefs(context).getLong(KEY_GUARD_COOLDOWN_MS, DEFAULT_GUARD_COOLDOWN_MS).coerceIn(0L, 60_000L)

    fun bootSuppressSeconds(context: Context): Int =
        prefs(context).getInt(KEY_BOOT_SUPPRESS_SECONDS, DEFAULT_BOOT_SUPPRESS_SECONDS).coerceIn(0, 1800)

    /** 需要被压制的包名集合：目标应用 + 用户附加的监控包名。 */
    fun guardPackages(context: Context): Set<String> {
        val result = LinkedHashSet<String>()
        val target = targetPackage(context)
        if (isValidPackageName(target)) result.add(target)
        result.addAll(parsePackageList(prefs(context).getString(KEY_MONITOR_PACKAGES, "")))
        return result
    }

    // ---------- 纯函数（可单测） ----------

    /** 把用户填写的多包名字符串拆成规范列表：支持 , ; 空白 换行，自动去重并过滤非法包名。 */
    fun parsePackageList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',', ';', ' ', '\n', '\r', '\t', '，', '；')
            .map { it.trim() }
            .filter { isValidPackageName(it) }
            .distinct()
    }

    /** Android 包名规则：至少两级，每级以字母开头，只允许字母/数字/下划线。 */
    fun isValidPackageName(value: String): Boolean =
        value.isNotEmpty() && value.length <= 200 && PACKAGE_NAME_REGEX.matches(value)

    /** 当前时间是否仍处于某个截止时间之前（用于开机压制窗口）。 */
    fun isWithinWindow(now: Long, untilMillis: Long): Boolean = untilMillis > 0L && now < untilMillis

    fun sanitizeSeconds(raw: String?, fallback: Int = DEFAULT_BOOT_SUPPRESS_SECONDS): Int =
        raw?.trim()?.toIntOrNull()?.coerceIn(0, 1800) ?: fallback

    // ---------- 统计 ----------

    fun totalIntercepts(context: Context): Long = prefs(context).getLong(KEY_TOTAL_INTERCEPTS, 0L)

    fun addIntercept(context: Context, delta: Long = 1L): Long {
        val current = totalIntercepts(context)
        val next = current + delta
        prefs(context).edit().putLong(KEY_TOTAL_INTERCEPTS, next).apply()
        return next
    }

    fun describeSeconds(total: Long): String = when {
        total <= 0L -> "0 秒"
        total < 60L -> total.toString() + " 秒"
        else -> (total / 60).toString() + " 分 " + (total % 60).toString() + " 秒"
    }
}
