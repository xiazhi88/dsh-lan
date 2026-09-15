package com.dsh.remote.data

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 与 WebView 共用同一份 cookie 的 [CookieJar]。
 *
 * 手机端只有一条会话，各存一份必然会漂：原生握手拿到的 `dsh-auth-*` 只写进
 * [CookieManager]，后续无论是通知监听拉接口、还是页面注入那一次抓取，
 * 都必须带同一个 cookie。
 */
object WebViewCookies : CookieJar {

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
        return header.split(';').mapNotNull { part ->
            runCatching { Cookie.parse(url, part.trim()) }.getOrNull()
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val cm = CookieManager.getInstance()
        cookies.forEach { runCatching { cm.setCookie(url.toString(), it.toString()) } }
    }
}
