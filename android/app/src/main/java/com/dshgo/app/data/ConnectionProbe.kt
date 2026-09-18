package com.dshgo.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 探测每条连接「活着吗」。
 *
 * ## 为什么不需要登录也能给出有用的状态
 *
 * 探针打的是插件自己注册的两个端点：
 *
 * - `/__dshgo__/info` —— 插件在不在监听、上游端口、版本、是不是 Tailscale 地址
 * - `/__dshgo__/auth/status` —— 宿主有没有设访问密码
 *
 * 两个都**不需要先解锁**（`auth/status` 是闸门的白名单之一，见 lib/gate.mjs）。
 * 所以列表里那些你从没登录过的 DSH，也能显示「在线 / 离线 / 版本 / 要不要密码」。
 *
 * ## 做不到的那部分，说清楚
 *
 * **「这台 DSH 里有几个会话在跑」拿不到** —— 那要先登录它，而列表里的连接
 * 你未必都存过密码。当前连着的那一条有实时会话状态，靠的是事件流，不是探测。
 *
 * 不假装能做到，界面上也就不会出现一个永远空着的「0 个会话」。
 */
object ConnectionProbe {

    /** 单条探测的超时。列表是并发探的，最坏也就等这么久。 */
    private const val TIMEOUT_MS = 2500L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** 一条连接的探测结果。 */
    data class Status(
        val online: Boolean = false,
        /** 往返耗时（毫秒）。越小越可能是局域网直连。 */
        val ms: Long = 0L,
        /** 插件的版本；探不到时为空。 */
        val version: String = "",
        /** npm 上的最新版本；插件查不到时为空。 */
        val latestVersion: String = "",
        /** 宿主开了访问密码 —— 连它要先验证。 */
        val needsPassword: Boolean = false,
        /** 插件在监听。false 说明服务端起来但转发层没就绪。 */
        val listening: Boolean = false,
        /** 上游 `dsh web` 的端口，用来判断对面是不是 dshgo 的转发层。 */
        val upstreamPort: Int = 0,
        /** 失败原因；成功时为空。 */
        val error: String = "",
    ) {
        /** 有插件更新可装（只对自己这条有意义，但每条都能算）。 */
        val updateAvailable: Boolean get() = version.isNotEmpty() && latestVersion.isNotEmpty() &&
            com.dshgo.app.data.UpdateCheck.isNewer(latestVersion, version)
    }

    /**
     * 并发探一批。返回 `url → Status`。
     *
     * 并发而不是串行：五条连接串行各等 2.5 秒就是 12 秒，列表转半天。
     * 并发最坏 2.5 秒。
     */
    suspend fun probeAll(connections: List<Connection>): Map<String, Status> =
        withContext(Dispatchers.IO) {
            connections.map { c -> async { c.url to probe(c.url) } }
                .awaitAll()
                .toMap()
        }

    suspend fun probe(url: String): Status = withContext(Dispatchers.IO) {
        val base = url.trimEnd('/')
        val started = System.currentTimeMillis()

        val info = runCatching { getJson("$base/__dshgo__/info") }
            .getOrElse { return@withContext Status(online = false, ms = System.currentTimeMillis() - started, error = friendly(it)) }

        // 确认对面确实是 dshgo 的转发层，而不是同网段某个碰巧开了端口的东西
        if (info.optString("name") != "dshgo") {
            return@withContext Status(
                online = false,
                ms = System.currentTimeMillis() - started,
                error = "这个地址上不是 dshgo",
            )
        }

        val ms = System.currentTimeMillis() - started
        // 密码状态是另一个端点。它失败不影响「在线」这个判断 —— 少一个字段而已
        val needsPassword = runCatching { getJson("$base/__dshgo__/auth/status") }
            .getOrNull()?.optBoolean("hasPassword", false) ?: false

        Status(
            online = true,
            ms = ms,
            version = info.optString("version"),
            latestVersion = info.optString("latestVersion"),
            needsPassword = needsPassword,
            listening = info.optBoolean("listening", false),
            upstreamPort = info.optInt("upstreamPort", 0),
        )
    }

    private fun getJson(url: String): JSONObject {
        val parsed = url.toHttpUrlOrNull() ?: error("地址格式不对")
        val req = Request.Builder().url(parsed).header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return JSONObject(resp.body?.string().orEmpty())
        }
    }

    /** 把异常翻成用户能懂的一句话。 */
    private fun friendly(t: Throwable): String {
        val m = (t.message ?: "").lowercase()
        return when {
            "failed to connect" in m || "econnrefused" in m ->
                "拒绝连接 —— 电脑上的 dsh web 没在运行？"
            "timeout" in m || "timed out" in m ->
                "连不上 —— 不在同一网络？"
            "unable to resolve host" in m ->
                "地址解析不了"
            "地址格式不对" in m -> "地址格式不对"
            else -> t.message?.take(60) ?: "探测失败"
        }
    }
}
