package com.dshgo.app.notify

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.dshgo.app.MainActivity
import com.dshgo.app.R

/**
 * 主屏小组件：一眼看状态，两个动作。
 *
 * ## 为什么值得做
 *
 * 手机上大部分时刻你想知道的只是"跑完没有"。而现在的路径是：解锁 → 找到 App →
 * 打开 → 等 WebView → 看会话。小组件把这段压缩成**瞄一眼**。
 *
 * ## 状态从哪来
 *
 * 状态变化时 [SessionWatcher] 调 [update] 推给所有实例。小组件**不自己拉数据**
 * —— 它没有网络栈，也不该在桌面进程里发请求。
 *
 * ## 按钮
 *
 * 两个按钮复用长按图标的快捷方式 action，逻辑只有一份，在 MainActivity 里。
 */
class DshWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = buildViews(ctx, SessionWatcher.summary.value)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {

        /**
         * 把状态推给桌面上所有实例。
         *
         * 用户没放过小组件时直接返回 —— 不做任何事，代价可忽略。
         */
        fun update(ctx: Context, summary: SessionWatcher.Summary) {
            runCatching {
                val manager = AppWidgetManager.getInstance(ctx) ?: return
                val ids = manager.getAppWidgetIds(
                    ComponentName(ctx, DshWidgetProvider::class.java),
                )
                if (ids.isEmpty()) return
                val views = buildViews(ctx, summary)
                ids.forEach { manager.updateAppWidget(it, views) }
            }
        }

        private fun buildViews(ctx: Context, summary: SessionWatcher.Summary): RemoteViews =
            RemoteViews(ctx.packageName, R.layout.widget_dsh).apply {
                setTextViewText(R.id.widget_title, summary.title())
                setTextViewText(R.id.widget_detail, summary.detail("DSH"))
                setOnClickPendingIntent(R.id.widget_new, tap(ctx, MainActivity.ACTION_NEW_SESSION, 1))
                setOnClickPendingIntent(
                    R.id.widget_recent,
                    tap(ctx, MainActivity.ACTION_RECENT_SESSION, 2),
                )
            }

        private fun tap(ctx: Context, action: String, code: Int): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java)
                .setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                ctx,
                code,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
