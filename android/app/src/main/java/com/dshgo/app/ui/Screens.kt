package com.dshgo.app.ui

import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshgo.app.Prefs
import com.dshgo.app.R
import com.dshgo.app.ui.theme.DshColor
import kotlin.math.roundToInt


/** 三个面：连接配置 / 握手等待 / DSH 界面本身（没有首页）。 */
enum class Screen { Connecting, Setup, Dsh }

/** 顶部原生状态条的内容高度（不含状态栏）。 */
val StripHeight = 44.dp

// ---------------------------------------------------------------------------
// 连接配置
// ---------------------------------------------------------------------------

@Composable
fun SetupScreen(
    initialUrl: String,
    error: String?,
    connecting: Boolean,
    onConnect: (String) -> Unit,
    /** 点「扫码」的回调；返回扫到的内容（App 侧负责解析与填回）。 */
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
    /** 非空时在左上角显示返回箭头 —— 从会话里点「更改地址」进来才给退路。 */
    onCancel: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf(initialUrl) }
    val clipboard = LocalClipboardManager.current

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (onCancel != null) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                }
                Spacer(Modifier.height(6.dp))
            } else {
                Spacer(Modifier.height(18.dp))
            }
            BrandMark()
            Spacer(Modifier.height(16.dp))
            Text(
                "DSH Go",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "DeepSeek Harness · 远程终端",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(30.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.outline,
                ),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "连接你的电脑",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "电脑上打开 DSH 的「设置 → 局域网访问」，把那里的地址填进来 —— 或者直接扫那一页的二维码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(18.dp))

                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("入口地址") },
                        placeholder = { Text("http://192.168.1.100:3081") },
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = {
                            if (text.isNotBlank()) onConnect(text)
                        }),
                        trailingIcon = {
                            TextButton(onClick = {
                                val clip = clipboard.getText()?.text
                                if (!clip.isNullOrBlank()) {
                                    text = Prefs.normalize(clip)
                                }
                            }) {
                                Text(
                                    "粘贴",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "同 WiFi 用局域网地址最快；不在同一网络用 Tailscale 地址（100.x.x.x）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(14.dp))

                    // 扫码填地址：比手敲 IP 快，也比「先在电脑上复制再粘贴」少一步 ——
                    // 电脑上「设置 → 局域网访问」那一页就摆着二维码。
                    OutlinedButton(
                        onClick = onScan,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("扫描二维码", style = MaterialTheme.typography.labelLarge)
                    }

                    if (error != null) {
                        Spacer(Modifier.height(14.dp))
                        ErrorNote(error)
                    }

                    Spacer(Modifier.height(18.dp))

                    Button(
                        onClick = { if (text.isNotBlank()) onConnect(text) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        enabled = !connecting,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        if (connecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.size(10.dp))
                        }
                        Text("连接", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Text(
                "设备与电脑直连，不经过任何中转服务器",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorNote(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DshColor.DangerSoft)
            .border(1.dp, DshColor.Danger.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Rounded.Warning,
            contentDescription = null,
            tint = DshColor.Danger,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(9.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 品牌标记：渐变圆角方块 + 终端提示符，和启动图标一致。 */
@Composable
private fun BrandMark(size: Int = 62) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.28f).dp))
            .background(
                Brush.linearGradient(
                    listOf(Color8(0x6EE7F9), Color8(0x5B78FF), Color8(0x3E5BF0)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 复用启动图标里的终端提示符矢量，品牌形象与桌面图标一致
        Icon(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.size(size.dp),
        )
    }
}

private fun Color8(v: Int) = androidx.compose.ui.graphics.Color(0xFF000000.toInt() or v)

// ---------------------------------------------------------------------------
// 会话加载遮罩
// ---------------------------------------------------------------------------


// ---------------------------------------------------------------------------
// 设置
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    url: String,
    layout: String,
    awake: Boolean,
    notifyEnabled: Boolean,
    onToggleNotify: () -> Unit,
    /** 非 null 时说明通知现在有毛病（没授权 / 渠道被静音），直接显示给用户。 */
    notifyProblem: String?,
    /** 事件流是否连着；通知全靠它。 */
    streamLive: Boolean,
    /** 当前靠什么获知会话状态："stream" / "poll" / "down"。 */
    streamMode: String,
    /** 最近一次断开的原因（null = 没出过错）。 */
    streamError: String?,
    /** 最近收到会话事件的时间，0 = 一条都没收到过。 */
    lastEventAt: Long,
    onTestNotify: () -> Unit,
    onNotifySettings: () -> Unit,
    scale: Float,
    onScale: (Float) -> Unit,
    onLayout: (String) -> Unit,
    onToggleAwake: () -> Unit,
    onClearSession: () -> Unit,
    onChangeUrl: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // 必须能滚：内容比一屏高时，ModalBottomSheet 会把超出的部分直接
                // 裁掉，用户既看不到也滑不到（诊断块就这么够不着过）。
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, bottom = 30.dp),
        ) {
            Text(
                "设置",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(20.dp))

            // 入口地址
            Text(
                "入口地址",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(7.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 13.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        url.ifEmpty { "未设置" },
                        modifier = Modifier.weight(1f).padding(vertical = 13.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(onClick = onChangeUrl) {
                        Text(
                            "更改",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // 布局
            Text(
                "会话内界面布局",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(7.dp))
            SegmentedControl(
                options = listOf(
                    Prefs.LAYOUT_AUTO to "自动",
                    Prefs.LAYOUT_MOBILE to "手机",
                    Prefs.LAYOUT_DESKTOP to "宽屏",
                ),
                selected = layout,
                onSelect = onLayout,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when (layout) {
                    Prefs.LAYOUT_MOBILE -> "始终用抽屉布局，单手操作最顺。"
                    Prefs.LAYOUT_DESKTOP -> "始终用电脑布局，平板横屏信息密度最高。"
                    else -> "窄屏用抽屉布局，≥1024px 用电脑布局，手机和平板各就各位。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))

            // 界面缩放
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "界面缩放",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${(scale * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                if (scale != Prefs.SCALE_DEFAULT) {
                    TextButton(onClick = { onScale(Prefs.SCALE_DEFAULT) }) {
                        Text("重置", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Slider(
                value = scale,
                onValueChange = onScale,
                valueRange = Prefs.SCALE_MIN..Prefs.SCALE_MAX,
                steps = 16,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "小",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "大",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "等比缩放整个 DSH 界面（文字、按钮、间距一起变），拖动即时生效。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))

            // 屏幕常亮
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "屏幕常亮",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "看 agent 跑长任务时不让屏幕熄灭",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = awake, onCheckedChange = { onToggleAwake() })
                }
            }

            Spacer(Modifier.height(20.dp))

            // 通知
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                // 必须自己套一层 Column：Surface 的内容按 Box 排布，直接放多个
                // 子节点会**互相重叠**（诊断文字压在标题上，实测踩过）。
                Column(Modifier.padding(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                        Text(
                            "会话完成通知",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "某个会话回答完成后推送通知；开启后会有一条常驻通知保活",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = notifyEnabled, onCheckedChange = { onToggleNotify() })
                }

                // 开着却收不到，是最让人困惑的状态：给一条能自己验证的路，
                // 以及一个直接跳到系统通知设置的入口 —— 渠道的重要性归系统管，
                // 代码改不动用户已经选过的设置。
                if (notifyEnabled) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = onTestNotify, contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Text("发条测试通知", style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = onNotifySettings, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("系统设置", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    notifyProblem?.let { problem ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            problem,
                            style = MaterialTheme.typography.labelSmall,
                            color = DshColor.Danger,
                        )
                    }

                    // 事件流诊断。通知是从这条流里来的，它断了就什么都收不到 ——
                    // 而以前断了只把状态点变灰，原因哪儿都看不到。
                    Spacer(Modifier.height(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (streamLive) DshColor.Running else DshColor.TextFaintLight),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (streamMode) {
                                    "stream" -> "事件流已连接"
                                    "poll" -> "轮询模式（上游不支持事件流）"
                                    else -> "事件流未连接"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        val detail = when {
                            streamMode == "poll" ->
                                "这台电脑上的 DSH 没有 WebSocket 事件流（0.1.2 之前没有），" +
                                    "已自动改用轮询，通知延迟最多几秒。"
                            streamError != null -> "上次断开：$streamError"
                            streamLive && lastEventAt > 0 -> "最近一条会话事件：${relativeTime(lastEventAt)}"
                            streamLive -> "已连接，还没收到过会话事件"
                            else -> "正在重连…"
                        }
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (streamError != null) DshColor.Danger else MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // 让用户能把完整诊断贴出来。截图会把长错误压成一行看不清，
                        // 而这条信息正是定位问题唯一需要的东西。
                        val clip = LocalClipboardManager.current
                        var copied by remember { mutableStateOf(false) }
                        TextButton(
                            onClick = {
                                clip.setText(AnnotatedString(
                                    "dshgo 诊断\n" +
                                        "入口地址：$url\n" +
                                        "事件流：" + when (streamMode) {
                                            "stream" -> "已连接（实时）"
                                            "poll" -> "轮询模式"
                                            else -> "未连接"
                                        } + "\n" +
                                        "断开原因：" + (streamError ?: "（无）") + "\n" +
                                        "最近事件：" + (if (lastEventAt > 0) relativeTime(lastEventAt) else "从未收到"),
                                ))
                                copied = true
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(
                                if (copied) "已复制，粘贴给我即可" else "复制诊断信息",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                }
            }

            Spacer(Modifier.height(20.dp))

            var confirming by remember { mutableStateOf(false) }
            if (confirming) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = DshColor.DangerSoft,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, DshColor.Danger.copy(alpha = 0.3f),
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "清除后会退回连接页，需要重新握手。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { confirming = false }) { Text("取消") }
                            TextButton(onClick = onClearSession) {
                                Text("确认清除", color = DshColor.Danger)
                            }
                        }
                    }
                }
            } else {
                OutlinedActionButton(
                    label = "清除登录与缓存",
                    tint = DshColor.Danger,
                    onClick = { confirming = true },
                )
            }

            Spacer(Modifier.height(22.dp))
            Text(
                "DSH Go · 直连你自己的电脑 · 界面为 DSH 原生",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun OutlinedActionButton(label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.4f)),
    ) {
        Text(
            label,
            modifier = Modifier.padding(vertical = 14.dp),
            textAlign = TextAlign.Center,
            color = tint,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SegmentedControl(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(4.dp)) {
            options.forEach { (value, label) ->
                val active = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else androidx.compose.ui.graphics.Color.Transparent,
                        )
                        .clickable { onSelect(value) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (active) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                        }
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 平板右栏：尚未选择会话时的占位
// ---------------------------------------------------------------------------


// ---------------------------------------------------------------------------
// 崩溃报告
// ---------------------------------------------------------------------------

/**
 * 上次运行的崩溃栈。
 *
 * 不做自动上报（没有服务器），就地把栈显示出来、一键复制——手机端崩溃往往
 * 只在特定 ROM/系统版本上出现，能把现场带出来就省掉一整轮来回。
 */
@Composable
fun CrashDialog(report: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "上次运行崩溃了",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column {
                Text(
                    "把下面的内容发给我就能定位。也可以直接复制。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                report,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(report))
                onDismiss()
            }) { Text("复制并关闭") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

// ---------------------------------------------------------------------------
// 连接中
// ---------------------------------------------------------------------------

@Composable
fun ConnectingScreen(host: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(size = 56)
            Spacer(Modifier.height(22.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "正在连接",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (host.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    host,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// DSH 界面加载中 / 出错
// ---------------------------------------------------------------------------

@Composable
fun DshLoadingOverlay(
    host: String,
    error: String?,
    onReload: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (error == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(34.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    "正在载入 DSH 界面…",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (host.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        host,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Icon(
                    Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = DshColor.Danger,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "打不开 DSH 界面",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onReload, shape = RoundedCornerShape(14.dp)) {
                        Text("重新加载")
                    }
                    TextButton(onClick = onSettings) { Text("设置") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 顶部状态条
// ---------------------------------------------------------------------------

/**
 * DSH 界面顶部的原生状态条（44dp）。
 *
 * 这个 app 没有首页 —— 会话列表在 DSH 自己的侧栏里。所以这条是**唯一**的原生入口，
 * 必须常驻：左边显示连的是哪台机器，右边是「重新加载」和「设置」。
 *
 * 只占 44dp，且和 DSH 自己的顶栏同色，视觉上是一条连续的顶栏而不是两层。
 */
@Composable
fun DshStatusStrip(
    host: String,
    live: Boolean,
    unseen: Int,
    onNotice: () -> Unit,
    onReload: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(StripHeight)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 绿 = 事件流通着；灰 = 断了（断了就收不到完成通知）
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (live) DshColor.Running else DshColor.TextFaintLight),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                host.ifEmpty { "DSH" },
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // 铃铛 + 未读角标
            Box {
                IconButton(onClick = onNotice) {
                    Icon(
                        Icons.Rounded.Notifications,
                        contentDescription = "通知",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(21.dp),
                    )
                }
                if (unseen > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 4.dp, end = 2.dp)
                            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                            .clip(CircleShape)
                            .background(DshColor.Danger)
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (unseen > 99) "99+" else unseen.toString(),
                            fontSize = 9.5.sp,
                            lineHeight = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color.White,
                        )
                    }
                }
            }

            IconButton(onClick = onReload) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "重新加载",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp),
                )
            }
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
    }
}
