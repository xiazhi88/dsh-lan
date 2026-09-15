package com.dshgo.app.web

import com.dshgo.app.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [PageInject] 的产物是**用字符串拼出来的 JS** —— 拼错一个字符就是页面上一处静默失效，
 * 肉眼看不出来。所以这里把三种布局的产物落到文件，再由外部 `node --check` 做语法校验
 * （见 `tools/check-injection.sh`）。
 *
 * 同时也断言几个关键契约：只在缺失时补 polyfill、只覆盖那一个媒体查询、
 * 重复注入不叠加。
 */
class PageInjectTest {

    private val outDir = File(System.getProperty("java.io.tmpdir"), "dsh-inject").apply { mkdirs() }

    private fun dump(name: String, js: String) {
        File(outDir, "$name.js").writeText(js)
    }

    @Test
    fun `三种布局都能生成合法脚本`() {
        for ((name, layout) in listOf(
            "auto" to Prefs.LAYOUT_AUTO,
            "mobile" to Prefs.LAYOUT_MOBILE,
            "desktop" to Prefs.LAYOUT_DESKTOP,
        )) {
            val js = PageInject.script(layout, needPolyfill = true)
            dump(name, js)
            assertTrue("$name: 应带注入标记", js.contains(PageInject.MARK))
            assertTrue("$name: 应补 randomUUID", js.contains("randomUUID"))
            assertTrue("$name: 应补 AbortSignal.any", js.contains("AbortSignal.any"))
        }
    }

    @Test
    fun `布局映射：宽屏=非窄屏，手机=窄屏，自动=不覆盖`() {
        assertTrue(PageInject.script(Prefs.LAYOUT_DESKTOP, false).contains("var F=false"))
        assertTrue(PageInject.script(Prefs.LAYOUT_MOBILE, false).contains("var F=true"))
        assertTrue(PageInject.script(Prefs.LAYOUT_AUTO, false).contains("var F=null"))
    }

    @Test
    fun `自动模式下即使带了覆盖代码也必须走 null 分支`() {
        // F=null 时代码不该改写 matchMedia 的返回；断言分支判断存在即可
        val js = PageInject.script(Prefs.LAYOUT_AUTO, false)
        assertTrue(js.contains("if(F!==null)"))
        assertTrue(js.contains("var F=null"))
    }

    @Test
    fun `两种查询串都要认，其余原样转发`() {
        val js = PageInject.script(Prefs.LAYOUT_DESKTOP, false)
        // pocket 移植版用 (max-width:1023px)，dsh-web-mobile 用带 pointer 条件的版本，
        // 还有一条 (min-width:1024px) 的反向查询 —— 都得覆盖
        assertTrue("要认窄屏断言", js.contains("(max-width:1023px)"))
        assertTrue("也要认宽屏断言", js.contains("(min-width:1024px)"))
        assertTrue("非目标查询要转发给原生实现", js.contains("return orig(q)"))
        assertTrue("宽屏断言取反", js.contains("want=!F"))
    }

    @Test
    fun `上游补过 polyfill 时不重复补`() {
        val without = PageInject.script(Prefs.LAYOUT_AUTO, needPolyfill = false)
        assertFalse("不该再补 randomUUID", without.contains("randomUUID"))
        // 但布局覆盖仍要在
        assertTrue(without.contains("matchMedia"))
    }

    @Test
    fun `注入到 head 之后，且在页面脚本之前`() {
        val html = "<html><head><meta charset=\"utf-8\"><script src=\"/app.js\"></script></head><body>x</body></html>"
        val script = PageInject.script(Prefs.LAYOUT_DESKTOP, true)
        val out = PageInject.inject(html, script)

        val injectAt = out.indexOf(PageInject.MARK)
        val appJsAt = out.indexOf("/app.js")
        assertTrue("应注入到 head 之后", out.indexOf("<head>") < injectAt)
        assertTrue("必须早于页面自己的脚本", injectAt < appJsAt)
    }

    @Test
    fun `重复注入不叠加`() {
        val script = PageInject.script(Prefs.LAYOUT_DESKTOP, true)
        val once = PageInject.inject("<html><head></head><body>x</body></html>", script)
        val twice = PageInject.inject(once, script)
        assertEquals(once, twice)
        assertEquals(1, twice.split(PageInject.MARK).size - 1)
    }

    @Test
    fun `没有 head 也能注入`() {
        val out = PageInject.inject("<html><body>x</body></html>", PageInject.script(Prefs.LAYOUT_AUTO, true))
        assertTrue(out.contains(PageInject.MARK))
    }

    @Test
    fun `识别上游代理的 polyfill 标记`() {
        assertTrue(PageInject.proxyAlreadyPolyfilled("<script data-dsh-lan-polyfill=\"1\"></script>"))
        assertTrue(PageInject.proxyAlreadyPolyfilled("<script data-dsh-pocket-polyfill=\"1\"></script>"))
        assertFalse(PageInject.proxyAlreadyPolyfilled("<html><head></head></html>"))
    }
}
