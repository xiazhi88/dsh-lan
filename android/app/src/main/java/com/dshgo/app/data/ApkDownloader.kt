package com.dshgo.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用内下载新版 APK。
 *
 * ## 为什么不交给浏览器
 *
 * 跳浏览器下载之后，用户要在浏览器的下载列表里找到那个 apk、点开、确认安装 ——
 * 中间隔着一个我们看不见的界面，出问题也没法提示。放在应用内则：
 * 有进度、能取消、失败能说清原因、下完直接进安装器。
 *
 * ## 为什么落到 cacheDir 而不是 filesDir
 *
 * APK 有几个 MB，属于「用完就不需要」的东西。放 cache 目录，系统空间紧张时
 * 可以自己回收，我们也少一件要清理的事。
 *
 * ## 为什么下完要校验大小
 *
 * 下载中断时 OkHttp 会抛异常 ✓，但**代理返回一个截断的 200** 不会 ——
 * 那种情况下装的是个坏包，而报错要到安装器里才出现，用户根本不知道是哪一步坏的。
 * 有 Content-Length 就比一下，几行代码换掉一类很难查的问题。
 */
object ApkDownloader {

    private const val DIR = "update"
    private const val NAME = "dshgo-update.apk"

    /** 下载超时给得比别处长：几个 MB 在慢网上确实要时间。 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 下载进度。`total <= 0` 表示服务端没给 Content-Length，只能显示已下载量。 */
    data class Progress(val bytes: Long, val total: Long) {
        val percent: Int? get() = if (total > 0) ((bytes * 100) / total).toInt() else null
    }

    fun targetFile(ctx: Context): File =
        File(File(ctx.cacheDir, DIR).apply { mkdirs() }, NAME)

    /** 已经下好且非空的包（上次下完没装就退出了，可以复用）。 */
    fun existing(ctx: Context): File? =
        targetFile(ctx).takeIf { it.exists() && it.length() > 0 }

    fun clear(ctx: Context) {
        runCatching { targetFile(ctx).delete() }
    }

    /**
     * 下载。**在调用方的协程里跑**，通过 [onProgress] 回报进度。
     *
     * 完成后返回文件；失败抛异常（调用方负责翻译成人话）。
     */
    suspend fun download(
        ctx: Context,
        url: String,
        onProgress: (Progress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val out = targetFile(ctx)
        // 先删旧的：上次下到一半的文件还在的话，看起来"已经有了"，实际是坏的
        runCatching { out.delete() }

        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        resp.use {
            if (!it.isSuccessful) error("HTTP ${it.code}")
            val body = it.body ?: error("响应没有内容")
            val total = body.contentLength()

            body.byteStream().use { input ->
                out.outputStream().use { sink ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    onProgress(Progress(0, total))
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        done += n
                        onProgress(Progress(done, total))
                    }
                    sink.flush()
                }
            }

            // 服务端给了长度就核对 —— 代理截断的 200 只有这里能发现
            if (total > 0 && out.length() != total) {
                runCatching { out.delete() }
                error("下载不完整（$total 字节只拿到 ${out.length()}）")
            }
        }
        out
    }
}
