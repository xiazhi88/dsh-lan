package com.dshgo.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dshgo.app.data.DshApi

/**
 * 通知栏「允许 / 拒绝」两个按钮的落点。
 *
 * ## 为什么用广播而不是 Activity
 *
 * 点通知动作时**不该把 App 拉到前台** —— 用户可能正在别的应用里，
 * 他的意图只是"点个头让 agent 继续跑"，不是"我要去看 DSH"。
 * 广播能在后台悄悄把回执发出去，通知一撤，用户继续做他的事。
 *
 * ## 为什么现在就回答、不排队等网络
 *
 * agent 正卡在这一步上。回执晚一秒，它就多等一秒。这里的网络调用本身很快
 * （一条 POST），而且是发到转发的 `/api` 上，不是 GitHub 那种外网。
 * 失败了也只是这次审批没答上 —— 网页那边仍然可以答。
 */
class ApprovalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val allow = when (intent.action) {
            NotificationCenter.ACTION_APPROVE -> true
            NotificationCenter.ACTION_REJECT -> false
            else -> return
        }

        val eventId = intent.getStringExtra(NotificationCenter.EXTRA_EVENT_ID) ?: return
        val clientId = intent.getStringExtra(NotificationCenter.EXTRA_CLIENT_ID) ?: return
        val tool = intent.getStringExtra(NotificationCenter.EXTRA_TOOL) ?: "操作"
        val reason = intent.getStringExtra(NotificationCenter.EXTRA_REASON)

        val ask = DshApi.ApprovalAsk(
            clientId = clientId,
            eventId = eventId,
            toolName = tool,
            reason = reason,
        )

        // 无论成不成功都要撤掉通知并摘掉待办 —— 否则会出现「点过了通知还在」，
        // 用户会反复点，而那条 waterfall 早就不在了。
        SessionWatcher.answer(ask, allow)
    }
}
