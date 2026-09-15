package com.dshgo.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.dshgo.app.notify.NotificationCenter
import com.dshgo.app.notify.SessionWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 保活用的前台服务。
 *
 * 纯粹是为了「**手机锁屏、你不在看 app 的时候，agent 跑完了也能收到通知**」。
 * 没有它的话，app 退到后台几分钟就会被系统冻结，WebSocket 断掉，
 * 完成通知只能等你重新打开 app 才补上 —— 那就失去意义了。
 *
 * 代价是一条常驻通知（最低优先级，不响不震）。关掉通知开关时这个服务也会停。
 *
 * 服务本身不干活：它只是让进程活着，真正的事件流由 [SessionWatcher] 这个单例持有，
 * Activity 和本服务读的是同一份状态，不会各推一条。
 */
class WatchService : Service() {

    companion object {
        /** 和 NotificationCenter.FOREGROUND_ID 必须是同一个值 —— 否则原地更新会变成新增一条。 */
        private const val NOTIF_ID = NotificationCenter.FOREGROUND_ID

        /** 幂等启动；系统不允许时静默失败（比如从后台启动前台服务）。 */
        fun start(ctx: Context) {
            val intent = Intent(ctx, WatchService::class.java)
            runCatching { ctx.startForegroundService(intent) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, WatchService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        val prefs = Prefs(this)
        val host = runCatching {
            java.net.URI(prefs.entryUrl()).host
        }.getOrNull() ?: prefs.entryUrl()

        runCatching {
            startForeground(
                NOTIF_ID,
                NotificationCenter.foreground(this, host, SessionWatcher.summary.value),
            )
        }

        // 系统可能在 Activity 不在时把服务拉起来，这里兜一次底
        val url = prefs.entryUrl()
        if (url.isNotEmpty()) {
            SessionWatcher.start(this, url)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
