package com.dsh.remote.web

import com.dsh.remote.Prefs

/**
 * 往 DSH 页面里注入的一小段脚本 —— **客户端侧的布局控制**。
 *
 * ## 为什么不能在主文档之外做
 *
 * DSH 决定用抽屉布局还是电脑布局，看的是页面启动时的一次媒体查询：
 *
 * ```js
 * const narrowMQ = window.matchMedia('(max-width: 1023px)');
 * ```
 *
 * 它是**纯看视口宽度**的，而且只在启动时判定一次。所以：
 *
 * - 页面加载完再改，已经晚了（布局早定了）
 * - 改 CSS 也没用（判定走的是 JS，不是 CSS 媒体查询）
 *
 * 只有一个时机有效：**在页面自己的脚本执行之前**，把 `matchMedia` 对这一个查询的
 * 返回值改掉。这正是 `shouldInterceptRequest` + HTML 注入能做的事。
 *
 * ## 为什么不改视口宽度
 *
 * 把 `<meta viewport>` 的 `width=device-width` 换成 `width=1100` 确实能让
 * `matchMedia` 返回 false，但手机只有 ~411dp 宽，WebView 会把 1100px 的布局
 * 缩到 0.37 倍 —— 字小到没法看。
 *
 * 覆盖媒体查询的结果则是**在原始宽度下**切换布局形态，跟 DSH 自己认的窄屏/宽屏
 * 是同一个效果。
 *
 * ## 失败会怎样
 *
 * 判断依据是那个查询字符串。DSH 若改了断点，这段覆盖会静默失效 ——
 * 退化成「自动」，不会把页面弄坏。polyfill 同理：只在缺失时才补。
 */
object PageInject {

    /** 注入标记。既用于防重复注入，也让测试能断言。 */
    const val MARK = "data-dsh-app-inject"

    /**
     * 不同适配层用的查询串**不一样**，所以按语义匹配而不是精确比对：
     *
     * ```
     * pocket 移植版：  (max-width: 1023px)
     * dsh-web-mobile： (max-width: 1023px) and (pointer: coarse)   ← 多个 pointer 条件
     *                  (min-width: 1024px)                          ← 还有一条反向查询
     * ```
     *
     * 早期版本只精确匹配第一种，结果驱动不了上游版本 —— 症状是「设置了但布局不变」，
     * 而且不报错。所以这里归一化后只认「这是窄屏判定还是宽屏判定」，两种都喂。
     */
    private const val NARROW_KEY = "(max-width:1023px)"
    private const val WIDE_KEY = "(min-width:1024px)"

    /**
     * 生成注入脚本。
     *
     * @param layout [Prefs.LAYOUT_AUTO] / [Prefs.LAYOUT_MOBILE] / [Prefs.LAYOUT_DESKTOP]
     * @param needPolyfill 上游代理没注入过 polyfill 时才补（避免重复）
     */
    fun script(layout: String, needPolyfill: Boolean): String {
        // null = 不覆盖，交给 DSH 自己按视口宽度判定
        val forced: String = when (layout) {
            Prefs.LAYOUT_DESKTOP -> "false"   // 不是窄屏 → 电脑布局
            Prefs.LAYOUT_MOBILE -> "true"     // 是窄屏 → 抽屉布局
            else -> "null"
        }

        return buildString {
            append("<script ").append(MARK).append("=\"1\">(function(){")
            if (needPolyfill) {
                append(POLYFILL_JS)
            }
            append("try{")
            append("var F=").append(forced).append(";")
            append("if(F!==null){")
            append("var orig=window.matchMedia.bind(window);")
            append("var key=function(s){return String(s).replace(/\\s+/g,'').toLowerCase()};")
            // 只截「窄屏/宽屏」这两类断言，其余原样转发 —— 别影响页面上的其它库
            append("window.matchMedia=function(q){")
            append("var k=key(q),want=null;")
            append("if(k.indexOf('").append(NARROW_KEY).append("')>=0)want=F;")
            append("else if(k.indexOf('").append(WIDE_KEY).append("')>=0)want=!F;")
            append("if(want===null||want===undefined)return orig(q);")
            append("var ls=[];")
            append("return{matches:want,media:String(q),onchange:null,")
            append("addEventListener:function(t,f){if(t==='change'&&ls.indexOf(f)<0)ls.push(f)},")
            append("removeEventListener:function(t,f){var i=ls.indexOf(f);if(i>=0)ls.splice(i,1)},")
            append("addListener:function(f){if(ls.indexOf(f)<0)ls.push(f)},")
            append("removeListener:function(f){var i=ls.indexOf(f);if(i>=0)ls.splice(i,1)},")
            append("dispatchEvent:function(){return false}};")
            append("};")
            append("}")
            append("}catch(e){}")
            append("})();</script>")
        }
    }

    /**
     * 把脚本插到 `<head>` 之后（页面自己的脚本之前）。
     * 已经有标记就原样返回，重复注入会让标记失去意义。
     */
    fun inject(html: String, script: String): String {
        if (html.contains(MARK)) return html
        val head = HEAD_RE.find(html)
        return if (head != null) {
            val at = head.range.last + 1
            html.substring(0, at) + script + html.substring(at)
        } else {
            script + html
        }
    }

    /** 上游代理（dsh-lan / dsh-pocket）已经补过 polyfill 就不再补。 */
    fun proxyAlreadyPolyfilled(html: String): Boolean =
        html.contains("data-dsh-lan-polyfill") || html.contains("data-dsh-pocket-polyfill")

    private val HEAD_RE = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)

    /**
     * 两个 polyfill，只在缺失时定义，绝不覆盖原生实现。
     *
     * `crypto.randomUUID` 在 `http://<IP>`（非安全上下文）里原生不可用，
     * 而 DSH 连接层拿它生成 RPC id；`AbortSignal.any` 在旧 WebView 上缺失会让
     * 发送消息直接失败。正常情况下上游代理会注入，这里只是**不依赖代理的兜底**。
     */
    private const val POLYFILL_JS = """
try{if(self.crypto&&!self.crypto.randomUUID){self.crypto.randomUUID=function(){var b=new Uint8Array(16);self.crypto.getRandomValues(b);b[6]=b[6]&15|64;b[8]=b[8]&63|128;var h='';for(var i=0;i<16;i++){var x=b[i].toString(16);h+=x.length<2?'0'+x:x;if(i===3||i===5||i===7||i===9)h+='-'}return h}}}catch(e){}
try{if(self.AbortSignal&&!self.AbortSignal.any){self.AbortSignal.any=function(signals){var ctrl=new AbortController(),list=Array.prototype.slice.call(signals||[]),done=false;function stop(s){if(done)return;done=true;try{ctrl.abort(s&&s.reason)}catch(e){ctrl.abort()}}for(var i=0;i<list.length;i++){if(list[i].aborted){stop(list[i]);break}list[i].addEventListener('abort',(function(s){return function(){stop(s)}})(list[i]),{once:true})}return ctrl.signal}}}catch(e){}
"""
}
