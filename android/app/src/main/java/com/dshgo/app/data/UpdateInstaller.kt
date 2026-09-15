package com.dshgo.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把下好的 APK 交给系统安装器。
 *
 * ## 能做到什么、做不到什么
 *
 * 能做到：**不用跳浏览器**、不用让用户去文件管理器找 apk —— 应用内下完直接
 * 唤起安装界面，用户看到的就只剩最后那一次"安装"确认。
 *
 * 做不到：**跳过那个确认**。Android 从 8 起要求侧载必须由用户点一下，
 * 除非应用是设备所有者或系统应用。任何声称"静默安装"的做法都要求 root。
 * 所以这里的目标是把流程压到**一次点击**，而不是零次。
 */
object UpdateInstaller {

    /** 本应用有没有被允许"安装未知应用"（API 26+）。 */
    fun canInstall(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * 跳到「安装未知应用」的授权页。
     *
     * 这一步在国产 ROM 上路径可能不同 —— 用 `ACTION_MANAGE_UNKNOWN_APP_SOURCES`
     * 带包名，绝大多数 ROM 都实现了；失败就让调用方回退到应用详情页。
     */
    fun unknownSourcesSettings(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.fromParts("package", ctx.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 应用详情页 —— 上面那条不被支持时的兜底。 */
    fun appDetailsSettings(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", ctx.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 唤起安装器。返回 true 表示已经发出去了。
     *
     * 用 FileProvider 而不是 `Uri.fromFile`：Android 7 起把 `file://` 传给别的
     * 应用会抛 FileUriExposedException，必须走 content:// 并授予临时读权限。
     */
    fun install(ctx: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }.isSuccess
}
