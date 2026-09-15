package com.dshgo.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 配置存储：入口地址、布局模式、屏幕常亮。
 *
 * <p>用 commit() 而不是 apply()：地址这类值需要立刻落盘——用户改完地址马上被杀进程也不能丢。
 */
public final class Prefs {

    private static final String FILE = "dsh_remote";
    private static final String KEY_ENTRY = "entry_url";
    private static final String KEY_LAYOUT = "layout_mode";
    private static final String KEY_AWAKE = "keep_awake";
    private static final String KEY_SCALE = "ui_scale";
    private static final String KEY_NOTIFY = "notify_enabled";
    private static final String KEY_UPDATE_AT = "update_checked_at";
    private static final String KEY_UPDATE_LATEST = "update_latest";

    /** 界面缩放的取值范围与默认值。 */
    public static final float SCALE_MIN = 0.75f;
    public static final float SCALE_MAX = 1.60f;
    public static final float SCALE_DEFAULT = 1.0f;

    /**
     * 首次启动预填的地址：dsh-pocket 的默认端口是 3081。
     * 这里预填的是当前这台 Mac 的 Tailscale 地址（pocket settings 里的 lanIpOverride），
     * 换网络也能通；同 WiFi 场景改成局域网 IP（如 http://192.168.1.100:3081）延迟更低。
     */
    /**
     * 默认入口地址：**留空**。
     *
     * 这里绝不能放一个具体地址 —— 那是开发者的机器，别人装上必然连不上，
     * 而且等于把私人地址随 APK 发出去。地址由用户在「连接你的 Mac」页填，
     * 或者在 DSH 的「设置 → 局域网访问」页一键复制。
     */
    public static final String DEFAULT_URL = "";

    /** 布局模式取值：让 dsh-pocket 自己按屏幕宽度判定 / 强制手机布局 / 强制电脑布局。 */
    public static final String LAYOUT_AUTO = "auto";
    public static final String LAYOUT_MOBILE = "mobile";
    public static final String LAYOUT_DESKTOP = "desktop";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String entryUrl() {
        return sp.getString(KEY_ENTRY, "");
    }

    public boolean hasEntryUrl() {
        return !entryUrl().isEmpty();
    }

    public void setEntryUrl(String v) {
        sp.edit().putString(KEY_ENTRY, normalize(v)).commit();
    }

    public String layout() {
        return sp.getString(KEY_LAYOUT, LAYOUT_AUTO);
    }

    public void setLayout(String v) {
        sp.edit().putString(KEY_LAYOUT, v).commit();
    }

    /**
     * 会话完成通知。默认关 —— 发通知要运行时授权，且会打扰人，让用户自己开。
     * 关着的时候铃铛面板里会给一个「开启通知」入口。
     */
    public boolean notifyEnabled() {
        return sp.getBoolean(KEY_NOTIFY, false);
    }

    public void setNotifyEnabled(boolean v) {
        sp.edit().putBoolean(KEY_NOTIFY, v).commit();
    }

    /** 会话内 DSH 界面的整体缩放（1.0 = 100%）。 */
    public float scale() {
        float v = sp.getFloat(KEY_SCALE, SCALE_DEFAULT);
        // 老版本/异常值兜底，避免算出 0 或者离谱的缩放把界面搞没
        if (v < SCALE_MIN || v > SCALE_MAX) return SCALE_DEFAULT;
        return v;
    }

    public void setScale(float v) {
        float clamped = Math.max(SCALE_MIN, Math.min(SCALE_MAX, v));
        sp.edit().putFloat(KEY_SCALE, clamped).commit();
    }

    public boolean keepAwake() {
        return sp.getBoolean(KEY_AWAKE, false);
    }

    public void setKeepAwake(boolean v) {
        sp.edit().putBoolean(KEY_AWAKE, v).commit();
    }

    /**
     * 规范化用户输入 / 分享进来的文本成可用入口地址。
     *
     * <p>规则：补 http:// 前缀、只取第一个空白前的部分、去掉结尾斜杠。
     * 保留 path 与 query——pocket 的分享链接可能带 {@code ?token=<PIN>}，带上可免输密码。
     *
     * @return 规范化后的地址；无法识别时返回空串。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        int ws = s.indexOf(' ');
        if (ws > 0) {
            s = s.substring(0, ws);
        }
        ws = s.indexOf('\n');
        if (ws > 0) {
            s = s.substring(0, ws);
        }
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            if (s.contains("://")) {
                return ""; // 其它 scheme（ftp:// 之类）不接受
            }
            s = "http://" + s;
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // ------------------------------------------------------------------
    // 检查更新（UpdateCheck 用）
    // ------------------------------------------------------------------

    /** 上次检查更新的时间戳，0 = 从没查过。 */
    public long lastUpdateCheckAt() {
        return sp.getLong(KEY_UPDATE_AT, 0L);
    }

    public void setLastUpdateCheckAt(long v) {
        sp.edit().putLong(KEY_UPDATE_AT, v).commit();
    }

    /** 上次查到的远端版本号；null = 还不知道。 */
    public String lastKnownLatest() {
        return sp.getString(KEY_UPDATE_LATEST, null);
    }

    public void setLastKnownLatest(String v) {
        sp.edit().putString(KEY_UPDATE_LATEST, v).commit();
    }
}
