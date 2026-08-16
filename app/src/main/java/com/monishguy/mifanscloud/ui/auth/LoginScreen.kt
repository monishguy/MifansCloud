package com.monishguy.mifanscloud.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private enum class InputMode { Cookie, Manual }

/**
 * 登录/配置页：粘贴整段 Cookie（推荐）、手动填写 userId/passToken，
 * 或使用 WebView 内嵌浏览器登录；保存后立即验证 serviceToken 换取链。
 */
@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    initialError: String?,
    modifier: Modifier = Modifier,
) {
    var webLoginOpen by remember { mutableStateOf(false) }
    if (webLoginOpen) {
        WebViewLoginScreen(
            viewModel = viewModel,
            onClose = { webLoginOpen = false },
        )
        return
    }

    var mode by remember { mutableStateOf(InputMode.Cookie) }
    var cookieText by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf("") }
    var passToken by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val state by viewModel.state.collectAsState()
    val loading = state is AuthUiState.Loading
    val error = (state as? AuthUiState.NotConfigured)?.error ?: initialError

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("配置小米云凭证", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "在浏览器登录 https://i.mi.com/ 并访问一次相册页后，" +
                "从开发者工具 → Application → Cookies 复制 userId 与 passToken。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = mode == InputMode.Cookie,
                onClick = { mode = InputMode.Cookie },
            )
            Text("粘贴整段 Cookie")
            Spacer(Modifier.padding(horizontal = 8.dp))
            RadioButton(
                selected = mode == InputMode.Manual,
                onClick = { mode = InputMode.Manual },
            )
            Text("手动填写")
        }
        Spacer(Modifier.height(8.dp))

        when (mode) {
            InputMode.Cookie -> OutlinedTextField(
                value = cookieText,
                onValueChange = { cookieText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Cookie（userId=…; passToken=…; …）") },
                supportingText = { Text("支持粘贴整段，自动解析 userId/passToken") },
                minLines = 3,
                enabled = !loading,
            )

            InputMode.Manual -> {
                OutlinedTextField(
                    value = userId,
                    onValueChange = { userId = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("userId") },
                    enabled = !loading,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = passToken,
                    onValueChange = { passToken = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("passToken") },
                    visualTransformation = if (showPassword) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        androidx.compose.material3.TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "隐藏" else "显示")
                        }
                    },
                    enabled = !loading,
                )
            }
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                when (mode) {
                    InputMode.Cookie -> viewModel.saveFromCookie(cookieText)
                    InputMode.Manual -> viewModel.saveManually(userId, passToken)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading,
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.padding(horizontal = 8.dp))
                Text("验证中…")
            } else {
                Text("保存并验证")
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { webLoginOpen = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("或使用浏览器登录 i.mi.com")
        }
    }
}
