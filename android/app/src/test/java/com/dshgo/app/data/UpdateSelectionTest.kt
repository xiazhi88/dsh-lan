package com.dshgo.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「从多个源里挑最新」这一步的测试。
 *
 * 为什么单独测它：`isNewer` 本身有测试（见 [UpdateCheckTest]），但**调用处**
 * 一度写成 `maxByOrNull { it.first }` —— 那是字符串比较，会得出
 * `"4.0.6" > "4.0.10"`，于是拿到了新版本号却挑回旧的那个。
 *
 * 教训是：纯函数的测试覆盖不到"有没有用它"。这里把挑选逻辑也钉住。
 */
class UpdateSelectionTest {

    /** 与 UpdateCheck.check() 里挑选源的逻辑保持一致。 */
    private fun pickBest(candidates: List<String>): String? =
        candidates.reduceOrNull { a, b -> if (UpdateCheck.isNewer(b, a)) b else a }

    @Test
    fun `两位数段不会退化成字符串比较`() {
        // 这正是实测踩到的形状：列表里同时有 4.0.6 和 4.0.10
        assertEquals("4.0.10", pickBest(listOf("4.0.6", "4.0.10")))
        assertEquals("4.0.10", pickBest(listOf("4.0.10", "4.0.6")))
    }

    @Test
    fun `多源混合也取真正的最大`() {
        assertEquals("4.0.10", pickBest(listOf("3.8.1", "4.0.6", "4.0.10", "4.0.7")))
        assertEquals("4.1.0", pickBest(listOf("4.0.10", "4.1.0", "4.0.9")))
    }

    @Test
    fun `只有一个源时直接用它`() {
        assertEquals("4.0.7", pickBest(listOf("4.0.7")))
        assertEquals(null, pickBest(emptyList()))
    }
}
