package com.dsh.remote

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃自记录。
 *
 * 手机端的崩溃很难现场复现（型号、系统版本、厂商 ROM 差异），所以让 app 自己把
 * 栈写下来：下次启动时 [MainActivity] 会把它显示成一个可复制的对话框。
 *
 * 写在 `filesDir`（应用私有目录）—— 不需要任何存储权限，也不会被卸载残留。
 */
object CrashLog {

    private const val TAG = "DshRemote"
    private const val FILE_NAME = "last-crash.txt"
    /** 单次记录上限，防止无限递归或超大栈把文件写爆。 */
    private const val MAX_CHARS = 64 * 1024

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    /** 记录一次未捕获异常。自身绝不抛异常——崩溃处理里再崩就真没线索了。 */
    fun record(ctx: Context, thread: Thread, error: Throwable) {
        runCatching {
            val sw = StringWriter()
            PrintWriter(sw).use { pw ->
                pw.println("DSH 口袋崩溃报告")
                pw.println("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                pw.println("线程: ${thread.name}")
                pw.println("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                pw.println("系统: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                pw.println("ABI : ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
                pw.println("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                pw.println("─────────────────────────────────────────")
                error.printStackTrace(pw)
            }
            val text = sw.toString().take(MAX_CHARS)
            file(ctx).writeText(text)
            Log.e(TAG, "未捕获异常，已写入 ${file(ctx).absolutePath}\n$text")
        }
    }

    /** 读取上次崩溃记录；没有则返回 null。 */
    fun read(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()
    }

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }
}
