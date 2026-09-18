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
    private const val KEY_MIGRATED = "connections_migrated"

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /** 全部连接，最近用过的在前。 */
    fun all(ctx: Context): List<Connection> {
        migrateIfNeeded(ctx)
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
     * 把老版本的单地址 + 已知地址列表迁成连接列表。只跑一次。
     *
     * 迁移完**不动** entry_url —— 它仍然是「当前入口地址」的唯一真源，
     * 连接列表只是给它加了名字和别的选项。这样万一迁移出问题，退回旧版本也能用。
     */
    private fun migrateIfNeeded(ctx: Context) {
        val p = sp(ctx)
        if (p.getBoolean(KEY_MIGRATED, false)) return
        p.edit().putBoolean(KEY_MIGRATED, true).commit()

        if (p.getString(KEY_LIST, null) != null) return   // 已经有列表了，别覆盖

        val prefs = Prefs(ctx)
        val current = prefs.entryUrl()
        val known = prefs.knownUrls()

        val urls = LinkedHashSet<String>()
        if (current.isNotEmpty()) urls.add(current)
        urls.addAll(known)
        if (urls.isEmpty()) return

        var activeId = ""
        val list = urls.map { url ->
            val c = Connection(id = deriveId(url), name = "", url = url)
            if (url == current) activeId = c.id
            c
        }
        persist(ctx, list)
        p.edit().putString(KEY_ACTIVE, activeId.ifEmpty { list.first().id }).commit()
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
