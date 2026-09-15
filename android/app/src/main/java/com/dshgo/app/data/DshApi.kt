package com.dshgo.app.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 与 DSH 说话 —— 只为一件事：**知道哪个会话跑完了**。
 *
 * DSH 界面里已经有会话列表、附件、审批，客户端不重复。但「跑完了推个通知」这件事
 * 页面做不到（页面在后台会被系统挂起），必须由原生侧维持一条连接。
 *
 * ## 两条通路
 *
 * **一元**：`POST /api/<ns>/<method>`，信封 `{type:'client-request', rpcId, method, payload:{args}}`。
 * 只用来在启动时拉一次全量会话，拿到 id→标题 的对照表（事件里不带标题）。
 *
 * **事件流**：`ws://…/api/remote.mux`，发 `{type:'open', streamId, endpoint:'$events', payload:{args:{}}}`。
 * 服务端推的逻辑帧有三种：
 * ```
 * {type:'ready',     clientId, host}                       ← 通路就绪
 * {type:'emit',      event, args}                          ← 单向广播，不用回
 * {type:'waterfall', event, eventId, agentId, request}     ← 必须回执
 * ```
 *
 * ## 为什么 waterfall 必须回执
 *
 * `approval/request`（工具审批）是 waterfall 模式：宿主会等每个订阅者表态。
 * 会话内的 DSH 网页本来就在订阅同一条流 —— 原生侧再订一条却**不回执**的话，
 * 宿主的 waterfall 会一直挂着，**把用户的审批卡住**。
 *
 * 所以这里对每个 waterfall 帧立刻回 `{kind:'next'}`（「我不处理，链条继续」），
 * 走 `POST /api/$events/result`。这样它只是旁观，永远不抢网页的活。
 */
