package com.haojay.leapmotor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haojay.leapmotor.data.DailyPoint
import com.haojay.leapmotor.data.WeekBreakdown
import com.haojay.leapmotor.data.WeeklyEc
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** 逐日里程柱状图。直角硬边 + 细基线，读起来像档案表格而不是营销图。 */
@Composable
fun DailyBarChart(points: List<DailyPoint>, modifier: Modifier = Modifier) {
    val barColor = MaterialTheme.colorScheme.primary
    // 弱化柱不能用一个 alpha 打天下：深色下 primary 是亮青，0.28 叠在近黑底上
    // 混出来的颜色只有 1.6:1 对比度，柱子几乎看不见。深色给 0.55 —— 混色后约 4:1，
    // 够当图形元素读，又不会压过当前选中柱（它用不透明 primary）。
    val barMuted = MaterialTheme.colorScheme.primary.copy(
        alpha = if (isSystemInDarkTheme()) 0.55f else 0.28f
    )
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val max = (points.maxOfOrNull { it.mileage } ?: 0.0).coerceAtLeast(1.0)
    val lastActive = points.indexOfLast { it.mileage > 0 }

    // Canvas 自绘图形对读屏是空白的，必须自己给语义摘要，
    // 否则 TalkBack 用户完全不知道这里有图。
    val peak = points.maxByOrNull { it.mileage }
    val totalKm = points.sumOf { it.mileage }
    val dailyDesc = if (points.isEmpty()) {
        "暂无逐日里程数据"
    } else {
        buildString {
            append("逐日里程柱状图，共 ${points.size} 天，合计 ${totalKm.toInt()} 公里")
            if (peak != null && peak.mileage > 0) {
                append("，峰值 ${peak.mileage.toInt()} 公里，出现在 ${peak.day}")
            }
        }
    }

    Column(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .semantics { contentDescription = dailyDesc }
        ) {
            val w = size.width
            val h = size.height
            val baseline = h - 1f

            // 顶部与中线，弱化
            listOf(0f, 0.5f).forEach { frac ->
                val y = h * frac
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }

            val n = points.size.coerceAtLeast(1)
            val slot = w / n
            val barW = (slot * 0.62f).coerceAtLeast(1.5f)

            points.forEachIndexed { i, p ->
                val ratio = (p.mileage / max).toFloat().coerceIn(0f, 1f)
                val barH = (baseline - 2f) * ratio
                if (barH <= 0f) return@forEachIndexed
                val x = i * slot + (slot - barW) / 2f
                drawRoundRect(
                    color = if (i == lastActive) barColor else barMuted,
                    topLeft = Offset(x, baseline - barH),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(1.5f, 1.5f)
                )
            }

            drawLine(
                gridColor,
                Offset(0f, baseline),
                Offset(w, baseline),
                strokeWidth = 1f
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                points.firstOrNull()?.day?.takeLast(5) ?: "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                "峰值 ${max.toInt()} km",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                points.lastOrNull()?.day?.takeLast(5) ?: "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
    }
}

/** 六周百公里能耗折线。 */
@Composable
fun WeeklyLineChart(weeks: List<WeeklyEc>, modifier: Modifier = Modifier) {
    if (weeks.isEmpty()) return
    val line = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val values = weeks.map { it.ec }
    val minV = (values.minOrNull() ?: 0.0) * 0.92
    val maxV = (values.maxOrNull() ?: 1.0) * 1.06
    val span = (maxV - minV).coerceAtLeast(0.1)

    val lastEc = weeks.last().ec
    val weeklyDesc = buildString {
        append("近 ${weeks.size} 周百公里能耗折线图，最新 ${"%.1f".format(lastEc)} 度")
        append("，区间 ${"%.1f".format(values.min())} 到 ${"%.1f".format(values.max())} 度")
    }

    Column(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .semantics { contentDescription = weeklyDesc }
        ) {
            val w = size.width
            val h = size.height
            listOf(0f, 0.5f, 1f).forEach { f ->
                drawLine(grid, Offset(0f, h * f), Offset(w, h * f), strokeWidth = 1f)
            }

            val n = weeks.size
            val stepX = if (n > 1) w / (n - 1) else w
            val path = Path()
            val dots = mutableListOf<Offset>()

            weeks.forEachIndexed { i, wk ->
                val x = i * stepX
                val ratio = ((wk.ec - minV) / span).toFloat().coerceIn(0f, 1f)
                val y = h - ratio * (h - 8f) - 4f
                dots.add(Offset(x, y))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            drawPath(path, line, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
            dots.forEach {
                drawCircle(line, radius = 3.2f, center = it)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                weeks.first().start.takeLast(5),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                "kWh/100km",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                weeks.last().end.takeLast(5),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
    }
}

/** 上周能耗三段堆叠（驱动 / 空调 / 其他）。 */
@Composable
fun BreakdownBar(b: WeekBreakdown, modifier: Modifier = Modifier) {
    val segments = listOf(
        Triple("驱动", b.driver, MaterialTheme.colorScheme.primary),
        Triple("空调", b.ac, MaterialTheme.colorScheme.tertiary),
        Triple("其他", b.other, MaterialTheme.colorScheme.secondary),
    )
    val total = b.total.coerceAtLeast(0.001)

    Column(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
        ) {
            Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                var x = 0f
                segments.forEach { (_, v, c) ->
                    val segW = (v / total * size.width).toFloat()
                    if (segW > 0f) {
                        drawRect(c, topLeft = Offset(x, 0f), size = Size(segW, size.height))
                        x += segW
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            segments.forEach { (name, v, c) ->
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).then(Modifier))
                    Canvas(Modifier.size(7.dp)) { drawCircle(c) }
                    Spacer(Modifier.width(5.dp))
                    Column {
                        Text(
                            name,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "%.1f".format(v),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
