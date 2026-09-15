package com.dshgo.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 少量自绘图标。
 *
 * 这里刻意**不引入 `material-icons-extended`** —— 那个库会给 APK 增加约 10MB，
 * 而本项目只用到 4 个非核心图标。核心图标集（`material-icons-core`，随 material3 带来）
 * 已覆盖 Add / Search / Settings / Close / Check / ArrowBack / Warning / KeyboardArrowRight。
 */
object DshIcons {

    /** 停止：实心圆角方块。用于「运行中」会话的取消按钮。 */
    val Stop: ImageVector by lazy {
        ImageVector.Builder(
            name = "Stop",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(7f, 7f)
                horizontalLineTo(17f)
                verticalLineTo(17f)
                horizontalLineTo(7f)
                close()
            }
        }.build()
    }

    /**
     * 麦克风。语音输入用。
     *
     * 自己画而不是引入 `material-icons-extended` —— 那个库为了一个图标要多带约 10MB。
     */
    val Mic: ImageVector by lazy {
        ImageVector.Builder(
            name = "Mic",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // 话筒头
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 14f)
                curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
                verticalLineTo(5f)
                curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
                curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
                verticalLineTo(11f)
                curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
                close()
            }
            // 支架
            path(fill = SolidColor(Color.White)) {
                moveTo(17f, 11f)
                curveTo(17f, 13.76f, 14.76f, 16f, 12f, 16f)
                curveTo(9.24f, 16f, 7f, 13.76f, 7f, 11f)
                horizontalLineTo(5f)
                curveTo(5f, 14.53f, 7.61f, 17.43f, 11f, 17.92f)
                verticalLineTo(21f)
                horizontalLineTo(13f)
                verticalLineTo(17.92f)
                curveTo(16.39f, 17.43f, 19f, 14.53f, 19f, 11f)
                horizontalLineTo(17f)
                close()
            }
        }.build()
    }
}
