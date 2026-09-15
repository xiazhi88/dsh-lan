# DSH 客户端协议（实测验证）

> 本文所有结论都在 `http://100.100.190.107:3081` 的真机上跑通过，不是读源码推测。
> 验证脚本：`tools/probe-api.mjs`、`tools/probe-stream.mjs`。

## 结论先说

**可以完全自己设计主界面。** DSH 对客户端暴露的是一套干净的双载体 RPC，
DSH 自己的 Web 前端也走同一套。你不用它的前端，照样能拿到会话列表、消息历史、
实时流式输出、审批请求。

---

## 一、两个载体

### 一元调用 —— HTTP

```
POST /api/<namespace>/<method>
Content-Type: application/json
Cookie: dsh-auth-<instance-key>=<signed-blob>

{
  "type": "client-request",
  "rpcId": "<任意非空字符串>",
  "method": "<namespace>/<method>",
  "payload": { "args": { ... } }
}
```

响应：

```json
{ "type": "server-response", "rpcId": "…",
  "result": { "ok": true, "value": { … } } }
```

失败：`result.ok` 为 `false`，`result.error` 形如
`{ code: "gateway/…", message: "…", details: { endpoint, field } }`。

> **端点用斜杠，不是点。** `session/list` ✓ / `session.list` ✗
> （踩过：点号会得到 `invalid Remote endpoint`）

> **一元方法不能走 WebSocket 载体**，会报
> `unary Remote methods cannot be opened through the stream carrier`。

### 流式调用 —— WebSocket

```
ws://<host>:3081/api/remote.mux        （同一个 cookie）
```

客户端帧只有两种：

```json
{ "type": "open",   "streamId": "<id>", "endpoint": "<ns>/<method>", "payload": { "args": { … } } }
{ "type": "cancel", "streamId": "<id>" }
```

服务端帧：

| type | 含义 |
|---|---|
| `item` | 一个流元素，真实负载在 `value` |
| `end` | 流正常结束 |
| `error` | `error: { code, message, details }` |

---

## 二、实测记录

### ① 会话列表（`session/list`）—— 通过

```bash
curl -s -X POST "$T/api/session/list" -H "Cookie: $CK" \
  -H 'Content-Type: application/json' \
  -d '{"type":"client-request","rpcId":"r1","method":"session/list",
       "payload":{"args":{"_request":{}}}}'
```

真实返回（截断）：

```json
{"result":{"ok":true,"value":{"items":[{
  "sessionId":"session-1030a51a-…","updatedAt":1789361786040,
  "running":true,"blank":false,"cwd":"/Users/xiazhi",
  "projections":{"asOfSeq":666,"values":{
    "title":"开发安卓 APK 连接 Mac dsh 服务",
    "goal":null,
    "tokenUsage":{"uncachedInputTokens":61177,"outputTokens":112621,
                  "cacheReadTokens":13036800,"cacheWriteTokens":0},
    "contextPressure":{"pressureTokens":185852,"contextWindow":1000000},
    "contextBreakdown":{"systemTokens":7786,"toolsTokens":14502,"messageTokens":132997},
    "sessionStats":{"turns":2,"steps":105,"llmMs":524666,"toolMs":123628},
    "turnOutline":[{"turn":1,"seq":5,"prompt":"…","response":"…"}],
    "modelSelection":{"lastUsed":{"provider":"deepseek-official",
                                  "model":"deepseek-flash","reasoningEffort":"max"}},
    "permissions":{"options":[…],"currentValue":"danger-full-access"},
    "agentPreset":"standard"
  }}]}}}
```

注意 `list` 的参数名是 `_request`（占位、传空对象即可）。

### ② 实时事件流（`$events`）—— 通过

```json
{"type":"open","streamId":"p1","endpoint":"$events","payload":{"args":{}}}
```

首个 `item` 就是主机握手信息：

```json
{"type":"item","streamId":"p1","value":{
  "type":"ready","clientId":"7431e0c2-…","host":{"home":"/Users/xiazhi"}}}
```

转发给客户端的事件（来自 `dsh-api-remotes` 的允许清单，与实测一致）：

| 事件 | 用途 |
|---|---|
| `api-session/added` `removed` `status` `activity` `error` | 会话列表与运行状态实时刷新 |
| `approval/request` | **工具审批弹窗**（自定义 UI 必须处理） |
| `user-questions/request` | agent 反问用户 |
| `goal/activation-changed` | 目标态变化 |
| `settings/document-updated` `llm/adapters-updated` | 设置/模型变化 |
| `commands/change` | 可用命令变化 |
| `agent-preset/selected` | 预设切换 |
| `credentials/reference-updated` | 凭据引用变化 |
| `cordis/*` | 插件热装/热卸 |

