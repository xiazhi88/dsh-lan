package com.dsh.remote;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * dsh-pocket 的「原生握手」。
 *
 * <p>为什么需要它：手机端（尤其 Safari）第一次访问 {@code http://<IP>:3081} 时，pocket 代理会
 * 先返回一张过渡页 + {@code Set-Cookie: dsh-auth-…}，再 meta-refresh 回 {@code /} 才进真正的 GUI。
 * 如果在 WebView 里跑这一套，会带来三个副作用：多一个历史记录项（返回键会退回过渡页）、
 * 每失败一次就累加 pocket 的握手重试计数（3 次/60 秒 → 503 卡死）、以及 303 上丢 cookie。
 *
 * <p>这里改成在原生侧先把这一跳走完：手动跟重定向、逐跳收集 {@code Set-Cookie}，
 * 把 cookie 灌进 {@link android.webkit.CookieManager} 之后，WebView 再一次性加载入口地址——
 * 首屏直接就是 GUI，无过渡页、无多余历史。
 *
 * <p>顺带把「Mac 是否可达」这件事变成一次可报错的探测，连不上直接出原生错误页。
 */
public final class Handshake {

    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_HOPS = 6;

    /** 用桌面级 UA 请求：pocket 的握手注入与 dsh web 的首屏都按普通浏览器对待。 */
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Mobile Safari/537.36 DshRemote/1.0";

    /** 一次握手的结局。{@code cookies} 即便失败也带回来，方便调用方按需清理。 */
    public static final class Result {
        public final boolean ok;
        public final int status;
        public final String message;
        public final List<String> cookies;

        Result(boolean ok, int status, String message, List<String> cookies) {
            this.ok = ok;
            this.status = status;
            this.message = message;
            this.cookies = cookies;
        }
    }

    private Handshake() {
    }

    /**
     * 执行握手：GET 入口地址，逐跳跟随重定向，收集全部 Set-Cookie。
     *
     * @param entryUrl 形如 {@code http://192.168.1.100:3081} 的入口地址
     * @param retry    是否为「用户主动重试」——是则带上 pocket 的 {@code ?dsh-pocket-retry=1}
     *                 清掉它那一侧的失败计数（该参数只对本代理有意义，不会透传给上游）
     */
    public static Result run(String entryUrl, boolean retry) {
        List<String> cookies = new ArrayList<>();
        String url = entryUrl;
        if (retry && !entryUrl.contains("dsh-pocket-retry=")) {
            url = entryUrl + (entryUrl.contains("?") ? "&" : "?") + "dsh-pocket-retry=1";
        }

        HttpURLConnection conn = null;
        int status = 0;
        try {
            for (int hop = 0; hop < MAX_HOPS; hop++) {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setInstanceFollowRedirects(false); // 手写跳转，否则中间跳的 Set-Cookie 收不到
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
                if (!cookies.isEmpty()) {
                    conn.setRequestProperty("Cookie", join(cookies));
                }

                status = conn.getResponseCode();
                collectCookies(conn, cookies);

                if (status >= 300 && status < 400) {
                    String location = conn.getHeaderField("Location");
                    drain(conn);
                    conn.disconnect();
                    conn = null;
                    if (location == null || location.isEmpty()) {
                        return new Result(false, status, "重定向缺少 Location 头", cookies);
                    }
                    url = new URL(new URL(url), location).toString();
                    continue;
                }

                drain(conn);
                if (status >= 400) {
                    return new Result(false, status, describeStatus(status), cookies);
                }
                return new Result(true, status, "OK", cookies);
            }
            // 跳转次数用尽但没拿到错误：当作可用（cookie 通常已在手）
            return new Result(true, status, "max-hops", cookies);
        } catch (Exception e) {
            return new Result(false, 0, describeException(e), cookies);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 收集响应里的所有 Set-Cookie，只保留 {@code name=value}（丢掉 Path/Expires 等属性）。 */
    private static void collectCookies(HttpURLConnection conn, List<String> out) {
        for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
            String key = e.getKey();
            if (key == null || !"set-cookie".equalsIgnoreCase(key)) {
                continue;
            }
            for (String raw : e.getValue()) {
                if (raw == null) {
                    continue;
                }
                int semi = raw.indexOf(';');
                String pair = (semi >= 0 ? raw.substring(0, semi) : raw).trim();
                if (pair.isEmpty() || pair.indexOf('=') <= 0) {
                    continue;
                }
                // 同名 cookie 后到的覆盖先到的
                String name = pair.substring(0, pair.indexOf('='));
                out.removeIf(c -> c.startsWith(name + "="));
                out.add(pair);
            }
        }
    }

    /** 读掉响应体，释放连接（HttpURLConnection 复用连接池，不读完会拖住后续请求）。 */
    private static void drain(HttpURLConnection conn) {
        try (InputStream in = conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream()) {
            if (in == null) {
                return;
            }
            byte[] buf = new byte[8192];
            long total = 0;
            while (in.read(buf) >= 0) {
                total += buf.length;
                if (total > 2L * 1024 * 1024) {
                    break; // 首屏 HTML 也就几十 KB，超过纯属异常
                }
            }
        } catch (Exception ignored) {
            // 读体失败不影响握手结论
        }
    }

    private static String join(List<String> cookies) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cookies.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(cookies.get(i));
        }
        return sb.toString();
    }

    private static String describeStatus(int status) {
        switch (status) {
            case 401:
                return "401 未授权：dsh web 要求重新登录";
            case 403:
                return "403 被拒绝：局域网访问可能已在 Mac 上关闭";
            case 404:
                return "404 找不到页面：端口可能填错了";
            case 502:
            case 504:
                return status + " 代理连不上 dsh web：Mac 上的 dsh web 可能没在运行";
            case 503:
                return "503 握手被 pocket 限流（60 秒后自愈，或直接点重试）";
            default:
                return "HTTP " + status;
        }
    }

    private static String describeException(Exception e) {
        String n = e.getClass().getSimpleName();
        if (n.contains("UnknownHost")) {
            return "域名解析失败，检查地址拼写";
        }
        if (n.contains("Connect") || n.contains("SocketTimeout")) {
            return "连接超时：手机和 Mac 不在同一网络，或 Mac 没开 dsh web";
        }
        if (n.contains("Cleartext")) {
            return "系统拦截了明文 HTTP 请求";
        }
        return n + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
}
