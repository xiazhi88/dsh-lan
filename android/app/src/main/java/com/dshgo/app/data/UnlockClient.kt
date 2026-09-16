package com.dshgo.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 和宿主的访问闸门打交道。
 *
 * 宿主（插件）在转发端口上加了一道密码闸门：浏览器看到密码页，App 看到 401。
 * 这里负责 App 那一侧 —— 问状态、交密码、拿解锁 Cookie。
 *
 * ## 为什么解锁 Cookie 要交给 WebView 的 CookieManager
 *
 * 解锁之后真正要访问 DSH 界面的是 WebView，不是 OkHttp。两者各有一套 Cookie，
 * 所以拿到 Cookie 之后必须**转存进 CookieManager** —— 否则会出现
 * "App 说解锁成功了，页面还是密码页"这种很难查的现象。
 */
object UnlockClient {

    private const val UNLOCK_PATH = "/__dshgo__/unlock"
    private const val STATUS_PATH = "/__dshgo__/auth/status"

    /** 解锁 Cookie 的名字。和插件侧 lib/auth.mjs 的 UNLOCK_COOKIE 必须一致。 */
    const val COOKIE_NAME = "dshgo-unlock"

    /**
     * 不跟随重定向、也不用 cookie jar —— 这里要的就是"看一眼 Set-Cookie 是什么"，
     * 让 OkHttp 自动存下来反而拿不到它。
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    sealed interface Unlock {
        /** 宿主没设密码，直接进。 */
        data object NotRequired : Unlock

        /** 解锁成功；[cookie] 要转存进 CookieManager。 */
        data class Ok(val cookie: String) : Unlock

        /** 密码不对 —— 调用方应该清掉本机存的、重新问用户。 */
        data object BadPassword : Unlock

        /** 网络或宿主的问题；[message] 直接给用户看。 */
        data class Failed(val message: String) : Unlock
    }

    /** 宿主有没有设密码。问不到时当作"没设"（保持可用优先）。 */
    suspend fun required(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(baseUrl.trimEnd('/') + STATUS_PATH)
                .header("User-Agent", UA)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                JSONObject(resp.body?.string().orEmpty()).optBoolean("hasPassword", false)
            }
        }.getOrDefault(false)
    }

    /** 交密码换解锁 Cookie。 */
    suspend fun unlock(baseUrl: String, password: String): Unlock = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("password", password).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(baseUrl.trimEnd('/') + UNLOCK_PATH)
                .header("User-Agent", UA)
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> {
                        // OkHttp 的 Headers 没有 getSetCookie()（那是 java.net 的 API），
                        // 用 values() 拿同名头的全部值
                        val setCookie = resp.headers.values("Set-Cookie")
                            .firstOrNull { it.startsWith("$COOKIE_NAME=") }
                            ?: return@use Unlock.Failed("宿主没有回解锁凭据")
                        // 只取 name=value，属性交给 CookieManager 自己解析
                        Unlock.Ok(setCookie.substringBefore(';'))
                    }
                    401 -> Unlock.BadPassword
                    // 404 = 宿主上根本没有这个端点 = 旧版插件或者没设密码
                    404 -> Unlock.NotRequired
                    else -> Unlock.Failed("宿主返回 HTTP ${resp.code}")
                }
            }
        }.getOrElse { Unlock.Failed(it.message ?: "连不上宿主") }
    }

    /** 让宿主知道这是 App —— 决定它给 JSON 还是给密码页。 */
    const val UA = "DSHGo/4.2.0 (Android)"
}
