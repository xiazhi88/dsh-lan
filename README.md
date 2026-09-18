# dshgo

在手机 / 平板上用 DSH —— **一个 Android App，加一个让电脑可达的插件。**

| | |
|---|---|
| **[Android App](android/)** | 为手机做的客户端：会话跑完推送通知、**等批准时直接在通知栏点「允许」**、语音输入、主屏小组件、应用内更新。[**下载 APK**](https://github.com/xiazhi88/dshgo/releases/latest/download/dshgo-app.apk) · [源码](android/) |
| **[插件](.)** | `dshgo`：把只监听 `127.0.0.1` 的 `dsh web` 暴露到局域网，带一个「局域网访问」设置页签（地址 + 二维码），以及**可选的访问密码**。 |

两者独立：App 也可以连你自己的隧道，插件也可以只给浏览器用。但配在一起是完整体验。

<p align="center">
  <img src="docs/phone.jpg" alt="DSH Go 在手机上" width="248">
  &nbsp;&nbsp;&nbsp;
  <img src="docs/tablet.jpg" alt="DSH Go 在平板上" width="404">
</p>

```
┌─────────┐        局域网 / Tailscale        ┌──────────────────────┐
│ 手机 App │ ────────────────────────────────> │ dshgo :3081        │
│         │ <──────────────────────────────── │   ↓ 改写成 loopback   │
└─────────┘                                   │ dsh web  :3080       │
                                              └──────────────────────┘
```

同一台机器上的浏览器也能用 —— 打开 `设置 → 局域网访问` 就有地址和二维码。

---

## 为什么需要它

`dsh web` 有三道限制，手机端一条都绕不过去：

| 限制 | 说明 |
|---|---|
| **只绑 loopback** | `dsh web` 固定监听 `127.0.0.1`。官方明确禁了 `--host 0.0.0.0`：*"it would expose remote code execution to the network"* |
| **`/api` 有 Host/Origin 栅栏** | 只认 loopback（`127/8`、`localhost`、`::1`）或 `--trusted-host` 白名单，且 `Origin` 必须与 `Host` 同源 |
| **会话 cookie 绑定 authority** | cookie 名是 `dsh-auth-<authority 的 hash>`，校验时比对签发时的 authority |

所以手机即使能连上 3080，也会被栅栏 401。本插件把入站请求的 `Host`/`Origin`
统一改写成 `127.0.0.1:<dshPort>` 再转发 —— 栅栏永远看到 loopback，cookie 的
authority 也始终一致。

另外它还补两件小事：

- **首屏注入一次 launch token**：token 每次 `dsh web` 启动都会变，不带就永远拿不到会话 cookie
- **注入两个 polyfill**：`crypto.randomUUID` 在 `http://<IP>`（非安全上下文）里原生不可用，
  而 DSH 连接层拿它生成 RPC id；`AbortSignal.any` 在旧 WebView 上缺失会让发送消息直接失败

## DSH 版本要求

**最低 `0.1.2-rc.1`。**

插件本身任何版本都能跑（它只是转发），但**通知与应用内的会话状态**依赖两个服务端能力，
而它们都是 `0.1.2-alpha.2` 才加的：

| 能力 | 起始版本 | 谁声明 |
|---|---|---|
| `session/list` RPC | **0.1.2-alpha.2** | `@deepseek-ai/dsh-api-session-controller` |
| WebSocket 事件流 `/api/remote.mux` | **0.1.2-alpha.2** | `@deepseek-ai/dsh-api-gateway` 的 `registerUpgrade` |

版本过老时的表现（我们在这上面栽过）：

```
WebSocket 事件流 /api/remote.mux  → 服务端直接掐断（升级路由没注册）
session/list RPC                  → HTTP 404 not found（包都不存在）
```

> `0.1.2-alpha.2` 是最早同时具备两者的版本，但 alpha 不建议用 —— 所以写 `0.1.2-rc.1`。

**升级**：

```sh
npm i -g @deepseek-ai/dsh@latest
```

> App 侧对「太老」做了兜底：WS 连不上会自动退到轮询 `session/list`。
> 但那也要求 `session/list` 存在 —— 低于 `0.1.2-alpha.2` 时它是真的没有，
> 只能在界面上明确告诉你去升级。

## 安装

```sh
dsh plugin --profile web add github:xiazhi88/dshgo -w
# 然后重启 dsh web
```

也可以走 npm（更快，且免掉一次构建授权）：
`dsh plugin --profile web add @xiazhi88/dshgo -w`。

装完就有：**局域网入口**（设置 → 局域网访问，带二维码）、**移动端布局**、
大响应压缩、删除会话。移动端布局的代码已内联在本包里，**不需要另装
`dsh-web-mobile`**。

> **两个注意点**
>
> - **装完必须重启 `dsh web`。** 后端半只在启动时挂载。不重启的话页签会出现，
>   但会显示「服务端半没有运行」—— 不是装失败，是没重启。
> - **从旧名 `dsh-lan` 升级要先卸旧包**，否则设置里会多出一个同名的「局域网访问」
>   （两个包的页签 id 不同，不会被去重）：`dsh plugin --profile web remove dsh-lan`。
>   App 同理 —— `com.dsh.remote` 和 `com.dshgo.app` 是两个应用。

## 配置（可选）

在 profile 的 `cordis.patch.yml` 里：

```yaml
- id: dshgo
  config:
    port: 3081        # 对外端口，默认 3081
    bind: 0.0.0.0     # 对外绑定地址
```

端口被占用时会自动往后试 10 个（启动日志会打印实际用的那个），不影响 `dsh web` 本身。

## 设置页签

装上后 DSH 设置里会多一个一级入口 **「局域网访问」**（与「通用设置 / 模型 / 插件」同级）：

![设置页签](https://raw.githubusercontent.com/xiazhi88/dshgo/main/docs/settings-tab.png)

它展示：本机所有可用地址（点一下复制）、**从外面访问的引导**、监听状态。

### 访问密码（可选）

转发端口默认对同网络的所有设备开放 —— 装上就能用，但也意味着同一 WiFi 下
任何人都能打开它。加一道密码即可（**默认关闭**，不设就不生效）。

在「局域网访问」页签最上面：

![访问密码](https://raw.githubusercontent.com/xiazhi88/dshgo/main/docs/unlock-mode.png)

| 谁 | 看到什么 |
|---|---|
| **浏览器** | 密码页，输一次记 30 天 |
| **App** | 原生解锁界面 → 指纹 / 面部 / 设备密码 |

**两类客户端要的是同一个密码**，`User-Agent` 只决定"用什么界面输"，
不决定"要不要输" —— 所以伪装 UA 没有收益。

实现上有几处是刻意的：

- 闸门拦在**注入启动 token 之前**。顺序反了就是摆设：token 一注入，请求等于已登录
- 密码用 **scrypt**（不是 sha256）加盐存 `~/.dsh/dshgo-auth.json`（0600）
- 改密码时**同时轮换签名密钥** → 所有已解锁设备立刻失效
- 改密码**只接受来自本机的请求**。否则同网络的人可以抢先设一个密码，
  把机主锁在门外 —— 一个"防外人"的功能变成"让外人进来锁门"的工具

### 公网访问（Tailscale）

本插件只暴露到局域网 —— 直接开端口转发是危险的：`dsh web` 没有登录口令，
暴露到公网等于把这台电脑的任意命令执行权交给扫描器。

推荐 Tailscale：设备级私有网络，点对点直连，不用开端口。

页签会**自动识别 Tailscale 是否已就绪** —— 服务端枚举网卡时就能认出
`100.64.0.0/10`（CGNAT 段）的地址，所以两种状态给的是不同内容：

| 已装 Tailscale | 未装 |
|---|---|
| ![已就绪](https://raw.githubusercontent.com/xiazhi88/dshgo/main/docs/settings-tab.png) | ![引导](https://raw.githubusercontent.com/xiazhi88/dshgo/main/docs/settings-tailscale-guide.png) |

对应关系：

```
GET /__dshgo__/info
→ { "addresses": ["http://192.168.1.100:3081", "http://100.101.102.103:3081"],
    "tailscale": ["http://100.101.102.103:3081"] }
```

`tailscale` 非空即「已就绪」。它同时也是 `addresses` 的子集，所以老客户端不受影响。

实现上是 DSH 客户端插件的一个 slot：

```js
ctx.slots.inject('settings.section', () =>
  ctx.slots.register(
    { name: 'settings.section', id: 'dshgo', order: 2, label: () => '局域网访问' },
    LanSettings,
  ),
);
```

`client/client.js` 是**手写源码，没有构建步骤** —— 页面很简单，为它引入 esbuild
不划算。数据直接来自下面的自描述端点（与页面同源，不需要额外开 RPC 通道）。

## App 上有什么

截图都是真机/模拟器实拍，不是设计稿。

### 会话跑完、或者卡在批准上

agent 卡在审批上等你点头，就是纯浪费。桌面你在看着，手机上你人在厨房 —— 所以**退到后台时原生会接管那条审批**，通知栏直接给两个按钮：

- 点「允许」agent 立刻继续
- **点按钮不会把 App 拉到前台** —— 你的意图只是点个头，不是要去看 DSH
- 前台时不接管：页面自己在处理，两边抢答会打架

### 多台电脑，随手切

家里一台、公司一台、还有一台常开的服务器 —— 各存一条连接。**首页就是连接列表**，
一眼看到每台的在线状态、延迟、插件版本、要不要解锁，点一下就切过去。

```
🐋 DSH Go                          (+)
   选择要连接的电脑

╭────────────────────────────────╮
│ ●  MacBook Pro           当前  │   ← 强调色边框 = 当前
│    Tailscale · 100.100.190.107 │
│    223 ms · v2.0.5 · 需要解锁   │
╰────────────────────────────────╯
╭────────────────────────────────╮
│ ●  192.168.0.91:3100       ⚙  │
│    连不上 —— 不在同一网络？      │
╰────────────────────────────────╯
            刷新状态
```

**切换是瞬时的。** 页面常驻在内存里（当前 + 上一个），切过去时滚动位置、
输入框内容都还在，不重新加载。

状态是**探测**出来的 —— 打的是插件那两个不需要先解锁的端点，
所以列表里那些你从没登录过的 DSH 也显示得了状态。**「几个会话在跑」列不出来**：
那要先登录它，而列表里的连接你未必都存过密码 —— 与其显示一个永远空着的「0」，
不如不显示。

### 主屏小组件

![小组件](https://raw.githubusercontent.com/xiazhi88/dshgo/main/docs/widget.png)

一眼看状态，两个动作。

有东西等你批准时，状态点从绿变琥珀色，**两个按钮自己换成「允许」「拒绝」** —— 不用打开 App，也不用下拉去找那条通知：

```
🐋  等你批准：Bash              ●
   正在执行… · 刚完成「重构登录模块」
   [   允许   ]  [   拒绝   ]
```

（上图是深色模式；浅色主题与系统一起切。）

### 访问闸门

转发端口上可以加一道密码 —— **浏览器看到密码页，App 走指纹或面部**。怎么配见
[访问密码](#访问密码可选)；安全边界在插件侧，拦在注入启动 token 之前。

App 侧的解锁方式可选四档：自动 / 仅指纹人脸 / 仅设备密码 / 每次输密码。

> **密码是根凭据**，所以第一次必须先输一次；之后才是指纹或人脸。
> 选了「每次输密码」则本机不存密码，每次都要打 —— 最严的一档。

### 其余

| 功能 | 说明 |
|---|---|
| **语音输入** | 状态条麦克风按钮。prompt 恰恰适合说 —— 「把那个登录接口的错误处理改成返回 JSON」，说比打快十倍。边说边出字 |
| **应用内更新** | 带进度条下载 + 直接唤起安装器，不跳浏览器。**最后一次「安装」确认绕不过**（Android 硬性要求） |
| **多地址自动选路** | 家里局域网、出门 Tailscale 都记着；当前地址能用就不动，连不上才切 |
| **分享进 DSH** | 相册/浏览器分享过来：图片暂存，回到 DSH 点附件就直接发；文字进输入框 |
| **长按图标** | 新会话 / 继续最近 / 设置 |

## 自描述端点

```
GET /__dshgo__/info
→ {
    "name": "dshgo",
    "listening": true,                             // 转发代理到底有没有起来
    "port": 3081,                                  // 实际监听的端口（被占时可能是 3082…）
    "upstreamPort": 3080,
    "addresses": ["http://192.168.1.100:3081"],
    "tailscale": ["http://100.101.102.103:3081"]   // 是 addresses 的子集
  }
```

它注册在 **DSH 的 web server 上**，所以本机直连和走代理都能拿到：

| 访问方式 | 结果 |
|---|---|
| 本机 `http://127.0.0.1:3080/__dshgo__/info` | 直接命中 |
| 手机 `http://192.168.1.100:3081/__dshgo__/info` | 代理转发到 3080，同一个处理器 |

不需要认证（只回本机地址，不含敏感信息）。

## 安全

**它把能执行任意代码的 DSH 暴露到了局域网。** 请确认：

- 只在可信网络里开（家里 / 自己的 WiFi）；需要多一层就用下面的访问密码
- 不要把地址或端口转发到公网
- 需要公网访问时，用带认证的隧道，别直接暴露这个端口

**需要更强隔离时**，插件已经内置了访问密码（见[访问密码](#访问密码可选)）——
浏览器要密码，App 走指纹或面部。它挡的是"同一网络下的别人"，不是公网扫描器：
真要暴露到公网，仍然该用带认证的隧道，别直接开这个端口。

## 许可

本项目：MIT。

移动端布局与两项宿主功能内联自
[`dsh-web-mobile`](https://github.com/mexiaosqwq/dsh-web-mobile)，
MIT，Copyright (c) 2026 mexiaosqwq，
许可见 [`client/vendor/LICENSE.dsh-web-mobile`](client/vendor/LICENSE.dsh-web-mobile)。
