package com.dshgo.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本判定的测试。
 *
 * 这段逻辑看着简单，但**字符串比较会得出「3.10 < 3.9」这种荒谬结果**，
 * 所以必须逐段转数字。而这些纯函数是能测的 —— 就该测。
 */
class UpdateCheckTest {

    @Test
    fun `标签能解析出版本号`() {
        assertEquals("3.6.5", UpdateCheck.tagToVersion("app-v3.6.5"))
        assertEquals("3.6.5", UpdateCheck.tagToVersion("v3.6.5"))
        assertEquals("3.6.5", UpdateCheck.tagToVersion("3.6.5"))
        assertEquals("1.2.3", UpdateCheck.tagToVersion("release-1.2.3-final"))
    }

    @Test
    fun `标签里没有版本号就返回 null 而不是瞎猜`() {
        assertNull(UpdateCheck.tagToVersion(""))
        assertNull(UpdateCheck.tagToVersion("latest"))
        assertNull(UpdateCheck.tagToVersion("app-v"))
    }

    @Test
    fun `逐段比数字，不是比字符串`() {
        // 字符串比较会把 "3.10" 判成小于 "3.9" —— 这是这个测试存在的理由
        assertTrue(UpdateCheck.isNewer("3.10.0", "3.9.0"))
        assertFalse(UpdateCheck.isNewer("3.9.0", "3.10.0"))
    }

    @Test
    fun `各段依次比较`() {
        assertTrue(UpdateCheck.isNewer("3.7.0", "3.6.9"))
        assertTrue(UpdateCheck.isNewer("4.0.0", "3.99.99"))
        assertFalse(UpdateCheck.isNewer("3.6.5", "3.6.5"))
        assertFalse(UpdateCheck.isNewer("3.6.4", "3.6.5"))
    }

    @Test
    fun `段数不一样时缺的当零`() {
        assertTrue(UpdateCheck.isNewer("3.6.6", "3.6"))
        assertFalse(UpdateCheck.isNewer("3.6", "3.6.0"))
        assertTrue(UpdateCheck.isNewer("3.7", "3.6.9"))
    }

    @Test
    fun `后缀不影响比较`() {
        // 版本号里可能带 -rc / +build 之类，正则应把数字段取出来即可
        assertTrue(UpdateCheck.isNewer("3.7.0-rc.1", "3.6.5"))
        assertFalse(UpdateCheck.isNewer("3.6.5", "3.6.5-rc.2"))
    }

    @Test
    fun `垃圾输入不会崩，也不会误报有新版本`() {
        assertFalse(UpdateCheck.isNewer("", "3.6.5"))
        assertFalse(UpdateCheck.isNewer("abc", "3.6.5"))
        assertFalse(UpdateCheck.isNewer("3.6.5", ""))
    }
}
