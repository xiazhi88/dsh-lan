package com.dshgo.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 从多个已知地址里挑一个能用的。
 *
 * ## 为什么需要
 *
 * 同一台电脑在两套网络里有两个地址：家里 WiFi 是 `192.168.x.x`，出门走 Tailscale
 * 是 `100.x.x.x`。手工切换既烦、又容易忘了切 —— 而选错了就是"连不上"。
 *
 * ## 探针为什么打在 `/__dshgo__/info`
 *
 * 那个端点是插件自己注册的，**只在 dshgo 的转发层上才有**。所以一次探测同时确认两件事：
 * 这个地址通不通，以及对面确实是 DSH 而不是同网段某个碰巧开了 80 端口的东西。
 *
 * 拿到的响应里还有 `listening` / `upstreamPort`，可以排除"插件在跑但上游 dsh web 没在跑"。
 *
 * ## 并发而不是串行
 *
 * 三个地址各等 2.5 秒就是 7.5 秒。并发探测最坏也就 2.5 秒，而且**取最快的那个**
 * —— 局域网通常十几毫秒返回，Tailscale 可能要一两百毫秒，这个差距正好用来选路。
 */
object AddressPicker {

    /** 单个地址的探测超时。够局域网和 Tailscale 响应，又不至于让人干等。 */
    private const val PROBE_TIMEOUT_MS = 2500L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    data class Candidate(
        val url: String,
        /** 往返耗时（毫秒）。越小越可能是局域网直连。 */
        val ms: Long,
    )

    /**
     * 并发探测所有地址，按耗时从快到慢返回**能用的那些**。
     *
     * 探测失败的直接丢掉 —— 不做"失败也留着当备选"，因为选路的输入就该只有能用的。
     */
    suspend fun probeAll(urls: List<String>): List<Candidate> = withContext(Dispatchers.IO) {
        urls.distinct().map { url ->
            async { probe(url) }
        }.awaitAll().filterNotNull().sortedBy { it.ms }
    }

    /**
     * 挑一个最好的。全都探测失败时返回 null —— 调用方据此保持现状，
     * 不要因为探测失败就把用户的地址改掉。
     */
    suspend fun pickBest(urls: List<String>): Candidate? = probeAll(urls).firstOrNull()

    private fun probe(url: String): Candidate? {
        val base = url.trimEnd('/')
        val parsed = "$base/__dshgo__/info".toHttpUrlOrNull() ?: return null
        val started = System.currentTimeMillis()
        return runCatching {
            client.newCall(Request.Builder().url(parsed).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                // 确认是 dshgo 的转发层，而且它认为自己正在监听
                val json = JSONObject(body)
                if (json.optString("name") != "dshgo") return null
                if (!json.optBoolean("listening", false)) return null
                Candidate(base, System.currentTimeMillis() - started)
            }
        }.getOrNull()
    }
}
