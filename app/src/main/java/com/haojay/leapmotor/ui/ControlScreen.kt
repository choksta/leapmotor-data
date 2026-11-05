@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.haojay.leapmotor.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Luggage
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haojay.leapmotor.data.CarSnapshot
import com.haojay.leapmotor.data.CmdLog

private data class Act(
    val label: String,
    val cmdId: Int,
    val state: Map<String, Any?>,
    val confirm: String? = null,
    val danger: Boolean = false,
)

/**
 * 车控页。布局顺序对齐官方 App：
 *
 *   1. 状态总览（电量 / 续航 / 车锁）
 *   2. 快捷操作 —— 圆形按钮一排：解锁 · 上锁 · 后备箱 · 寻车
 *   3. 车窗与遮阳帘（四档 + 遮阳帘）
 *   4. 空调面板
 *   5. 一键速调（场景）
 *   6. 充电
 *   7. 哨兵（开关形态，读真实状态 3636）
 *
 * 之所以把门锁放在最前：官方也是这么排的 —— 用户开 App 最常做的是「锁没锁 / 锁一下」，
 * 而不是先调空调。原来按功能同类分组（常用 / 座舱 / 场景 / 充电 / 安全）看着整齐，
 * 但每一项都要先想"它在哪一组"，反而不如按操作频率排。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ControlScreen(
    snap: CarSnapshot?,
    busy: Int?,
    hasPin: Boolean,
    pendingAcOn: Boolean? = null,
    pendingLock: Boolean? = null,
    cmdLog: List<CmdLog.Entry> = emptyList(),
    cmdLogPath: String = "",
    chargeSoc: Int? = null,
    chargeBusy: Boolean = false,
    onSetChargeSoc: (Int) -> Unit = {},
    onLoadChargePlan: () -> Unit = {},
    onSavePin: (String) -> Unit,
    onSend: (Int, Map<String, Any?>) -> Unit,
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
    bottomInset: androidx.compose.ui.unit.Dp = 96.dp,
) {
    var pending by remember { mutableStateOf<Act?>(null) }
    var pinInput by remember { mutableStateOf("") }

    // 哨兵开关的乐观状态：点击立刻翻转，等车端信号追上来再交还真实值
    var sentryPending by remember { mutableStateOf<Boolean?>(null) }
    val realSentry = snap?.i("sentry_mode") == 1
    LaunchedEffect(realSentry) {
        if (sentryPending == realSentry) sentryPending = null
    }
    val sentryOn = sentryPending ?: realSentry

    LaunchedEffect(Unit) { onLoadChargePlan() }

    // 乐观状态优先：点完立刻反映，观察期结束交还车端信号
    val locked = pendingLock ?: snap?.locked
    val soc = snap?.socPrecise ?: 0.0
    val busyOrNot = busy != null

    // ⚠️ 车门 110 / 后备箱 130 / 寻车 120 的 value 必须是**字符串**。
    // 项目里 CMD_DOOR 的注释写着「1=锁, 2=解锁」，那是过时的；
    // 传整数服务端会 500：java.lang.Integer cannot be cast to java.lang.String
    val unlockAct = Act(
        "解锁", 110, mapOf("value" to "unlock"),
        confirm = "即将真实打开车门锁，确定继续？", danger = true
    )
    val trunkAct = Act(
        "后备箱", 130, mapOf("value" to "true"),
        confirm = "尾门会真实弹开，请确认车后无人。", danger = true
    )

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topInset + 8.dp, bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item { StatusHero(snap, locked, soc) }

        item {
            Column {
                SectionLabel("快捷操作")
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickCircle(
                        Icons.Outlined.LockOpen, "解锁", locked == false, busyOrNot,
                        Modifier.weight(1f)
                    ) { pending = unlockAct }
                    QuickCircle(
                        Icons.Outlined.Lock, "上锁", locked == true, busyOrNot,
                        Modifier.weight(1f)
                    ) { onSend(110, mapOf("value" to "lock")) }
                    QuickCircle(
                        Icons.Outlined.Luggage, "后备箱", false, busyOrNot,
                        Modifier.weight(1f)
                    ) { pending = trunkAct }
                    QuickCircle(
                        Icons.Outlined.VolumeUp, "寻车", false, busyOrNot,
                        Modifier.weight(1f)
                    ) { onSend(120, mapOf("value" to "true")) }
                }
            }
        }

        item {
            Column {
                SectionLabel("车窗与遮阳帘")
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("微开" to "2", "半开" to "5", "全开" to "10", "全关" to "0")
                        .forEach { (label, v) ->
                            Tile(label, busyOrNot, Modifier.weight(1f)) {
                                onSend(230, mapOf("value" to v))
                            }
                        }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("遮阳帘开", busyOrNot, Modifier.weight(1f)) {
                        onSend(240, mapOf("value" to "10"))
                    }
                    Tile("遮阳帘关", busyOrNot, Modifier.weight(1f)) {
                        onSend(240, mapOf("value" to "0"))
                    }
                }
            }
        }

        item {
            ClimatePanel(
                snap = snap,
                busy = busy,
                pendingOn = pendingAcOn,
                onSend = onSend
            )
        }

        item {
            Column {
                SectionLabel("一键速调")
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SceneTile(
                        Icons.Outlined.AcUnit, "极速降温", busyOrNot, Modifier.weight(1f)
                    ) {
                        onSend(
                            170, mapOf(
                                "circle" to "in", "mode" to "cold", "operate" to "manual",
                                "position" to "all", "temperature" to "18", "windlevel" to 7, "wshld" to "0"
                            )
                        )
                    }
                    SceneTile(
                        Icons.Outlined.Whatshot, "极速升温", busyOrNot, Modifier.weight(1f)
                    ) {
                        onSend(
                            170, mapOf(
                                "circle" to "in", "mode" to "hot", "operate" to "manual",
                                "position" to "all", "temperature" to "32", "windlevel" to 7, "wshld" to "0"
                            )
                        )
                    }
                    SceneTile(
                        Icons.Outlined.Air, "快速除味", busyOrNot, Modifier.weight(1f)
                    ) {
                        onSend(
                            170, mapOf(
                                "circle" to "out", "mode" to "wind", "operate" to "manual",
                                "position" to "all", "temperature" to "24", "windlevel" to 5, "wshld" to "0"
                            )
                        )
                    }
                    SceneTile(
                        Icons.Outlined.CloudQueue, "风挡除霜", busyOrNot, Modifier.weight(1f)
                    ) {
                        onSend(
                            170, mapOf(
                                "circle" to "out", "mode" to "hot", "operate" to "manual",
                                "position" to "all", "temperature" to "32", "windlevel" to 7, "wshld" to "1"
                            )
                        )
                    }
                }
            }
        }

        item {
            ChargeSection(
                soc = soc,
                limit = chargeSoc,
                busy = chargeBusy || busyOrNot,
                onSetLimit = onSetChargeSoc,
                onSend = onSend
            )
        }

        item {
            SentryCard(sentryOn, busyOrNot) { want ->
                sentryPending = want
                onSend(400, mapOf("value" to if (want) "on" else "off"))
            }
        }

        if (!hasPin) {
            item { PinCard(pinInput, { pinInput = it }, onSavePin) }
        }

        item { CmdLogCard(cmdLog, cmdLogPath) }
    }

    pending?.let { a ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(a.label, fontSize = 18.sp, fontWeight = FontWeight.Medium) },
            text = { Text(a.confirm ?: "", fontSize = 16.sp, lineHeight = 24.sp) },
            confirmButton = {
                TextButton(onClick = {
                    onSend(a.cmdId, a.state)
                    pending = null
                }) { Text("确定", fontSize = 16.sp, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("取消", fontSize = 16.sp) }
            }
        )
    }
}

// ==================== 组件 ====================

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.4.sp,
        // 标记为标题，读屏用户可以按"跳转到下一个标题"快速导航整页
        modifier = Modifier.semantics { heading() }
    )
}

/** 状态总览：电量与续航是主角，其余靠字号退让。 */
@Composable
private fun StatusHero(snap: CarSnapshot?, locked: Boolean?, soc: Double) {
    // 做成卡片，与控制页其他区块边界一致
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "零跑 B01",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.4.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    when (locked) {
                        true -> "车门已锁"
                        false -> "车门未锁"
                        null -> "状态未知"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = when (locked) {
                        true -> MaterialTheme.colorScheme.primary
                        false -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    snap?.socPrecise?.let { "%.0f".format(it) } ?: "--",
                    fontSize = 62.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "%",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp, start = 3.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    snap?.rangeLive?.let { "%.0f km" .format(it) } ?: "-- km",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            SocBar(soc = soc, modifier = Modifier.fillMaxWidth(), height = 16.dp)
        }
    }
}

