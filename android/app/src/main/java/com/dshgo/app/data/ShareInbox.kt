package com.dshgo.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * 从别的应用分享过来的东西，暂存在这里等 DSH 取。
 *
 * ## 两种东西走两条完全不同的路
 *
 * **文字**（链接、选中片段）直接写进输入框 —— 这是用户分享时的心智模型：
 * "把这段丢进去"。
 *
 * **图片**不能直接写进输入框（DSH 的附件走的是页面自己的 file input），
 * 所以先落到应用私有目录，等用户在 DSH 里点「附件」时**直接把它交出去**
 * —— 见 [consumePendingImage]，由 `onShowFileChooser` 调用。
 *
 * 这样用户的操作是：相册里分享 → 回到 DSH Go → 点附件 → 图就在那儿了。
 * 比让他"先保存到文件、再在 DSH 里翻文件"少好几步。
 */
object ShareInbox {

    /** 暂存图片的文件名。用固定名字：一次只可能有一个待处理分享。 */
    private const val PENDING_NAME = "pending_share.img"

    /** 这份分享对应的显示名，交给 file input 当文件名。 */
    private const val PENDING_LABEL = "shared_filename"

    private fun pendingFile(ctx: Context) = File(ctx.filesDir, PENDING_NAME)

    /**
     * 收下一个分享 Intent。返回 true 表示收下了。
     *
     * 调用方（MainActivity）负责在文字的情况下把它写进输入框。
     */
    fun accept(ctx: Context, intent: Intent): Kind? {
        val type = intent.type.orEmpty()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val uri: Uri? = @Suppress("DEPRECATION")
        (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)

        return when {
            uri != null && type.startsWith("image") -> {
                runCatching {
                    // 复制进私有目录：分享方给的是临时 URI 授权，出了这次回调就可能读不到
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        pendingFile(ctx).outputStream().use { input.copyTo(it) }
                    } ?: return null
                    val name = queryName(ctx, uri) ?: "分享的图片.png"
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(PENDING_LABEL, name).apply()
                }.getOrNull()
                Kind.Image
            }

            !text.isNullOrBlank() -> {
                // 有些应用把链接放在 EXTRA_TEXT 里，也带一份标题
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                val body = if (subject.isNullOrBlank()) text else "$subject\n$text"
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(PENDING_TEXT, body).apply()
                Kind.Text
            }

            else -> null
        }
    }

    /** 取出暂存的文字（取出即清）。 */
    fun consumePendingText(ctx: Context): String? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = sp.getString(PENDING_TEXT, null)
        if (v != null) sp.edit().remove(PENDING_TEXT).apply()
        return v
    }

    /**
     * 有没有待处理的分享图片。给 `onShowFileChooser` 用 ——
     * 用户点附件时，如果正好有一张分享进来的图，就直接把它交出去。
     */
    fun consumePendingImage(ctx: Context): Pair<File, String>? {
        val f = pendingFile(ctx)
        if (!f.exists() || f.length() == 0L) return null
        val name = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PENDING_LABEL, "分享的图片.png") ?: "分享的图片.png"
        return f to name
    }

    /** 处理完之后清掉，避免下次点附件又冒出同一张老图。 */
    fun clearPendingImage(ctx: Context) {
        runCatching { pendingFile(ctx).delete() }
    }

    enum class Kind { Text, Image }

    private const val PREFS = "dshgo_share"
    private const val PENDING_TEXT = "pending_text"

    private fun queryName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()
}
