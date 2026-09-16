package com.dshgo.app

import androidx.biometric.BiometricManager
import com.dshgo.app.data.UnlockClient
import com.dshgo.app.data.AppLock
import com.dshgo.app.data.UpdateInstaller
import com.dshgo.app.data.ApkDownloader
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import java.io.File
import com.dshgo.app.data.ShareInbox
import com.dshgo.app.data.ComposerWriter
import java.util.Locale
import android.widget.Toast
import android.speech.RecognizerIntent
import com.dshgo.app.data.AddressPicker
import android.util.Log
import android.net.ConnectivityManager
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.dshgo.app.data.UpdateCheck
import android.annotation.SuppressLint
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import android.webkit.ValueCallback
import android.net.Uri
import android.content.ActivityNotFoundException
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import java.util.concurrent.TimeUnit
import java.io.ByteArrayInputStream
import okhttp3.Request
import okhttp3.OkHttpClient
import com.dshgo.app.web.PageInject
import com.dshgo.app.data.WebViewCookies
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import com.dshgo.app.ui.NoticePanel
import com.dshgo.app.notify.SessionWatcher
import com.dshgo.app.notify.NotificationCenter
import androidx.compose.ui.window.Popup
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.Build
import android.Manifest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.dshgo.app.ui.ConnectingScreen
import com.dshgo.app.ui.CrashDialog
import com.dshgo.app.ui.DshLoadingOverlay
import com.dshgo.app.ui.LockScreen
import com.dshgo.app.ui.PageErrorOverlay
import com.dshgo.app.ui.DshStatusStrip
import com.dshgo.app.ui.Screen
import com.dshgo.app.ui.SettingsSheet
import com.dshgo.app.ui.SetupScreen
import com.dshgo.app.ui.StripHeight
import com.dshgo.app.ui.theme.DshTheme

/**
 * 单 Activity，**没有首页**。
 *
 * DSH 界面里本来就有会话列表（侧栏抽屉），客户端再画一份是纯重复。所以配好地址之后
 * 打开就是 DSH 界面本身。
 *
 * 原生层只负责 DSH 界面做不到或做得别扭的几件事：
 *   · 与 dsh-pocket 的握手、会话凭据维护
 *   · 从会话里改入口地址、切换布局、界面缩放
 *   · 崩溃自记录
 *
 * WebView 常驻不销毁，所以「设置 → 改地址 → 连回来」不会丢掉页面状态。
 */
/**
 * 宿主 Activity。
 *
 * 继承 [androidx.fragment.app.FragmentActivity] 而不是 ComponentActivity，
 * 只是因为 `BiometricPrompt` 的构造函数要求前者（它内部用 Fragment 承载对话框）。
 * 其余能力不变 —— FragmentActivity 本身就是 ComponentActivity 的子类，
 * Compose、registerForActivityResult、enableEdgeToEdge 都照常。
 */
class MainActivity : androidx.fragment.app.FragmentActivity() {

    /**
     * 快捷方式的 action 常量。
     *
     * 用 `internal` 而不是 `private`：主屏小组件的按钮要复用同一套 action ——
     * 逻辑只写一份，在 MainActivity 里处理。
     */
    internal companion object {
        const val TAG = "dshgo"

        /** 与 res/xml/shortcuts.xml 里的 action 一一对应。 */
        const val ACTION_NEW_SESSION = "com.dshgo.app.NEW_SESSION"
        const val ACTION_RECENT_SESSION = "com.dshgo.app.RECENT_SESSION"
        const val ACTION_OPEN_SETTINGS = "com.dshgo.app.OPEN_SETTINGS"
    }


    private lateinit var prefs: Prefs
    private lateinit var webView: WebView

    /** DSH 页面是否已经成功载入过一次（决定能不能往页面里注入 JS）。 */
    private var loaded = false

    private val handler = Handler(Looper.getMainLooper())
    private var loadTimeout: Runnable? = null

    /**
     * 自己发请求用的客户端。用于 `shouldInterceptRequest` 里取主文档来改写
     * （注入布局覆盖 + polyfill 兜底），以及取不到时的兜底判断。
     * cookie 走 [WebViewCookies] —— 和 WebView 共用一份会话。
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(WebViewCookies)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private var ui by mutableStateOf(ShellState())

    /**
     * WebView 文件选择器的回执通道。
     *
     * 没有它，页面上任何 `<input type="file">`（DSH 的附件按钮就是这个）点了都**毫无反应** ——
     * WebView 通过 [WebChromeClient.onShowFileChooser] 把选择请求交给宿主，宿主不接管就等于丢弃。
     *
     * 选文件走的是 SAF（`ACTION_GET_CONTENT`），**不需要任何权限**：用户选中的文件由系统
     * 逐次授权给本 app。所以「图片/文件权限」在设置里找不到是正常的，本来就没有这个开关。
     */
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /**
     * 从通知点进来、但页面还没载入完时先存着，等 onPageFinished 再应用。
     * 冷启动点通知会走这条路径。
     */
    private var pendingSessionId: String? = null

