package com.dshgo.app.notify

import kotlinx.coroutines.withTimeoutOrNull
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
    /** WS 连续失败几次就退到轮询。1 次可能是网络抖动，2 次基本可以判定不支持。 */
    private const val WS_FAILURES_BEFORE_POLL = 2

    /** 轮询间隔。通知不比聊天，3 秒的延迟完全可接受。 */
    private const val POLL_MS = 3000L

    /** 轮询模式下每这么多次回头试一次 WS（× POLL_MS ≈ 1 分钟）。 */
    private const val WS_RETRY_EVERY = 20

    /** 单次 WS 连接的最长等待，超时也算失败。 */
    private const val OPEN_TIMEOUT_MS = 20000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var api: DshApi? = null
    private var stream: DshApi.EventStream? = null
    private var loop: Job? = null
    private var appContext: Context? = null

    /** id → 标题。事件里不带标题，靠启动快照 + `api-session/added` 维持。 */
    private val titles = HashMap<String, String>()

    /** 当前在跑的会话。 */
    private val running = HashSet<String>()

    /**
     * 最近一次事件流断开的原因，null 表示没出过错。
     *
     * 为什么要暴露出来：以前断了只把状态点变灰，**具体原因哪儿都看不到** ——
     * 用户只知道「没通知」，我只能猜。现在设置页直接显示。
     */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** 最近收到会话事件的时间（0 = 一条都没收到过）。 */
    private val _lastEventAt = MutableStateFlow(0L)
    val lastEventAt: StateFlow<Long> = _lastEventAt.asStateFlow()

    /**
     * 现在靠什么获知会话状态。
     *
     * DSH 的 WebSocket 事件流（`/api/remote.mux`）是 **0.1.2 之后**才有的；
     * 0.1.1 那一代根本没注册这个升级路由，WS 握手会被直接掐断。
     * 而 `session/list` 是各版本都有的普通 HTTP 接口 —— 所以 WS 连不上时
     * 退到轮询它，老版本也能收到通知。
     */
    private val _mode = MutableStateFlow(Mode.Stream)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    enum class Mode { Stream, Poll, Down }

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

            var wsFailures = 0
            var pollTicks = 0

            while (isActive) {
                // ── 轮询模式 ──
                // 上游是 0.1.1 那一代时没有 WS 路由，硬连只会不停被掐断。
                // 退到轮询 session/list —— 这个接口各版本都有。
                if (wsFailures >= WS_FAILURES_BEFORE_POLL) {
                    _mode.value = Mode.Poll
                    pollTick(client)
                    pollTicks++
                    // 每 POLL_MS × WS_RETRY_EVERY ≈ 1 分钟再试一次 WS：
                    // 上游升级之后能自动切回实时流。
                    if (pollTicks % WS_RETRY_EVERY != 0) {
                        delay(POLL_MS)
                        continue
                    }
                    wsFailures = 0
                }

                val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                stream = client.openEvents(object : DshApi.EventSink {
                    override fun onReady() {
                        _live.value = true
                        _lastError.value = null
                        _mode.value = Mode.Stream
                    }

                    override fun onEmit(event: String, args: JSONArray) {
                        _lastEventAt.value = System.currentTimeMillis()
                        handle(event, args)
                    }

                    override fun onDown(reason: String) {
                        _live.value = false
                        _lastError.value = buildString {
                            append(reason)
                            val extra = api?.lastCookieInfo
                            if (!extra.isNullOrEmpty()) append("｜").append(extra)
                        }
                        done.complete(Unit)
                    }
                })
                val opened = withTimeoutOrNull(OPEN_TIMEOUT_MS) { done.await() } != null
                runCatching { stream?.close() }
                stream = null
                // 连上过又断了 → 计入失败；一直连不上更要计
                wsFailures++
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

    /**
     * 轮询一次会话列表，靠 `running` 集合的变化判断「跑完了」。
     *
     * 这是 WS 的降级方案，语义上等价：原来靠 `api-session/status(sessionId, running)`
     * 事件，现在靠两次快照的差集。代价是最多 [POLL_MS] 的延迟。
     */
    private suspend fun pollTick(client: DshApi) {
        var failure: String? = null
        val snap = runCatching { client.snapshot() }.onFailure {
            failure = it.message?.take(80) ?: it.toString().take(80)
        }.getOrNull()
        if (snap == null) {
            _live.value = false
            _mode.value = Mode.Down
            // 轮询失败也要留下原因 —— 否则界面只显示「未连接」，无从判断是
            // 认证问题、网络问题，还是这个接口在宿主上不存在。
            val f = failure ?: "未知"
            _lastError.value = if (f.contains("404")) {
                // 404 = 这台 DSH 根本没有这个接口。0.1.1 那一代连
                // dsh-api-session-controller 都还没有，session/* 的 RPC 端点
                // 是后来才加的 —— 属于「服务端没提供」，不是能适配的差异。
                "这台 DSH 太老了，没有 App 需要的接口（session/list 返回 404）。" +
                    "升级 DSH 即可：npm i -g @deepseek-ai/dsh@latest"
            } else {
                "轮询 session/list 失败：$f"
            }
            return
        }
        _live.value = true
        _lastEventAt.value = System.currentTimeMillis()
        val finished = synchronized(this) {
            titles.putAll(snap.titles)
            val gone = running.filter { it !in snap.running }
            running.clear()
            running.addAll(snap.running)
            gone
        }
        for (id in finished) push(id, Kind.Done, null)
    }

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
