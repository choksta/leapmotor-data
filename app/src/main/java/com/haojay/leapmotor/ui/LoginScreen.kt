@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.haojay.leapmotor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size

/**
 * 登录页：手机号 → 短信验证码 → 自动交换车端凭证。
 *
 * 注意风控：验证码错误与频繁发送都会累计，1023 后重试会延长冷却，
 * 所以界面上失败后不自动重试，只提示用户等待。
 */
@Composable
fun LoginScreen(
    busy: Boolean,
    codeSent: Boolean,
    error: String?,
    onSendCode: (String) -> Unit,
    onLogin: (String, String) -> Unit,
) {
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val phoneOk = phone.length == 11 && phone.all { it.isDigit() }

    // 键盘弹出后整块可滚动，否则小屏上"登录"按钮会被输入法盖住。
    // imePadding 让内容整体上移到键盘之上（edge-to-edge 下必须自己处理 IME inset）。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "LeapCo",
            fontSize = 36.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "用零跑账号登录，读取车辆云端数据",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(30.dp))

        OutlinedTextField(
            value = phone,
            onValueChange = { v -> if (v.length <= 11 && v.all { it.isDigit() }) phone = v },
            label = { Text("手机号") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = code,
                onValueChange = { v -> if (v.length <= 6 && v.all { it.isDigit() }) code = v },
                label = { Text("短信验证码") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { onSendCode(phone) },
                enabled = phoneOk && !busy,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(if (codeSent) "重新发送" else "发送验证码", fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onLogin(phone, code) },
            enabled = phoneOk && code.length >= 4 && !busy,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            if (busy) {
                androidx.compose.material3.LoadingIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(10.dp))
                Text("正在登录…", fontSize = 16.sp)
            } else {
                Text("登录", fontSize = 16.sp)
            }
        }

        error?.let {
            Spacer(Modifier.height(14.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    it,
                    modifier = Modifier.padding(14.dp),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "说明",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "· 零跑云是单账号单会话：这里的登录会让官方 App 掉线，反之亦然。\n" +
                "· 建议用「车辆共享」授权出来的子账号登录，两边互不影响。\n" +
                "· 登录态约 2 小时，App 会自动免密续期，无需重复收短信。",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            fontFamily = FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
        )
    }
}
