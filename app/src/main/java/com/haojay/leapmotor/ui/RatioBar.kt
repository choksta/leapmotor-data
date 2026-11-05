package com.haojay.leapmotor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 比例条：实心填充 + 可选阈值线。
 *
 * 这里原来用的是 `LinearWavyProgressIndicator`（波浪条）。它是 Expressive 的装饰动效，
 * 波浪本身不表达刻度——同样是 50%，波峰波谷会干扰对「还剩多少」的判断，
 * 读比例的精度远不如实心填充。所以电量、活跃率这类要读数值的地方统一改用这个。
 */
@Composable
fun RatioBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    /** 阈值线位置（0~1），null 表示不画。用来标「低电量提醒线」这类参考点。 */
    threshold: Float? = null,
    thresholdColor: Color = MaterialTheme.colorScheme.error,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val f = fraction.coerceIn(0f, 1f)
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = size.height / 2f
        // 轨道
        drawRoundRect(color = track, cornerRadius = CornerRadius(r, r))
        // 已用量
        if (f > 0f) {
            drawRoundRect(
                color = color,
                size = Size(size.width * f, size.height),
                cornerRadius = CornerRadius(r, r)
            )
        }
        // 阈值线：给出一个可对照的参考点，比纯色块多一层信息
        if (threshold != null && threshold in 0f..1f) {
            val x = size.width * threshold
            drawLine(
                color = thresholdColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = (size.height / 6f).coerceAtLeast(1.5f)
            )
        }
    }
}

/**
 * 电量条：低于 20% 整体变红，并在 20% 处保留阈值线，
 * 这样「还剩多少」和「离该充电还有多远」能同时看到。
 */
@Composable
fun SocBar(
    soc: Double,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
) {
    RatioBar(
        fraction = (soc / 100.0).toFloat(),
        modifier = modifier,
        height = height,
        color = if (soc < 20.0) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary,
        threshold = 0.2f,
    )
}
