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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haojay.leapmotor.data.DailyStore
import com.haojay.leapmotor.data.ReportStats
import com.haojay.leapmotor.data.ReportWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 用车报告。
 *
 * 数据全部来自本地日表（[DailyStore]）+ 云端回填，模型只负责组织语言；
 * 这里不做任何"模型说得好听就采信"的处理 —— 数字是数字，叙事是叙事。
 */
@Composable
fun ReportDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var period by remember { mutableStateOf(ReportStats.Period.WEEK) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<ReportWriter.Report?>(null) }
    var stats by remember { mutableStateOf<ReportStats.Stats?>(null) }
    var showKey by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(ReportWriter.hasKey(ctx)) }

    // 打开时先把已有数据算一遍，即使没 Key 也能看到数字
    LaunchedEffect(period) {
        stats = withContext(Dispatchers.IO) {
            ReportStats.forDays(DailyStore.load(ctx), period)
        }
        report = null
        error = null
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("用车报告", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showKey = true }) {
                        Text(if (hasKey) "模型设置" else "填 Key", fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReportStats.Period.entries.forEach { p ->
                        FilterChip(
                            selected = period == p,
                            onClick = { period = p },
                            label = { Text(p.label, fontSize = 13.sp) }
                        )
                    }
                }

                stats?.takeIf { it.partial }?.let { st ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        // 「今年」在只有 30 天数据时和「近 90 天」是同一段，必须说清楚
                        "本地数据从 ${st.coverageStartDay} 开始（共 ${st.coverageDays} 天），" +
                            "「${period.label}」实际只统计这段时间",
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(12.dp))

                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    stats?.let { StatGrid(it) }

                    if (report != null) {
                        Spacer(Modifier.height(18.dp))
                        Text(
                            report!!.title,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 28.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            report!!.body,
                            fontSize = 15.sp,
                            lineHeight = 26.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    }

                    if (loading) {
                        Spacer(Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("正在把这些天写下来…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("关闭", fontSize = 14.sp) }
                    Spacer(Modifier.weight(1f))
                    androidx.compose.material3.Button(
                        onClick = {
                            val s = stats ?: return@Button
                            loading = true
                            error = null
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) { ReportWriter.generate(ctx, s) }
                                }.onSuccess { report = it }
                                    .onFailure { error = it.message ?: "生成失败" }
                                loading = false
                            }
                        },
                        enabled = !loading
                    ) {
                        Text(if (report == null) "生成报告" else "重新生成", fontSize = 14.sp)
                    }
                }
            }
        }
    }

    if (showKey) {
        KeyDialog(
            onDismiss = { showKey = false },
            onSaved = { hasKey = ReportWriter.hasKey(ctx) }
        )
    }
}

@Composable
private fun StatGrid(s: ReportStats.Stats) {
    val items = buildList {
        add("总里程" to "${s.totalKm.roundToInt()} km")
        if (s.ecPer100 != null) add("百公里电耗" to "${s.ecPer100} kWh")
        add("用车天数" to "${s.driveDays}/${s.totalDays} 天")
        add("充电" to "${s.chargeCount} 次")
        if (s.longestDayKm > 0) add("跑得最多的一天" to "${s.longestDayKm.roundToInt()} km")
        ReportStats.changePercent(s.totalKm, s.prevKm)?.let {
            add("对比上一周期" to (if (it >= 0) "+$it%" else "$it%"))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(2.dp))
                            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun KeyDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(ReportWriter.model(ctx)) }
    var base by remember { mutableStateOf(ReportWriter.baseUrl(ctx)) }
    var saved by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型设置", fontSize = 18.sp, fontWeight = FontWeight.Medium) },
        text = {
            Column {
                Text(
                    "Key 用 Android Keystore 加密后存在本机，不会上传到除模型服务之外的任何地方。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; saved = false },
                    label = { Text("API Key", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it },
                    label = { Text("接口地址", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ReportWriter.saveConfig(ctx, key.ifBlank { null }, base, model)
                saved = true
                onSaved()
                onDismiss()
            }) { Text("保存", fontSize = 14.sp) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", fontSize = 14.sp) } }
    )
}
