package com.dsh.remote.ui

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
}
