package com.dshgo.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dshgo.app.MainActivity
import com.dshgo.app.data.DshApi
import com.dshgo.app.R

/**
 * 系统通知。两个渠道：常驻的监听提示是静音的，会话完成是要打扰你的。
 *
 * ── 关于「为什么没有提示音」──
 *
 * Android 有一条容易踩的规则：**渠道一旦创建，之后再改重要性/声音都不生效** ——
 * 系统记住的是首次创建时的配置（之后归用户在系统设置里管）。所以修渠道配置
 * 只能靠**换一个新 id**，不能改老 id 的代码。这也是 CHANNEL_DONE 从
 * `session_done` 变成 `session_done_v2` 的原因。
 *
 * 另外两点是实测教训：
 *   · 要**显式** setSound + enableVibration。光靠 IMPORTANCE_DEFAULT 的默认行为，
 *     在国产 ROM（MIUI / ColorOS / EMUI）上经常是静音的。
 *   · 从 DEFAULT 提到 HIGH：DEFAULT 可能只进通知栏、不弹横幅，而「回答完了」
 *     是需要你立刻知道的。
 */
object NotificationCenter {

    /** v2：见上面那段注释 —— 改渠道配置必须换 id。 */
    const val CHANNEL_DONE = "session_done_v2"
    const val CHANNEL_WATCH = "watch"

    const val EXTRA_SESSION_ID = "open_session_id"
    const val EXTRA_NOTICE_ID = "open_notice_id"

    /** 测试通知用的固定 id，重复点只替换不堆积。 */
    private const val TEST_ID = 0x7E57

