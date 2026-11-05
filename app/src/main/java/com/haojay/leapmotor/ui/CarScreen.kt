@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.haojay.leapmotor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haojay.leapmotor.data.CarSnapshot
import com.haojay.leapmotor.data.Values
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

private data class InfoRow(val label: String, val value: String)
private data class InfoGroup(val title: String, val rows: List<InfoRow>)

private fun fmtTime(ms: Long): String =
    if (ms <= 0) "--" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))

private fun since(ms: Long): String {
    if (ms <= 0) return "--"
    val diff = (System.currentTimeMillis() - ms) / 1000
    return when {
        diff < 60 -> "刚刚"
        diff < 3600 -> "${diff / 60} 分钟前"
        diff < 86400 -> "${diff / 3600} 小时前"
        else -> "${diff / 86400} 天前"
    }
}

private fun groupsOf(s: CarSnapshot): List<InfoGroup> {
    val m = s.signals
    fun r(label: String, key: String, f: (Double?) -> String) = InfoRow(label, f(s.d(key)))

    val known = setOf(
        "soc_precise", "soc", "range_live", "range_ev_cltc", "range_ev_wltc",
        "charge_connection", "dc_cable_connected", "charging_voltage", "charging_current",
        "battery_min_temp", "battery_heating", "healthy_charging",
        "odometer", "speed", "gear", "vehicle_state_code", "parking_brake", "ready",
        "tire_fl", "tire_fr", "tire_rl", "tire_rr",
        "tire_alarm_fl", "tire_alarm_fr", "tire_alarm_rl", "tire_alarm_rr",
        "climate_on", "climate_mode", "ac_fan_level", "climate_temp_left", "interior_temp",
        "seat_heat_driver", "seat_vent_driver", "seat_heat_passenger", "seat_vent_passenger",
        "seat_heat_rear_left", "seat_heat_rear_right", "seat_vent_rear_left", "seat_vent_rear_right",
        "steering_wheel_heating", "steering_heat_minutes", "rear_window_heat",
        "mirror_heat_left", "mirror_heat_right", "windshield_defrost",
        "lock_status", "trunk_open", "sunshade_percent", "sentry_mode", "parking_photo",
        "remote_session", "window_percent_left_front", "window_percent_right_front",
        "window_percent_left_rear", "window_percent_right_rear",
        "latitude", "longitude", "signal_time",
    )

    val list = listOf(
        InfoGroup(
            "未破解信号", m.filterKeys { it !in known }.toSortedMap()
                .map { (k, v) -> InfoRow(k, Values.num(v)) }
        ),
    )
    return list.map { g -> InfoGroup(g.title, g.rows.filter { it.value != "--" }) }
        .filter { it.rows.isNotEmpty() }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CarScreen(
    snap: CarSnapshot?,
    loading: Boolean,
    error: String?,
    polling: Boolean,
    /** 乐观状态：非空时优先于车端信号，点完立刻反映（仅影响显示，不触发任何指令）。 */
    pendingLock: Boolean? = null,
    pendingTrunk: Boolean? = null,
    onRefresh: () -> Unit,
    onLogout: () -> Unit = {},
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
    bottomInset: androidx.compose.ui.unit.Dp = 96.dp,
) {
    if (snap == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                when {
                    loading -> "正在读取车辆数据…"
                    error != null -> error
                    else -> "暂无数据"
                },
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (error != null && !loading) {
                Spacer(Modifier.height(14.dp))
                androidx.compose.material3.Button(onClick = onRefresh) { Text("重试") }
            }
        }
        return
    }

    val groups = groupsOf(snap)
    val chip = when {
        snap.isCharging -> "充电中"
        snap.plugged -> "已插枪"
        snap.isDriving -> "行驶中"
        else -> "驻车"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topInset + 8.dp, bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // 顶部电量区做成卡片：与下方区块边界对齐，扫视时不需要在背景上找数字起点。
            // 主数字仍是 62sp Light，卡片只提供边界，不抢焦点。
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (snap.isCharging) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.primary
                                )
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            chip,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            since(snap.signalTime),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "%.0f".format(snap.socPrecise ?: 0.0),
                            fontSize = 62.sp,
                            fontWeight = FontWeight.Light,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "%",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Light,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 11.dp, start = 3.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            Values.km(snap.rangeLive),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    SocBar(
                        soc = snap.socPrecise ?: 0.0,
                        modifier = Modifier.fillMaxWidth(),
                        height = 14.dp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        MiniStat("里程", Values.km(snap.odometer).removeSuffix(" km"))
                        MiniStat("挡位", Values.gear(snap.d("gear")))
                        MiniStat("车内", Values.celsius(snap.d("interior_temp")))
                    }
                }
            }
        }

        item { KeyMetrics(snap, pendingLock) }

        // 车身：胎压 + 车门车窗合成一块。
        // 原来 6 张同底色的卡片连续排列，视觉上分不出"这组讲什么"，
        // 合成区块后用分隔线区分子项，扫一眼就能落到要看的那个数。
        item {
            MergedSection {
                TireCard(snap, bare = true)
                SectionDivider()
                CarBodyCard(snap, bare = true)
            }
        }
        item {
            MergedSection {
                BatteryCard(snap, bare = true)
                SectionDivider()
                DriveCard(snap, bare = true)
            }
        }
        item {
            MergedSection {
                CabinCard(snap, bare = true)
                SectionDivider()
                LocationCard(snap, bare = true)
            }
        }

        items(groups, key = { it.title }) { g ->
            GroupCard(g, collapsible = g.title == "未破解信号")
        }

        item {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (polling) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (polling) "前台刷新中 · 行驶 6 秒 / 其余 30 秒" else "自动刷新已暂停",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.TextButton(
                onClick = onRefresh,
                // 刷新期间禁用：既能给出反馈，也避免连点产生并发请求
                enabled = !loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (loading) {
                        androidx.compose.material3.LoadingIndicator(
                            modifier = Modifier.size(14.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(
                        if (loading) "刷新中…" else "刷新车况",
                        fontSize = 16.sp,
                        color = if (loading) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
            androidx.compose.material3.TextButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "退出登录",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(
            label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
        )
        Text(
            value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun GroupCard(g: InfoGroup, collapsible: Boolean = false) {
    // 未破解信号默认收起：18 项 raw 值摊开会占两屏，把真正有用的信息推出视野
    var expanded by remember { mutableStateOf(!collapsible) }
    Card(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (collapsible) Modifier.clickable { expanded = !expanded }
                        else Modifier
                    )
                    .then(if (collapsible) Modifier.padding(vertical = 4.dp) else Modifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    g.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (collapsible) {
                    Text(
                        if (expanded) "收起" else "${g.rows.size} 项 · 展开",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!expanded) return@Column
            Spacer(Modifier.height(6.dp))
            g.rows.forEachIndexed { i, row ->
                if (i > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.label,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        row.value,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * 关键指标四宫格：把"一眼要知道的"从长列表里提出来。
 * 充电 / 胎压 / 车门 / 空调 —— 驻车时最常确认的四件事。
 */
@Composable
private fun KeyMetrics(snap: CarSnapshot, pendingLock: Boolean? = null) {
    // ⚠️ 不能用 chargeState.contains("插枪") 判断：字符串"未插枪"也含"插枪"，
    // 会永远判成已插枪。必须按信号值判：1=充电中 / 2·3·4=已插枪 / 0·5=未插枪。
    val cc = snap.i("charge_connection")
    val chargeLabel = when {
        cc == 1 -> "充电中"
        cc != null && cc in 2..4 -> "已插枪"
        else -> "未插枪"
    }
    val tires = listOfNotNull(
        snap.d("tire_fl"), snap.d("tire_fr"), snap.d("tire_rl"), snap.d("tire_rr")
    )
    val tireMin = tires.minOrNull()
    val tireAbnormal = listOf(
        snap.i("tire_alarm_fl"), snap.i("tire_alarm_fr"),
        snap.i("tire_alarm_rl"), snap.i("tire_alarm_rr")
    ).any { it == 1 }
    val acOn = snap.i("climate_on") == 1

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Metric(
            label = "充电",
            value = chargeLabel,
            highlight = snap.isCharging,
            modifier = Modifier.weight(1f)
        )
        Metric(
            label = "胎压",
            value = tireMin?.let { "%.2f".format(it / 100.0) } ?: "--",
            warn = tireAbnormal,
            modifier = Modifier.weight(1f)
        )
        Metric(
            label = "车门",
            value = when (pendingLock ?: snap.locked) {
                true -> "已锁"
                false -> "未锁"
                null -> "--"
            },
            highlight = (pendingLock ?: snap.locked) == true,
            warn = (pendingLock ?: snap.locked) == false,
            modifier = Modifier.weight(1f)
        )
        Metric(
            label = "空调",
            value = if (acOn) "运行中" else "关闭",
            highlight = acOn,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
    unit: String = "",
    highlight: Boolean = false,
    warn: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val color = when {
        warn -> MaterialTheme.colorScheme.error
        highlight -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Card(
        shape = MaterialTheme.shapes.large,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 11.dp)) {
            // 单位跟随标签行：四宫格单列仅约 75dp 宽，系统字号放大到 1.3x 时
            // "2.53 bar" 放不下，单位会被折行再裁掉。标签行高度自适应，折行安全。
            Text(
                if (unit.isEmpty()) label else "$label · $unit",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                maxLines = 2,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(3.dp))
            Text(
                value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                color = color
            )
        }
    }
}


@Composable
private fun TireCard(snap: CarSnapshot, bare: Boolean = false) {
    BodyContainer(bare) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "胎压",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "建议 2.3–2.8 bar",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                TireGauge("左前", snap.d("tire_fl"), snap.i("tire_alarm_fl") == 1)
                TireGauge("右前", snap.d("tire_fr"), snap.i("tire_alarm_fr") == 1)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                TireGauge("左后", snap.d("tire_rl"), snap.i("tire_alarm_rl") == 1)
                TireGauge("右后", snap.d("tire_rr"), snap.i("tire_alarm_rr") == 1)
            }
        }
    }
}

/** 单轮环形仪表：270° 弧，量程 0~3.0 bar。 */
@Composable
private fun TireGauge(label: String, kpa: Double?, alarm: Boolean) {
    val bar = kpa?.div(100.0)
    val frac = ((kpa ?: 0.0) / 300.0).toFloat().coerceIn(0f, 1f)
    val low = bar != null && bar < 2.3
    val bad = alarm || low
    val arcColor = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(78.dp)) {
                val stroke = 7.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = trackColor,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                    topLeft = Offset(inset, inset),
                    size = arcSize
                )
                drawArc(
                    color = arcColor,
                    startAngle = 135f,
                    sweepAngle = 270f * frac,
                    useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                    topLeft = Offset(inset, inset),
                    size = arcSize
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    // 两位小数：253kPa → 2.53 bar，比一位小数更容易看出缓慢漏气
                    bar?.let { "%.2f".format(it) } ?: "--",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (bad) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "bar",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 车身状态：用条形图和状态块代替文字列表。
 *
 * 车窗用横向进度条表达开度（比"30%"更直观地看出留了多少缝），
 * 车门/后备箱/遮阳帘用状态块，异常（开着）标红。
 */
@Composable
private fun CarBodyCard(snap: CarSnapshot, bare: Boolean = false, pendingLock: Boolean? = null, pendingTrunk: Boolean? = null) {
    val windows = listOf(
        "左前" to snap.d("window_percent_left_front"),
        "右前" to snap.d("window_percent_right_front"),
        "左后" to snap.d("window_percent_left_rear"),
        "右后" to snap.d("window_percent_right_rear"),
    )
    val anyWindowOpen = windows.any { (it.second ?: 0.0) > 0.0 }
    val locked = pendingLock ?: snap.locked
    val trunkOpen = pendingTrunk ?: (snap.i("trunk_open") == 1)
    val sunshade = snap.d("sunshade_percent")

    BodyContainer(bare) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "车身",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (anyWindowOpen || trunkOpen) "有开启项" else "全部闭合",
                    fontSize = 13.sp,
                    color = if (anyWindowOpen || trunkOpen) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))

            // 状态块
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StateChip("车锁", if (locked == true) "已锁" else "未锁", locked != true, Modifier.weight(1f))
                StateChip("后备箱", if (trunkOpen) "开启" else "关闭", trunkOpen, Modifier.weight(1f))
                StateChip(
                    "遮阳帘",
                    sunshade?.let { "%.0f%%".format(it) } ?: "--",
                    false,
                    Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "车窗开度",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            windows.forEach { (label, pct) ->
                WindowBar(label, pct ?: 0.0)
                Spacer(Modifier.height(7.dp))
            }
        }
    }
}

@Composable
private fun StateChip(label: String, value: String, warn: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(
                if (warn) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.shapes.medium
            )
            .padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = if (warn) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (warn) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun WindowBar(label: String, pct: Double) {
    val frac = (pct / 100.0).toFloat().coerceIn(0f, 1f)
    val open = pct > 0.0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(30.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.shapes.extraSmall
                )
        ) {
            if (frac > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(frac)
                        .height(8.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.shapes.extraSmall
                        )
                )
            }
        }
        Text(
            if (open) "%.0f%%".format(pct) else "关",
            fontSize = 14.sp,
            color = if (open) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(38.dp).padding(start = 8.dp)
        )
    }
}

/**
 * 电池与充电：环形 SOC + 续航对比条 + 电池温度色带。
 *
 * 为什么用环形：SOC 是"还剩多少"的比例问题，环形比 58.1% 更容易一眼判断。
 * 续航用双条对比：实时值 / CLTC 标称，能看出当前工况打了多少折。
 */
@Composable
private fun BatteryCard(snap: CarSnapshot, bare: Boolean = false) {
    val arcTrack = MaterialTheme.colorScheme.surfaceContainerHighest
    val arcPrimary = MaterialTheme.colorScheme.primary
    val soc = snap.socPrecise ?: 0.0
    val live = snap.rangeLive ?: 0.0
    val cltc = snap.d("range_ev_cltc") ?: 0.0
    val battTemp = snap.d("battery_min_temp")
    val cc = snap.i("charge_connection")
    val volt = snap.d("charging_voltage")
    val amp = snap.d("charging_current")

    BodyContainer(bare) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Text(
                "电池与充电",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 环形 SOC
                Box(contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(96.dp)) {
                        val stroke = 9.dp.toPx()
                        val inset = stroke / 2
                        val arcSize = Size(size.width - stroke, size.height - stroke)
                        drawArc(
                            color = arcTrack,
                            startAngle = 135f,
                            sweepAngle = 270f,
                            useCenter = false,
                            style = Stroke(stroke, cap = StrokeCap.Round),
                            topLeft = Offset(inset, inset),
                            size = arcSize
                        )
                        drawArc(
                            color = arcPrimary,
                            startAngle = 135f,
                            sweepAngle = 270f * (soc / 100.0).toFloat().coerceIn(0f, 1f),
                            useCenter = false,
                            style = Stroke(stroke, cap = StrokeCap.Round),
                            topLeft = Offset(inset, inset),
                            size = arcSize
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "%.1f".format(soc),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text("%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(18.dp))

                // 续航对比
                Column(Modifier.weight(1f)) {
                    Text(
                        "续航",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    RangeBar("实时", live, cltc, highlight = true)
                    Spacer(Modifier.height(7.dp))
                    RangeBar("CLTC", cltc, cltc, highlight = false)
                }
            }

            Spacer(Modifier.height(16.dp))
            // 电池温度色带
            battTemp?.let { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("电池温度", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                MaterialTheme.shapes.extraSmall
                            )
                    ) {
                        // -10~50°C 映射到 0~1
                        val frac = ((t + 10) / 60.0).toFloat().coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(frac)
                                .height(6.dp)
                                .background(
                                    when {
                                        t < 0 -> MaterialTheme.colorScheme.tertiary
                                        t > 40 -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.primary
                                    },
                                    MaterialTheme.shapes.extraSmall
                                )
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "%.0f°C".format(t),
                        fontSize = 14.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // 充电信息：充电时才展开电压电流
            if (cc == 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniMetric("电压", volt?.let { "%.0f V".format(it) } ?: "--", Modifier.weight(1f))
                    MiniMetric("电流", amp?.let { "%.0f A".format(it) } ?: "--", Modifier.weight(1f))
                    MiniMetric(
                        "功率",
                        if (volt != null && amp != null) "%.1f kW".format(volt * amp / 1000.0) else "--",
                        Modifier.weight(1f)
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniMetric("充电状态", Values.chargeState(snap.d("charge_connection")), Modifier.weight(1f))
                    MiniMetric(
                        "健康充电",
                        if (snap.i("healthy_charging") == 1) "开启" else "关闭",
                        Modifier.weight(1f)
                    )
                    MiniMetric(
                        "电池加热",
                        if (snap.i("battery_heating") == 1) "加热中" else "未启用",
                        Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RangeBar(label: String, value: Double, max: Double, highlight: Boolean) {
    val frac = if (max > 0) (value / max).toFloat().coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontSize = 13.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(42.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(7.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.extraSmall)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(frac)
                    .height(7.dp)
                    .background(
                        if (highlight) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        MaterialTheme.shapes.extraSmall
                    )
            )
        }
        Text(
            "%.0f km".format(value),
            fontSize = 14.sp,
            maxLines = 1,
            softWrap = false,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            // 字号放大后 58dp 放不下 "284 km"，会折成两行
            modifier = Modifier.width(76.dp).padding(start = 8.dp)
        )
    }
}

@Composable
private fun MiniMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Medium, )
    }
}

/**
 * 行驶与里程：挡位盘 + 里程大字 + 状态点。
 *
 * 挡位用 P/R/N/D 四个圆点表示，当前挡位实心高亮——
 * 比一行"挡位: 驻车挡"更接近真实档把的直觉。
 */
@Composable
private fun DriveCard(snap: CarSnapshot, bare: Boolean = false) {
    val gearRaw = snap.i("gear")
    // 实测 1010：1/3=行驶，0/2=驻车
    val gearIdx = when (gearRaw) {
        1, 3 -> 3   // D
        2 -> 1      // R（存疑，按驻车处理时不会出现）
        else -> 0   // P
    }
    val speed = snap.d("speed") ?: 0.0
    val ready = snap.i("ready") == 1
    val brake = snap.i("parking_brake") == 1

    BodyContainer(bare) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "行驶与里程",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "%.0f km/h".format(speed),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (speed > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 挡位盘
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("P", "R", "N", "D").forEachIndexed { i, g ->
                        val active = i == gearIdx
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    MaterialTheme.shapes.medium
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                g,
                                fontSize = 16.sp,
                                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "总里程",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        Values.km(snap.odometer).removeSuffix(" km"),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatusDot("Ready", ready)
                StatusDot("手刹", brake)
                StatusDot("车门", snap.locked == true)
            }
        }
    }
}

@Composable
private fun StatusDot(label: String, on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .background(
                    if (on) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    androidx.compose.foundation.shape.CircleShape
                )
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            fontSize = 14.sp,
            color = if (on) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 座舱与空调：温度对比 + 风量条 + 座椅档位点阵 + 开关块。
 *
 * 温度用"设定 vs 车内"并排，能立刻看出空调有没有在起作用；
 * 座椅加热/通风用三点阵，档位比"开/关"更有信息量。
 */
@Composable
private fun CabinCard(snap: CarSnapshot, bare: Boolean = false) {
    val acOn = snap.i("climate_on") == 1
    val setTemp = snap.d("climate_temp_left")
    val innerTemp = snap.d("interior_temp")
    val fan = snap.d("ac_fan_level") ?: 0.0
    val mode = Values.climateMode(snap.d("climate_mode"))

    BodyContainer(bare) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "座舱与空调",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (acOn) "运行中 · " + mode else "已关闭",
                    fontSize = 13.sp,
                    color = if (acOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(14.dp))

            // 温度对比
            Row(verticalAlignment = Alignment.Bottom) {
                Column {
                    Text("设定", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            setTemp?.let { "%.0f".format(it) } ?: "--",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Light,
                            color = if (acOn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "°C",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }
                }
                Spacer(Modifier.width(24.dp))
                Column {
                    Text("车内", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            innerTemp?.let { "%.0f".format(it) } ?: "--",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Light,
                        )
                        Text(
                            "°C",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // 风量条（1~7）
                Column(horizontalAlignment = Alignment.End) {
                    Text("风量", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        (1..7).forEach { i ->
                            Box(
                                Modifier
                                    .width(5.dp)
                                    .height(if (i <= fan) 18.dp else 10.dp)
                                    .background(
                                        if (i <= fan) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        MaterialTheme.shapes.extraSmall
                                    )
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("座椅与舒适件", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            // 座椅档位点阵
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeatDots("主驾加热", snap.i("seat_heat_driver"), Modifier.weight(1f))
                SeatDots("副驾加热", snap.i("seat_heat_passenger"), Modifier.weight(1f))
            }
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeatDots("主驾通风", snap.i("seat_vent_driver"), Modifier.weight(1f))
                SeatDots("副驾通风", snap.i("seat_vent_passenger"), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ComfortChip("方向盘", snap.i("steering_wheel_heating") == 1, Modifier.weight(1f))
                ComfortChip("后窗加热", snap.i("rear_window_heat") == 1, Modifier.weight(1f))
                ComfortChip("前挡除霜", snap.i("windshield_defrost") == 1, Modifier.weight(1f))
            }
        }
    }
}

/** 座椅档位：三个点，填充个数=档位。 */
@Composable
private fun SeatDots(label: String, level: Int?, modifier: Modifier = Modifier) {
    val lv = level ?: 0
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (1..3).forEach { i ->
                Box(
                    Modifier
                        .size(7.dp)
                        .background(
                            if (i <= lv) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            androidx.compose.foundation.shape.CircleShape
                        )
                )
            }
        }
    }
}

@Composable
private fun ComfortChip(label: String, on: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(
                if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.shapes.medium
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(
                    if (on) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    androidx.compose.foundation.shape.CircleShape
                )
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            fontSize = 13.sp,
            color = if (on) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 车辆位置：坐标 + 一键跳高德。
 *
 * 车端信号 2/3 是 **GCJ-02**（火星坐标），与高德底图同一基准，
 * 直接传给高德即可精确落点，不需要 WGS-84 转换。
 */
/**
 * 车辆位置：优先显示可读地址，坐标降为辅助信息。
 *
 * 逆地理编码用系统 [android.location.Geocoder]（免费、无需 API Key）。
 * 它有网络依赖且部分 ROM 可能不可用，所以解析失败时回落显示坐标，
 * 不让这块变成空白。
 */
@Composable
private fun LocationCard(snap: CarSnapshot, bare: Boolean = false) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lat = snap.d("latitude")
    val lon = snap.d("longitude")
    val valid = lat != null && lon != null && lat != 0.0 && lon != 0.0

    var address by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(lat, lon) {
        if (!valid) return@LaunchedEffect
        resolving = true
        address = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            reverseGeocode(ctx, lat!!, lon!!)
        }
        resolving = false
    }

    BodyContainer(bare) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "车辆位置",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    since(snap.signalTime),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))

            when {
                !valid -> Text(
                    "无定位数据",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                address != null -> {
                    Text(
                        address!!,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 23.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "%.5f, %.5f · GCJ-02".format(lat, lon),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (resolving) {
                            androidx.compose.material3.LoadingIndicator(
                                modifier = Modifier.size(14.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (resolving) "正在解析地址…"
                            else "%.5f, %.5f".format(lat, lon),
                            fontSize = if (resolving) 13.sp else 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (resolving) "GCJ-02 坐标系" else "地址解析不可用，仅显示坐标",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (valid) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapButton("高德地图", Modifier.weight(1f)) {
                        openMap(ctx, lat!!, lon!!, "零跑 B01", amap = true)
                    }
                    MapButton("其他地图", Modifier.weight(1f), secondary = true) {
                        openMap(ctx, lat!!, lon!!, "零跑 B01", amap = false)
                    }
                }
            }
        }
    }
}

/**
 * 逆地理编码：把 GCJ-02 坐标翻成中文地址。
 *
 * 用系统 Geocoder 而不是高德 Web API —— 后者要单独申请 Key，
 * 而 Geocoder 系统自带。代价是依赖网络，且部分国产 ROM 可能返回空，
 * 所以调用方必须能接受 null。
 *
 * ⚠️ 阻塞调用，只能在 IO 线程执行。
 */
private fun reverseGeocode(
    ctx: android.content.Context,
    lat: Double,
    lon: Double,
): String? {
    if (!android.location.Geocoder.isPresent()) return null

    val geocoder = android.location.Geocoder(ctx, java.util.Locale.CHINA)
    val list: MutableList<android.location.Address>? = try {
        @Suppress("DEPRECATION")
        geocoder.getFromLocation(lat, lon, 1)
    } catch (e: Exception) {
        null
    }
    val addr: android.location.Address = list?.firstOrNull() ?: return null

    val parts = ArrayList<String>(5)
    val province: String? = addr.adminArea
    val city: String? = addr.locality
    val district: String? = addr.subLocality
    val street: String? = addr.thoroughfare
    val number: String? = addr.subThoroughfare

    if (!province.isNullOrBlank()) parts.add(province)
    if (!city.isNullOrBlank() && city != province) parts.add(city)
    if (!district.isNullOrBlank()) parts.add(district)
    if (!street.isNullOrBlank()) parts.add(street)
    if (!number.isNullOrBlank()) parts.add(number)

    if (parts.isEmpty()) return addr.getAddressLine(0)
    return parts.joinToString("")
}


@Composable
private fun MapButton(
    label: String,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .background(
                if (secondary) MaterialTheme.colorScheme.surfaceContainerHighest
                else MaterialTheme.colorScheme.primary,
                MaterialTheme.shapes.medium
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (secondary) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onPrimary
        )
    }
}

/**
 * 调起地图。
 *
 * 优先高德（androidamap://viewMap，包名 com.autonavi.minimap）；
 * 未安装时回落到系统 geo: 协议（会弹出可选地图列表）。
 */
private fun openMap(
    ctx: android.content.Context,
    lat: Double,
    lon: Double,
    name: String,
    amap: Boolean,
) {
    if (amap) {
        val uri = android.net.Uri.parse(
            "androidamap://viewMap?sourceApplication=leapmotor_data" +
                "&poiname=" + android.net.Uri.encode(name) +
                "&lat=$lat&lon=$lon&dev=0"
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            .setPackage("com.autonavi.minimap")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(intent) }.isSuccess) return
    }
    // 回落：通用 geo 协议
    val geo = android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon(" + android.net.Uri.encode(name) + ")")
    runCatching {
        ctx.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, geo)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}


// ==================== 合并容器 ====================

/** 把若干 bare 卡片装进同一块底色，子项之间用分隔线断开。 */
@Composable
private fun MergedSection(
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(vertical = 2.dp), content = content)
    }
}

@Composable
private fun SectionDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}

/** bare 模式：不画自己的底色，交给外层 MergedSection 统一承载。 */
@Composable
private fun BodyContainer(bare: Boolean, content: @Composable () -> Unit) {
    if (bare) {
        Column(Modifier.fillMaxWidth()) { content() }
    } else {
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            modifier = Modifier.fillMaxWidth()
        ) { content() }
    }
}
