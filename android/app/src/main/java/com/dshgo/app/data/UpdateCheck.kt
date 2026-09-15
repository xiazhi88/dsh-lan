package com.dshgo.app.data

import android.content.Context
import com.dshgo.app.Prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 检查有没有新版本。
 *
 * 数据源是 GitHub 的 release 接口 —— 没有自建服务，也就不需要维护一个。
 *
 * 三条原则：
 *
 * 1. **失败一律静默。** 检查更新不是主功能，网络不通（国内直连 GitHub 时常不通）
 *    不该在界面上留下任何痕迹 —— 更不该弹错误。查不到就是「不知道」，
 *    不是「出错了」。
 * 2. **不能拖慢启动。** 走后台线程 + 短超时，App 不等它。
 * 3. **自动检查有节流。** 一天一次就够 —— 每启动一次都打一次 GitHub 接口，
 *    既没必要也容易触发限流（未认证的 GitHub API 每小时 60 次）。
 */
object UpdateCheck {

    /** release 资产名固定不带版本号，所以这条链接长期有效。 */
    const val APK_URL = "https://github.com/xiazhi88/dshgo/releases/latest/download/dshgo-app.apk"

    private const val LATEST_API =
        "https://api.github.com/repos/xiazhi88/dshgo/releases/latest"

    /** 自动检查的间隔：一天。 */
    private const val AUTO_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** 一次检查的结果。 */
    data class Result(
        /** 远端版本号，如 `3.6.5`；拿不到时为 null。 */
        val latest: String?,
        /** 是否比当前安装的版本新。 */
        val newer: Boolean,
    ) {
        val unknown: Boolean get() = latest == null
    }

    /**
     * 查一次。永不抛异常 —— 拿不到就返回 [Result] 里 latest = null。
     *
     * @param force 忽略节流，立刻查（用户手动点「检查更新」时用）。
     */
    fun check(ctx: Context, currentVersion: String, force: Boolean = false): Result {
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        if (!force) {
            val last = prefs.lastUpdateCheckAt()
            if (now - last < AUTO_INTERVAL_MS) {
                // 节流期内：直接回报上次已知的结果，不再打网络
                val cached = prefs.lastKnownLatest()
                return Result(cached, cached != null && isNewer(cached, currentVersion))
            }
        }

        val latest = runCatching { fetchLatestTag() }.getOrNull()?.let(::tagToVersion)
        prefs.setLastUpdateCheckAt(now)
        if (latest != null) prefs.setLastKnownLatest(latest)

        // 查失败时保留上一次的结果，总比什么都不显示强
        val effective = latest ?: prefs.lastKnownLatest()
        return Result(effective, effective != null && isNewer(effective, currentVersion))
    }

    private fun fetchLatestTag(): String {
        val req = Request.Builder()
            .url(LATEST_API)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            return JSONObject(body).optString("tag_name")
        }
    }

    /** `app-v3.6.5` → `3.6.5`。标签格式变了就返回 null（不猜）。 */
    internal fun tagToVersion(tag: String): String? {
        val m = Regex("""v?(\d+(?:\.\d+)+)""").find(tag) ?: return null
        return m.groupValues[1]
    }

    /**
     * a 是否比 b 新。比较数字段，`-rc` 之类的后缀一律忽略。
     *
     * 有意做得简单：这个 App 的版本号就是 x.y.z，把它当字符串比会得出
     * 「3.10 < 3.9」这种荒谬结果，所以必须逐段转数字。
     */
    internal fun isNewer(a: String, b: String): Boolean {
        fun parts(v: String) = v.split('.', '-', '+')
            .mapNotNull { it.toIntOrNull() }
        val x = parts(a); val y = parts(b)
        // 任一边解析不出数字就判「不更新」。宁可漏报也不能误报 ——
        // 凭一段解析不了的字符串去提示用户升级，比不提示更糟。
        if (x.isEmpty() || y.isEmpty()) return false
        for (i in 0 until maxOf(x.size, y.size)) {
            val l = x.getOrElse(i) { 0 }
            val r = y.getOrElse(i) { 0 }
            if (l != r) return l > r
        }
        return false
    }
}