/** 圆形快捷按钮：官方就是这种形态，图标在上、文字在下，目标足够大。 */
@Composable
private fun QuickCircle(
    icon: ImageVector,
    label: String,
    active: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "quickCircle"
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(
                    if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHigh
                )
                .clickable(interactionSource = interaction, indication = null, enabled = !busy) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (active) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun Tile(
    label: String,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large)
            .clickable(enabled = !busy) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 场景按钮：图标 + 文字，比纯文字方块更容易一眼分辨。 */
@Composable
private fun SceneTile(
    icon: ImageVector,
    label: String,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .height(84.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large)
            .clickable(enabled = !busy) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ChargeSection(
    soc: Double,
    limit: Int?,
    busy: Boolean,
    onSetLimit: (Int) -> Unit,
    onSend: (Int, Map<String, Any?>) -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "充电上限",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    limit?.let { "$it%" } ?: "--",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(80, 90, 100).forEach { v ->
                    val active = limit == v
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                                MaterialTheme.shapes.medium
                            )
                            .clickable(enabled = !busy) { onSetLimit(v) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "$v%",
                            fontSize = 15.sp,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                            color = if (active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("开始充电", busy, Modifier.weight(1f)) { onSend(193, mapOf("value" to "start")) }
                Tile("停止充电", busy, Modifier.weight(1f)) { onSend(193, mapOf("value" to "stop")) }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("健康充电", busy, Modifier.weight(1f)) { onSend(480, mapOf("state" to "1")) }
                Tile("普通充电", busy, Modifier.weight(1f)) { onSend(480, mapOf("state" to "0")) }
                Tile("电池预热", busy, Modifier.weight(1f)) { onSend(160, mapOf("value" to "ptcon")) }
            }
        }
    }
}

/** 哨兵：官方是开关形态，并且明确标注「仅当次驻车有效」，这里也照做。 */
@Composable
private fun SentryCard(on: Boolean, busy: Boolean, onChange: (Boolean) -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Shield,
                contentDescription = null,
                tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("哨兵模式", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                Text(
                    "仅当次驻车有效",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = on,
                onCheckedChange = { if (!busy) onChange(it) },
                enabled = !busy
            )
        }
    }
}

