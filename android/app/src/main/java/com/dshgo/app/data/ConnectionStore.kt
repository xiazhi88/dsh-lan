package com.dshgo.app.data

import android.content.Context
import com.dshgo.app.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条 DSH 连接。
 *
 * ## 为什么需要 id 而不是直接拿 url 当主键
 *
 * 两个原因：
 * 1. **同一个 DSH 可能有好几个地址**（家里局域网一个、出门 Tailscale 一个）。
 *    按 URL 当主键的话，那就是两条连接，用户得自己记住哪个是哪个。
 * 2. 重命名、换地址时，按 URL 找条目会在改地址那一刻找不到自己。
 *
 * id 一旦生成就不再变 —— 改名字、改地址都只是改字段。
 */
data class Connection(
    /** 稳定标识，创建后不变。 */
    val id: String,
    /** 用户起的名字；空则界面按地址自动生成一个。 */
    val name: String,
    /** 入口地址，如 `http://192.168.0.91:3081`。 */
    val url: String,
    /** 上次连上的时间（毫秒），0 = 没连过。用于排序与「最近用过」提示。 */
    val lastUsedAt: Long = 0L,
)

/**
 * 连接列表的存取。
 *
 * 存成一段 JSON 放在 SharedPreferences 里 —— 条目数量是个位数，
 * 为它引一个数据库不值得。
 *
 * ## 升级路径
 *
 * 老版本只存一个 `entry_url`，外加一份 `known_urls`（多地址自动选路用的）。
 * 这里**首次读取时把它们迁成连接列表**，用户升级后地址一个都不会丢：
 * 当前地址是「主连接」，其余已知地址各成一条，名字留空由界面生成。
 */
object ConnectionStore {