class DshApi(private val baseUrlProvider: () -> String) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookies)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)   // 保活，防 NAT 静默断链
        .build()

    private val base: String get() = baseUrlProvider().trimEnd('/')

    /** 最近一次握手附带的 cookie 情况，写进诊断用（不参与逻辑）。 */
    var lastCookieInfo: String = "（还没试过）"
        private set

    // ------------------------------------------------------------------
    // 启动时的一次性快照
    // ------------------------------------------------------------------

    /** 会话的静态信息：标题，以及当前是否在跑。 */
    data class Snapshot(
        val titles: Map<String, String>,
        val running: Set<String>,
    )

    /**
     * 拉一次全量会话。
     *
     * 事件流里 `api-session/status` 只带 `(sessionId, running)`，**没有标题** ——
     * 通知要说「『检查库存字段』跑完了」，就得先有这张对照表。
     * 之后靠 `api-session/added` 增量补。
     */
    suspend fun snapshot(): Snapshot = withContext(Dispatchers.IO) {
        val value = unary("session/list", JSONObject().put("_request", JSONObject()))
        val items: JSONArray = value.optJSONArray("items") ?: JSONArray()

        val titles = HashMap<String, String>(items.length())
        val running = HashSet<String>()
        for (i in 0 until items.length()) {
            val s = items.optJSONObject(i) ?: continue
            val id = s.str("sessionId")
            if (id.isEmpty()) continue
            val title = s.optJSONObject("projections")
                ?.optJSONObject("values")
                ?.str("title")
                .orEmpty()
            if (title.isNotEmpty()) titles[id] = title
            if (s.optBoolean("running", false)) running.add(id)
        }
        Snapshot(titles, running)
    }

    private fun unary(endpoint: String, args: JSONObject): JSONObject {
        val envelope = JSONObject()
            .put("type", "client-request")
            .put("rpcId", UUID.randomUUID().toString())
            .put("method", endpoint)
            .put("payload", JSONObject().put("args", args))

        val request = Request.Builder()
            .url("$base/api/$endpoint")
            .post(envelope.toString().toRequestBody(JSON))
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { resp: Response ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: throw DshApiException("bad-json", "HTTP ${resp.code}：${text.take(120)}")
            val result = json.optJSONObject("result")
                ?: throw DshApiException("bad-envelope", text.take(120))
            if (!result.optBoolean("ok", false)) {
                val err = result.optJSONObject("error")
                throw DshApiException(
                    err?.str("code").orEmpty().ifEmpty { "unknown" },
                    err?.str("message").orEmpty().ifEmpty { "未知错误" },
                )
            }
            return result.optJSONObject("value") ?: JSONObject()
        }
    }

    // ------------------------------------------------------------------
    // 事件流
    // ------------------------------------------------------------------

    /**
     * 一条等待批准的请求。
     *
     * 字段名来自服务端：`approval/request` 的 request 带
     * `{agent, toolName, callId?, reason?, signal?}`（见 `dsh-tools` 发起审批处）。
     */
    data class ApprovalAsk(
        val clientId: String,
        val eventId: String,
        val toolName: String,
        val reason: String?,
    )

    interface EventSink {
        /** 通路建立。 */
        fun onReady()

        /** 单向广播。`args` 是事件参数数组。 */
        fun onEmit(event: String, args: JSONArray)

        /** 连接断开（重连由调用方负责）。 */
        /**
         * 收到一条审批请求，且**本端愿意接管**。
         *
         * 返回 true 表示已经接管 —— 调用方会挂起这条 waterfall 等用户决定；
         * 返回 false 则立刻回 `next`，交回给网页那条链路。
         */
        fun onApproval(ask: ApprovalAsk): Boolean = false

        fun onDown(reason: String)
    }

    /** 打开 `$events`。断开后 [EventSink.onDown] 会回调，隔几秒再调一次即可。 */
    fun openEvents(sink: EventSink): EventStream {
        val url = base.replaceFirst("http", "ws") + "/api/remote.mux"
        var clientId: String? = null

        // 显式把会话 cookie 附上，不依赖 OkHttp 的 cookie jar。
        //
        // 为什么要显式：jar 读的是 WebView 的 CookieManager，而那条路径在产品里
        // 出过一次问题 —— 握手被服务端掐断，客户端只看到 "unexpected end of stream"，
        // 完全看不出是「没带 cookie 被 401 了」。显式取一次还有个好处：取到几条
        // 能直接写进诊断，一眼就能分清是认证问题还是网络问题。
        // ★ 必须把 ws:// 换成 http:// 再解析：OkHttp 的 toHttpUrl() **不接受 ws 协议**，
        // 直接传会抛异常。这里原来用 runCatching 包着，异常被吞成空列表，
        // 表现为「cookie 0 条」—— 握手从来没带上会话 cookie，而日志上看不出原因。
        val cookieUrl = url.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")
        val jarCookies = runCatching {
            WebViewCookies.loadForRequest(cookieUrl.toHttpUrl())
        }.onFailure {
            lastCookieInfo = "读 cookie 失败：${it.message?.take(60)}"
        }.getOrDefault(emptyList())
        val cookieHeader = jarCookies.joinToString("; ") { "${it.name}=${it.value}" }
        val authCount = jarCookies.count { it.name.startsWith("dsh-auth") }
        if (jarCookies.isNotEmpty()) {
            lastCookieInfo = "cookie ${jarCookies.size} 条（其中 dsh-auth $authCount 条）"
        }

        val builder = Request.Builder().url(url)
        if (cookieHeader.isNotEmpty()) builder.header("Cookie", cookieHeader)

        val ws = client.newWebSocket(
            builder.build(),
            object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val frame = JSONObject()
                        .put("type", "open")
                        .put("streamId", "dsh-watch")
                        .put("endpoint", "\$events")
                        .put("payload", JSONObject().put("args", JSONObject()))
                    webSocket.send(frame.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val outer = runCatching { JSONObject(text) }.getOrNull() ?: return
                    if (outer.str("type") != "item") return
                    val v = outer.optJSONObject("value") ?: return

                    when (v.str("type")) {
                        "ready" -> {
                            clientId = v.str("clientId").ifEmpty { null }
                            sink.onReady()
                        }

                        "emit" -> {
                            val event = v.str("event")
                            val args = v.optJSONArray("args") ?: JSONArray()
                            sink.onEmit(event, args)
                        }

                        "waterfall" -> {
                            val cid = clientId
                            val eventId = v.str("eventId")
                            if (cid == null || eventId.isEmpty()) return@onMessage

                            // 审批是唯一值得接管的一类 waterfall：手机上你人不在，
                            // agent 卡在审批上就是纯浪费。其余一律立刻表态「不处理，继续」——
                            // 不回执会让宿主的 waterfall 一直挂着，把网页那边的审批卡死。
                            val ask = if (v.str("event") == "approval/request") {
                                val req = v.optJSONObject("request")
                                ApprovalAsk(
                                    clientId = cid,
                                    eventId = eventId,
                                    toolName = req?.optString("toolName").orEmpty().ifEmpty { "操作" },
                                    reason = req?.optString("reason")?.ifEmpty { null },
                                )
                            } else null

                            if (ask == null || !sink.onApproval(ask)) replyNext(cid, eventId)
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // 把状态码和响应体一起带上。只报 t.message 的话，握手被拒时
                    // 往往只有一句泛泛的「Expected HTTP 101」，而真正的原因
                    // （401 未授权 / 403 栅栏 / 404 路由不在）全在 status 和 body 里。
                    val detail = buildString {
                        append(t.message ?: "连接中断")
                        if (response != null) {
                            append("｜HTTP ").append(response.code)
                            runCatching {
                                response.body?.string()?.trim()?.take(160)?.let {
                                    if (it.isNotEmpty()) append("｜").append(it)
                                }
                            }
                        }
                    }
                    sink.onDown(detail)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    sink.onDown("事件流已关闭（code=$code${if (reason.isNotEmpty()) " $reason" else ""}）")
                }
            },
        )
        return EventStream(ws)
    }

    /**
     * 回执 waterfall。
     *
     * 只有两种合法 outcome（服务端的 `parseRemoteEventResult` 就是这么校验的）：
     *
     * ```
     * {kind:'next'}                          我不处理，链条继续
     * {kind:'result', value:<任意 JSON>}     我处理了，结果是 value
     * ```
     *
     * 审批的 value 取 `allowed-once` / `rejected`（见 `dsh-user-approval` 的
     * `OUTCOMES`）。绝不能占着主线程。
     */
    private fun reply(cid: String, eventId: String, outcome: JSONObject) {
        Thread({
            runCatching {
                unary(
                    "\$events/result",
                    JSONObject()
                        .put("clientId", cid)
                        .put("eventId", eventId)
                        .put("outcome", outcome),
                )
            }
        }, "dsh-event-reply").start()
    }

    private fun replyNext(cid: String, eventId: String) =
        reply(cid, eventId, JSONObject().put("kind", "next"))

    /** 替用户回答一条审批。 */
    fun answerApproval(ask: ApprovalAsk, allow: Boolean) = reply(
        ask.clientId,
        ask.eventId,
        JSONObject()
            .put("kind", "result")
            .put("value", if (allow) "allowed-once" else "rejected"),
    )

    class EventStream internal constructor(private val ws: WebSocket) {
        fun close() {
            runCatching {
                ws.send(JSONObject().put("type", "cancel").put("streamId", "dsh-watch").toString())
                ws.close(1000, "bye")
            }
        }
    }

    // ------------------------------------------------------------------

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

class DshApiException(val code: String, override val message: String) : Exception(message)

/** JSON 的 null 安全取值：`optString` 遇到 JSON null 会返回字符串 "null"。 */
internal fun JSONObject.str(key: String): String =
    if (isNull(key)) "" else optString(key).orEmpty()
