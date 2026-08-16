package com.monishguy.mifanscloud.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.monishguy.mifanscloud.data.auth.XiaomiCredential
import com.monishguy.mifanscloud.ui.auth.AuthUiState
import com.monishguy.mifanscloud.ui.auth.AuthViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 主页：认证状态总览 + 刷新/清除操作 + 后续里程碑占位。
 */
@Composable
fun HomeScreen(
    viewModel: AuthViewModel,
    state: AuthUiState.Ready,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.state.collectAsState()
    val loading = uiState is AuthUiState.Loading
    val isPassToken = state.credential is XiaomiCredential.PassToken

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("米饭云服务", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "小米云备份同步（M2 认证闭环）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("已连接小米云", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                InfoRow("userId", state.credential.userId)
                InfoRow(
                    "凭证类型",
                    if (isPassToken) "passToken（10 分钟自动换取）" else "serviceToken 直连（浏览器会话）",
                )
                InfoRow(
                    "serviceToken",
                    if (isPassToken) formatTokenTime(state.tokenObtainedAt) else "—（浏览器会话）",
                )
                InfoRow(
                    "刷新策略",
                    if (isPassToken) "按需刷新（10 分钟周期，401 自动重试）"
                    else "不可自动刷新，失效后需重新登录 i.mi.com",
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row {
            if (isPassToken) {
                Button(
                    onClick = { viewModel.refreshNow() },
                    enabled = !loading,
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (loading) "刷新中…" else "立即刷新")
                }
                Spacer(Modifier.width(12.dp))
            }
            OutlinedButton(onClick = { viewModel.clearCredentials() }) {
                Text("清除凭证")
            }
        }

        Spacer(Modifier.height(24.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("数据模块", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "相册 / 录音 / 通讯录 / 笔记 / 短信 同步将在后续里程碑开放（M3 起）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private val TOKEN_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun formatTokenTime(obtainedAt: Long?): String =
    obtainedAt?.let { TOKEN_TIME_FORMAT.format(Instant.ofEpochMilli(it)) } ?: "—"
