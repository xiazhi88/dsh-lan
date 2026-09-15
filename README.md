# dsh-lan

把只监听 `127.0.0.1` 的 **`dsh web` 暴露到局域网**，供手机客户端连接。

一个包、一个插件、一件小事。不做移动端 CSS、不做公网隧道、不管访问密码 ——
界面怎么呈现、要不要通知，那是客户端自己的事。

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

**一个包，装完就能用：**

```sh
# 从 GitHub 安装
dsh plugin --profile web add github:xiazhi88/dsh-lan -w
# 然后重启 dsh web
```

> 已发布到 npm 之后可以简写成 `dsh plugin --profile web add dsh-lan -w`。
>
> `dsh plugin add` 会把声明了 `dsh.bundle` 的包自动加进 profile 的 `bundles`，
> 所以你不用手改配置文件。

装上后它自动挂两件事：

| | |
|---|---|
| **网络桥**（本包） | 把只监听 `127.0.0.1` 的 dsh web 暴露到局域网 |
| **移动端适配** | [`dsh-web-mobile`](https://www.npmjs.com/package/dsh-web-mobile)（MIT，独立维护）—— 抽屉布局、移动端 CSS、触控优化 |

第二条是**依赖**，不是 vendored 代码：dsh-web-mobile 在 npm 上独立迭代，本包只是把它一起挂进来。
`dsh plugin add` 会把声明了 `dsh.bundle` 的包自动加进 `profiles/*/package.json` 的 `bundles`，
而 profile 的模块回退会为整个依赖闭包建链接 —— 所以**装一个包，两行插件都到位**。

> 它在宽屏 / 非触摸设备上不会激活（自己的媒体查询带 `pointer: coarse`），
> 所以桌面浏览器通过局域网访问不受影响。

重启后终端会打印局域网地址：

```
dsh-lan: 局域网地址 http://192.168.1.100:3081  （上游 127.0.0.1:3080）
```

手机连同一 WiFi，把地址填进客户端即可。

## 配置（可选）

在 profile 的 `cordis.patch.yml` 里：

```yaml
- id: dsh-lan
  config:
    port: 3081        # 对外端口，默认 3081
    bind: 0.0.0.0     # 对外绑定地址
```

端口被占用时（比如 `dsh-pocket` 还在跑）插件只会打一条警告，不影响 `dsh web` 本身。

## 自描述端点

```
GET http://<你的IP>:3081/__dsh_lan__/info
→ {"name":"dsh-lan","port":3081,"addresses":["http://192.168.1.100:3081"]}
```

客户端可以据此做地址自动发现。该端点不转发、不需要认证（只回本机地址，不含敏感信息）。

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

| | dsh-lan | dsh-pocket |
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
