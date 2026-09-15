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
    fun foreground(ctx: Context, host: String): android.app.Notification {
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
            .setContentTitle("正在监听会话")
            .setContentText("$host · 会话跑完会通知你")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
