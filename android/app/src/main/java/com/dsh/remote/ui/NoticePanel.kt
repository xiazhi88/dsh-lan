package com.dsh.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.remote.notify.SessionWatcher
import com.dsh.remote.ui.theme.DshColor
import java.util.concurrent.TimeUnit

/**
 * 通知悬浮窗。
 *
 * 从状态条的铃铛拉下来，锚在右上角。点某一条就直接跳到那个会话 ——
 * 跳转靠写 DSH 自己的 `localStorage['dsh.sessions.current']` 再 reload
 * （它的前端没有 URL 路由，这是唯一可靠的深链方式）。
 */
@Composable
fun NoticePanel(
    notices: List<SessionWatcher.Notice>,
    notifyEnabled: Boolean,
    onEnableNotify: () -> Unit,
    onOpen: (SessionWatcher.Notice) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(330.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {

            // 开关关着时先给入口 —— 否则用户会以为功能坏了
            if (!notifyEnabled) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "通知已关闭",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.size(3.dp))
                        Text(
                            "开启后，会话回答完成会推送通知（后台也能收到）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.size(8.dp))
                        Button(
                            onClick = onEnableNotify,
                            modifier = Modifier.heightIn(min = 34.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 16.dp, vertical = 0.dp,
                            ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Text("开启通知", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "通知",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (notices.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text("清空", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (notices.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "还没有通知",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "会话回答完成时会出现在这里",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 10.dp, vertical = 4.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(notices, key = { it.id }) { n ->
                        NoticeRow(n, onOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeRow(notice: SessionWatcher.Notice, onOpen: (SessionWatcher.Notice) -> Unit) {
    val isError = notice.kind == SessionWatcher.Kind.Error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onOpen(notice) }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(if (isError) DshColor.Danger else DshColor.Running),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                notice.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(3.dp))
            Text(
                buildString {
                    append(if (isError) "出错了" else "回答完成")
                    append(" · ")
                    append(relativeTime(notice.at))
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (isError) DshColor.Danger else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            notice.detail?.let {
                Spacer(Modifier.size(3.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 「刚刚 / 5 分钟前 / 今天 14:20 / 昨天 09:10 / 9月12日」 */
fun relativeTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMs <= 0) return ""
    val diff = now - epochMs
    if (diff < 60_000) return "刚刚"
    if (diff < 3_600_000) return "${TimeUnit.MILLISECONDS.toMinutes(diff)} 分钟前"

    val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val dayStart = (cal.clone() as java.util.Calendar).apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochMs))

    return when {
        epochMs >= dayStart -> "今天 $hm"
        epochMs >= dayStart - 86_400_000L -> "昨天 $hm"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000L} 天前"
        else -> java.text.SimpleDateFormat("M月d日", java.util.Locale.getDefault())
            .format(java.util.Date(epochMs))
    }
}
