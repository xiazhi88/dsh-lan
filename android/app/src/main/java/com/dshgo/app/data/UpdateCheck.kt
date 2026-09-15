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
 * **多源。** GitHub 国内经常访问不了，所以清单优先走 jsDelivr 镜像
 * （`cdn.jsdelivr.net/gh/…@latest/version.json`），失败再回落 GitHub 的 release 接口。
 * APK 同理：镜像优先，官方兜底。两条路都不通也不算「出错」，只是「不知道」。
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

    /** 官方下载地址（release 资产名固定，这条链接长期有效）。 */
    const val APK_URL = "https://github.com/xiazhi88/dshgo/releases/latest/download/dshgo-app.apk"

    /**
     * jsDelivr 的**包元数据接口** —— 返回仓库的全部 tag。
     *
     * ★ 这是整个更新检查里最关键的一条。踩过的坑，按时间顺序：
     *
     * 1. `@dist/version.json`（分支）—— 分支缓存能卡死三个版本：
     *    GitHub 上已经是 4.0.0，jsDelivr 还咬着 3.7.0，**连 purge 报 finished 都不动**。
     * 2. `@latest/version.json` —— 以为 @latest 服务端解析就新鲜，实测**同样被缓存**
     *    （purge 后仍是 4.0.0）。
     * 3. **具体 tag 永远新鲜** ✓ —— 但知道 tag 名字需要一个新鲜的指针。
     *
     * 这个元数据接口就是那个指针：它是 API 不是静态文件，所以**不经过文件缓存**。
     * 拿到 tag 列表 → 取最新的 `dist-v<版本>` → 从那个**不可变 tag** 读清单和 APK。
     * 全程 jsDelivr，国内可达。
     */
    private const val JSDELIVR_META =
        "https://data.jsdelivr.com/v1/packages/gh/xiazhi88/dshgo"

    /** 镜像 tag 的命名：`dist-v<版本>`。见 tools/publish-dist.sh。 */
    private const val TAG_PREFIX = "dist-v"

    private fun cdnApk(version: String) =
        "https://cdn.jsdelivr.net/gh/xiazhi88/dshgo@$TAG_PREFIX$version/dshgo-app.apk"

    private const val LATEST_API =
        "https://api.github.com/repos/xiazhi88/dshgo/releases/latest"

    /**
     * npmmirror（阿里托管的 npm 国内镜像）上的包元数据。
     *
     * 为什么需要第三个源：jsDelivr 对**分支**的缓存能拖到 12 小时，连 purge 都
     * 时好时坏（实测：GitHub 上已经是 3.8.0，jsDelivr 还咬着 3.7.0，加查询参数
     * 也绕不过）。而 npm 是**按版本不可变**的，registry 返回的永远是最新元数据 ——
     * 国内可达性又好。所以它是最可靠的那个源。
     *
     * App 版本信息放在 package.json 的 `dshgo.appVersion`，registry 会原样返回。
     */
    private const val NPMMIRROR_API = "https://registry.npmmirror.com/dshgo/latest"

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
        /** 优先用的下载地址（镜像），拿不到清单时退回官方。 */
        val apkUrl: String = APK_URL,
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

        // 所有源都问一遍，取**最大的那个版本号**。
        //
        // 不"第一个成功就返回"：某个源可能因为缓存落后好几个版本，先问它就会
        // 把新版本挡掉。取最大值既能让快的源立刻生效，也不怕慢的源拖后腿。
        //
        // jsDelivr 的 tag 列表放在第一位 —— 它最不容易失手（见 JSDELIVR_META 的说明）。
        val candidates = listOfNotNull(
            runCatching { fetchFromJsDelivrTags() }.getOrNull(),
            runCatching { fetchFromNpmMirror() }.getOrNull(),
            runCatching { fetchLatestTag() }.getOrNull()?.let(::tagToVersion)?.let { it to APK_URL },
        )
        val best = candidates.maxByOrNull { it.first }
        val latest = best?.first
        val apkUrl = best?.second ?: APK_URL

        prefs.setLastUpdateCheckAt(now)
        if (latest != null) prefs.setLastKnownLatest(latest)

        // 查失败时保留上一次的结果，总比什么都不显示强
        val effective = latest ?: prefs.lastKnownLatest()
        return Result(
            latest = effective,
            newer = effective != null && isNewer(effective, currentVersion),
            apkUrl = apkUrl,
        )
    }


    /**
     * 从 jsDelivr 的 tag 列表拿最新版本。返回 (版本号, APK 地址)。
     *
     * 取列表里**版本号最大的** `dist-v*`，而不是"第一个" —— 接口返回的顺序
     * 不该被我们依赖，版本号大小才是唯一可靠的依据。
     */
    private fun fetchFromJsDelivrTags(): Pair<String, String>? {
        val req = Request.Builder().url(JSDELIVR_META).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("versions")
                ?: return null
            var best: String? = null
            for (i in 0 until arr.length()) {
                val name = arr.optJSONObject(i)?.optString("version").orEmpty()
                if (!name.startsWith(TAG_PREFIX)) continue
                val v = tagToVersion(name.removePrefix(TAG_PREFIX)) ?: continue
                if (best == null || isNewer(v, best)) best = v
            }
            val v = best ?: return null
            return v to cdnApk(v)
        }
    }

    /**
     * 从 npmmirror 拿 App 版本。返回 (版本号, APK 地址)。
     *
     * 未发布到 npm 之前这个源会 404 —— 那属于"这个源还没有"，不是错误，
     * 直接返回 null 让其他源顶上。
     */
    private fun fetchFromNpmMirror(): Pair<String, String>? {
        val req = Request.Builder()
            .url(NPMMIRROR_API)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val o = JSONObject(resp.body?.string().orEmpty())
            val meta = o.optJSONObject("dshgo") ?: return null
            val v = tagToVersion(meta.optString("appVersion")) ?: return null
            val apk = meta.optString("appApk").ifEmpty { APK_URL }
            return v to apk
        }
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
