package com.dshgo.app.notify

import android.content.Context
import com.dshgo.app.data.DshApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 盯着 DSH 的会话，谁跑完了就记一条。
 *
 * 完成信号来自事件流的 `api-session/status(sessionId, running)` —— `running` 由 true
 * 变 false 就是「这一轮回答结束」。这个判断只在**我们亲眼看见它从跑变成不跑**时才成立，
 * 启动时已经在跑的会话会先记进 [running] 基线，所以不会有「一启动就补发一堆通知」。
 *
 * 是个单例：Activity 和前台服务都读同一份，不会各推一条。
 */
object SessionWatcher {

    enum class Kind { Done, Error }

    data class Notice(
        val id: String,
        val sessionId: String,
        val title: String,
        val at: Long,
        val kind: Kind,
        val detail: String? = null,
    )

    private const val PREFS = "dsh_notices"
    private const val KEY_LIST = "list"
    private const val MAX_KEPT = 50
    private const val RECONNECT_MS = 5_000L

    private val _notices = MutableStateFlow<List<Notice>>(emptyList())
    val notices: StateFlow<List<Notice>> = _notices.asStateFlow()

    /** 未读数，用来给铃铛画角标。 */
    private val _unseen = MutableStateFlow(0)
    val unseen: StateFlow<Int> = _unseen.asStateFlow()

    /** 事件流是否连着 —— 状态条上要如实显示。 */
    private val _live = MutableStateFlow(false)
    val live: StateFlow<Boolean> = _live.asStateFlow()

    /** 进程级作用域：不跟 Activity 生命周期绑定，否则界面一销毁事件流就断。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var api: DshApi? = null
    private var stream: DshApi.EventStream? = null
    private var loop: Job? = null
    private var appContext: Context? = null

    /** id → 标题。事件里不带标题，靠启动快照 + `api-session/added` 维持。 */
    private val titles = HashMap<String, String>()

    /** 当前在跑的会话。 */
    private val running = HashSet<String>()

    @Synchronized
    fun start(context: Context, baseUrl: String) {
        if (loop?.isActive == true) return

        appContext = context.applicationContext
        load()

        val client = DshApi { baseUrl }
        api = client

        loop = scope.launch(Dispatchers.IO) {
            // 先拿一次全量：标题对照表 + 「已经在跑」的基线
            runCatching { client.snapshot() }.onSuccess { snap ->
                synchronized(this@SessionWatcher) {
                    titles.clear()
                    titles.putAll(snap.titles)
                    running.clear()
                    running.addAll(snap.running)
                }
            }

            while (isActive) {
                val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                stream = client.openEvents(object : DshApi.EventSink {
                    override fun onReady() {
                        _live.value = true
                    }

                    override fun onEmit(event: String, args: JSONArray) {
                        handle(event, args)
                    }

                    override fun onDown(reason: String) {
                        _live.value = false
                        done.complete(Unit)
                    }
                })
                done.await()
                runCatching { stream?.close() }
                stream = null
                if (isActive) delay(RECONNECT_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        loop?.cancel()
        loop = null
        runCatching { stream?.close() }
        stream = null
        api = null
        _live.value = false
    }

    fun markAllSeen() {
        _unseen.value = 0
        save()
    }

    fun clear() {
        _notices.value = emptyList()
        _unseen.value = 0
        save()
    }

    // ------------------------------------------------------------------

    private fun handle(event: String, args: JSONArray) {
        val sessionId = args.optString(0).orEmpty()
        if (sessionId.isEmpty()) return

        when (event) {
            // (sessionId, running)
            "api-session/status" -> {
                val isRunning = args.optBoolean(1, false)
                val wasRunning = synchronized(this) {
                    if (isRunning) {
                        running.add(sessionId); true
                    } else {
                        running.remove(sessionId)
                    }
                }
                if (!isRunning && wasRunning) {
                    push(sessionId, Kind.Done, null)
                }
            }

            // (summary) —— 借机补标题
            "api-session/added" -> {
                val summary = args.optJSONObject(0) ?: return
                val title = summary.optJSONObject("projections")
                    ?.optJSONObject("values")
                    ?.let { if (it.isNull("title")) "" else it.optString("title") }
                    .orEmpty()
                if (title.isNotEmpty()) synchronized(this) { titles[sessionId] = title }
            }

            // (sessionId, message)
            "api-session/error" -> {
                val message = args.optString(1).orEmpty()
                push(sessionId, Kind.Error, message.take(200).ifEmpty { null })
            }
        }
    }

    private fun push(sessionId: String, kind: Kind, detail: String?) {
        val title = synchronized(this) { titles[sessionId] }
            ?: "会话 ${sessionId.removePrefix("session-").take(8)}"

        val notice = Notice(
            id = "$sessionId-${System.currentTimeMillis()}",
            sessionId = sessionId,
            title = title,
            at = System.currentTimeMillis(),
            kind = kind,
            detail = detail,
        )
        _notices.value = (listOf(notice) + _notices.value).take(MAX_KEPT)
        _unseen.value += 1
        save()

        appContext?.let { NotificationCenter.post(it, notice) }
    }

    // ------------------------------------------------------------------
    // 持久化：重启 app 后通知列表还在，不然悬浮窗一开就是空的
    // ------------------------------------------------------------------

    private fun file(ctx: Context) = File(ctx.filesDir, "notices.json")

    private fun save() {
        val ctx = appContext ?: return
        runCatching {
            val arr = JSONArray()
            _notices.value.forEach { n ->
                arr.put(
                    JSONObject()
                        .put("id", n.id)
                        .put("sessionId", n.sessionId)
                        .put("title", n.title)
                        .put("at", n.at)
                        .put("kind", n.kind.name)
                        .put("detail", n.detail ?: JSONObject.NULL),
                )
            }
            val root = JSONObject()
                .put("unseen", _unseen.value)
                .put("list", arr)
            file(ctx).writeText(root.toString())
        }
    }

    private fun load() {
        val ctx = appContext ?: return
        runCatching {
            val f = file(ctx)
            if (!f.exists()) return
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("list") ?: JSONArray()
            val list = ArrayList<Notice>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    Notice(
                        id = o.optString("id"),
                        sessionId = o.optString("sessionId"),
                        title = o.optString("title"),
                        at = o.optLong("at"),
                        kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.Done),
                        detail = if (o.isNull("detail")) null else o.optString("detail"),
                    ),
                )
            }
            _notices.value = list
            _unseen.value = root.optInt("unseen", 0)
        }
    }
}
