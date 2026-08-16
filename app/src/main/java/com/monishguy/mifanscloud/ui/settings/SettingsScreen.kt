package com.monishguy.mifanscloud.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.monishguy.mifanscloud.data.auth.XiaomiCredential
import com.monishguy.mifanscloud.data.local.SaveDirStore
import com.monishguy.mifanscloud.data.local.SaveSection
import com.monishguy.mifanscloud.ui.auth.AuthUiState
import com.monishguy.mifanscloud.ui.auth.AuthViewModel

/**
 * 设置页：账号凭证信息（续期/清除）+ 四个板块各自的保存目录
 * （默认空，点击调系统文件夹选择器）。
 */
@Composable
fun SettingsScreen(
    viewModel: AuthViewModel,
    saveDirStore: SaveDirStore,
    state: AuthUiState.Ready,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val isPassToken = state.credential is XiaomiCredential.PassToken
    val canRenew = (state.credential as? XiaomiCredential.ServiceToken)?.rawCookie != null

    var dirs by remember {
        mutableStateOf(
            mapOf(
                SaveSection.ALBUM to saveDirStore.get(SaveSection.ALBUM),
                SaveSection.RECORDING to saveDirStore.get(SaveSection.RECORDING),
                SaveSection.CONTACT to saveDirStore.get(SaveSection.CONTACT),
                SaveSection.NOTE to saveDirStore.get(SaveSection.NOTE),
            )
        )
    }

    var pendingSection by remember { mutableStateOf<SaveSection?>(null) }

    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val section = pendingSection
        pendingSection = null
        if (uri != null && section != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                saveDirStore.set(section, uri.toString())
                dirs = dirs + (section to uri.toString())
            }
        }
    }

    fun pickDir(section: SaveSection) {
        pendingSection = section
        dirPicker.launch(null)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("小米云账号", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row {
                    Text(
                        "userId",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(90.dp),
                    )
                    Text(state.credential.userId, style = MaterialTheme.typography.bodyMedium)
                }
                Row {
                    Text(
                        "凭证",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(90.dp),
                    )
                    Text(
                        when {
                            isPassToken -> "passToken（自动换取）"
                            canRenew -> "serviceToken（AutoRenewal 自动续期）"
                            else -> "serviceToken（直连）"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    if (isPassToken) {
                        Button(onClick = { viewModel.refreshNow() }) { Text("立即刷新") }
                        Spacer(Modifier.width(8.dp))
                    }
                    if (canRenew) {
                        Button(onClick = { viewModel.renewNow() }) { Text("续期会话") }
                        Spacer(Modifier.width(8.dp))
                    }
                    OutlinedButton(onClick = { viewModel.clearCredentials() }) {
                        Text("清除凭证")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("保存目录", style = MaterialTheme.typography.titleMedium)
                Text(
                    "各板块下载/导出的目标文件夹，默认未设置（点击选择）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                SaveSection.entries.forEach { section ->
                    DirRow(
                        title = section.title,
                        dir = dirs[section],
                        onPick = { pickDir(section) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DirRow(title: String, dir: String?, onPick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
        Text(
            dir?.let { "已设置" } ?: "未设置",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onPick) { Text(if (dir == null) "选择" else "更换") }
    }
}
