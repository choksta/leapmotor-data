package com.haojay.leapmotor.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.haojay.leapmotor.data.CmdLog
import com.haojay.leapmotor.data.UpdateChecker

private val INTERVALS = listOf(6L to "6 小时", 72L to "3 天", 168L to "7 天")

/**
 * 检查更新。
 *
 * 仓库是私有的，GitHub 的 `/releases/latest` 匿名访问返回 404，
 * 所以这里必须能配一个只读令牌；没配也能用，只是只能打开发布页手动下载。
 */
@Composable
fun UpdateDialog(vm: MainViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var tokenInput by remember { mutableStateOf("") }
    var editingToken by remember { mutableStateOf(false) }

    fun open(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = AppShape.card,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)
        ) {
            Column(
                Modifier
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // ---- 标题 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("检查更新", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "当前版本 " + vm.currentVersion,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onClose) { Text("关闭") }
                }

                Spacer(Modifier.height(14.dp))

                // ---- 状态 ----
                UpdateStatus(vm) { url -> open(url) }

                Spacer(Modifier.height(18.dp))

                // ---- 检查频率 ----
                Text(
                    "自动检查",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    INTERVALS.forEach { (hours, label) ->
                        FilterChip(
                            selected = vm.updateIntervalH == hours,
                            onClick = { vm.setUpdateInterval(hours) },
                            label = { Text(label, fontSize = 13.sp) }
                        )
                    }
                }
                if (vm.lastCheckAt > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "上次检查 " + CmdLog.timeText(vm.lastCheckAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(18.dp))

                // ---- 令牌 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "只读令牌",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (vm.hasUpdateToken) "已配置" else "未配置，只能手动打开发布页",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (vm.hasUpdateToken) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { editingToken = !editingToken }) {
                        Text(if (editingToken) "收起" else if (vm.hasUpdateToken) "更换" else "配置")
                    }
                }
                if (editingToken) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("github_pat_… 只读，Contents: Read 即可", fontSize = 12.sp) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                vm.saveUpdateToken(tokenInput)
                                tokenInput = ""
                                editingToken = false
                            },
                            enabled = tokenInput.isNotBlank()
                        ) { Text("保存并检查") }
                        TextButton(
                            onClick = {
                                vm.saveUpdateToken("")
                                tokenInput = ""
                                editingToken = false
                            }
                        ) { Text("清除") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "为什么要令牌：仓库是私有的，匿名请求返回 404。令牌只存在本机，" +
                            "用 Keystore 加密，与零跑账号凭证完全隔离。",
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(18.dp))

                // ---- 操作 ----
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { vm.checkUpdate() },
                        enabled = !vm.updateChecking
                    ) { Text("立即检查") }
                    TextButton(onClick = { open(UpdateChecker.RELEASES_PAGE) }) {
                        Text("打开发布页")
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateStatus(vm: MainViewModel, openUrl: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val release = vm.latestRelease

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = cs.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            when {
                vm.updateChecking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            "  正在检查…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                vm.updateError != null -> {
                    Text(
                        "检查失败",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = cs.error
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        vm.updateError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 18.sp,
                        color = cs.onSurfaceVariant
                    )
                }

                vm.updateAvailable && release != null -> {
                    Text(
                        "有新版本 " + release.version,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = cs.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    val meta = buildString {
                        if (release.apkSizeMb > 0) {
                            append(String.format("%.1f MB", release.apkSizeMb))
                            append(" · ")
                        }
                        append(release.publishedAt.take(10))
                    }
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                    if (release.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            cleanNotes(release.notes),
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 18.sp,
                            maxLines = 8,
                            color = cs.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { openUrl(release.apkUrl ?: release.pageUrl) },
                        shape = MaterialTheme.shapes.medium
                    ) { Text("下载 APK") }
                }

                release != null -> {
                    Text(
                        "已是最新版本",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = cs.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "最新发布 " + release.version + " · " + release.publishedAt.take(10),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }

                else -> {
                    Text(
                        if (vm.hasUpdateToken) "还没检查过" else "未配置令牌",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (vm.hasUpdateToken) {
                            "点「立即检查」查一次。"
                        } else {
                            "私有仓库匿名读不到版本信息。可以点「打开发布页」在浏览器里看，" +
                                "浏览器登录着 GitHub 就能下载。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 18.sp,
                        color = cs.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** release notes 是 markdown，这里只去符号，不渲染格式。 */
private fun cleanNotes(s: String): String = s
    .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
    .replace("**", "")
    .replace("`", "")
    .trim()