    /** Android 13+ 发通知要运行时授权；用户拒绝就保持关闭，不反复骚扰。 */
    private val notifyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // ★ 关键时机：权限刚拿到就建渠道。
            // 在还没权限时创建的渠道会被系统按静默落库（国产 ROM 尤其明显），
            // 而渠道一旦创建就不可变 —— 之后再改代码也没用。
            NotificationCenter.ensureDoneChannel(this)
        }
        setNotify(granted)
    }

    /**
     * 扫码填地址。
     *
     * 用 ZXing 的嵌入式 ScanContract：它自带一个相机 Activity，扫完把内容回传，
     * 不用自己写预览/解码。选它而不是 ML Kit，是因为后者依赖 Google Play 服务 ——
     * 侧载安装的设备上不保证有。
     *
     * 扫到就直接连，不再让用户点一次「连接」：二维码就是入口地址本身，
     * 扫的动作已经表达了「用这个」。
     */
    /** 语音识别器。见 [onVoice]。 */
    private var recognizer: android.speech.SpeechRecognizer? = null

    /** 麦克风权限。老版本没有这个权限，回调只会在新版本触发。 */
    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startListening() else toast("需要麦克风权限才能语音输入")
    }

    private val scanLauncher = registerForActivityResult(
        com.journeyapps.barcodescanner.ScanContract(),
    ) { result ->
        val raw = result.contents
        if (raw.isNullOrBlank()) return@registerForActivityResult
        val normalized = Prefs.normalize(raw)
        if (normalized.isEmpty()) {
            ui = ui.copy(setupError = "扫到的不是可用的地址：$raw")
            return@registerForActivityResult
        }
        connect(normalized)
    }

    /** 点「扫描二维码」。没相机的设备会由系统抛出来，接住给一句人话。 */
    private fun onScan() {
        val options = com.journeyapps.barcodescanner.ScanOptions().apply {
            setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
            setPrompt("对准电脑上的二维码")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        runCatching { scanLauncher.launch(options) }
            .onFailure { ui = ui.copy(setupError = "打不开相机 —— 检查有没有给相机权限，或直接手输地址。") }
    }

    private val fileChooser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val cb = filePathCallback
        filePathCallback = null
        // parseResult 会把多选、取消、以及 file:///content:// 都归一成 Uri 数组
        cb?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
    }

    // ------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        // 回到前台：页面自己会处理审批，把挂起的撤掉，别让两条链路抢答
        SessionWatcher.onForegroundChanged(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 统一 edge-to-edge。Android 15 起对 targetSdk 35 是强制的，旧版本上显式开启
        // 才能拿到同一套 inset 语义 —— 否则「输入法遮挡输入框」在不同系统上表现不一致。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = ShellState(
            entryUrl = prefs.entryUrl(),
            layout = prefs.layout(),
            scale = prefs.scale(),
            awake = prefs.keepAwake(),
            notifyEnabled = prefs.notifyEnabled(),
            crashReport = CrashLog.read(this),
        )
        CookieManager.getInstance().setAcceptCookie(true)

        buildWebView()
        applyKeepAwake()

        setContent {
            DshTheme {
                Shell(
                    state = ui,
                    webView = webView,
                    onConnect = ::connect,
                    onReload = ::reloadDsh,
                    onBack = ::handleBack,
                    onOpenSettings = { ui = ui.copy(settingsOpen = true, notifyProblem = notifyProblem()) },
                    onCloseSettings = { ui = ui.copy(settingsOpen = false) },
                    onChangeUrl = {
                        ui = ui.copy(
                            screen = Screen.Setup,
                            settingsOpen = false,
                            setupError = null,
                            connecting = false,
                        )
                    },
                    onCancelSetup = { ui = ui.copy(screen = Screen.Dsh) },
                    onLayout = ::setLayout,
                    onScale = ::applyScale,
                    onToggleAwake = ::toggleAwake,
                    onClearSession = ::clearSession,
                    onToggleNotify = ::requestNotify,
                    onTestNotify = ::onTestNotify,
                    onNotifySettings = ::onNotifySettings,
                    onScan = ::onScan,
                    onVoice = ::onVoice,
                    onUnlock = ::submitPassword,
                    onRetryUnlock = ::tryUnlock,
                    updateLatest = ui.updateLatest,
                    updateNewer = ui.updateNewer,
                    updateChecking = ui.updateChecking,
                    updateApkUrl = ui.updateApkUrl,
                    onCheckUpdate = { checkUpdate(force = true) },
                    onPinWidget = ::pinWidget,
                    updatePhase = ui.updatePhase,
                    updateBytes = ui.updateBytes,
                    updateTotal = ui.updateTotal,
                    updateError = ui.updateError,
                    onInstall = { startUpdateDownload(ui.updateApkUrl) },
                    onDownload = { url -> openDownload(url) },
                    onOpenPanel = {
                        SessionWatcher.markAllSeen()
                        ui = ui.copy(panelOpen = true)
                    },
                    onClosePanel = { ui = ui.copy(panelOpen = false) },
                    onClearNotices = { SessionWatcher.clear() },
                    onOpenNotice = { sessionId -> openSessionFromNotice(sessionId) },
                    onDismissCrash = {
                        CrashLog.clear(this)
                        ui = ui.copy(crashReport = null)
                    },
                )
            }
        }

        NotificationCenter.ensureChannels(this)

        if (prefs.hasEntryUrl()) connect(null) else ui = ui.copy(screen = Screen.Setup)
        maybeHandleShare(intent)
        // 从通知点进来 / 冷启动时带过来的目标会话
        consumeOpenSession(intent)
        if (prefs.notifyEnabled()) startWatching()

        // 后台查一次更新（内部有 24 小时节流，失败静默）
        checkUpdate()

        // 已知地址不止一个时，看看现在这个还用不用得上
        autoPickAddress()
        watchNetwork()
        maybeHandleShortcut(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeHandleShortcut(intent)
        maybeHandleShare(intent)
        consumeOpenSession(intent)
    }

    /**
     * 长按图标的快捷方式。
     *
     * 「新会话」和「继续最近」都是往 DSH 页面里注入一次点击/localStorage，
     * 而不是另开一个界面 —— 会话的选与开本来就是 DSH 自己的事，
     * 我们只负责把他送到那个状态。
     */
    private fun maybeHandleShortcut(intent: Intent?) {
        when (intent?.action) {
            ACTION_NEW_SESSION -> handler.postDelayed({ clickNewSession() }, 600)
            ACTION_RECENT_SESSION -> openMostRecentSession()
            ACTION_OPEN_SETTINGS -> ui = ui.copy(settingsOpen = true)
        }
    }


    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        // 退到后台：审批改由原生接管（前台时页面自己会处理，抢答会打架）
        SessionWatcher.onForegroundChanged(false)
    }

    override fun onDestroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        clearLoadTimeout()
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // WebView
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView() {
        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.WHITE)
        }
        WebView.setWebContentsDebuggingEnabled(true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            textZoom = 100
            allowFileAccess = false
            allowContentAccess = true          // 附件上传要读 content://
            // 不覆盖 UA：DSH 按视口宽度决定移动/桌面布局，手机平板各就各位
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {

            /**
             * 接管主文档，注入布局覆盖与 polyfill 兜底。
             *
             * 为什么必须在这一层：DSH 决定手机/电脑布局靠的是页面启动时的一次
             * `matchMedia('(max-width: 1023px)')`，页面加载完再改就晚了，
             * 改 CSS 也没用（判定走 JS）。只有在自己的脚本之前把结果换掉才有效。
             *
             * 任何一步不顺利都返回 null，交回 WebView 走原路 ——
             * 布局设置出问题也不能让页面打不开。
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                if (!request.isForMainFrame || request.method != "GET") return null
                val scheme = request.url.scheme
                if (scheme != "http" && scheme != "https") return null
                return runCatching { rewriteMainDocument(request.url) }.getOrNull()
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                // 新的一次导航，先把上一次的失败清掉
                if (ui.pageError != null) ui = ui.copy(pageError = null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                loaded = true
                clearLoadTimeout()

                // 从通知点进来的目标会话：页面刚就绪，正好写 localStorage
                pendingSessionId?.let { pending ->
                    pendingSessionId = null
                    view.evaluateJavascript(setCurrentSessionJs(pending), null)
                    prefs.setLastSessionId(pending)
                    return
                }

                // 整页载入会把 html 的 zoom 冲掉，这里补回来
                view.evaluateJavascript(scaleJs(prefs.scale()), null)
                // 加载失败时**不能**把 dshReady 置回 true —— Chrome 对错误页
                // 同样会调 onPageFinished，置回去会把失败界面撤掉、露出原生错误页。
                if (ui.screen == Screen.Dsh && ui.pageError == null) {
                    ui = ui.copy(dshReady = true, dshError = null)
                }
            }

            override fun onReceivedError(
                view: WebView,
                req: WebResourceRequest,
                err: WebResourceError,
            ) {
                if (!req.isForMainFrame) return
                clearLoadTimeout()
                if (ui.screen == Screen.Dsh) {
                    ui = ui.copy(
                        dshReady = false,
                        pageError = err.description.toString(),
                        dshError = err.description.toString(),
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                req: WebResourceRequest,
                res: WebResourceResponse,
            ) {
                if (!req.isForMainFrame || res.statusCode < 400) return
                clearLoadTimeout()
                if (ui.screen == Screen.Dsh) {
                    ui = ui.copy(pageError = "HTTP ${res.statusCode}")
                    ui = ui.copy(
                        dshReady = false,
                        dshError = when (res.statusCode) {
                            401 -> "登录凭据失效了（电脑上的 dsh web 可能重启过）。点「重新加载」换一份新的。"
                            502, 504 -> "代理连不上 dsh web：电脑上的 dsh web 可能没在运行。"
                            else -> "HTTP ${res.statusCode}"
                        },
                    )
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                // 上一次没回执就作废，否则页面的 input 会一直卡在「已打开」状态
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                // 正好有一张从别的应用分享进来的图？直接把它交出去。
                //
                // 这是「分享进 DSH」的另一半：分享时图片已经落到私有目录，
                // 用户回到 DSH 点「附件」就应该是把那张图发出去，而不是又弹一次
                // 文件选择器让他重新找一遍。
                ShareInbox.consumePendingImage(this@MainActivity)?.let { (file, name) ->
                    // 必须叫这个名字，页面才会把它当图片附件处理
                    val named = File(file.parentFile, name)
                    runCatching { file.renameTo(named) }
                    val target = if (named.exists()) named else file
                    ShareInbox.clearPendingImage(this@MainActivity)
                    filePathCallback = null
                    callback.onReceiveValue(arrayOf(Uri.fromFile(target)))
                    return true
                }

                return try {
                    fileChooser.launch(params.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    // 没有文件管理器 / 相机：回 null 让页面知道没选到，别把 input 卡死
                    filePathCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // 只拒绝 getUserMedia 那类（摄像头/麦克风实时流）。
                // 附件上传不经过这里 —— 那是 onShowFileChooser 的事。
                request.deny()
            }
        }
    }

    // ------------------------------------------------------------------
    // 连接
    // ------------------------------------------------------------------

    /** @param url 来自连接页的输入；为 null 表示沿用已保存的地址。 */
    private fun connect(url: String?) {
        if (url != null) {
            val normalized = Prefs.normalize(url)
            if (normalized.isEmpty()) {
                ui = ui.copy(setupError = "地址格式不对，例：http://192.168.1.100:3081")
                return
            }
            prefs.setEntryUrl(normalized)
            ui = ui.copy(entryUrl = prefs.entryUrl())
        }

        val entry = prefs.entryUrl()
        if (entry.isEmpty()) {
            ui = ui.copy(screen = Screen.Setup, connecting = false)
            return
        }

        loaded = false
        ui = ui.copy(
            screen = Screen.Connecting,
            connecting = true,
            setupError = null,
            dshError = null,
        )

        Thread({
            val result = Handshake.run(entry, false)
            handler.post { onHandshake(entry, result) }
        }, "dsh-handshake").start()
    }

    private fun onHandshake(entry: String, result: Handshake.Result) {
        val cm = CookieManager.getInstance()
        if (!result.ok) {
            cm.removeAllCookies(null)
            cm.flush()
            ui = ui.copy(
                screen = Screen.Setup,
                connecting = false,
                setupError = buildString {
                    append(if (result.status == 0) "连不上电脑" else "电脑拒绝了这次连接")
                    if (result.message.isNotEmpty()) append(" · ").append(result.message)
                },
            )
            return
        }
        result.cookies.forEach { cm.setCookie(entry, it) }
        cm.flush()

        // 握手成功 = 这个地址确实能用，记下来供以后自动选路
        prefs.rememberUrl(entry)

        ui = ui.copy(
            screen = Screen.Dsh,
            connecting = false,
            dshReady = false,
            dshError = null,
        )

        // ★ 先过访问闸门，再加载页面。
        //
        // 宿主（插件）在转发端口上加了密码闸门：没解锁的话，注入启动 token 那一步
        // 会被挡下，页面拿到的是密码页或 401。App 这一侧的正确顺序是
        // 「握手 → 解锁 → 带 Cookie 加载」，顺序反了会先闪一下密码页。
        gateThenLoad(entry)

        // 通知开着的话，握手之后把事件流（重新）接上。
        //
        // 为什么必须在这里做：SessionWatcher 把 baseUrl 用闭包捕获了，改了入口
        // 地址之后那条流还连着旧机器 —— 界面显示新地址、诊断里却报旧 IP（实测踩过）。
        // SessionWatcher.start 现在会识别地址变化并重开，但前提是**有人再调它一次**，
        // 而改地址走的正是 connect → 握手，这里就是那个该调的地方。
        if (prefs.notifyEnabled()) startWatching()

        // 后台查一次更新（内部有 24 小时节流，失败静默）
        checkUpdate()

        // 重新加载时顺手看看地址还用不用得上（不重复注册网络回调）
        autoPickAddress()

        startLoadTimeout()
        webView.loadUrl(entry)
    }

    /**
     * 重新加载 DSH 界面。
     *
     * 单纯 `reload()` 不够：电脑上重启过 dsh web 之后旧 cookie 已经作废，页面只会拿到 401。
     * 所以先走一遍原生握手换新凭据，再加载。
     */
    private fun reloadDsh() {
        val entry = prefs.entryUrl()
        if (entry.isEmpty()) return

        ui = ui.copy(screen = Screen.Dsh, dshReady = false, dshError = null)
        startLoadTimeout()

        Thread({
            val result = Handshake.run(entry, false)
            handler.post {
                if (result.ok) {
                    val cm = CookieManager.getInstance()
                    result.cookies.forEach { cm.setCookie(entry, it) }
                    cm.flush()
                    webView.loadUrl(entry)
                } else {
                    // 握手失败就退回普通 reload，至少让页面自己把错误显示出来
                    webView.reload()
                }
            }
        }, "dsh-rehandshake").start()
    }

    private fun clearSession() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        webView.stopLoading()
        webView.clearCache(true)
        webView.clearHistory()
        loaded = false
        ui = ui.copy(
            screen = Screen.Setup,
            settingsOpen = false,
            connecting = false,
            setupError = null,
            dshReady = false,
            dshError = null,
        )
    }

    // ------------------------------------------------------------------
    // 会话完成通知
    // ------------------------------------------------------------------

    /** 通知/启动意图里带的会话 id。 */
    private fun consumeOpenSession(intent: Intent?) {
        val id = intent?.getStringExtra(NotificationCenter.EXTRA_SESSION_ID)
        if (id.isNullOrEmpty()) return
        intent.removeExtra(NotificationCenter.EXTRA_SESSION_ID)
        openSessionFromNotice(id)
    }

    /** 跳到指定会话。 */
    private fun openSessionFromNotice(sessionId: String) {
        ui = ui.copy(panelOpen = false, screen = Screen.Dsh, dshReady = false, dshError = null)
        if (loaded) {
            // 页面已就绪：直接写 localStorage 再 reload
            webView.evaluateJavascript(setCurrentSessionJs(sessionId), null)
            prefs.setLastSessionId(sessionId)   // 供「继续最近」用
        } else {
            // 冷启动还没载入 —— localStorage 要等 origin 就绪才写得进去
            pendingSessionId = sessionId
        }
    }

    /**
     * DSH 前端**没有 URL 路由**（实测无 pushState / URLSearchParams），
     * 但它把当前会话持久化在 `localStorage['dsh.sessions.current']`。
     * 写这个键再 reload 是唯一可靠的深链方式。
     */
    private fun setCurrentSessionJs(sessionId: String): String {
        val q = org.json.JSONObject.quote(sessionId)
        return "(function(){try{localStorage.setItem('dsh.sessions.current'," +
            "JSON.stringify({sessionId:$q}));location.reload();}catch(e){}})();"
    }

    private fun startWatching() {
        val url = prefs.entryUrl()
        if (url.isEmpty()) return
        SessionWatcher.start(this, url)
        WatchService.start(this)
    }

    private fun setNotify(enabled: Boolean) {
        prefs.setNotifyEnabled(enabled)
        // 一起算上 notifyProblem：开关刚打开时最需要知道「到底能不能响」
        ui = ui.copy(notifyEnabled = enabled, notifyProblem = notifyProblem())
        if (enabled) {
            startWatching()
        } else {
            SessionWatcher.stop()
            WatchService.stop(this)
        }
    }

    /**
     * 通知现在能不能真的响。
     *
     * 三种失败各有各的修法，所以分开说：
     *   · 没授权        → 引导去系统设置给权限（Android 13+）
     *   · 渠道被静音    → 去系统通知设置里把「会话完成」调回默认或更高
     *   · 都没问题      → null
     *
     * 之所以要显示出来：通知不开是**静默失败**的 —— 用户只会觉得「怎么不响」，
     * 而代码里没有任何地方会告诉他为什么。
     */
    private fun notifyProblem(): String? {
        if (!prefs.notifyEnabled()) return null
        if (!NotificationCenter.granted(this)) {
            return "系统还没给通知权限 —— 点「系统设置」打开；开了之后回到这里点「发条测试通知」验证。"
        }
        // 具体缺哪一项（横幅/铃声/震动）由它说 —— 只说"静音"用户不知道开哪个开关
        return NotificationCenter.channelProblem(this)
    }

    /** 发一条测试通知，让用户当场确认能响。 */
    private fun onTestNotify() {
        val ok = NotificationCenter.postTest(this)
        ui = ui.copy(
            notifyProblem = if (ok) null else "没能发出通知 —— 先看系统有没有给通知权限。",
        )
    }

    /**
     * 语音输入。
     *
     * ## 为什么不用 ACTION_RECOGNIZE_SPEECH
     *
     * 那条路是"让系统弹一个识别界面"，但它要求设备上**有 Activity 处理这个 Intent**。
     * 实测 ColorOS 上没有 —— 语音能力被做进了输入法，不暴露系统入口，
     * 于是 `launch` 直接抛 ActivityNotFoundException，用户看到的是
     * "这台设备没有语音识别服务"，而手机明明有。
     *
     * 改成 [SpeechRecognizer]：它直接找系统里的 `RecognitionService`，
     * 不依赖有没有那个界面。代价是要 `RECORD_AUDIO` 权限（原来不需要），
     * 值得 —— 能用比少一个权限重要。
     *
     * 实在连识别服务都没有时，才回退到系统界面那条路。
     */
    private fun onVoice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startListening()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // 兜底：连识别服务都没有，再试系统界面（有些设备只提供后者）
            toast("这台设备没有语音识别服务，可以先用键盘上的麦克风")
            return
        }
        runCatching {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onPartialResults(partialResults: Bundle?) {
                        // 边说边写：用户能看见自己正在说什么，说错了可以立刻停
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { writeToComposer(it) }
                    }

                    override fun onResults(results: Bundle?) {
                        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { writeToComposer(it) }
                            ?: toast("没听清，再试一次")
                    }

                    override fun onError(error: Int) {
                        // 不把错误码直接丢给用户 —— 翻译成人话
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            -> "没听清，再试一次"
                            SpeechRecognizer.ERROR_AUDIO -> "麦克风被占用或不可用"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                            -> "识别服务需要联网，检查一下网络"
                            else -> "语音识别失败（$error）"
                        }
                        toast(msg)
                    }

                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }
            recognizer?.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                },
            )
            toast("请说话…")
        }.onFailure { toast("打不开语音识别：${it.message}") }
    }

    /** 把识别到的文字写进 DSH 的输入框。 */
    private fun writeToComposer(text: String) {
        if (text.isBlank()) return
        webView.evaluateJavascript(ComposerWriter.writeJs(text)) { result ->
            // JS 回的是带引号的字符串字面量
            if (result != null && !result.contains("ok")) {
                Log.w(TAG, "写入输入框失败：$result")
                toast("没找到 DSH 的输入框，先把页面刷新一下")
            }
        }
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    /**
     * 从已知地址里挑一个能用的。
     *
     * 规则刻意保守：**当前地址能用就什么都不做。** 只在它连不上时才切到最快的
     * 可用地址 —— 用户可能正在输入，无端换地址（进而整页重载）比慢一点烦人得多。
     *
     * 全都探测失败时也什么都不做：那说明电脑那边没起来，改地址没用，
     * 改错了反而更找不回来。
     */
    private fun autoPickAddress() {
        val known = prefs.knownUrls()
        if (known.size < 2) return
        lifecycleScope.launch {
            val reachable = AddressPicker.probeAll(known)
            if (reachable.isEmpty()) return@launch
            val current = prefs.entryUrl()
            if (reachable.any { it.url == current }) return@launch   // 现在这个能用，不动
            val best = reachable.first()
            Log.i(TAG, "自动切换地址：$current → ${best.url}（${best.ms}ms，共 ${known.size} 个候选）")
            connect(best.url)
        }
    }

    /** 网络变化时重新探一遍 —— WiFi ↔ 蜂窝切换后原来的地址往往就不通了。 */
    private fun watchNetwork() {
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    // 系统刚切换网络，底层的地址很可能已经变了
                    handler.postDelayed({ autoPickAddress() }, 1500)
                }
            })
        }
    }

    /**
     * 检查有没有新版本。
     *
     * 走后台线程、短超时，**失败一律静默** —— 检查更新不是主功能，
     * 国内直连 GitHub 时常不通，不该因此让界面出现报错或卡顿。
     */
    private fun checkUpdate(force: Boolean = false) {
        if (force) ui = ui.copy(updateChecking = true)
        Thread({
            val r = UpdateCheck.check(this, BuildConfig.VERSION_NAME, force)
            handler.post {
                ui = ui.copy(
                    updateLatest = r.latest,
                    updateNewer = r.newer,
                    updateApkUrl = r.apkUrl,
                    updateChecking = false,
                )
            }
        }, "dsh-update-check").start()
    }

    /**
     * 应用内下载并安装新版本。
     *
     * 为什么不再跳浏览器：跳过去之后用户要在浏览器的下载列表里找那个 apk、
     * 点开、确认 —— 中间隔着一个我们看不见的界面，出问题也没法提示。
     * 放在应用内能显示进度、失败能说清原因、下完直接进安装器。
     *
     * 装完**不可能**由我们自动打开新版本：系统会杀掉旧进程。真正的"重启"
     * 是安装器上的「打开」按钮；另外 [UpdateInstalledReceiver] 会在新版本
     * 启动时补一条通知。
     */
    private fun startUpdateDownload(url: String) {
        if (ui.updatePhase == "downloading") return
        val target = url.ifEmpty { UpdateCheck.APK_URL }
        ui = ui.copy(updatePhase = "downloading", updateBytes = 0, updateTotal = 0, updateError = null)

        lifecycleScope.launch {
            fun report(p: ApkDownloader.Progress) {
                handler.post { ui = ui.copy(updateBytes = p.bytes, updateTotal = p.total) }
            }

            // 先镜像，失败再试官方。
            //
            // 国内网络下这两个源经常"一个通一个不通"，而用户不该为此做选择 ——
            // 他点的是"下载并安装"，不是"选一个 CDN"。
            var firstError: String? = null
            runCatching { ApkDownloader.download(this@MainActivity, target, ::report) }
                .onFailure { firstError = it.message ?: "下载失败" }

            if (ui.updatePhase == "downloading" && !ApkDownloader.existing(this@MainActivity).let { it != null && it.length() > 0 }) {
                // 镜像没成，试官方
                runCatching {
                    ApkDownloader.download(this@MainActivity, UpdateCheck.APK_URL, ::report)
                }.onFailure {
                    ui = ui.copy(
                        updatePhase = "failed",
                        // 两个源都失败时把两个原因都给出来 —— 只说一个会让人以为换个源就好了
                        updateError = listOfNotNull(firstError, it.message).joinToString("；"),
                    )
                    return@launch
                }
            }

            val apk = ApkDownloader.existing(this@MainActivity)
            if (apk == null) {
                ui = ui.copy(updatePhase = "failed", updateError = firstError ?: "下载失败")
                return@launch
            }
            ui = ui.copy(updatePhase = "ready")
            launchInstaller(apk)
        }
    }

    /**
     * 唤起系统安装器。
     *
     * 没被允许"安装未知应用"时先跳去授权页 —— 不然用户点了安装什么都不会发生，
     * 而系统连个提示都不给，那是这个流程里最容易卡住的一步。
     */
    private fun launchInstaller(apk: java.io.File) {
        if (!UpdateInstaller.canInstall(this)) {
            toast("先允许「安装未知应用」，回来再点一次安装")
            runCatching { startActivity(UpdateInstaller.unknownSourcesSettings(this)) }
                .onFailure { runCatching { startActivity(UpdateInstaller.appDetailsSettings(this)) } }
            return
        }
        if (!UpdateInstaller.install(this, apk)) {
            ui = ui.copy(updatePhase = "failed", updateError = "打不开系统安装器")
        }
    }

    /**
     * 打开 APK 下载页（交给浏览器，App 自己不下载、不装）。
     *
     * 默认用镜像 —— GitHub 国内经常打不开。用户真要官方包时，设置里另有入口。
     */
    private fun openDownload(url: String) {
        // 空串 = 走官方地址（镜像可能因为索引延迟还没有新版本）
        val target = url.ifEmpty { UpdateCheck.APK_URL }
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * 问宿主要不要解锁；要就弹界面，不要就直接加载。
     */
    private fun gateThenLoad(entry: String) {
        lifecycleScope.launch {
            val required = UnlockClient.required(entry)
            if (!required) {
                loadDsh(entry)
                return@launch
            }
            ui = ui.copy(
                screen = Screen.Locked,
                lockStage = "prompt",
                lockError = null,
                lockDeviceMethod = AppLock.describe(this@MainActivity),
                lockHasStored = AppLock.hasPassword(this@MainActivity),
            )
            tryUnlock()
        }
    }

    /** 有存过的密码就先过生物识别；没有就问用户。 */
    private fun tryUnlock() {
        val stored = AppLock.loadPassword(this)
        if (stored == null) {
            ui = ui.copy(lockStage = "password", lockError = null)
            return
        }
        when (AppLock.method(this)) {
            // 设备既没有生物识别也没有设备密码 —— 本地无从校验，只能用存下的密码解锁。
            // 安全边界仍在宿主那边（密码不对照样进不去），这里只是少一道本地门。
            AppLock.Method.None -> submitPassword(stored)
            else -> promptBiometric { submitPassword(stored) }
        }
    }

    /** 拉起系统生物识别 / 设备密码。 */
    private fun promptBiometric(onSuccess: () -> Unit) {
        val executor = androidx.core.content.ContextCompat.getMainExecutor(this)
        val callback = object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: androidx.biometric.BiometricPrompt.AuthenticationResult,
            ) {
                onSuccess()
            }

            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                // 用户主动取消不算错误，别在界面上留红字
                val cancelled = code == androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED ||
                    code == androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    code == androidx.biometric.BiometricPrompt.ERROR_CANCELED
                ui = ui.copy(
                    lockStage = "prompt",
                    lockError = if (cancelled) null else msg.toString(),
                )
            }
        }

        runCatching {
            val prompt = androidx.biometric.BiometricPrompt(this, executor, callback)
            val builder = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle(AppLock.promptTitle(this))
                .setSubtitle(AppLock.promptSubtitle())
                .setAllowedAuthenticators(AppLock.authenticators(this))

            // 只允许设备密码时**不能**设 negativeButtonText ——
            // 两者同时存在会抛 IllegalArgumentException（BiometricPrompt 的硬性约束）。
            // 用 authenticators() 判断而不是 method()：只支持弱生物识别时，
            // 传 STRONG 进去会立刻报错，用户点了什么也不发生。
            if (AppLock.authenticators(this) !=
                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            ) {
                builder.setNegativeButtonText("取消")
            }
            prompt.authenticate(builder.build())
        }.onFailure {
            // 没有指纹硬件、被策略禁用等等 —— 退到手输密码，而不是卡住
            ui = ui.copy(lockStage = "password", lockError = "这台设备用不了生物识别：${it.message}")
        }
    }

    /** 把密码交给宿主，拿到解锁 Cookie 后转存进 WebView 的 CookieManager。 */
    private fun submitPassword(password: String) {
        ui = ui.copy(lockStage = "busy", lockError = null)
        lifecycleScope.launch {
            when (val r = UnlockClient.unlock(prefs.entryUrl(), password)) {
                is UnlockClient.Unlock.Ok -> {
                    val cm = CookieManager.getInstance()
                    cm.setCookie(prefs.entryUrl(), r.cookie)
                    cm.flush()
                    AppLock.savePassword(this@MainActivity, password)
                    ui = ui.copy(screen = Screen.Dsh, lockError = null)
                    loadDsh(prefs.entryUrl())
                }
                UnlockClient.Unlock.NotRequired -> {
                    ui = ui.copy(screen = Screen.Dsh, lockError = null)
                    loadDsh(prefs.entryUrl())
                }
                UnlockClient.Unlock.BadPassword -> {
                    // 本机存的那份已经不对了（宿主改过密码）—— 清掉再问
                    AppLock.clear(this@MainActivity)
                    ui = ui.copy(lockStage = "password", lockError = "密码不对，再试一次")
                }
                is UnlockClient.Unlock.Failed ->
                    ui = ui.copy(lockStage = "password", lockError = r.message)
            }
        }
    }

    /** 真正把页面载进来（原来直接写在 onHandshake 里的那段）。 */
    private fun loadDsh(entry: String) {
        ui = ui.copy(screen = Screen.Dsh, dshReady = false, dshError = null)
        startLoadTimeout()
        webView.loadUrl(entry)
    }

    /**
     * 请系统把小组件钉到桌面。
     *
     * 小组件在桌面上是"用户自己去找"的东西 —— 系统把入口藏在长按桌面 →
     * 小组件 → 找到本应用 里面，很多人根本不知道。给一个直达入口是常规做法。
     *
     * API 26+ 才有 `requestPinAppWidget`；更早的版本只能提示用户手动加。
     */
    private fun pinWidget() {
        val mgr = android.appwidget.AppWidgetManager.getInstance(this)
        if (!mgr.isRequestPinAppWidgetSupported) {
            toast("这台设备不支持自动添加 —— 长按桌面 → 小组件 → 找到「DSH Go」")
            return
        }
        val ok = mgr.requestPinAppWidget(
            android.content.ComponentName(this, com.dshgo.app.notify.DshWidgetProvider::class.java),
            null,
            null,
        )
        if (!ok) toast("请长按桌面 → 小组件 → 找到「DSH Go」")
    }

    /**
     * 打开系统通知设置。
     *
     * 优先**直达渠道页**（`EXTRA_CHANNEL_ID`）—— 用户进去就看到「会话完成」
     * 那一条，横幅/响铃/震动三个开关就在眼前。国产 ROM 上这条路径未必实现，
     * 所以失败时回退到应用级通知设置。
     */
    private fun onNotifySettings() {
        runCatching { startActivity(NotificationCenter.channelSettingsIntent(this)) }
            .onFailure {
                runCatching { startActivity(NotificationCenter.settingsIntent(this)) }
            }
    }

    /** 开启通知：Android 13+ 先要运行时授权，其它版本直接开。 */
    private fun requestNotify() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationCenter.granted(this)
        ) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            setNotify(true)
        }
    }

    // ------------------------------------------------------------------
    // 缩放 / 布局 / 常亮
    // ------------------------------------------------------------------

    /**
     * 整体缩放 DSH 界面。
     *
     * 用 CSS `zoom` 而不是 WebSettings.setTextZoom：后者只放大字号，盒子/内边距
     * 不跟着长，结果是文字挤在一起、排版崩掉。`zoom` 把整棵子树连尺寸一起等比
     * 放大，才是真正的「整体大小」。
     *
     * `zoom` 不改变视口宽度，所以 DSH 的 matchMedia(max-width:1023px) 布局判定
     * 不受影响 —— 放大后仍是原来的手机/桌面形态，只是都变大。
     */
    private fun applyScale(scale: Float) {
        prefs.setScale(scale)
        ui = ui.copy(scale = prefs.scale())
        if (loaded) webView.evaluateJavascript(scaleJs(prefs.scale()), null)
    }

    private fun scaleJs(scale: Float): String {
        val v = String.format(java.util.Locale.US, "%.3f", scale)
        return "(function(){try{document.documentElement.style.zoom='" + v +
            "';}catch(e){}})();"
    }

    /**
     * 切换会话内布局。
     *
     * 由客户端自己实现（见 [PageInject]），**不依赖 dsh-pocket 的私有 localStorage 键** ——
     * 这样换成任何转发层（dsh-lan / 自建代理）都一样能用。
     *
     * 布局是在页面启动时按媒体查询定的，所以改完必须整页重载才生效。
     */
    private fun setLayout(mode: String) {
        prefs.setLayout(mode)
        ui = ui.copy(layout = mode)
        if (loaded) webView.reload()
    }

    /** 取主文档、注入、回给 WebView。任何一步不对就返回 null 交回原路。 */
    private fun rewriteMainDocument(url: Uri): WebResourceResponse? {
        val request = Request.Builder()
            .url(url.toString())
            .header("Accept", "text/html,application/xhtml+xml")
            .build()

        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val contentType = resp.header("Content-Type") ?: return null
            if (!contentType.contains("text/html", ignoreCase = true)) return null

            val html = resp.body?.string() ?: return null
            // 上游代理补过就不重复补，避免页面里出现两份 polyfill
            val script = PageInject.script(
                layout = prefs.layout(),
                needPolyfill = !PageInject.proxyAlreadyPolyfilled(html),
            )
            val out = PageInject.inject(html, script)

            // 响应体已经解压并改写，这三个头必须去掉，否则协议冲突
            val headers = LinkedHashMap<String, String>()
            for (name in resp.headers.names()) {
                when (name.lowercase()) {
                    "content-encoding", "content-length", "transfer-encoding" -> Unit
                    else -> headers[name] = resp.headers[name].orEmpty()
                }
            }

            return WebResourceResponse(
                "text/html",
                "utf-8",
                200,
                "OK",
                headers,
                ByteArrayInputStream(out.toByteArray(Charsets.UTF_8)),
            )
        }
    }

    private fun toggleAwake() {
        prefs.setKeepAwake(!prefs.keepAwake())
        applyKeepAwake()
        ui = ui.copy(awake = prefs.keepAwake())
    }

    private fun applyKeepAwake() {
        if (prefs.keepAwake()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ------------------------------------------------------------------
    // 返回
    // ------------------------------------------------------------------

    private fun handleBack() {
        when {
            ui.panelOpen -> ui = ui.copy(panelOpen = false)
            ui.settingsOpen -> ui = ui.copy(settingsOpen = false)
            // DSH 没有 URL 路由，canGoBack 基本都是 false → 直接退出
            ui.screen == Screen.Dsh && webView.canGoBack() -> webView.goBack()
            // 从设置里「更改地址」进来的，给一条退路
            ui.screen == Screen.Setup && loaded -> ui = ui.copy(screen = Screen.Dsh)
            else -> finish()
        }
    }

    private fun startLoadTimeout() {
        clearLoadTimeout()
        val runnable = Runnable {
            if (ui.screen == Screen.Dsh && !ui.dshReady && ui.dshError == null) {
                ui = ui.copy(dshError = "界面加载超时。检查电脑上的 dsh web 是否还在运行。")
            }
        }
        loadTimeout = runnable
        handler.postDelayed(runnable, 20_000)
    }

    private fun clearLoadTimeout() {
        loadTimeout?.let { handler.removeCallbacks(it) }
        loadTimeout = null
    }

    private fun maybeHandleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND &&
            intent?.action != Intent.ACTION_SEND_MULTIPLE
        ) {
            return
        }
        when (ShareInbox.accept(this, intent)) {
            ShareInbox.Kind.Image ->
                // 图片先存着。等用户进 DSH 点「附件」，onShowFileChooser 会把它交出去 ——
                // 比让他"先存到文件、再在 DSH 里翻出来"少好几步。
                toast("图片已就绪 —— 回到 DSH 点「附件」就能发出去")

            ShareInbox.Kind.Text -> {
                val body = ShareInbox.consumePendingText(this) ?: return
                // 只有在**看起来就是 DSH 入口地址**时才当成地址连过去。
                // 判定刻意严：必须带显式端口、且后面不带路径。
                // 否则分享一个普通链接（GitHub 页面之类）会被误当成地址，
                // 把用户从当前会话里踢出去 —— 那正是分享内容进来时最不希望发生的事。
                if (looksLikeEntryAddress(body)) connect(body.trim())
                else writeToComposer(body)
            }

            null -> Unit
        }
    }

    /** 点一下页面上的「新会话」。 */
    private fun clickNewSession() {
        webView.evaluateJavascript(
            """(function(){
              try {
                var btns = Array.prototype.slice.call(document.querySelectorAll('button, [role="button"]'));
                for (var i = 0; i < btns.length; i++) {
                  var t = (btns[i].innerText || '').trim();
                  if (t === '新会话' || t === 'New session' || t === 'New Session') {
                    btns[i].click();
                    return 'ok';
                  }
                }
                return 'not-found';
              } catch (e) { return 'error'; }
            })();""",
            null,
        )
    }

    /** 直接跳到最近用过的那个会话。走的是 notification 那套 localStorage 机制。 */
    private fun openMostRecentSession() {
        val last = prefs.lastSessionId()
        if (last.isNullOrEmpty()) {
            toast("还没有用过的会话")
            return
        }
        pendingSessionId = last
        if (loaded) {
            pendingSessionId = null
            webView.evaluateJavascript(setCurrentSessionJs(last), null)
            prefs.setLastSessionId(last)
        }
    }

    /**
     * 这个字符串看起来是不是一个 DSH 入口地址？
     *
     * 要求**同时**满足：有 scheme、有显式端口、路径为空或只有 `/`。
     * 端口是关键 —— DSH 的转发层永远带端口，而普通网页链接通常没有。
     */
    private fun looksLikeEntryAddress(s: String): Boolean {
        val t = s.trim().replace("\n", "").replace(" ", "")
        if (!t.startsWith("http://") && !t.startsWith("https://")) return false
        return Regex("^https?://[^/]+:\\d{2,5}/?$").matches(t)
    }
}

