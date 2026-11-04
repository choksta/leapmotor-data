@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.haojay.leapmotor.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haojay.leapmotor.data.CarSnapshot
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

private data class Act(
    val label: String,
    val cmdId: Int,
    val state: Map<String, Any?>,
    val confirm: String? = null,
    val danger: Boolean = false,
)

/**
 * 车控页 —— Material 3 Expressive 重做。
 *
 * 分层逻辑：
 *   1. 超大状态区（无卡片，靠 64sp 字号建立焦点）+ 波浪进度条
 *   2. 常用：大圆角方块，危险操作走 errorContainer
 *   3. 空调：独立面板（温度/风量/模式/座椅）
 *   4. 车窗：四档连体选择
 *   5. 充电：上限分段 + 操作
 *   6. 安全：哨兵
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ControlScreen(
    snap: CarSnapshot?,
    busy: Int?,
    hasPin: Boolean,
    pendingAcOn: Boolean? = null,
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
    androidx.compose.runtime.LaunchedEffect(Unit) { onLoadChargePlan() }

    val locked = snap?.locked
    val soc = snap?.socPrecise ?: 0.0
    val busyOrNot = busy != null

    // ⚠️ 车门 110 / 后备箱 130 / 寻车 120 的 value 必须是**字符串**。
    // 项目里 CMD_DOOR 的注释写着「1=锁, 2=解锁」，那是过时的；
    // 传整数服务端会 500：java.lang.Integer cannot be cast to java.lang.String
    val main = listOf(
        Act("解锁", 110, mapOf("value" to "unlock"), confirm = "即将真实打开车门锁，确定继续？", danger = true),
        Act("锁车", 110, mapOf("value" to "lock")),
        Act("后备箱", 130, mapOf("value" to "true"), confirm = "尾门会真实弹开，请确认车后无人。", danger = true),
        Act("寻车", 120, mapOf("value" to "true")),
    )

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topInset + 6.dp, bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { HeroStatus(snap, locked, soc) }

        item {
            Column {
                SectionLabel("常用")
                Spacer(Modifier.height(10.dp))
                // 这里不用 HorizontalFloatingToolbar：
                // 工具条容器本身已有圆角 + 阴影，里面再摆自带圆角的方块就是"圆套方"，
                // 两层圆角互相打架。除非把内部换成无底色的图标按钮，否则一定难看。
                // 四个方块直接铺在背景上，各自是一个独立目标，层级反而清楚。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    main.forEach { a ->
                        BigTile(a, busyOrNot, Modifier.weight(1f)) {
                            if (a.confirm != null) pending = a else onSend(a.cmdId, a.state)
                        }
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
                SectionLabel("车窗与遮阳帘")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "微开" to "2", "半开" to "5", "全开" to "10", "全关" to "0"
                    ).forEach { (label, v) ->
                        SmallTile(label, busyOrNot, Modifier.weight(1f), primary = false) {
                            onSend(230, mapOf("value" to v))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallTile("遮阳帘开", busyOrNot, Modifier.weight(1f)) {
                        onSend(240, mapOf("value" to "10"))
                    }
                    SmallTile("遮阳帘关", busyOrNot, Modifier.weight(1f)) {
                        onSend(240, mapOf("value" to "0"))
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
            Column {
                SectionLabel("安全")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallTile("哨兵开启", busyOrNot, Modifier.weight(1f)) {
                        onSend(400, mapOf("value" to "on"))
                    }
                    SmallTile("哨兵关闭", busyOrNot, Modifier.weight(1f)) {
                        onSend(400, mapOf("value" to "off"))
                    }
                }
            }
        }

        if (!hasPin) {
            item { PinCard(pinInput, { pinInput = it }, onSavePin) }
        }
    }

    pending?.let { a ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(a.label, fontSize = 16.sp) },
            text = { Text(a.confirm ?: "", fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    onSend(a.cmdId, a.state)
                    pending = null
                }) { Text("确定", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } }
        )
    }
}

// ==================== 组件 ====================

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.4.sp,
        // 标记为标题，读屏用户可以按"跳转到下一个标题"快速导航整页
        modifier = Modifier.semantics { heading() }
    )
}

/** 超大状态区：不用卡片，直接靠字号与字重建立层级。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeroStatus(snap: CarSnapshot?, locked: Boolean?, soc: Double) {
    Column {
        Text(
            "零跑 B01",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.5.sp
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                snap?.socPrecise?.let { "%.0f".format(it) } ?: "--",
                fontSize = 57.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "%",
                fontSize = 24.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 11.dp, start = 3.dp)
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 8.dp)) {
                Text(
                    snap?.rangeLive?.let { "%.0f km".format(it) } ?: "-- km",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    when (locked) {
                        true -> "车门已锁"
                        false -> "车门未锁"
                        null -> "状态未知"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        LinearWavyProgressIndicator(
            progress = { (soc / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(12.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

@Composable
private fun BigTile(
    a: Act,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "bigTile"
    )
    Box(
        modifier = modifier
            .height(70.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(
                if (a.danger) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.shapes.large
            )
            .clickable(interactionSource = interaction, indication = null, enabled = !busy) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            a.label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (a.danger) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SmallTile(
    label: String,
    busy: Boolean,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .background(
                if (primary) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.shapes.large
            )
            .clickable(enabled = !busy) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
            color = if (primary) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface
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
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "充电上限",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    limit?.let { "$it%" } ?: "--",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(80, 90, 100).forEach { v ->
                    val active = limit == v
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
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
                            fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                            color = if (active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallTile("开始充电", busy, Modifier.weight(1f)) { onSend(193, mapOf("value" to "start")) }
                SmallTile("停止充电", busy, Modifier.weight(1f)) { onSend(193, mapOf("value" to "stop")) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallTile("健康充电", busy, Modifier.weight(1f)) { onSend(480, mapOf("state" to "1")) }
                SmallTile("普通充电", busy, Modifier.weight(1f)) { onSend(480, mapOf("state" to "0")) }
                SmallTile("电池预热", busy, Modifier.weight(1f)) { onSend(160, mapOf("value" to "ptcon")) }
            }
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
        Column(Modifier.padding(18.dp)) {
            Text(
                "需要操作密码",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "车控指令必须携带 4 位车辆操作密码",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { v -> if (v.length <= 6 && v.all { it.isDigit() }) onChange(v) },
                    label = { Text("操作密码", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { onSave(value) },
                    enabled = value.length == 4,
                    shape = MaterialTheme.shapes.large
                ) { Text("保存", fontSize = 14.sp) }
            }
        }
    }
}
