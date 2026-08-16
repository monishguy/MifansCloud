package com.monishguy.mifanscloud.ui.note

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.monishguy.mifanscloud.data.local.SaveDirStore
import com.monishguy.mifanscloud.data.local.SaveSection

/**
 * 笔记板块页：按创建顺序（最新在前）清单 + 导出 JSON（目录见设置页）。
 */
@Composable
fun NotesScreen(
    viewModel: NotesViewModel,
    saveDirStore: SaveDirStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    var treeUri by remember { mutableStateOf(saveDirStore.get(SaveSection.NOTE)) }
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
                saveDirStore.set(SaveSection.NOTE, uri.toString())
                treeUri = uri.toString()
                folderError = null
            }.onFailure { folderError = it.message }
        }
    }

    LaunchedEffect(Unit) { viewModel.loadOnce() }
    val state by viewModel.state.collectAsState()

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("云端笔记", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { viewModel.load() }) { Text("刷新") }
        }
        val notes = (state as? NotesUiState.Notes)?.notes
        if (notes != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "共 ${notes.size} 条 · 最新在前",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(horizontal = 8.dp))
                OutlinedButton(onClick = {
                    val folder = treeUri
                    if (folder == null) {
                        folderError = "请先选择保存目录"
                        pickFolder.launch(null)
                    } else {
                        val uri = DocumentsContract.createDocument(
                            contentResolver, Uri.parse(folder), "application/json", "notes.json",
                        )
                        if (uri != null) {
                            viewModel.exportJson { contentResolver.openOutputStream(uri)!! }
                        }
                    }
                }) { Text("导出 JSON") }
            }
        }
        folderError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))

        when (val s = state) {
            NotesUiState.Loading, NotesUiState.Idle -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is NotesUiState.Error -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.load() }) { Text("重试") }
            }

            is NotesUiState.Notes -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(s.notes, key = { it.id }) { note ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                note.subject.ifBlank { note.snippet.ifBlank { "(无标题)" } },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                note.snippet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}
