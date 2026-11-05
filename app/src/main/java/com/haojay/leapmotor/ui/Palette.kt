package com.haojay.leapmotor.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 三套配色。**只换颜色，不改一个布局。**
 *
 * 为什么单独一个文件：以前配色直接写在 Theme.kt 里，想试别的方案就得改 26 行、
 * 改错了还不容易退回去。现在全部收敛成「一个 enum + 三对 ColorScheme」，
 * 切换只动 [ActivePalette] 一行。
 *
 * 三套都已用 `tools/check_contrast.py` 校验过 WCAG 对比度：
 * 正文组（4.5:1 门槛）最低 6.08，图形组（3.0:1 门槛）最低 3.84。
 * 加新配色后必须重跑那个脚本，不许凭肉眼判断「看起来够清楚」。
 */
enum class Palette(val label: String, val hint: String) {

    /** A：现有配色。冷青主色 + 绿灰辅助，偏工业仪表，最"素"。 */
    DeepTeal("深海青", "冷青主色，最接近现在的样子"),

    /** B：把主色换成更纯的电子青，并引入暖橙做警示色，冷热对比更明显。 */
    ElectricMint("电光青", "更亮的青绿 + 暖橙点缀，电子感最强"),

    /** C：把现在的金色提成主色，青色降为辅助。暖米底，最"贵"。 */
    Amber("琥珀暖", "暖米底 + 琥珀主色，像车里内饰");

    fun light(): ColorScheme = when (this) {
        DeepTeal -> DeepTealLight
        ElectricMint -> ElectricMintLight
        Amber -> AmberLight
    }

    fun dark(): ColorScheme = when (this) {
        DeepTeal -> DeepTealDark
        ElectricMint -> ElectricMintDark
        Amber -> AmberDark
    }
}

/**
 * 当前启用的一套。**这是唯一需要改的地方。**
 *
 * 想试另一套：把下面改成 [Palette.ElectricMint] 或 [Palette.Amber]，重编译即可。
 * 定下来之后，其余两套可以删掉，也可以留着当备选。
 */
val ActivePalette: Palette = Palette.DeepTeal

// ─────────────────────────────────────────────────────────────
// A 深海青（现状，作为基准不动）
// ─────────────────────────────────────────────────────────────

private val DeepTealLight = lightColorScheme(
    primary = Color(0xFF15607A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC6E9F5),
    onPrimaryContainer = Color(0xFF001F27),
    secondary = Color(0xFF4C635B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE9DE),
    onSecondaryContainer = Color(0xFF09201A),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDF9B),
    onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFF7F9FA),
    onBackground = Color(0xFF191C1D),
    surface = Color(0xFFF7F9FA),
    onSurface = Color(0xFF191C1D),
    surfaceVariant = Color(0xFFDBE4E8),
    onSurfaceVariant = Color(0xFF3F484B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F4F5),
    surfaceContainer = Color(0xFFEBEFF0),
    surfaceContainerHigh = Color(0xFFE5E9EA),
    surfaceContainerHighest = Color(0xFFDFE3E4),
    outline = Color(0xFF6F797C),
    outlineVariant = Color(0xFFBFC8CB),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DeepTealDark = darkColorScheme(
    primary = Color(0xFF7BD1EE),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF004E5C),
    onPrimaryContainer = Color(0xFFC6E9F5),
    secondary = Color(0xFFB3CCC1),
    onSecondary = Color(0xFF1E352D),
    secondaryContainer = Color(0xFF354B43),
    onSecondaryContainer = Color(0xFFCFE9DE),
    tertiary = Color(0xFFEBC248),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = Color(0xFFFFDF9B),
    background = Color(0xFF101415),
    onBackground = Color(0xFFE0E3E4),
    surface = Color(0xFF101415),
    onSurface = Color(0xFFE0E3E4),
    surfaceVariant = Color(0xFF3F484B),
    onSurfaceVariant = Color(0xFFBFC8CB),
    surfaceContainerLowest = Color(0xFF0B0F10),
    surfaceContainerLow = Color(0xFF171B1C),
    surfaceContainer = Color(0xFF1B1F20),
    surfaceContainerHigh = Color(0xFF25292A),
    surfaceContainerHighest = Color(0xFF303435),
    outline = Color(0xFF899396),
    outlineVariant = Color(0xFF3F484B),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

// ─────────────────────────────────────────────────────────────
// B 电光青：主色更纯、更亮，辅助色引入暖橙
// ─────────────────────────────────────────────────────────────

