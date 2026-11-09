package com.haojay.leapmotor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 报告的视觉部分。
 *
 * 纯文字读起来像总结陈词，加了图之后"这段时间长什么样"能先被看见：
 * 一行逐日柱子给出节奏（哪几天有柱、哪几天空着），每段正文前面一个语义图标，
 * 让五段文字在视觉上分区，而不是糊成一整块。
 */

private val PeakColor = Color(0xFF1F6F8B)
private val BarColor = Color(0xFFB9C6CC)
private val EmptyColor = Color(0xFFE6EBEE)

/**
 * 逐日里程迷你柱状图。最高的那天用主色标出，没动车的那天留一条极浅的底线，
 * 让"空档"本身也成为一种信息。
 */
@Composable
fun ReportDailyBars(days: List<Pair<String, Double>>) {
    if (days.isEmpty()) return
    // 年报 300 多天时柱子会细到看不见，只画最近 60 天
    val shown = if (days.size > 60) days.takeLast(60) else days
    val max = shown.maxOf { it.second }.coerceAtLeast(1.0)
    val peakIdx = shown.indexOfFirst { it.second == max }

    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            val slot = size.width / shown.size
            val barW = (slot * 0.62f).coerceAtLeast(2f)
            val base = size.height - 1f
            shown.forEachIndexed { i, (_, km) ->
                val h = (base * (km / max).toFloat()).coerceIn(0f, base)
                val x = i * slot + (slot - barW) / 2f
                if (h <= 0.5f) {
                    drawRect(EmptyColor, Offset(x, base - 1.5f), Size(barW, 1.5f))
                } else {
                    drawRoundRect(
                        color = if (i == peakIdx) PeakColor else BarColor,
                        topLeft = Offset(x, base - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(1.5f, 1.5f)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                shown.first().first.takeLast(5),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            if (peakIdx >= 0) {
                Text(
                    "峰值 ${max.roundToInt()} km · ${shown[peakIdx].first.takeLast(5)}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
            }
            Text(
                shown.last().first.takeLast(5),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 正文分段渲染，每段前面挂一个语义图标。
 *
 * 图标顺序对应提示词里的段落职责（整体 / 跑最多那天 / 没动车那段 / 习惯 / 变化），
 * 段落数超过图标时循环取用，不会出现"有段没图标"的割裂感。
 */
@Composable
fun ReportBody(body: String) {
    val paragraphs = body.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
    val icons = listOf(
        Icons.Outlined.Insights,
        Icons.Outlined.Route,
        Icons.Outlined.Schedule,
        Icons.Outlined.Bolt,
        Icons.Outlined.TrendingUp,
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        paragraphs.forEachIndexed { i, para ->
            Row {
                Icon(
                    imageVector = icons[i % icons.size],
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .size(17.dp)
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    para,
                    fontSize = 15.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
