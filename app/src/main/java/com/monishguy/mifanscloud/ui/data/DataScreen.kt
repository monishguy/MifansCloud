package com.monishguy.mifanscloud.ui.data

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.monishguy.mifanscloud.data.contact.RemoteContact
import com.monishguy.mifanscloud.data.note.RemoteNote
import com.monishguy.mifanscloud.data.sms.RemoteSms

/**
 * 「数据」页：通讯录 / 笔记 / 短信 清单拉取与 JSON 导出。
 */
@Composable
fun DataScreen(
    viewModel: DataViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    var treeUri by remember { mutableStateOf(prefs.getString(KEY_TREE_URI, null)) }
    var folderError by remember { mutableStateOf<String?>(null) }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
                treeUri = uri.toString()
                folderError = null
            }.onFailure { folderError = it.message }
        }
    }

    fun export(kind: ExportKind) {
        val folder = treeUri
        if (folder == null) {
            folderError = "请先选择备份文件夹"
            pickFolder.launch(null)
            return
        }
        viewModel.exportJson(kind) { fileName ->
            val uri = DocumentsContract.createDocument(
                contentResolver,
                Uri.parse(folder),
                "application/json",
                fileName,
            ) ?: throw IllegalStateException("无法创建导出文件")
            contentResolver.openOutputStream(uri)!!
        }
    }

    LaunchedEffect(Unit) { viewModel.loadAll() }
    val state by viewModel.state.collectAsState()

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("通讯录 / 笔记 / 短信", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { viewModel.loadAll() }) { Text("刷新") }
        }
        Text(
            "拉取云端清单（仅元数据）并导出 JSON 备份。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        folderError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))

        when (val s = state) {
            DataUiState.Loading, DataUiState.Idle -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is DataUiState.Error -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.loadAll() }) { Text("重试") }
            }

            is DataUiState.Loaded -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    SectionCard(
                        title = "通讯录（${s.contacts.size}）",
                        onExport = { export(ExportKind.CONTACTS) },
                    )
                }
                items(s.contacts.take(50)) { ContactRow(it) }
                item {
                    SectionCard(
                        title = "笔记（${s.notes.size}）",
                        onExport = { export(ExportKind.NOTES) },
                    )
                }
                items(s.notes.take(50)) { NoteRow(it) }
                item {
                    SectionCard(
                        title = "短信（${s.sms.size}）",
                        onExport = { export(ExportKind.SMS) },
                    )
                }
                items(s.sms.take(50)) { SmsRow(it) }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, onExport: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onExport) { Text("导出 JSON") }
        }
    }
}

@Composable
private fun ContactRow(contact: RemoteContact) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            "${contact.displayName.ifBlank { "(未命名)" }}  ${contact.phoneNumbers.joinToString(" ") { it.value }}",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
    }
}

@Composable
private fun NoteRow(note: RemoteNote) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            note.subject.ifBlank { note.snippet.ifBlank { "(无标题)" } },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
        Text(
            note.snippet,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun SmsRow(sms: RemoteSms) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            sms.recipients.ifBlank { sms.folder ?: "(未知)" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
        Text(
            sms.snippet,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private const val PREFS_NAME = "mifans_prefs"
private const val KEY_TREE_URI = "backup_tree_uri"