// ---------------------------------------------------------------------------
// 状态
// ---------------------------------------------------------------------------

data class ShellState(
    val screen: Screen = Screen.Connecting,
    val entryUrl: String = "",
    /** 握手是否在飞 —— 连接页的按钮靠它转圈。 */
    val connecting: Boolean = false,
    val setupError: String? = null,
    val dshReady: Boolean = false,
    val dshError: String? = null,
    /**
     * 主文档加载失败的原因（null = 没问题）。
     *
     * 为什么不能只用 dshReady：Chrome 对**错误页也会调 onPageFinished**，
     * 于是「错误 → dshReady=false → onPageFinished → dshReady=true」，
     * 覆盖层刚出现就被撤掉，用户看到的是 WebView 的原生错误页。
     */
    val pageError: String? = null,
    val layout: String = Prefs.LAYOUT_AUTO,
    val scale: Float = Prefs.SCALE_DEFAULT,
    val awake: Boolean = false,
    val settingsOpen: Boolean = false,
    val notifyEnabled: Boolean = false,
    /** 通知有问题时的说明；null = 一切正常。设置面板会把它显示出来。 */
    val notifyProblem: String? = null,
    val panelOpen: Boolean = false,
    val crashReport: String? = null,
    /** 检查更新的结果：远端最新版本号（null = 还不知道）。 */
    val updateLatest: String? = null,
    /** 远端是否比本机新。 */
    val updateNewer: Boolean = false,
    /** 正在手动检查。 */
    val updateChecking: Boolean = false,
    /** 本次结果建议的下载地址（镜像优先，官方兜底）。 */
    val updateApkUrl: String = UpdateCheck.APK_URL,

    /** 闸门界面：prompt（去过生物识别） / password（要手输） / busy。 */
    val lockStage: String = "prompt",
    val lockError: String? = null,
    /** 这台设备支持什么解锁方式（AppLock.describe 的结果）。 */
    val lockDeviceMethod: String = "",
    /** 本机有没有存过密码 —— 决定下次能否直接用人脸/指纹。 */
    val lockHasStored: Boolean = false,

    /** 应用内下载的状态：idle / downloading / ready / failed。 */
    val updatePhase: String = "idle",
    val updateBytes: Long = 0L,
    val updateTotal: Long = 0L,
    val updateError: String? = null,
)

