package com.haojay.leapmotor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp

/**
 * 三套配色的对照表。
 *
 * 在 Android Studio 里打开「Split」或「Design」面板即可同时看到 6 个格子
 * （3 套 × 明/暗），不用装到手机上就能比。选定后把 [ActivePalette] 改成那一套。
 */
private data class PaletteCase(val palette: Palette, val dark: Boolean) {
    val caption: String get() = "${palette.label} · ${if (dark) "深色" else "浅色"}"
}

private class PaletteCaseProvider : PreviewParameterProvider<PaletteCase> {
    override val values: Sequence<PaletteCase> = sequence {
        Palette.entries.forEach { p ->
            yield(PaletteCase(p, false))
            yield(PaletteCase(p, true))
        }
    }
}

@Preview(name = "配色对照", widthDp = 320, heightDp = 500)
@Composable
private fun PaletteComparePreview(
    @PreviewParameter(PaletteCaseProvider::class) case: PaletteCase,
) {
    AppTheme(palette = case.palette, dark = case.dark) {
        PaletteSample(case)
    }
}

@Composable
private fun PaletteSample(case: PaletteCase) {
    val cs = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(cs.primary)
                )
                Spacer(Modifier.size(10.dp))
                Text(case.caption, style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
            }

            // 卡片：真实界面里最常出现的层级（surfaceContainerHigh + 28dp 圆角）
            Surface(
                Modifier.fillMaxWidth(),
                shape = AppShape.card,
                color = cs.surfaceContainerHigh,
                tonalElevation = 0.dp,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("剩余电量", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    Text("78%", style = MaterialTheme.typography.headlineMedium, color = cs.onSurface)
                    // 进度条：primary 填充 + surfaceVariant 底
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(AppShape.pill)
                            .background(cs.surfaceVariant)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.78f)
                                .height(8.dp)
                                .clip(AppShape.pill)
                                .background(cs.primary)
                        )
                    }
                    Text(
                        "最后更新 14:32",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant
                    )
                }
            }

            // 强调色并排：看 primary / tertiary / secondary / error 的区分度
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                swatch(cs.primary)
                swatch(cs.tertiary)
                swatch(cs.secondary)
                swatch(cs.error)
            }

            // 按钮：onPrimary 在 primary 上的实际观感
            Surface(
                Modifier.fillMaxWidth(),
                shape = AppShape.block,
                color = cs.primary,
            ) {
                Text(
                    "锁车",
                    Modifier.padding(vertical = 13.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onPrimary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }

            Text(
                "bodyLarge 正文示例：车辆处于驻车状态，空调已关闭。",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun swatch(color: Color) = Box(
    Modifier
        .size(44.dp)
        .clip(AppShape.tile)
        .background(color)
)
