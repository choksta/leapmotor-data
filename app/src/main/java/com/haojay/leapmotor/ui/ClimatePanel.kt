@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.haojay.leapmotor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haojay.leapmotor.data.CarSnapshot

/**
 * 空调与舒适件控制面板。
 *
 * 指令参数（实测自 App，见 api_client.ac_on / set_seat）：
 * - 170 空调：temperature 16~32、windlevel 1~7、mode=cold/hot/wind/nohotcold、
 *   operate=manual/auto/off、circle=in/out、position=all、wshld=0/1
 * - 301 座椅加热 / 370 座椅通风：`{"position":"driver"|"copilot","level":"0"~"3"}`
 * - 320 方向盘加热：`{"level":"2"}` 开 / `{"level":"1"}` 关
 * - 440 后视镜加热：`{"value":"2"}` 开 / `{"value":"1"}` 关
 *
 * ⚠️ 关空调必须用 `operate:"off"` + 完整字段；`operate:"close"` 云端返回成功但车不执行。
 */
@Composable
fun ClimatePanel(
    snap: CarSnapshot?,
    busy: Int?,
    /** 乐观开关状态：非空时优先于车端信号，点击后立即给出反馈。 */
    pendingOn: Boolean? = null,
    onSend: (Int, Map<String, Any?>) -> Unit,
) {
    val temp = snap?.d("climate_temp_left")?.toInt()?.coerceIn(16, 32) ?: 24
    val fan = snap?.d("ac_fan_level")?.toInt()?.coerceIn(1, 7) ?: 3
    val modeCode = snap?.i("climate_mode")
    val acOn = pendingOn ?: (snap?.i("climate_on") == 1)
    val locked = busy != null

    /** 组装空调指令；温度/风量都带当前值，避免只发操作项被车端忽略。 */
    fun sendAc(operate: String, t: Int = temp, f: Int = fan, mode: String? = null) {
        val m = mode ?: when (modeCode) {
            1 -> "cold"
            3 -> "hot"
            4 -> "wind"
            else -> "wind"
        }
        onSend(
            170,
            mapOf(
                "circle" to "out",
                "mode" to m,
                "operate" to operate,
                "position" to "all",
                "temperature" to t.toString(),
                "windlevel" to f,
                "wshld" to if (operate == "off") "1" else "0",
            )
        )
    }

    Card(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "空调",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (acOn) "运行中" else "已关闭",
                    fontSize = 12.sp,
                    color = if (acOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(14.dp))

            // 温度
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("温度", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                StepBtn("−", !locked) { sendAc("manual", t = (temp - 1).coerceAtLeast(16)) }
                Text(
                    "$temp°C",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
                StepBtn("+", !locked) { sendAc("manual", t = (temp + 1).coerceAtMost(32)) }
            }
            Spacer(Modifier.height(12.dp))

            // 风量
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("风量", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                StepBtn("−", !locked && fan > 1) { sendAc("manual", f = (fan - 1).coerceAtLeast(1)) }
                Text(
                    "$fan 档",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
                StepBtn("+", !locked && fan < 7) { sendAc("manual", f = (fan + 1).coerceAtMost(7)) }
            }
            Spacer(Modifier.height(14.dp))

            // 模式：M3 Expressive 的连体按钮组（ButtonGroup + ToggleButton）。
            // 与普通 chip 的区别是相邻按钮共享圆角、零间距，选中态由形状本身表达，
            // 不再靠"填色 + 加粗"两个信号重复表达同一件事。
            // 模式：Expressive 连体按钮组。
            // ⚠️ 不用 toggleableItem：alpha06 里它不给未选中项画容器，
            // 结果"制热/换气"看起来像两段纯文字，不像按钮。
            // 改用 customItem + ToggleButton，形状取 ButtonGroupDefaults 的连体令牌。
            val modeSources = androidx.compose.runtime.remember {
                List(3) { androidx.compose.foundation.interaction.MutableInteractionSource() }
            }
            val modes = listOf(
                Triple("制冷", 1, "cold"),
                Triple("制热", 3, "hot"),
                Triple("换气", 4, "wind")
            )
            androidx.compose.material3.ButtonGroup(
                overflowIndicator = {},
                expandedRatio = 1f,
                // 间距归零，配合 connected*Shapes 才是真正的连体组
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                modes.forEachIndexed { i, item ->
                    val label = item.first
                    val code = item.second
                    val mode = item.third
                    customItem(
                        buttonGroupContent = {
                            androidx.compose.material3.ToggleButton(
                                checked = modeCode == code,
                                onCheckedChange = { on -> if (on) sendAc("manual", mode = mode) },
                                enabled = !locked,
                                shapes = when (i) {
                                    0 -> androidx.compose.material3.ButtonGroupDefaults
                                        .connectedLeadingButtonShapes()
                                    modes.lastIndex -> androidx.compose.material3.ButtonGroupDefaults
                                        .connectedTrailingButtonShapes()
                                    else -> androidx.compose.material3.ButtonGroupDefaults
                                        .connectedMiddleButtonShapes()
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 14.dp, vertical = 8.dp
                                ),
                                // alpha06 的 ToggleButton 未选中态默认容器是透明的，
                                // 在连体组里会退化成"纯文字"。显式给容器色。
                                colors = androidx.compose.material3.ToggleButtonDefaults
                                    .toggleButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                        checkedContainerColor = MaterialTheme.colorScheme.primary,
                                        checkedContentColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                interactionSource = modeSources[i],
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(label, fontSize = 14.sp)
                            }
                        },
                        menuContent = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            androidx.compose.material3.ButtonGroup(
                // ⚠️ 必须用带 overflowIndicator 的重载：
                // 另一个重载（I/O 时期的签名）在 1.5.0-alpha06 里内容完全不渲染。
                // overflowIndicator 传空 lambda 表示不做溢出折叠。
                overflowIndicator = {},
                modifier = Modifier.fillMaxWidth()
            ) {
                clickableItem(
                    onClick = { if (acOn) sendAc("off") else sendAc("manual") },
                    label = if (acOn) "关闭空调" else "开启空调",
                    weight = 1f,
                    enabled = !locked
                )
                clickableItem(
                    onClick = { sendAc("auto") },
                    label = "自动",
                    weight = 1f,
                    enabled = !locked
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "座椅与舒适件",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            // 座椅加热 / 通风：level 0~3，点一次进一档，到 3 归 0
            SeatRow(
                label = "主驾加热",
                level = snap?.i("seat_heat_driver"),
                cmdId = 301,
                position = "driver",
                locked = locked,
                onSend = onSend
            )
            SeatRow(
                label = "副驾加热",
                level = snap?.i("seat_heat_passenger"),
                cmdId = 301,
                position = "copilot",
                locked = locked,
                onSend = onSend
            )
            SeatRow(
                label = "主驾通风",
                level = snap?.i("seat_vent_driver"),
                cmdId = 370,
                position = "driver",
                locked = locked,
                onSend = onSend
            )
            SeatRow(
                label = "副驾通风",
                level = snap?.i("seat_vent_passenger"),
                cmdId = 370,
                position = "copilot",
                locked = locked,
                onSend = onSend
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(
                    "方向盘加热",
                    snap?.i("steering_wheel_heating") == 1,
                    !locked
                ) {
                    val on = snap?.i("steering_wheel_heating") == 1
                    onSend(320, mapOf("level" to if (on) "1" else "2"))
                }
                ModeChip(
                    "后视镜加热",
                    snap?.i("mirror_heat_left") == 1,
                    !locked
                ) {
                    val on = snap?.i("mirror_heat_left") == 1
                    onSend(440, mapOf("value" to if (on) "1" else "2"))
                }
            }
        }
    }
}

@Composable
private fun StepBtn(text: String, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(bg, CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun ModeChip(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.55f else 0.25f),
                RoundedCornerShape(999.dp)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 13.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            color = when {
                active -> MaterialTheme.colorScheme.onPrimary
                enabled -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            }
        )
    }
}

@Composable
private fun SeatRow(
    label: String,
    level: Int?,
    cmdId: Int,
    position: String,
    locked: Boolean,
    onSend: (Int, Map<String, Any?>) -> Unit,
) {
    val lv = level ?: 0
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            (0..3).forEach { i ->
                val on = i in 1..lv
                Box(
                    modifier = Modifier
                        .size(width = 26.dp, height = 8.dp)
                        .background(
                            if (on) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            MaterialTheme.shapes.extraSmall
                        )
                        .clickable(enabled = !locked) {
                            // 点第 i 档：若已是该档则关闭，否则设到该档
                            val target = if (lv == i) 0 else i
                            onSend(cmdId, mapOf("position" to position, "level" to target.toString()))
                        }
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (lv == 0) "关" else "$lv 档",
            fontSize = 12.sp,
            color = if (lv > 0) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(38.dp)
        )
    }
}
