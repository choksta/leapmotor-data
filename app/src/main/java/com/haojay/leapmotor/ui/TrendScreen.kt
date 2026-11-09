@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.haojay.leapmotor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haojay.leapmotor.data.TrendData
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TrendScreen(
    trend: TrendData?,
    loading: Boolean,
    onLoad: () -> Unit,
    /** 打开「用车报告」。报告页是独立弹窗，不占这个列表的位置。 */
    onOpenReport: () -> Unit = {},
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
    bottomInset: androidx.compose.ui.unit.Dp = 96.dp,
) {
    LaunchedEffect(Unit) { if (trend == null && !loading) onLoad() }

    if (trend == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (loading) "正在读取云端记录…" else "暂无趋势数据",
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!loading) {
                Spacer(Modifier.height(14.dp))
                androidx.compose.material3.Button(onClick = onLoad) { Text("加载") }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topInset + 8.dp, bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // 报告入口放在最上面：它是"回头看"的功能，比看数字更值得先被看见
            Card(
                onClick = onOpenReport,
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "用车报告",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "把这周的里程、充电和出行时段，写成一段能看的话",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                        )
                    }
                    Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }

        item {
            // Expressive Hero：无卡片底色，靠 62sp 细字重与波浪进度建立焦点。
            // 波浪条表达「用车活跃率」= 用车天数 / 交付天数，
            // 比单独看"交付 97 天、用车 62 天"更直观地知道这台车利用率多高。
            Column(Modifier.padding(top = 2.dp, bottom = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "累计行驶",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "车龄 ${trend.deliveryDays} 天",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${trend.totalMileage}",
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        " km",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 11.dp, start = 2.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text(
                            "近 30 天",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${trend.dailyMileageSum.toInt()} km",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                RatioBar(
                    // 分母用 30 而不是车龄：云端只返回近 30 天数据，
                    // 拿 463 天当分母会让进度条永远接近空，失去表达意义。
                    fraction = trend.activeDays.toFloat() / 30f,
                    modifier = Modifier.fillMaxWidth(),
                    height = 14.dp,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Mini2("用车天数", "${trend.activeDays} 天")
                    Mini2("日均里程", "${(trend.dailyMileageSum / 30).toInt()} km")
                    Mini2("30 天活跃", "${(trend.activeDays * 100 / 30)}%")
                }
            }
        }

        item {
            SectionCard("近 30 天日里程") {
                DailyBarChart(trend.daily)
            }
        }

        item {
            SectionCard("能耗概览") {
                // 三条横向对比：长度=能耗高低，共同基准是终身均值。
                // 这样"近 30 天比终身低多少"是肉眼可见的长度差，不用心算减法。
                val base = trend.lifetimeEc ?: 1.0
                val scale = listOfNotNull(trend.avgEc, trend.lifetimeEc, trend.hundredKmEc)
                    .maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
                EcBar(
                    label = "近 30 天",
                    value = trend.avgEc,
                    scaleMax = scale,
                    baseline = base,
                    highlight = true
                )
                Spacer(Modifier.height(9.dp))
                EcBar(
                    label = "终身均值",
                    value = trend.lifetimeEc,
                    scaleMax = scale,
                    baseline = base,
                    highlight = false,
                    isBaseline = true
                )
                Spacer(Modifier.height(9.dp))
                EcBar(
                    label = "最新六周",
                    value = trend.hundredKmEc,
                    scaleMax = scale,
                    baseline = base,
                    highlight = false
                )
                trend.plugEc?.let { plug ->
                    Spacer(Modifier.height(9.dp))
                    EcBar(
                        label = "插枪期间",
                        value = plug,
                        scaleMax = scale,
                        baseline = base,
                        highlight = false
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "终身累计 ${trend.totalEnergy.toInt()} kWh / ${trend.totalMileage} km" +
                        (trend.rankPercent?.let { " · 全国排名前 $it" } ?: ""),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (trend.weekly.isNotEmpty()) {
            item {
                SectionCard("近六周百公里能耗") {
                    WeeklyLineChart(trend.weekly)
                    Spacer(Modifier.height(12.dp))
                    trend.weekly.reversed().take(3).forEach { w ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(
                                "${w.start.takeLast(5)} ~ ${w.end.takeLast(5)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "%.1f".format(w.ec),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        trend.lastWeek?.let { lw ->
            item {
                SectionCard("上周能耗拆分") {
                    BreakdownBar(lw)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "合计 %.1f kWh".format(lw.total),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (trend.daily.isNotEmpty()) {
            item {
                SectionCard("逐日明细") {
                    val rows = trend.daily.sortedByDescending { it.day }.take(14)
                    val maxKm = rows.maxOfOrNull { it.mileage }?.coerceAtLeast(1.0) ?: 1.0
                    rows.forEachIndexed { i, p ->
                        if (i > 0) Spacer(Modifier.height(8.dp))
                        DailyRow(p.day.substringAfter('-'), p.mileage, p.energy, maxKm)
                    }
                }
            }
        }

    }
}

@Composable
private fun Mini2(label: String, value: String) {
    Column {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
        )
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Metric(
    label: String,
    value: String,
    unit: String,
) {
    Column(Modifier.weight(1f)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(unit, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * 能耗对比条。
 *
 * 长度按三条中的最大值归一化，所以彼此可比；`baseline` 用一条细线标出
 * 终身均值位置，高亮条超出或不足基准一眼可见。
 */
@Composable
private fun EcBar(
    label: String,
    value: Double?,
    scaleMax: Double,
    baseline: Double,
    highlight: Boolean,
    isBaseline: Boolean = false,
) {
    val frac = value?.let { (it / scaleMax).toFloat().coerceIn(0f, 1f) } ?: 0f
    val baseFrac = (baseline / scaleMax).toFloat().coerceIn(0f, 1f)
    val delta = value?.minus(baseline)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(68.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(20.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            // 轨道
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
                        MaterialTheme.shapes.small
                    )
            )
            // 填充
            if (frac > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(frac)
                        .height(20.dp)
                        .background(
                            if (highlight) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            MaterialTheme.shapes.small
                        )
                )
            }
            // 基准线（终身均值位置）
            if (!isBaseline && baseFrac in 0.02f..0.98f) {
                Box(
                    Modifier
                        .fillMaxWidth(baseFrac)
                        .height(20.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(20.dp)
                            .background(
                                MaterialTheme.colorScheme.tertiary,
                                MaterialTheme.shapes.extraSmall
                            )
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            value?.let { "%.1f".format(it) } ?: "--",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = if (highlight) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.width(46.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
        Text(
            if (delta == null || isBaseline) "" else (if (delta < 0) "↓" else "↑") + "%.1f".format(kotlin.math.abs(delta)),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = when {
                delta == null || isBaseline -> MaterialTheme.colorScheme.onSurfaceVariant
                delta < 0 -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.error
            },
            maxLines = 1,
            modifier = Modifier.width(42.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

/** 逐日一行：日期 + 里程横条 + 里程 + 能耗。 */
@Composable
private fun DailyRow(day: String, km: Double, kwh: Double, maxKm: Double) {
    val frac = (km / maxKm).toFloat().coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            day,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // "11-05" 在 40dp 里会被拆成 "11-0" + "5"
            modifier = Modifier.width(56.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(14.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
                    MaterialTheme.shapes.extraSmall
                )
        ) {
            if (frac > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(frac)
                        .height(14.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            MaterialTheme.shapes.extraSmall
                        )
                )
            }
        }
        Text(
            "${km.toInt()} km",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(70.dp).padding(start = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
        Text(
            "%.1f".format(kwh),
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(46.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}