    private const val KEY_LIST = "connections_v1"
    private const val KEY_ACTIVE = "active_connection_id"

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 全部连接，最近用过的在前。
     *
     * ★ 每次读取都会跟 `entry_url` **对一次账** —— 不在列表里就补进去。
     *
     * 原来的做法是"只迁移一次"（一个 migrated 标志），那是错的：
     * 迁移跑的那天列表恰好是空的，这个状态就被永久固化了 ——
     * 后来手敲地址连上的机器**再也不会进列表**，回到首页看到的是「还没有连接」。
     * 实测才发现：代码看着对，跑一遍才暴露。
     *
     * 对账是幂等的、几乎不花钱，换来的是这种状态不会再卡死。
     */
    fun all(ctx: Context): List<Connection> {
        reconcileEntryUrl(ctx)
        val raw = sp(ctx).getString(KEY_LIST, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<Connection>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isEmpty()) continue
            out.add(
                Connection(
                    id = o.optString("id").ifEmpty { deriveId(url) },
                    name = o.optString("name"),
                    url = url,
                    lastUsedAt = o.optLong("lastUsedAt", 0L),
                ),
            )
        }
        return out.sortedByDescending { it.lastUsedAt }
    }

    /** 当前选中的连接；列表为空时返回 null。 */
    fun active(ctx: Context): Connection? {
        val list = all(ctx)
        if (list.isEmpty()) return null
        val id = sp(ctx).getString(KEY_ACTIVE, null)
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    fun activeId(ctx: Context): String? = active(ctx)?.id

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    fun setActive(ctx: Context, id: String) {
        sp(ctx).edit().putString(KEY_ACTIVE, id).commit()
    }

    /**
     * 新增或更新一条。按 [Connection.id] 匹配。
     *
     * 返回落库后的条目 —— 新建时 id 由这里生成，调用方要拿它去切过去。
     */
    fun upsert(ctx: Context, conn: Connection): Connection {
        val list = all(ctx).toMutableList()
        val stored = if (conn.id.isNotEmpty()) conn else conn.copy(id = newId())
        val idx = list.indexOfFirst { it.id == stored.id }
        if (idx >= 0) list[idx] = stored else list.add(stored)
        persist(ctx, list)
        return stored
    }

    /**
     * 记下「用过这条」。同时把它设成当前连接。
     *
     * lastUsedAt 是用来排序的 —— 最近连过的排前面，列表顺序才符合直觉。
     */
    fun markUsed(ctx: Context, id: String, at: Long = System.currentTimeMillis()) {
        val list = all(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = list[idx].copy(lastUsedAt = at)
        persist(ctx, list)
        setActive(ctx, id)
    }

    /**
     * 确保某个地址在列表里，并把它设为当前连接。返回那一条。
     *
     * ★ 为什么必须有这个：从设置页手敲地址连上时，只写了 entry_url ——
     * 而迁移只在首次读取跑一次（那时列表还是空的），于是**后连的地址永远不进列表**，
     * 回到首页看到的是「还没有连接」。这是实测才发现的：
     * 代码看着完全对，跑一遍才发现列表里没有刚连的那台。
     */
    fun ensure(ctx: Context, url: String): Connection? {
        if (url.isEmpty()) return null
        val list = all(ctx)
        val existing = list.firstOrNull { it.url == url }
        if (existing != null) {
            markUsed(ctx, existing.id)
            return existing
        }
        val created = upsert(ctx, Connection(id = "", name = "", url = url))
        setActive(ctx, created.id)
        return created
    }

    fun remove(ctx: Context, id: String) {
        val list = all(ctx).toMutableList()
        list.removeAll { it.id == id }
        persist(ctx, list)
        if (sp(ctx).getString(KEY_ACTIVE, null) == id) {
            sp(ctx).edit().putString(KEY_ACTIVE, list.firstOrNull()?.id ?: "").commit()
        }
    }

    // ------------------------------------------------------------------

    private fun persist(ctx: Context, list: List<Connection>) {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("url", c.url)
                    .put("lastUsedAt", c.lastUsedAt),
            )
        }
        sp(ctx).edit().putString(KEY_LIST, arr.toString()).commit()
    }

    /**
     * 让列表与「当前入口地址」（以及老版本的已知地址列表）保持一致。
     *
     * 幂等：每次读取都跑，代价是两次 SharedPreferences 读，可以忽略。
     * 好处是不会再出现「迁移那天列表是空的，于是永远空着」。
     */
    private fun reconcileEntryUrl(ctx: Context) {
        val p = sp(ctx)
        val prefs = Prefs(ctx)
        val current = prefs.entryUrl()

        val raw = p.getString(KEY_LIST, null)
        val arr = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
        val known = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val u = arr.optJSONObject(i)?.optString("url").orEmpty()
            if (u.isNotEmpty()) known.add(u)
        }
        known.addAll(prefs.knownUrls())

        val missing = known.filterNot { it.isEmpty() }
            .filter { url -> (0 until arr.length()).none { arr.optJSONObject(it)?.optString("url") == url } }
        val needCurrent = current.isNotEmpty() &&
            (0 until arr.length()).none { arr.optJSONObject(it)?.optString("url") == current }

        if (missing.isEmpty() && !needCurrent) return

        // ★ 直接从 arr 里读，**不能调 all(ctx)** —— all() 会再调回这里，无限递归。
        //   编译能过，跑起来栈溢出，所以宁可在这里多写几行。
        val list = ArrayList<Connection>(arr.length() + missing.size)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("url")
            if (u.isEmpty()) continue
            list.add(
                Connection(
                    id = o.optString("id").ifEmpty { deriveId(u) },
                    name = o.optString("name"),
                    url = u,
                    lastUsedAt = o.optLong("lastUsedAt", 0L),
                ),
            )
        }
        for (url in missing) {
            if (list.none { it.url == url }) list.add(Connection(id = deriveId(url), name = "", url = url))
        }
        persist(ctx, list)

        // 当前连接还没定过（或指向已删除的）就补一个
        val activeId = p.getString(KEY_ACTIVE, null).orEmpty()
        if (activeId.isEmpty() || list.none { it.id == activeId }) {
            val pick = list.firstOrNull { it.url == current }?.id ?: list.firstOrNull()?.id.orEmpty()
            p.edit().putString(KEY_ACTIVE, pick).commit()
        }
    }

    /** 从地址推一个稳定的 id。用户看不到它，只要同一地址每次都得到同一个即可。 */
    private fun deriveId(url: String): String = "c" + url.hashCode().toUInt().toString(16)

    private fun newId(): String = "c" + java.util.UUID.randomUUID().toString().take(12)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("dshgo_connections", Context.MODE_PRIVATE)
}

/**
 * 界面上怎么称呼一条连接。
 *
 * 没有名字时从地址里取一段 —— 局域网显示主机段（`192.168.0.91`），
 * Tailscale 显示「Tailscale」，其余用原地址。**不编造**（比如别自己叫它「我的电脑」），
 * 因为用户会以为那是他设过的名字。
 */
fun Connection.displayName(): String = when {
    name.isNotBlank() -> name
    url.contains("100.") && url.substringAfter("//").substringBefore(":").startsWith("100.") ->
        "Tailscale · " + url.substringAfter("//").substringBefore(":")
    else -> url.removePrefix("http://").removePrefix("https://").trimEnd('/')
}
