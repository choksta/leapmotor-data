package com.haojay.leapmotor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CornerBasedShape

/**
 * M3 官方形状刻度：只有 4 / 8 / 12 / 16 / 28 五档（full 用 999 表示）。
 *
 * 之前用的是自造的 8/14/18/24/32，问题不在"好不好看"，而在**层级不可预期**：
 * 组件之间差 2dp 时，用户分辨不出谁更"重"，形状就不再承担层级信息。
 * 现在全部回到令牌值，语义是：extraSmall=分隔线，small=小标签，
 * medium=芯片/小按钮，large=按钮/输入框，extraLarge=卡片。
 */
private val M3Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * M3 类型刻度。字号只允许落在这些档位上。
 *
 * 之前的 13/15/18sp 属于"差一档"的中间值 —— 单看没问题，但放在一起就没有
 * 可辨认的层级。数值（SOC / 里程 / 胎压）统一用等宽字体，保证数字跳动时不抖位。
 */
private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Light,
        fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Normal, fontSize = 32.sp, lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 36.sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 16.sp,
        lineHeight = 24.sp, letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 14.sp,
        lineHeight = 20.sp, letterSpacing = 0.1.sp
    ),
    // 全部 Text 的默认样式（MaterialTheme 用 bodyLarge 作为 LocalTextStyle）。
    // 这里开 tnum（表格数字）：数值刷新时字宽不变，不会左右跳动，
    // 但字形仍是系统默认字体，不像 Monospace 那样整体换脸。
    bodyLarge = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp,
        fontFeatureSettings = "tnum"
    ),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 14.sp,
        lineHeight = 20.sp, letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 12.sp,
        lineHeight = 16.sp, letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 11.sp,
        lineHeight = 16.sp, letterSpacing = 0.5.sp
    ),
)

/**
 * Material 3 **Expressive** 主题，只负责组装：
 *
 * - 形状：官方五档刻度，层级可预期
 * - 动效：表达性弹簧曲线，短促带回弹
 * - 配色：全部在 [Palette] 里，三套可选。换配色改 [ActivePalette] 一行，布局不受影响
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(
    palette: Palette = ActivePalette,
    // 仅供预览用：不传就跟随系统深浅色
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = if (dark) palette.dark() else palette.light(),
        // 表达性动效：短促、带回弹，交互反馈更"活"
        motionScheme = MotionScheme.expressive(),
        shapes = M3Shapes,
        typography = AppTypography,
        content = content
    )
}

/**
 * 组件统一取圆角。全部转发到主题令牌，不再各写一套数字 ——
 * 这样以后调形状只改 [M3Shapes] 一处。
 */
object AppShape {
    /** 卡片：extraLarge（28dp） */
    val card: CornerBasedShape
        @Composable get() = MaterialTheme.shapes.extraLarge
    /** 块/大按钮：large（16dp） */
    val block: CornerBasedShape
        @Composable get() = MaterialTheme.shapes.large
    /** 芯片/小方块：medium（12dp） */
    val tile: CornerBasedShape
        @Composable get() = MaterialTheme.shapes.medium
    /** 胶囊：full */
    val pill: RoundedCornerShape
        @Composable get() = RoundedCornerShape(999.dp)
}
