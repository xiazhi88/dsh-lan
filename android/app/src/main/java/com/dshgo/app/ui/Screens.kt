package com.dshgo.app.ui

import androidx.compose.ui.graphics.Color
import com.dshgo.app.ui.theme.softAccent
import com.dshgo.app.data.displayName
import com.dshgo.app.data.ConnectionProbe
import com.dshgo.app.data.Connection
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.LinearProgressIndicator
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
import com.dshgo.app.data.UpdateCheck
import com.dshgo.app.R
import com.dshgo.app.ui.theme.DshColor
import kotlin.math.roundToInt


/** 三个面：连接配置 / 握手等待 / DSH 界面本身（没有首页）。 */
/** Locked = 宿主开了访问闸门，App 还没解锁。 */
enum class Screen { Connecting, Setup, Dsh, Locked, Connections }

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
    /** 本机版本号（BuildConfig.VERSION_NAME）。 */
    currentVersion: String,
    /** 远端最新版本号；null = 还不知道。 */
    updateLatest: String?,
    /** 远端是否比本机新。 */
    updateNewer: Boolean,
    /** 正在手动检查。 */
    updateChecking: Boolean,
    /** 本次结果建议的下载地址（镜像优先）。 */
    updateApkUrl: String,
    onCheckUpdate: () -> Unit,
    onDownload: (String) -> Unit,
    /** 把主屏小组件钉到桌面。 */
    onPinWidget: () -> Unit,
    /** 解锁方式（AppLock.Mode 的 id）。 */
    lockMode: String,
    /** 这台设备实际能用什么（给说明文字用）。 */
    lockDeviceMethod: String,
    onLockMode: (String) -> Unit,
    /** 打开连接管理。 */
    onOpenConnections: () -> Unit,
    /** 已有几条连接（入口上显示数量）。 */
    connectionCount: Int,
    /** 应用内下载/安装：idle / downloading / ready / failed。 */
    updatePhase: String,
    updateBytes: Long,
    updateTotal: Long,
    updateError: String?,
    /** 开始应用内下载并安装。 */
    onInstall: () -> Unit,
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

            // 连接管理入口。放在入口地址下面 —— 它管的就是那个地址。
            Spacer(Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenConnections,
                shape = RoundedCornerShape(14.dp),
                color = softAccent(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "连接",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (connectionCount > 1) "$connectionCount 个 · 切换" else "管理",
                        style = MaterialTheme.typography.labelMedium,
                        color = DshColor.Accent,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // 解锁方式
            //
            // 放在这里而不是塞进通知那块：它和"怎么进这个 App"有关，
            // 和通知无关。设备实际支持什么写在下面 —— 用户选了"仅指纹/人脸"
            // 但这台设备只有人脸时，得让他看得见为什么弹的是人脸。
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "解锁方式",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "打开 App 时怎么验证身份。访问密码始终是根凭据 —— 生物识别只是本机的一道门。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(10.dp))
                    SegmentedControl(
                        options = listOf(
                            "auto" to "自动",
                            "biometric" to "指纹/人脸",
                            "credential" to "设备密码",
                            "none" to "每次输密码",
                        ),
                        selected = lockMode,
                        onSelect = onLockMode,
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        buildString {
                            append(
                                when (lockMode) {
                                    "biometric" -> "只用指纹或人脸，不给设备密码回退。"
                                    "credential" -> "只用设备的 PIN / 图案 / 密码。"
                                    "none" -> "本机不存密码，每次打开都手输 —— 最严的一档。"
                                    else -> "指纹或人脸优先，没有就用设备密码。"
                                },
                            )
                            if (lockMode != "none") {
                                append("　这台设备可用：")
                                append(lockDeviceMethod.ifEmpty { "无" })
                                if (lockDeviceMethod == "无") {
                                    // 文案要跟着选项走 —— 选了「设备密码」却说"配好指纹或人脸"，
                                    // 用户会以为选错了
                                    append(
                                        if (lockMode == "credential") "（先去系统设置里设一个 PIN 或图案）"
                                        else "（配好指纹或人脸后再来）",
                                    )
                                }
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

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

            // 版本与更新
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "版本",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (updateChecking) "正在检查…" else "当前 $currentVersion",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = onCheckUpdate,
                            enabled = !updateChecking,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                        ) {
                            Text("检查更新", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    // 有新版本才占地方 —— 没查到（网络不通）时什么都不说，
                    // 检查更新失败不该打扰用户。
                    if (updateNewer && updateLatest != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (updatePhase == "ready") "已下载，等待安装" else "有新版本 $updateLatest",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = onInstall,
                                enabled = updatePhase != "downloading",
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                            ) {
                                Text(
                                    when (updatePhase) {
                                        "downloading" -> "下载中"
                                        "ready" -> "去安装"
                                        "failed" -> "重试"
                                        else -> "下载并安装"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }

                        // 进度条。总长未知时（服务端没给 Content-Length）用不确定态 ——
                        // 硬凑一个百分比反而是在骗人。
                        if (updatePhase == "downloading") {
                            Spacer(Modifier.height(8.dp))
                            val pct = if (updateTotal > 0) {
                                ((updateBytes * 100) / updateTotal).toInt().coerceIn(0, 100)
                            } else {
                                null
                            }
                            if (pct == null) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(
                                    progress = { pct / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                buildString {
                                    append(fmtSize(updateBytes))
                                    if (updateTotal > 0) append(" / ").append(fmtSize(updateTotal))
                                    if (pct != null) append("　").append(pct).append("%")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        updateError?.let { err ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "下载失败：$err",
                                style = MaterialTheme.typography.labelSmall,
                                color = DshColor.Danger,
                            )
                        }

                        // 应用内下载只是方便，不是唯一的路 —— 装不上时还得能手工兜底
                        TextButton(
                            onClick = { onDownload(UpdateCheck.APK_URL) },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            Text("改用浏览器下载", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    TextButton(
                        onClick = onPinWidget,
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text("把小组件放到桌面", style = MaterialTheme.typography.labelSmall)
                    }

                    if (!updateChecking && updateLatest != null && !updateNewer) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "已是最新版本（$updateLatest）",
                            style = MaterialTheme.typography.labelSmall,
                            color = DshColor.Running,
                        )
                        // 查得到版本、但你知道有更新的情况确实会存在：镜像有索引延迟，
                        // GitHub 国内又可能不通。所以给一条永远可用的手工路，
                        // 而不是让用户对着"已是最新"干瞪眼。
                        TextButton(
                            onClick = { onDownload("") },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            Text("手动下载最新版", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    if (updateChecking || updateLatest == null) {
                        // 一路都没查到 —— 那就别装"已是最新"，直接给手工入口
                        TextButton(
                            onClick = { onDownload("") },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            Text("直接打开下载页", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

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
    /** 回首页（连接列表）。 */
    onHome: () -> Unit,
    /** 语音输入：说话 → 写进 DSH 的输入框。 */
    onVoice: () -> Unit,
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
            // 回首页（连接列表）。放在最左 —— 它管的是「现在连着哪个」，
            // 比通知 / 麦克风这些更靠外一层。
            IconButton(onClick = onHome, modifier = Modifier.size(38.dp)) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "返回连接列表",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }

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

            // 语音输入：手机上打字是最大的摩擦，而 prompt 恰恰适合用说的
            IconButton(onClick = onVoice) {
                Icon(
                    DshIcons.Mic,
                    contentDescription = "语音输入",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp),
                )
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

/**
 * 主文档加载失败时的界面。
 *
 * 为什么自己做而不用 WebView 的原生错误页：那一页只有一句
 * `net::ERR_CONNECTION_REFUSED`，对用户毫无指导意义 —— 他不知道该去开 dsh web、
 * 还是该改地址、还是该检查是不是同一个网络。
 *
 * 这里做两件事：**把错误翻成人话**，以及**给出下一步动作**。
 */
@Composable
fun PageErrorOverlay(
    host: String,
    url: String,
    detail: String,
    onRetry: () -> Unit,
    onEditUrl: () -> Unit,
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 26.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))

            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(DshColor.DangerSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = DshColor.Danger,
                )
            }

            Spacer(Modifier.height(18.dp))

            Text(
                "连不上 $host",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                explainLoadFailure(detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            // 逐条排查。顺序按「最常见的先查」排 —— 电脑上 dsh web 停了是最常见的原因。
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "按顺序查一遍",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    listOf(
                        "电脑上的 dsh web 还在运行吗？关掉那个终端窗口它就停了。",
                        "dshgo 插件装了吗、装完重启过 dsh web 吗？",
                        "手机和电脑在同一个 WiFi（或同一个 Tailscale 网络）吗？",
                        "地址对吗？电脑上「设置 → 局域网访问」里能一键复制。",
                    ).forEachIndexed { i, line ->
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                "${i + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(18.dp),
                            )
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("重试", style = MaterialTheme.typography.labelLarge)
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onEditUrl,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("改地址", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onSettings,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("设置", style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(Modifier.height(18.dp))

            // 原始错误留在最后：普通用户用不上，但排查时是唯一线索
            Text(
                "$url\n$detail",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 把 WebView 的错误码翻成人话。 */
/** 字节数写成人看的量级。 */
private fun fmtSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun explainLoadFailure(detail: String): String = when {
    detail.contains("CONNECTION_REFUSED", ignoreCase = true) ->
        "电脑拒绝了这个连接 —— 最常见的原因是 dsh web 没在运行，或者 dshgo 插件没装上（装完要重启 dsh web）。"
    detail.contains("TIMED_OUT", ignoreCase = true) ||
        detail.contains("ADDRESS_UNREACHABLE", ignoreCase = true) ||
        detail.contains("HOST_LOOKUP", ignoreCase = true) ->
        "连不上这台电脑 —— 手机和它不在同一个网络，或者地址填错了。"
    detail.contains("NAME_NOT_RESOLVED", ignoreCase = true) ->
        "这个地址解析不了 —— 地址可能写错了，请对照电脑上显示的那一串。"
    detail.contains("CLEARTEXT", ignoreCase = true) ->
        "系统拦截了明文 HTTP 请求。这不该发生，麻烦反馈一下。"
    detail.startsWith("HTTP 4") ->
        "服务器返回了 $detail —— 多半是登录凭据失效了，点「重试」会重新握手。"
    detail.startsWith("HTTP 5") ->
        "服务器返回了 $detail —— 转发层或 dsh web 那边出了状况。"
    else -> "页面没能加载。"
}

/**
 * 访问闸门界面。
 *
 * 宿主（插件）在转发端口上加了一道密码闸门：浏览器看到密码页，App 看到 401。
 * 这是 App 那一侧的界面 —— 先过生物识别（或设备密码），再去宿主那边换解锁凭据。
 *
 * 设计上刻意**不做成"App 自己的锁"**：真正的安全边界在宿主那边，
 * 这一屏只是把"交密码"这一步做得舒服一点。所以即使有人绕过这一屏，
 * 他面对的仍然是宿主的闸门 —— 这也正是为什么两类客户端要的是同一个密码。
 */
@Composable
fun LockScreen(
    host: String,
    stage: String,
    error: String?,
    /** 这台设备支持什么（AppLock.describe）；没有生物识别时为空。 */
    deviceMethod: String,
    /** 是否已经存过密码 —— 决定了"下次能不能直接用人脸/指纹"。 */
    hasStoredPassword: Boolean,
    onUnlock: (String) -> Unit,
    onRetryBiometric: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = DshColor.Accent,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "需要解锁",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "$host 暴露在网络上，已开启访问验证。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(26.dp))

            if (stage == "busy") {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    "正在验证…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (stage == "prompt") {
                // 生物识别由系统弹窗承载，这里只放一个重新拉起的入口
                Button(
                    onClick = onRetryBiometric,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("用指纹或面部解锁", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { input = "" }) {
                    Text("改用访问密码", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                // ★ 把「为什么这次要输密码」说清楚。
                //
                // 密码是根凭据，生物识别只是本地的一道门 —— 所以第一次必须先输一次，
                // 之后才能用人脸/指纹。不解释的话，用户配好了人脸解锁却发现还是密码框，
                // 会直接认为"生物识别没生效"。
                if (deviceMethod != "无" && !hasStoredPassword) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = softAccent(),
                    ) {
                        Text(
                            "这台设备支持$deviceMethod。先输一次密码，之后就能直接用$deviceMethod 解锁，不用再输。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("访问密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = {
                        if (input.isNotBlank()) onUnlock(input)
                    }),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { if (input.isNotBlank()) onUnlock(input) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("解锁", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "这个密码是在电脑上的 DSH「设置 → 局域网访问」里设的。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = DshColor.Danger,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onOpenSettings) {
                Text("设置", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * 首页：选择要连接的电脑。
 *
 * ## 为什么连接列表是首页，而不是藏在设置里
 *
 * 一个人可能有好几台跑 DSH 的机器，也可能同一个 DSH 在不同网络下地址不同
 * （家里局域网、出门 Tailscale）。藏在设置里意味着每次换机器都要翻三层菜单。
 * 摆在首页就是一屏之遥，而且每个状态一眼可见。
 *
 * ## 状态能给什么、不能给什么
 *
 * 探针打插件自己的两个端点（`/info` 和 `/auth/status`），**都不需要先解锁** ——
 * 所以从没登录过的 DSH 也能显示在线、延迟、版本、要不要密码。
 *
 * **「这台机器里有几个会话在跑」拿不到** —— 那要先登录它，而列表里的连接你
 * 未必都存过密码。所以不显示，而不是放一个永远空着的「0 个会话」。
 */
@Composable
fun ConnectionsScreen(
    connections: List<Connection>,
    statuses: Map<String, ConnectionProbe.Status>,
    activeId: String?,
    probing: Boolean,
    onSwitch: (Connection) -> Unit,
    onProbeAll: () -> Unit,
    onEdit: (Connection) -> Unit,
    onDelete: (Connection) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<Connection?>(null) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {

            // ── 品牌头 ────────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    // top 只留 4dp：上面已经有 statusBarsPadding() 给的系统 inset，
                    // 再叠一层自己的内边距就是白留一条。
                    .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_whale),
                    contentDescription = null,
                    tint = DshColor.Accent,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "DSH Go",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (probing) "正在检查各台电脑…" else "选择要连接的电脑",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 用文字「+」而不是图标：项目的图标集里没有 Add，
                // 为一个加号引入整套 extended icons 不划算。
                Surface(
                    onClick = onAdd,
                    shape = CircleShape,
                    color = softAccent(0.16f),
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "+",
                            style = MaterialTheme.typography.titleMedium,
                            color = DshColor.Accent,
                        )
                    }
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(8.dp))

                if (connections.isEmpty()) {
                    EmptyConnections()
                } else {
                    connections.forEach { conn ->
                        ConnectionCard(
                            conn = conn,
                            status = statuses[conn.url],
                            active = conn.id == activeId,
                            probing = probing && statuses[conn.url] == null,
                            onEnter = { onSwitch(conn) },
                            onEdit = { editing = conn },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }

                Spacer(Modifier.height(4.dp))

                // 刷新做成一整条，比右上角一个小图标好按得多
                TextButton(
                    onClick = onProbeAll,
                    enabled = !probing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (probing) "检查中…" else "刷新状态",
                        style = MaterialTheme.typography.labelMedium,
                        color = DshColor.Accent,
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "状态是探测出来的：插件在不在监听、版本多少、要不要密码。" +
                        "「几个会话在跑」只对当前连着的那条有效 —— 其他连接没登录过，探不到。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    editing?.let { conn ->
        EditConnectionDialog(
            conn = conn,
            onDismiss = { editing = null },
            onSave = { name, url ->
                onEdit(conn.copy(name = name, url = url))
                editing = null
            },
            onDelete = {
                onDelete(conn)
                editing = null
            },
        )
    }
}

/** 一条连接都没存过时的引导。 */
@Composable
private fun EmptyConnections() {
    Surface(
        Modifier.fillMaxWidth().padding(top = 40.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "还没有连接",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "地址在电脑上的 DSH「设置 → 局域网访问」里 —— 也可以扫那一页的二维码，" +
                    "不用手敲 IP。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )
        }
    }
}

/**
 * 一张连接卡片。
 *
 * ## 当前那条怎么强调
 *
 * **用边框，不用填充。** 一开始填充了浅色底，深色主题下白字涂上去直接隐形 ——
 * 同一个坑设置里的「连接」卡片也踩过。边框 + 淡底（带透明度，不是写死的浅色）
 * 在明暗两套主题下都稳。
 */
@Composable
private fun ConnectionCard(
    conn: Connection,
    status: ConnectionProbe.Status?,
    active: Boolean,
    probing: Boolean,
    onEnter: () -> Unit,
    onEdit: () -> Unit,
) {
    val online = status?.online == true && status.listening
    val dot = when {
        status == null -> MaterialTheme.colorScheme.onSurfaceVariant
        !status.online -> DshColor.Danger
        !status.listening -> DshColor.Warning
        else -> DshColor.Running
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onEnter,
        shape = RoundedCornerShape(18.dp),
        color = if (active) softAccent(0.10f) else MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(
            width = if (active) 1.5.dp else 1.dp,
            color = if (active) DshColor.Accent.copy(alpha = 0.55f) else DshColor.OutlineDark.copy(alpha = 0.35f),
        ),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 15.dp, bottom = 15.dp)) {

            // 名字行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(dot),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    conn.displayName(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // ★ weight(1f) 而不是 weight(1f, fill = false)。
                    //
                    // fill = false 时这个 Text 只占内容宽度，于是它和后面那个
                    // Spacer(weight(1f)) **按内容宽度分剩余空间** —— 名字越长，
                    // 右边那个齿轮越靠左，两张卡片的 ⚙ 就对不齐。
                    // 让 Text 吃满剩余宽度、并把后面那个 Spacer 删掉，
                    // 齿轮就永远贴右边。
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = DshColor.Accent,
                    ) {
                        Text(
                            "当前",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = "编辑",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }

            // 地址 —— 只在用户起过名字时才单独显示。
            // 没起名时标题就是地址推出来的（displayName 的回退），再显示一遍是重复。
            if (conn.name.isNotBlank()) {
                Text(
                    conn.url.removePrefix("http://"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 19.dp, top = 3.dp),
                )
            }

            // 状态
            Spacer(Modifier.height(if (conn.name.isNotBlank()) 6.dp else 4.dp))
            Text(
                when {
                    probing -> "检查中…"
                    status == null -> "未检查"
                    !status.online -> status.error.ifEmpty { "连不上" }
                    !status.listening -> "插件在，但没在监听 —— 看电脑上的启动日志"
                    else -> buildString {
                        append(status.ms).append(" ms")
                        if (status.version.isNotEmpty()) append(" · v").append(status.version)
                        if (status.needsPassword) append(" · 需要解锁")
                        if (status.updateAvailable) append(" · 插件有新版")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    status != null && !status.online -> DshColor.Danger
                    status != null && !status.listening -> DshColor.Warning
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 19.dp),
            )
        }
    }
}

/** 改名 / 改地址 / 删除。 */
@Composable
private fun EditConnectionDialog(
    conn: Connection,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(conn.name) }
    var url by remember { mutableStateOf(conn.url) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑连接", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名字（可留空）") },
                    placeholder = { Text(conn.url.removePrefix("http://")) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("地址") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = RoundedCornerShape(12.dp),
                )
                if (confirmDelete) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "再点一次「删除」确认。",
                        style = MaterialTheme.typography.labelSmall,
                        color = DshColor.Danger,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), url.trim()) },
                enabled = url.trim().isNotEmpty(),
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            // 两段式：第一次点变成「确认删除」，第二次才真删。
            // 原来两个分支都写「删除」，等于没有确认这一步。
            TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                Text(
                    if (confirmDelete) "确认删除" else "删除",
                    color = DshColor.Danger,
                )
            }
        },
    )
}