// ---------------------------------------------------------------------------
// 组装
// ---------------------------------------------------------------------------

@Composable
private fun Shell(
    state: ShellState,
    webView: WebView,
    onConnect: (String?) -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onChangeUrl: () -> Unit,
    onCancelSetup: () -> Unit,
    onLayout: (String) -> Unit,
    onScale: (Float) -> Unit,
    onToggleAwake: () -> Unit,
    onClearSession: () -> Unit,
    onToggleNotify: () -> Unit,
    onTestNotify: () -> Unit,
    onNotifySettings: () -> Unit,
    onScan: () -> Unit,
    onVoice: () -> Unit,
    /** 交访问密码（闸门解锁）。 */
    onUnlock: (String) -> Unit,
    /** 重新拉起生物识别。 */
    onRetryUnlock: () -> Unit,
    updateLatest: String?,
    updateNewer: Boolean,
    updateChecking: Boolean,
    updateApkUrl: String,
    onCheckUpdate: () -> Unit,
    onPinWidget: () -> Unit,
    /** 应用内下载/安装的状态。 */
    updatePhase: String,
    updateBytes: Long,
    updateTotal: Long,
    updateError: String?,
    onInstall: () -> Unit,
    onDownload: (String) -> Unit,
    onOpenPanel: () -> Unit,
    onClosePanel: () -> Unit,
    onClearNotices: () -> Unit,
    onOpenNotice: (String) -> Unit,
    onDismissCrash: () -> Unit,
) {
    BackHandler { onBack() }

    val streamLiveState by SessionWatcher.live.collectAsStateWithLifecycle()
    val streamErrorState by SessionWatcher.lastError.collectAsStateWithLifecycle()
    val lastEventAtState by SessionWatcher.lastEventAt.collectAsStateWithLifecycle()
    val streamModeState by SessionWatcher.mode.collectAsStateWithLifecycle()
    val notices by SessionWatcher.notices.collectAsStateWithLifecycle()
    val unseen by SessionWatcher.unseen.collectAsStateWithLifecycle()
    val live by SessionWatcher.live.collectAsStateWithLifecycle()

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val stripTotal = StripHeight + statusBarTop
    val showStrip = state.screen == Screen.Dsh

    Box(Modifier.fillMaxSize()) {
        // DSH 界面（常驻）。顶部让出原生状态条的高度。
        AndroidView(
            factory = { webView },
            modifier = Modifier
                .fillMaxSize()
                .then(if (showStrip) Modifier.padding(top = stripTotal) else Modifier)
                // 键盘弹起时把 WebView 整体压上去。DSH 的输入框是 position:fixed;bottom:0，
                // WebView 不缩它就被压在键盘底下 —— 看不到自己正在打什么。
                // 用 union（取两侧较大值）而不是两个 padding 相加：键盘弹起时系统同时报
                // 导航栏和输入法两条 inset，简单相加会多顶出一条导航栏的高度。
                .windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.ime),
                ),
        )

        when (state.screen) {
            Screen.Connecting -> ConnectingScreen(host = hostOf(state.entryUrl))

            Screen.Setup -> SetupScreen(
                initialUrl = state.entryUrl.ifEmpty { Prefs.DEFAULT_URL },
                error = state.setupError,
                connecting = state.connecting,
                onConnect = { onConnect(it) },
                onScan = onScan,
                onCancel = if (state.dshReady) onCancelSetup else null,
            )

            Screen.Locked -> LockScreen(
                host = hostOf(state.entryUrl),
                stage = state.lockStage,
                error = state.lockError,
                deviceMethod = state.lockDeviceMethod,
                hasStoredPassword = state.lockHasStored,
                onUnlock = onUnlock,
                onRetryBiometric = onRetryUnlock,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.padding(top = stripTotal),
            )

            Screen.Dsh -> if (state.pageError != null) {
                // 加载失败：给一个说人话的界面，而不是 WebView 的原生错误页
                PageErrorOverlay(
                    host = hostOf(state.entryUrl),
                    url = state.entryUrl,
                    detail = state.pageError,
                    onRetry = onReload,
                    onEditUrl = onChangeUrl,
                    onSettings = onOpenSettings,
                    modifier = Modifier.padding(top = stripTotal),
                )
            } else if (!state.dshReady) {
                DshLoadingOverlay(
                    host = hostOf(state.entryUrl),
                    error = state.dshError,
                    onReload = onReload,
                    onSettings = onOpenSettings,
                    modifier = Modifier.padding(top = stripTotal),
                )
            }
        }

        if (showStrip) {
            DshStatusStrip(
                host = hostOf(state.entryUrl),
                live = live,
                unseen = unseen,
                onNotice = onOpenPanel,
                onReload = onReload,
                onSettings = onOpenSettings,
                onVoice = onVoice,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            // 通知悬浮窗：锚在右上角，贴着状态条下沿
            if (state.panelOpen) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                Popup(
                    alignment = Alignment.TopEnd,
                    offset = with(density) {
                        IntOffset(x = (-10).dp.roundToPx(), y = stripTotal.roundToPx() + 6.dp.roundToPx())
                    },
                    onDismissRequest = onClosePanel,
                ) {
                    NoticePanel(
                        notices = notices,
                        notifyEnabled = state.notifyEnabled,
                        onEnableNotify = onToggleNotify,
                        onOpen = { onOpenNotice(it.sessionId) },
                        onClear = onClearNotices,
                    )
                }
            }
        }

        if (state.settingsOpen) {
            SettingsSheet(
                url = state.entryUrl,
                layout = state.layout,
                awake = state.awake,
                notifyEnabled = state.notifyEnabled,
                onToggleNotify = onToggleNotify,
                notifyProblem = state.notifyProblem,
                onTestNotify = onTestNotify,
                onNotifySettings = onNotifySettings,
                streamLive = streamLiveState,
                streamMode = streamModeState.name.lowercase(),
                streamError = streamErrorState,
                lastEventAt = lastEventAtState,
                scale = state.scale,
                onScale = onScale,
                onLayout = onLayout,
                onToggleAwake = onToggleAwake,
                onClearSession = onClearSession,
                onChangeUrl = onChangeUrl,
                updateLatest = updateLatest,
                updateNewer = updateNewer,
                updateChecking = updateChecking,
                updateApkUrl = updateApkUrl,
                onCheckUpdate = onCheckUpdate,
                onPinWidget = onPinWidget,
                updatePhase = updatePhase,
                updateBytes = updateBytes,
                updateTotal = updateTotal,
                updateError = updateError,
                onInstall = onInstall,
                onDownload = onDownload,
                currentVersion = BuildConfig.VERSION_NAME,
                onDismiss = onCloseSettings,
            )
        }

        state.crashReport?.let { report ->
            CrashDialog(report = report, onDismiss = onDismissCrash)
        }
    }
}

private fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