@Composable
private fun PinCard(value: String, onChange: (String) -> Unit, onSave: (String) -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "需要操作密码",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "车控指令必须携带 4 位车辆操作密码",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { v -> if (v.length <= 6 && v.all { it.isDigit() }) onChange(v) },
                    label = { Text("操作密码", fontSize = 14.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { onSave(value) },
                    enabled = value.length == 4,
                    shape = MaterialTheme.shapes.large
                ) { Text("保存", fontSize = 15.sp) }
            }
        }
    }
}

/**
 * 指令账本：把下发过的车控指令列出来。
 *
 * 用途是事后核对——「这个 App 到底发过什么」。界面只显示最近 10 条，
 * 完整记录（60 条）在 filesDir/command_log.json，路径也一并显示，方便 adb 直接读。
 */
@Composable
private fun CmdLogCard(log: List<CmdLog.Entry>, path: String) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "指令记录",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (log.isEmpty()) "空" else "${log.size} 条",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            if (log.isEmpty()) {
                Text(
                    "还没有下发过指令。",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                log.forEachIndexed { i, e ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                CmdLog.timeText(e.at) + "  " + e.label,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            if (e.params.isNotEmpty()) {
                                Text(
                                    e.params,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    maxLines = 2,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            if (e.ok) "成功" else "失败 ${e.code}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (e.ok) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            if (path.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "完整记录：$path",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}
