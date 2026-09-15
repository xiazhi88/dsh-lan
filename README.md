# dshgo

在手机 / 平板上用 DSH —— **一个 Android App，加一个让电脑可达的插件。**

| | |
|---|---|
| **[Android App](android/)** | 真正为手机做的客户端。输入法不挡输入框、能传附件、会话跑完推送通知、随时切换入口地址。[**下载 APK**](https://github.com/xiazhi88/dshgo/releases/latest/download/dshgo-app.apk) · [源码](android/) |
| **[插件](.)** | `dshgo`：把只监听 `127.0.0.1` 的 `dsh web` 暴露到局域网，并在设置里加一个「局域网访问」页签（地址 + 二维码）。 |

两者独立：App 也可以连你自己的隧道，插件也可以只给浏览器用。但配在一起是完整体验。

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

## 安装

**两个包各自独立，按需安装：**

```sh
# 局域网入口（本插件，必须）
dsh plugin --profile web add github:xiazhi88/dshgo -w

# 手机端布局适配（可选，但手机上没有它体验会差很多）
dsh plugin --profile web add dsh-web-mobile -w

# 然后重启 dsh web
```

> 已发布到 npm 之后第一条可以简写成 `dsh plugin --profile web add dshgo -w`。
>
> `dsh plugin add` 会把声明了 `dsh.bundle` 的包自动加进 profile 的 `bundles`，
> 所以你不用手改配置文件。

### ⚠️ 装完必须重启 `dsh web`

后端半（转发代理）只在**启动时**挂载，不会热生效。不重启的表现很有迷惑性：

- `dsh web` 本身照常运行
- 设置里**也会**多出「局域网访问」页签（前端半按已安装的包加载，与 bundle 层无关）
- 但页签里只显示「**服务端半没有运行**」，终端里也没有 `dshgo:` 那两行

这不是装失败，是没重启。重启后终端应该出现：

```
dshgo: 局域网地址 http://192.168.1.100:3081  （上游 127.0.0.1:3080）
dshgo: 自描述端点 /__dshgo__/info（客户端可据此自动发现地址）
```

**一行都没有**，才是真的没进 `bundle` 层 —— 去看 profile 的 `package.json` 里
`dsh.profile.bundles` 有没有 `dshgo`。

### 为什么不合成一个包

试过，是错的，而且失败方式很严重。

当时的做法是在本插件的 `cordis.patch.yml` 里**多插一行**把 `dsh-web-mobile` 一起挂上。
问题是市场的「一键安装」会热挂载插件，读的正是这张补丁：

```js
rows = parseSimplePatch(readFileSync(join(dir, 'cordis.patch.yml'), 'utf8'))
```

于是它不只挂了 `dshgo`，还把 `dsh-web-mobile` **又挂了一次**。如果用户自己也装过
（它本来就在 `bundles` 里），就是双重挂载：

```
failed to apply loader entry (dsh-web-mobile):
locale namespace "mobileNav" already has locale "zh"
```

这不是插件失效，是**整个 `dsh web` 起不来** —— 连市场的卸载页面都打不开，只能手改文件
才能恢复。而且 `dsh-web-mobile` 的宿主半并不空：它会 patch
`http.ServerResponse.prototype` 做响应压缩、还会注册路由，挂两次是真的有害。

**一个 patch 文件不该替另一个插件决定它挂几次。** 所以回到一人一半：本插件只做局域网
转发，移动端适配由你自己决定装不装 —— `dsh-web-mobile` 声明的是一条**可选**的
peer dependency，不装也能用，只是手机上看到的是 DSH 桌面 UI。


重启后终端会打印局域网地址：

```
dshgo: 局域网地址 http://192.168.1.100:3081  （上游 127.0.0.1:3080）
```

手机连同一 WiFi，把地址填进客户端即可。

## 配置（可选）

在 profile 的 `cordis.patch.yml` 里：

```yaml
- id: dshgo
  config:
    port: 3081        # 对外端口，默认 3081
    bind: 0.0.0.0     # 对外绑定地址
```

端口被占用时（比如 `dsh-pocket` 还在跑）插件只会打一条警告，不影响 `dsh web` 本身。

## 设置页签

装上后 DSH 设置里会多一个一级入口 **「局域网访问」**（与「通用设置 / 模型 / 插件」同级）：

![设置页签](https://raw.githubusercontent.com/xiazhi88/dshgo/main/docs/settings-tab.png)

它展示：本机所有可用地址（点一下复制）、**从外面访问的引导**、监听状态。

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
→ { "addresses": ["http://192.168.1.100:3081", "http://100.100.190.107:3081"],
    "tailscale": ["http://100.100.190.107:3081"] }
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

## 自描述端点

```
GET /__dshgo__/info
→ {
    "name": "dshgo",
    "listening": true,                             // 转发代理到底有没有起来
    "port": 3081,                                  // 实际监听的端口（被占时可能是 3082…）
    "upstreamPort": 3080,
    "addresses": ["http://192.168.1.100:3081"],
    "tailscale": ["http://100.100.190.107:3081"]   // 是 addresses 的子集
  }
```

它注册在 **DSH 的 web server 上**，不是只由转发代理提供 —— 这一点很关键：

| 访问方式 | 能否拿到 |
|---|---|
| 本机 `http://127.0.0.1:3080/__dshgo__/info` | ✅ 直接命中 |
| 手机 `http://192.168.1.100:3081/__dshgo__/info` | ✅ 代理转发到 3080，同一个处理器 |

早期版本只让代理应答这个路径，于是**本机看页面时永远是 404** —— 设置页写
「服务端半没有运行」，而终端里插件明明跑得好好的，重启也救不了。前端取的是
相对路径，请求跟着页面走，所以端点必须在两个入口上都够得着。

不需要认证（只回本机地址，不含敏感信息）。

## 安全

**它把能执行任意代码的 DSH 暴露到了局域网。** 请确认：

- 只在可信网络里开（家里 / 自己的 WiFi）
- 不要把地址或端口转发到公网
- 需要公网访问时，用带认证的隧道，别直接暴露这个端口

插件本身不做认证 —— 如果需要访问密码，那是另一个层面的需求，请用
[`dsh-pocket`](https://github.com/shaobeichen/dsh-pocket) 或自己的反向代理。

## 移动端 UI 是从哪来的

**DSH 核心是纯桌面 Web UI** —— `max-width: 1023px` 这个断点在核心里一次都没出现。
抽屉布局、移动端 CSS、触控优化全部来自
[`dsh-web-mobile`](https://github.com/mexiaosqwq/dsh-web-mobile)（MIT，作者 mexiaosqwq）。

本包把它作为**依赖**一起挂上（见上方 `cordis.patch.yml` 的第二行），所以用户装一个包就够。
它是独立维护的上游项目，不是本仓库的代码，出问题请先看
[上游 issues](https://github.com/mexiaosqwq/dsh-web-mobile/issues)。

| 移动端适配生效 | 强制宽屏 |
|---|---|
| 角落切换按钮、无侧栏、输入框满宽 | DSH 原生桌面布局 |

## 与 dsh-pocket 的关系

两者定位不同，可以并存：

| | dshgo | dsh-pocket |
|---|---|---|
| 定位 | 只做局域网转发，给客户端用 | 完整的手机访问方案 |
| 公网隧道 | ✗ | ✓（cloudflared） |
| 访问密码 | ✗ | ✓ |
| 二维码 / 设置页 | ✗ | ✓ |
| **移动端 UI 适配** | ✗（DSH 核心也没有） | ✓（移植自 MIT 的 dsh-web-mobile） |
| 依赖 | 无 | qrcode 等 |

需要扫码、公网、密码就用 dsh-pocket；只需要「让手机连得上」就用本插件。

## 许可

MIT
