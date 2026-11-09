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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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

/** 「待确认信号」那块的标题。展示与折叠判断共用一处，避免改一个漏一个。 */
private const val GROUP_PENDING = "待确认信号"

// SimpleDateFormat 每次 new 出来要几百微秒，界面每次重组都会调；
// 它本身不是线程安全的，所以用单例 + 同步块，而不是每次新建。
private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

private fun fmtTime(ms: Long): String =
    if (ms <= 0) "--" else synchronized(timeFormat) { timeFormat.format(Date(ms)) }

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
        // 以下 10 项在参考实现里有权威命名，已作为正式功能展示（或确认无需展示），
        // 不再进入待确认列表。混在里面会让人低估已掌握的数据量。
        "window_state_1", "window_state_2", "window_state_3", "window_state_4",
        "charge_plug", "ac_mode", "fast_cooling", "fast_heating",
        "range_mode_code", "charge_schedule_cancelled",
        "latitude", "longitude", "signal_time",
    )

    val list = listOf(
        InfoGroup(
            GROUP_PENDING, m.filterKeys { it !in known }.toSortedMap()
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
    /**
     * 数据到达时间，用 State 传：只有读 .value 的地方会重组。
     * 信号值没变的轮询只翻这个值，卡片整体跳过重组。
     */
    updatedAt: androidx.compose.runtime.State<Long>? = null,
    /** 乐观状态：非空时优先于车端信号，点完立刻反映（仅影响显示，不触发任何指令）。 */
    pendingLock: Boolean? = null,
    pendingTrunk: Boolean? = null,
    onRefresh: () -> Unit,
    onLogout: () -> Unit = {},
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
    bottomInset: androidx.compose.ui.unit.Dp = 96.dp,
    /** 高德是否按 WGS-84 取值（坐标校正开关）。 */
    mapUseWgs: Boolean = false,
    onToggleDatum: () -> Unit = {},
    parkingPhoto: android.graphics.Bitmap? = null,
    parkingPhotoAt: Long = 0L,
    parkingPhotoLoading: Boolean = false,
    parkingPhotoError: String? = null,
    onLoadParkingPhoto: () -> Unit = {},
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
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (error != null && !loading) {
                Spacer(Modifier.height(14.dp))
                androidx.compose.material3.Button(onClick = onRefresh) { Text("重试") }
            }
        }
        return
    }

    // 快照没变时不必重建分组（每次会是几十个 InfoRow 对象 + 字符串格式化）
    val groups = remember(snap) { groupsOf(snap) }
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
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        UpdatedAgo(updatedAt)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "%.0f".format(snap.socPrecise ?: 0.0),
                            fontSize = 56.sp,
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
                            fontSize = 20.sp,
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
                LocationCard(
                    snap = snap,
                    updatedAt = updatedAt,
                    bare = true,
                    mapUseWgs = mapUseWgs,
                    onToggleDatum = onToggleDatum
                )
                SectionDivider()
                ParkingPhotoCard(
                    photo = parkingPhoto,
                    uploadAt = parkingPhotoAt,
                    loading = parkingPhotoLoading,
                    error = parkingPhotoError,
                    onLoad = onLoadParkingPhoto,
                    bare = true
                )
            }
        }

        items(groups, key = { it.title }) { g ->
            GroupCard(g, collapsible = g.title == GROUP_PENDING)
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
                    if (polling) "前台刷新中 · 行驶 6 秒 / 充电 15 秒 / 其余 30 秒" else "自动刷新已暂停",
                    fontSize = 12.sp,
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
                        fontSize = 15.sp,
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
                    fontSize = 15.sp,
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
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
        )
        Text(
            value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun GroupCard(g: InfoGroup, collapsible: Boolean = false) {
    // 待确认信号默认收起：二十多项原始值摊开会占两屏，把真正有用的信息推出视野
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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (collapsible) {
                    Text(
                        if (expanded) "收起" else "${g.rows.size} 项 · 展开",
                        fontSize = 12.sp,
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
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        row.value,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun TireCard(snap: CarSnapshot, bare: Boolean = false) {
    val cells = listOf(
        Triple("左前", snap.d("tire_fl"), snap.i("tire_alarm_fl") == 1),
        Triple("右前", snap.d("tire_fr"), snap.i("tire_alarm_fr") == 1),
        Triple("左后", snap.d("tire_rl"), snap.i("tire_alarm_rl") == 1),
        Triple("右后", snap.d("tire_rr"), snap.i("tire_alarm_rr") == 1),
    )

    BodyContainer(bare) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "胎压",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "建议 2.3–2.8 bar",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            // 四个环形仪表占掉近三分之一屏，却只表达"在不在范围内"。
            // 换成一行数值 + 一条细轨：同样的信息，高度从 ~340dp 降到 ~60dp。
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                cells.forEach { (label, kpa, alarm) ->
                    TireCell(label, kpa, alarm, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TireCell(label: String, kpa: Double?, alarm: Boolean, modifier: Modifier = Modifier) {
    val bar = kpa?.div(100.0)
    // 环改条之后颜色要更克制：正常态用主色，仅低值/报警时才变警示色
    val low = bar != null && bar < 2.3
    val bad = alarm || low
    val color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Column(modifier) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                bar?.let { "%.2f".format(it) } ?: "--",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(
                " bar",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.extraSmall)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(((kpa ?: 0.0) / 300.0).toFloat().coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(color, MaterialTheme.shapes.extraSmall)
            )
        }
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
    // 开度的可靠性因车型而异：有的车只给状态位（1693~1696），百分比恒 0。
    // 所以「有没有开」用或判断，「开了多少」只在百分比有值时才说。
    val anyPct = windows.any { (it.second ?: 0.0) > 0.0 }
    val ajar = snap.windowsAjar
    val locked = pendingLock ?: snap.locked
    val trunkOpen = pendingTrunk ?: (snap.i("trunk_open") == 1)
    val sunshade = snap.d("sunshade_percent")
    val plugIn = snap.i("charge_plug") == 1

    BodyContainer(bare) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "车身",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (ajar || trunkOpen) "有开启项" else "全部闭合",
                    fontSize = 12.sp,
                    color = if (ajar || trunkOpen) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))

            // 原来是四个灰底状态块，和下面车窗条形成"块中有块"。改成两列「标签—值」，
            // 只有异常值才上色 —— 正常态集体抢注意力，等于没有重点。
            BodyRow(
                left = "车锁" to if (locked == true) "已锁" else "未锁",
                leftWarn = locked != true,
                right = "后备箱" to if (trunkOpen) "开启" else "关闭",
                rightWarn = trunkOpen,
            )
            Spacer(Modifier.height(8.dp))
            BodyRow(
                left = "充电枪" to if (plugIn) "已插入" else "未插入",
                leftWarn = false,
                right = "遮阳帘" to (sunshade?.let { "%.0f%%".format(it) } ?: "--"),
                rightWarn = false,
            )

            Spacer(Modifier.height(8.dp))
            // 右侧原本空着，把哨兵状态放进来：三行两列都是满的，扫视时不会出现断口
            val sentryOn = snap.i("sentry_mode") == 1
            BodyRow(
                left = "车窗" to if (ajar) "有开启" else "全部关闭",
                leftWarn = ajar,
                right = "哨兵" to if (sentryOn) "已开启" else "未开启",
                rightWarn = false,
            )

            if (anyPct) {
                Spacer(Modifier.height(10.dp))
                windows.forEach { (label, pct) ->
                    WindowBar(label, pct ?: 0.0)
                    Spacer(Modifier.height(7.dp))
                }
            } else {
                // 车端没报开度时，不摆四根空条——那会让人以为"四窗都关着"，
                // 而实际上可能只是这一型车不提供百分比。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ajar) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            "车端未上报开度",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * 车身一行的两组「标签—值」。
 *
 * 不用色块：车身卡内部已经有分隔线和车窗条，再铺一层浅底块会形成"块中有块"，
 * 边界反而更糊。异常值单独上警示色，正常值保持普通文字色。
 */
@Composable
private fun BodyRow(
    left: Pair<String, String>,
    leftWarn: Boolean,
    right: Pair<String, String>,
    rightWarn: Boolean,
) {
    Row(Modifier.fillMaxWidth()) {
        BodyCell(left.first, left.second, leftWarn, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        BodyCell(right.first, right.second, rightWarn, Modifier.weight(1f))
    }
}

@Composable
private fun BodyCell(label: String, value: String, warn: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // 标签固定 56dp：用 weight 把值推到列尾的话，左列的"值"会和右列的"标签"
        // 贴在一起，读起来像四个词连成一串。定宽后每列自成一组。
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(58.dp)
        )
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = if (warn) FontWeight.Medium else FontWeight.Normal,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
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
            fontSize = 12.sp,
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
            fontSize = 12.sp,
            color = if (open) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(38.dp).padding(start = 8.dp)
        )
    }
}

/**
 * 电池与充电：电池温度 + 充电过程 / 健康充电 / 电池加热，充电时展开电压电流。
 *
 * 电量与续航不在这里重复 —— 顶层大卡已经画过，同一数据换种画法只会让人挑一个看。
 */
@Composable
private fun BatteryCard(snap: CarSnapshot, bare: Boolean = false) {
    val battTemp = snap.d("battery_min_temp")
    val cc = snap.i("charge_connection")
    val volt = snap.d("charging_voltage")
    val amp = snap.d("charging_current")

    BodyContainer(bare) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "电池与充电",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                // 温度放到标题行右侧：它是个"环境读数"，不需要单独占一行
                battTemp?.let { t ->
                    Text(
                        "%.0f°C".format(t),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            t < 0 || t > 40 -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
            // 温度色带：-10~50°C 映射到 0~1，只用它表达"偏冷/偏热"的倾向
            battTemp?.let { t ->
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.extraSmall)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(((t + 10) / 60.0).toFloat().coerceIn(0f, 1f))
                            .height(3.dp)
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
            }

            Spacer(Modifier.height(12.dp))
            // 用纯文字三列，不再铺浅色小块：卡片内再套色块是"乱"的来源之一
            Row(Modifier.fillMaxWidth()) {
                if (cc == 1) {
                    PlainMetric("电压", volt?.let { "%.0f V".format(it) } ?: "--", Modifier.weight(1f))
                    PlainMetric("电流", amp?.let { "%.0f A".format(it) } ?: "--", Modifier.weight(1f))
                    PlainMetric(
                        "功率",
                        if (volt != null && amp != null) "%.1f kW".format(volt * amp / 1000.0) else "--",
                        Modifier.weight(1f)
                    )
                } else {
                    PlainMetric("充电过程", Values.chargeState(snap.d("charge_connection")), Modifier.weight(1f))
                    PlainMetric(
                        "健康充电",
                        if (snap.i("healthy_charging") == 1) "开启" else "关闭",
                        Modifier.weight(1f)
                    )
                    PlainMetric(
                        "电池加热",
                        if (snap.i("battery_heating") == 1) "加热中" else "未启用",
                        Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** 卡片内的三列读数：只有文字，没有底色。 */
@Composable
private fun PlainMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "%.0f km/h".format(speed),
                    fontSize = 15.sp,
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
                                fontSize = 15.sp,
                                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                // 总里程不在这里重复：顶层大卡已有「里程 12961」，
                // 行驶卡只负责"现在这一下"的状态。
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
            fontSize = 12.sp,
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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (acOn) "运行中 · " + mode else "已关闭",
                    fontSize = 12.sp,
                    color = if (acOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(14.dp))

            // 温度对比
            Row(verticalAlignment = Alignment.Bottom) {
                Column {
                    Text("设定", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            setTemp?.let { "%.0f".format(it) } ?: "--",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Light,
                            color = if (acOn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "°C",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }
                }
                Spacer(Modifier.width(24.dp))
                Column {
                    Text("车内", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            innerTemp?.let { "%.0f".format(it) } ?: "--",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Light,
                        )
                        Text(
                            "°C",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // 风量条（1~7）
                Column(horizontalAlignment = Alignment.End) {
                    Text("风量", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    // 自动/手动只在空调运行时才有意义：关机状态下 1939 恒为 0，
                    // 照着显示"手动"等于凭空断言。
                    if (acOn) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (snap.i("ac_mode") == 1) "自动" else "手动",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("座椅与舒适件", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            // 极速制冷/制热是两个独立开关（2669 / 2681），但界面上当一件事表达：
            // 用户关心的是"现在是不是在猛吹"，不关心走的是哪条通路。
            val fastCool = snap.i("fast_cooling") == 1
            val fastHeat = snap.i("fast_heating") == 1
            if (fastCool || fastHeat) {
                Spacer(Modifier.height(8.dp))
                ComfortChip(
                    if (fastCool) "极速制冷中" else "极速制热中",
                    true,
                    Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 座椅档位：三个点，填充个数=档位。 */
@Composable
private fun SeatDots(label: String, level: Int?, modifier: Modifier = Modifier) {
    val lv = level ?: 0
    // 不再铺灰底：座舱卡里原本有 7 个色块，套在卡片里形成"块中有块"。
    // 平面排版下靠"档位点阵"本身表达状态，比底色更省视觉预算。
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(58.dp)
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
        modifier = modifier.padding(vertical = 4.dp),
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
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            fontSize = 12.sp,
            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ParkingPhotoCard(
    photo: android.graphics.Bitmap?,
    uploadAt: Long,
    loading: Boolean,
    error: String?,
    onLoad: () -> Unit,
    bare: Boolean = false,
) {
    var zoom by remember { mutableStateOf(false) }

    if (zoom && photo != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { zoom = false }) {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(12.dp)) {
                    androidx.compose.foundation.Image(
                        bitmap = photo.asImageBitmap(),
                        contentDescription = "驻车照片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (uploadAt > 0) "拍摄于 " + fmtTime(uploadAt) else "驻车照片",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        androidx.compose.material3.TextButton(onClick = { zoom = false }) {
                            Text("关闭")
                        }
                    }
                }
            }
        }
    }

    BodyContainer(bare) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "驻车照片",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (uploadAt > 0) {
                    Text(
                        fmtTime(uploadAt),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            when {
                photo != null -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { zoom = true }
                    ) {
                        androidx.compose.foundation.Image(
                            bitmap = photo.asImageBitmap(),
                            contentDescription = "驻车照片，点击放大",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "点击图片放大",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.LoadingIndicator(
                        modifier = Modifier.size(14.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在获取照片…", fontSize = 12.sp)
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    MapButton("查看驻车照片", Modifier.weight(1f), secondary = true) { onLoad() }
                }
            }

            if (error != null && photo == null && !loading) {
                Spacer(Modifier.height(8.dp))
                Text(
                    error,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 车辆位置：优先显示可读地址，坐标降为辅助信息。
 *
 * 三个坐标系不要再混：
 * - 车端 `2/3` 是 **GCJ-02**
 * - 高德底图也是 GCJ-02 → 直接给
 * - 系统 `Geocoder` 和 `geo:` 协议要 **WGS-84** → 先转再给
 *
 * 之前直接把 GCJ 喂给 Geocoder，地址会整体偏 600 米——不报错，只是把你指到隔壁小区。
 */
@Composable
private fun LocationCard(
    snap: CarSnapshot,
    updatedAt: androidx.compose.runtime.State<Long>? = null,
    bare: Boolean = false,
    mapUseWgs: Boolean = false,
    onToggleDatum: () -> Unit = {},
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lat = snap.latitudeAny
    val lon = snap.longitudeAny
    val valid = lat != null && lon != null && lat != 0.0 && lon != 0.0

    // 车端值（GCJ-02）与换算出的 WGS-84，两套都留一份：
    // 地址解析与 geo: 用 WGS，高德用 GCJ（除非用户在校正里切过）
    val wgs = if (valid) com.haojay.leapmotor.data.Geo.gcjToWgs(lon!!, lat!!) else null
    val wgsLon = wgs?.first
    val wgsLat = wgs?.second

    var address by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(lat, lon) {
        if (!valid) return@LaunchedEffect
        resolving = true
        address = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Geocoder 要 WGS-84，传换算后的值
            reverseGeocode(ctx, wgsLat!!, wgsLon!!)
        }
        resolving = false
    }

    // 跳地图时把**解析出来的地址**当 POI 名字带过去。
    // 之前传的是写死的 "LeapCo 车辆位置"，于是高德上只画一个点、气泡里写着一句废话，
    // 用户还得自己对照地址找位置 —— 等于没跳。
    val poiName = address?.takeIf { it.isNotBlank() }
        ?: "%.5f, %.5f".format(lat ?: 0.0, lon ?: 0.0)
    val jumpAmap = {
        val la = if (mapUseWgs) wgsLat!! else lat!!
        val lo = if (mapUseWgs) wgsLon!! else lon!!
        openMap(ctx, la, lo, poiName, amap = true)
    }

    BodyContainer(bare) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "车辆位置",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                UpdatedAgo(updatedAt)
            }
            Spacer(Modifier.height(10.dp))

            when {
                !valid -> Text(
                    "无定位数据",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
                address != null -> {
                    Text(
                        address!!,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clickable { jumpAmap() }
                    )
                    Spacer(Modifier.height(4.dp))
                    // 显示的坐标跟跳转时用的保持一致，免得"界面上一个数、地图上是另一个点"
                    Text(
                        if (mapUseWgs) "WGS-84  %.5f, %.5f".format(wgsLat, wgsLon)
                        else "GCJ-02  %.5f, %.5f".format(lat, lon),
                        fontSize = 12.sp,
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
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (valid) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapButton("高德地图", Modifier.weight(1f)) { jumpAmap() }
                    MapButton("其他地图", Modifier.weight(1f), secondary = true) {
                        // geo: 协议是 WGS-84，系统地图按 WGS 摆点；名字同样带地址
                        openMap(ctx, wgsLat!!, wgsLon!!, poiName, amap = false)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (mapUseWgs) "高德按 WGS-84 取值，与系统地图同一套"
                        else "高德按 GCJ-02 取值，与车端信号同一套",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (mapUseWgs) "改回 GCJ-02" else "改成 WGS-84",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { onToggleDatum() }
                            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp)
                    )
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
            fontSize = 15.sp,
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
    // 线本身很细，如果上下不留白，两块内容会挤成一块，"车身"看着像"胎压"的子项。
    // 竖向内缩 6dp + 分隔线上下各 13dp，等价于让每个分区块有自己的呼吸。
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
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

/**
 * "更新于 X 前"。时间取自车端最近一次上报，不是本地拉取时刻 —— 用本地时刻会出现
 * 永远显示"刚刚"、和官方 App 对不上的问题。
 *
 * 因为信号没变时整页不重组，这个文本自己每 30 秒重算一次；
 * 只有它重组，卡片不受影响。
 */
@Composable
private fun UpdatedAgo(at: androidx.compose.runtime.State<Long>?) {
    val stamp = at?.value ?: 0L
    // 只在"显示文字真的变了"时才更新状态：轮询不重组页面之后，相对时间得自己走，
    // 但没必要每 30 秒都重画一次 —— 一分钟一条，文字不变就一个帧都不画。
    val label by androidx.compose.runtime.produceState(since(stamp), stamp) {
        while (true) {
            val next = since(stamp)
            if (next != value) value = next
            kotlinx.coroutines.delay(15_000)
        }
    }
    Text(
        label,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
