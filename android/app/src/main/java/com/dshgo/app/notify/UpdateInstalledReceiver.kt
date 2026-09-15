package com.dshgo.app.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dshgo.app.MainActivity
import com.dshgo.app.R
import com.dshgo.app.data.ApkDownloader

/**
 * 应用被更新之后收到的广播。
 *
 * ## 为什么需要它
 *
 * 装完新版本，系统会杀掉旧进程 —— 我们**不可能**在旧进程里"装完自动打开"。
 * 系统安装器那边有「打开」按钮，用户点了就进新版本；但他要是习惯性按返回，
 * 就会停留在一个不知道刚才装没装成的状态。
 *
 * 所以这里在新版本启动时补一条通知，把"已经更新好了"这件事说清楚，
 * 点一下就能进来。**这是能做到的"自动重启"里最接近的那个** ——
 * 真正零点击地拉起界面在 Android 10+ 是被禁止的（后台启动 Activity 受限），
 * 硬要绕就得申请特殊权限，为一个更新提示不值得。
 */
class UpdateInstalledReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // 装完了，缓存里的安装包就没用了 —— 几个 MB，别留着占地方
        ApkDownloader.clear(context)

        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            context,
            0x5A17,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()

        val n = NotificationCompat.Builder(context, NotificationCenter.CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("已更新到 $version")
            .setContentText("点一下回到 DSH Go")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(0x5A17, n) }
    }
}