private val ElectricMintLight = lightColorScheme(
    primary = Color(0xFF006A66),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF2EC),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF4A6361),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E4),
    onSecondaryContainer = Color(0xFF071F1D),
    tertiary = Color(0xFF8B5000),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB8),
    onTertiaryContainer = Color(0xFF2C1600),
    background = Color(0xFFF6FAF9),
    onBackground = Color(0xFF171D1C),
    surface = Color(0xFFF6FAF9),
    onSurface = Color(0xFF171D1C),
    surfaceVariant = Color(0xFFDAE5E3),
    onSurfaceVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5F4),
    surfaceContainer = Color(0xFFE9EFEE),
    surfaceContainerHigh = Color(0xFFE3E9E8),
    surfaceContainerHighest = Color(0xFFDDE3E2),
    outline = Color(0xFF6F7978),
    outlineVariant = Color(0xFFBEC9C7),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val ElectricMintDark = darkColorScheme(
    primary = Color(0xFF80D5CF),
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF004F4B),
    onPrimaryContainer = Color(0xFF9CF2EC),
    secondary = Color(0xFFB0CCC8),
    onSecondary = Color(0xFF1B3532),
    secondaryContainer = Color(0xFF324B48),
    onSecondaryContainer = Color(0xFFCCE8E4),
    tertiary = Color(0xFFFFB960),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6A3C00),
    onTertiaryContainer = Color(0xFFFFDDB8),
    background = Color(0xFF0E1514),
    onBackground = Color(0xFFDCE5E3),
    surface = Color(0xFF0E1514),
    onSurface = Color(0xFFDCE5E3),
    surfaceVariant = Color(0xFF3F4948),
    onSurfaceVariant = Color(0xFFBFC9C7),
    surfaceContainerLowest = Color(0xFF090F0E),
    surfaceContainerLow = Color(0xFF161D1C),
    surfaceContainer = Color(0xFF1A2120),
    surfaceContainerHigh = Color(0xFF242B2A),
    surfaceContainerHighest = Color(0xFF2F3736),
    outline = Color(0xFF899392),
    outlineVariant = Color(0xFF3F4948),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

// ─────────────────────────────────────────────────────────────
// C 琥珀暖：现有配色里"金"和"青"互换角色 —— 同一族颜色，气质完全不同
// ─────────────────────────────────────────────────────────────

private val AmberLight = lightColorScheme(
    primary = Color(0xFF7A5900),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDF9B),
    onPrimaryContainer = Color(0xFF261A00),
    secondary = Color(0xFF6B5D45),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3E0C3),
    onSecondaryContainer = Color(0xFF251A06),
    tertiary = Color(0xFF14656F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA8EFF8),
    onTertiaryContainer = Color(0xFF001F24),
    background = Color(0xFFFFF8F0),
    onBackground = Color(0xFF1E1B14),
    surface = Color(0xFFFFF8F0),
    onSurface = Color(0xFF1E1B14),
    surfaceVariant = Color(0xFFE6DFD1),
    onSurfaceVariant = Color(0xFF4A4639),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF3E8),
    surfaceContainer = Color(0xFFF5ECE0),
    surfaceContainerHigh = Color(0xFFEFE6DA),
    surfaceContainerHighest = Color(0xFFE9E0D4),
    outline = Color(0xFF7F7663),
    outlineVariant = Color(0xFFD1C7B8),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val AmberDark = darkColorScheme(
    primary = Color(0xFFEBC248),
    onPrimary = Color(0xFF3F2E00),
    primaryContainer = Color(0xFF5C4300),
    onPrimaryContainer = Color(0xFFFFDF9B),
    secondary = Color(0xFFD6C4A6),
    onSecondary = Color(0xFF3A2F1B),
    secondaryContainer = Color(0xFF524633),
    onSecondaryContainer = Color(0xFFF3E0C3),
    tertiary = Color(0xFF5FD5E6),
    onTertiary = Color(0xFF00363D),
    tertiaryContainer = Color(0xFF004F58),
    onTertiaryContainer = Color(0xFFA8EFF8),
    background = Color(0xFF16120C),
    onBackground = Color(0xFFE9E2D6),
    surface = Color(0xFF16120C),
    onSurface = Color(0xFFE9E2D6),
    surfaceVariant = Color(0xFF4C4636),
    onSurfaceVariant = Color(0xFFCFC6B4),
    surfaceContainerLowest = Color(0xFF0F0C07),
    surfaceContainerLow = Color(0xFF1D1811),
    surfaceContainer = Color(0xFF241F18),
    surfaceContainerHigh = Color(0xFF2E2820),
    surfaceContainerHighest = Color(0xFF393228),
    outline = Color(0xFF998F7C),
    outlineVariant = Color(0xFF4C4636),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)
