package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只测纯逻辑：包名解析、包名校验、时间窗判定、秒数格式化。
 * 这些函数不碰 Android API，所以可以在普通 JVM 上跑（无需 Robolectric）。
 */
class InterceptConfigTest {

    @Test
    fun parsePackageList_splitsOnCommonSeparators() {
        val raw = "com.foo.bar, com.baz.qux;com.aaa.bbb\ncom.ccc.ddd"
        val parsed = InterceptConfig.parsePackageList(raw)
        assertEquals(4, parsed.size)
        assertTrue(parsed.contains("com.foo.bar"))
        assertTrue(parsed.contains("com.ccc.ddd"))
    }

    @Test
    fun parsePackageList_dropsInvalidAndDuplicates() {
        val raw = "com.foo.bar,com.foo.bar,notapackage,,com.中文.x,"
        assertEquals(listOf("com.foo.bar"), InterceptConfig.parsePackageList(raw))
    }

    @Test
    fun parsePackageList_handlesNullAndBlank() {
        assertTrue(InterceptConfig.parsePackageList(null).isEmpty())
        assertTrue(InterceptConfig.parsePackageList("   ").isEmpty())
    }

    @Test
    fun isValidPackageName_followsAndroidRules() {
        assertTrue(InterceptConfig.isValidPackageName("test.ndk.com.ndktest2"))
        assertTrue(InterceptConfig.isValidPackageName("com.a_b.c1"))
        assertFalse(InterceptConfig.isValidPackageName("single"))
        assertFalse(InterceptConfig.isValidPackageName("1com.foo.bar"))
        assertFalse(InterceptConfig.isValidPackageName("com..bar"))
        assertFalse(InterceptConfig.isValidPackageName("com.foo-bar.baz"))
        assertFalse(InterceptConfig.isValidPackageName(""))
    }

    @Test
    fun isWithinWindow_respectsDeadline() {
        assertTrue(InterceptConfig.isWithinWindow(1_000L, 2_000L))
        assertFalse(InterceptConfig.isWithinWindow(2_000L, 2_000L))
        assertFalse(InterceptConfig.isWithinWindow(3_000L, 2_000L))
        assertFalse(InterceptConfig.isWithinWindow(1_000L, 0L))
    }

    @Test
    fun sanitizeSeconds_clampsAndFallsBack() {
        assertEquals(90, InterceptConfig.sanitizeSeconds(null))
        assertEquals(90, InterceptConfig.sanitizeSeconds("abc"))
        assertEquals(0, InterceptConfig.sanitizeSeconds("-5"))
        assertEquals(1800, InterceptConfig.sanitizeSeconds("99999"))
        assertEquals(45, InterceptConfig.sanitizeSeconds(" 45 "))
    }

    @Test
    fun describeSeconds_isReadable() {
        assertEquals("0 秒", InterceptConfig.describeSeconds(0L))
        assertEquals("45 秒", InterceptConfig.describeSeconds(45L))
        assertEquals("2 分 30 秒", InterceptConfig.describeSeconds(150L))
    }
}