    /** 与 WatchService.startForeground 用的是同一个 id，才能原地更新。 */
    const val FOREGROUND_ID = 1001

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DONE,
                "会话完成",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "某个会话回答完成或报错时提醒"
                // 显式指定：不写的话国产 ROM 上常常不出声
                setSound(
                    android.media.RingtoneManager.getDefaultUri(
                        android.media.RingtoneManager.TYPE_NOTIFICATION,
                    ),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                enableLights(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WATCH,
                "后台监听",
                NotificationManager.IMPORTANCE_MIN,   // 不响不震，只是别让系统杀掉进程
            ).apply { description = "保持与电脑的连接，以便及时收到完成通知" },
        )
    }

    /**
     * 发一条测试通知。
     *
     * 存在的理由：渠道的重要性/声音是**系统**在管，代码改不动用户已经选过的设置。
     * 与其让用户对着「怎么不响」猜，不如给一个按钮自己试，不响就去系统设置里调。
     * @returns 是否真的发出去了（没有权限时为 false）。
     */
    fun postTest(ctx: Context): Boolean {
        if (!granted(ctx)) return false
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("测试通知")
            .setContentText("能看到这条、能听到提示音，就说明设置对了")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        return runCatching {
            NotificationManagerCompat.from(ctx).notify(TEST_ID, n)
            true
        }.getOrDefault(false)
    }

    /**
     * 跳到系统的通知设置（这个 App 的）。
     *
     * 渠道一旦建好，能否出声、是否弹横幅都归系统管 —— 所以需要给用户一条
     * 直接过去调整的路，而不是让他在系统设置里自己翻。
     */
    fun settingsIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", ctx.packageName, null))
        }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /** 通知渠道能否出声 —— 系统设置里被关掉时用来提示用户。 */
    fun channelAudible(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return true
        val ch = nm.getNotificationChannel(CHANNEL_DONE) ?: return true
        return ch.importance >= NotificationManager.IMPORTANCE_DEFAULT
    }

    /** 是否有权发通知（Android 13+ 需要运行时授权）。 */
    fun granted(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        }

    fun post(ctx: Context, notice: SessionWatcher.Notice) {
        if (!granted(ctx)) return
        ensureChannels(ctx)

        val title = when (notice.kind) {
            SessionWatcher.Kind.Done -> "「${notice.title}」回答完成"
            SessionWatcher.Kind.Error -> "「${notice.title}」出错了"
        }

        val intent = Intent(ctx, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SESSION_ID, notice.sessionId)
            putExtra(EXTRA_NOTICE_ID, notice.id)
        }
        val pi = PendingIntent.getActivity(
            ctx,
            notice.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val body = notice.detail ?: "点一下跳到这个会话"
        val n = NotificationCompat.Builder(ctx, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            // HIGH 才会弹横幅；DEFAULT 只进通知栏，很容易被当成「没通知」
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setWhen(System.currentTimeMillis())
            .build()

        runCatching {
            NotificationManagerCompat.from(ctx).notify(notice.id.hashCode(), n)
        }
    }

    /** 前台服务的常驻通知。 */
    /**
     * 常驻通知。
     *
     * 以前这里只写「正在监听会话」—— 信息量为零，用户撇一眼手机什么也没得到。
     * 而事件流里的数据早就有了，白放着不用。现在它显示真正想瞄一眼的东西：
     * **几个在跑、几个在等我点头、最近完成是什么**。
     *
     * 重要度仍然是 MIN：它是"瞥一眼"用的，不该发出声音或占地方。
     */
    fun foreground(ctx: Context, host: String, summary: SessionWatcher.Summary): android.app.Notification {
        ensureChannels(ctx)
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(summary.title())
            .setContentText(summary.detail(host))
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary.detail(host)))
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
            // 有审批就地可点 —— 用户看到"等你批准"却点不了，那种界面等于没说
            .apply {
                summary.firstApproval?.let { ask ->
                    approvalActions(ctx, ask).forEach { addAction(it) }
                }
            }
            .build()
    }

    /** 原地更新常驻通知（同一个 id，不新增一条）。 */
    fun updateForeground(ctx: Context, host: String, summary: SessionWatcher.Summary) {
        runCatching {
            NotificationManagerCompat.from(ctx).notify(FOREGROUND_ID, foreground(ctx, host, summary))
        }
    }

    // ------------------------------------------------------------------
    // 审批通知：两个动作按钮，直接从通知栏回答
    // ------------------------------------------------------------------

    /** 动作的 Intent action —— 广播接收器按这个区分「允许」和「拒绝」。 */
    const val ACTION_APPROVE = "com.dshgo.app.APPROVE"
    const val ACTION_REJECT = "com.dshgo.app.REJECT"

    /** 通知里带的审批标识，用于回执和撤销。 */
    const val EXTRA_EVENT_ID = "eventId"
    const val EXTRA_CLIENT_ID = "clientId"
    const val EXTRA_TOOL = "tool"
    const val EXTRA_REASON = "reason"

    /**
     * 一条审批请求。
     *
     * 用 HIGH 重要度 + 响铃震动：agent 正卡在这里等你，这是**唯一一个
     * "不回答就真的白等"的场景**，比"会话完成"更该吵醒人。
     */
    /**
     * 造一个「替用户回答这条审批」的广播 Intent。
     *
     * 通知的动作按钮和小组件的按钮都走这里 —— 两处各写一遍迟早会不一致，
     * 而不一致的后果是"一个能点、一个点了没反应"，很难查。
     */
    fun approvalIntent(ctx: Context, ask: DshApi.ApprovalAsk, allow: Boolean): Intent =
        Intent(ctx, ApprovalReceiver::class.java)
            .setAction(if (allow) ACTION_APPROVE else ACTION_REJECT)
            .putExtra(EXTRA_EVENT_ID, ask.eventId)
            .putExtra(EXTRA_CLIENT_ID, ask.clientId)
            .putExtra(EXTRA_TOOL, ask.toolName)
            .putExtra(EXTRA_REASON, ask.reason)

    /**
     * 常驻通知上的两个动作按钮。
     *
     * 常驻通知原本只报状态，用户看到「1 个操作等你批准」却只能去通知栏翻那条
     * 单独的审批通知 —— 现在就地能点。requestCode 用 eventId 派生，
     * 不同审批不会互相覆盖 PendingIntent。
     */
    private fun approvalActions(
        ctx: Context,
        ask: DshApi.ApprovalAsk,
    ): List<NotificationCompat.Action> {
        val base = 0x3000_0000 or (ask.eventId.hashCode() and 0x0FFF_FFFF)
        fun make(allow: Boolean, label: String, code: Int) = NotificationCompat.Action.Builder(
            0,
            label,
            PendingIntent.getBroadcast(
                ctx,
                base + code,
                approvalIntent(ctx, ask, allow),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()
        return listOf(make(true, "允许", 1), make(false, "拒绝", 2))
    }

    fun postApproval(ctx: Context, ask: DshApi.ApprovalAsk) {
        ensureChannels(ctx)
        val id = approvalId(ask.eventId)

        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            ctx, id, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = buildString {
            append(ask.toolName)
            ask.reason?.takeIf { it.isNotBlank() }?.let { append("：").append(it.take(120)) }
        }

        val n = NotificationCompat.Builder(ctx, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("等待你的批准")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(pi)
            .apply { approvalActions(ctx, ask).forEach { addAction(it) } }
            .build()

        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    fun cancelApproval(ctx: Context, ask: DshApi.ApprovalAsk) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(approvalId(ask.eventId)) }
    }

    /** 由 eventId 稳定派生通知 id —— 同一条审批重复推送不会堆出好几条。 */
    private fun approvalId(eventId: String): Int = 0x2000_0000 or (eventId.hashCode() and 0x0FFF_FFFF)
}