回执走**一元**端点 `$events/result`（HTTP）。

---

## 三、`session/*` API 全表

名称空间 `session`，一元走 `POST /api/session/<m>`，流走 `remote.mux`。
参数都包在 `payload.args` 里，键名见下表。

| 方法 | 载体 | args | 返回 / 帧 |
|---|---|---|---|
| `list` | 一元 | `{_request:{}}` | `{items:[…]}` |
| `create` | 一元 | `{request:{sessionId?, workspaceId?\|cwd?, agentPreset?}}` | 会话身份 + 预设 |
| `page` | 一元 | `{request:{address, throughSeq, beforeSeq?, maxMessages?}}` | `{records, hasMore}` |
| `follow` | **流** | `{request:{address, maxMessages?, assistantStream?:true}}` | 历史快照 + 实时增量 |
| `prompt` | 一元 | `{request:{requestId, sessionId, mode:'queue'\|'steer', content:[…], clientTimeZone?}}` | `{accepted:true}` |
| `cancel` | 一元 | `{request:{sessionId}}` | 确认 |
| `control` | **流** | 见 `types/control.d.ts` | 审批/提问的应答通道 |
| `rename` | 一元 | `{request:{sessionId, title?}}` | 确认 |
| `fork` | 一元 | `{request:{sessionId, …}}` | 新会话 |
| `search` | 一元 | `{query}` | 命中列表 |
| `modelCatalog` | 一元 | `{}`（**无 `_request`**） | 模型目录 |
| `selectModel` | 一元 | `{request:{sessionId, …}}` | 归一化后的选择 |
| `attachment` | 一元 | `{request:{sessionId, attachmentId}}` | `{attachment, data(base64)}` |
| `updateQueue` | 一元 | `{request:{sessionId, itemId, action}}` | 确认 |
| `inspect` / `canOpenWorkspacePath` / `openWorkspacePath` | 一元 | 另有命名空间 | — |

> 注意 `modelCatalog` 不接受 `_request`（传了会报
> `unexpected "_request"`）。各方法参数名不统一，以 `typert.remote-client.js` 的
> `parameters[].wire` 为准。

### 关键结构

```ts
// session/page 的 address
type SessionAddress =
  | { kind: 'session';  sessionId: string }
  | { kind: 'subagent'; parentSessionId: string; childSessionId: string;
      mode: 'one-shot' | 'continuable' };

// session/page → { records, hasMore }
interface SessionPageRecord {
  type: 'event';
  event: { type: string; seq: number; time: number; data: JsonValue;
           ignorable?: true; sourceEventSeqs?: JsonValue; surfaceOp?: JsonValue };
}

// session/prompt 的 content 元素（至少一个非空文本或附件）
interface SessionPromptRequest {
  requestId: string;          // 客户端自铸，回执用它对齐
  sessionId: string;
  mode: 'queue' | 'steer';    // 排队 / 打断当前轮
  content: readonly PromptContentPart[];
  clientTimeZone?: string;    // 'UTC' 或 IANA 名
}
```

---

## 四、授权与信任栅栏

- 所有请求都要带 `dsh-auth-*` cookie（30 天有效，绑定 `dsh web` 进程；重启即失效）
- 客户端**不能**直连 `127.0.0.1:3080`：`dsh web` 的 `/api` 栅栏只认 loopback
  Host/Origin，且官方禁了 `0.0.0.0` 绑定。必须经 `dsh-pocket` 的 3081 转发
- WebSocket 升级同样要过栅栏，`Origin` 要与 Host 一致

---

## 五、自定义 UI 的推荐形态

```
Android WebView  ──▶  自己写的 SPA（assets/）
                          │  POST /api/session/list   （走原生 fetch）
                          │  ws  /api/remote.mux      （走原生 WebSocket）
                          ▼
                    dsh-pocket :3081  ──▶  dsh web :3080
```

**不用 DSH 的前端产物**，`web` 这一层加载的是你自己的页面。

这样做的取舍：

| 维度 | 套壳（现状） | 自绘 SPA |
|---|---|---|
| 视觉控制力 | 只能改外壳 | **100%，全部自己写** |
| 复用 DSH 已有能力 | 全部（含工具卡片、diff、审批、文件树） | 需自己实现，或用 `/api` 数据自己渲染 |
| 工作量 | 已完成 | 中~大（取决于要还原多少） |
| 协议风险 | 无 | 依赖本文档的接口稳定性 |
