package com.monishguy.mifanscloud.ui.recording

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import com.monishguy.mifanscloud.data.local.SafHelper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.monishguy.mifanscloud.data.recording.RemoteRecording
import com.monishguy.mifanscloud.data.recording.RecordingType
import com.monishguy.mifanscloud.data.local.DownloadNotifier
import com.monishguy.mifanscloud.data.local.SaveSection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录音列表页：名称（还原后的文件名 + 类型徽标）+ 大小/时间，
 * 点条目按需下载（目录见设置页，未设置时点选）。
 */
@Composable
fun RecordingsScreen(
    viewModel: RecordingsViewModel,
    saveDirStore: com.monishguy.mifanscloud.data.local.SaveDirStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    var treeUri by remember { mutableStateOf(saveDirStore.get(SaveSection.RECORDING)) }
    var folderError by remember { mutableStateOf<String?>(null) }
    var savedHint by remember { mutableStateOf<String?>(null) }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                saveDirStore.set(SaveSection.RECORDING, uri.toString())
                treeUri = uri.toString()
                folderError = null
            }.onFailure { folderError = it.message }
        }
    }

    LaunchedEffect(Unit) { viewModel.loadOnce() }
    val state by viewModel.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("云端录音", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { viewModel.load() }) { Text("刷新") }
        }
        Text(
            "点条目按需下载到备份文件夹（首次需选择文件夹）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        folderError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        savedHint?.let {
            Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))

        when (val s = state) {
            RecordingUiState.Loading, RecordingUiState.Idle -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is RecordingUiState.Error -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.load() }) { Text("重试") }
            }

            is RecordingUiState.Recordings -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(s.recordings, key = { it.recording.id }) { row ->
                    RecordingCard(row = row, onClick = {
                        val folder = treeUri
                        if (folder == null) {
                            folderError = "请先选择备份文件夹"
                            pickFolder.launch(null)
                        } else {
                            val uri = createRecordingDocument(contentResolver, folder, row.recording)
                            if (uri != null) {
                                val notifId = DownloadNotifier.start(context, "录音下载", row.recording.fileName)
                                viewModel.download(row.recording, outputProvider = {
                                    contentResolver.openOutputStream(uri)!!
                                }, onCompleted = { ok ->
                                    savedHint = if (ok) "已保存：${row.recording.fileName}" else "下载失败：${row.recording.fileName}"
                                    DownloadNotifier.finish(
                                        context, notifId,
                                        if (ok) "录音下载完成" else "录音下载失败",
                                        row.recording.fileName, success = ok,
                                    )
                                })
                            } else {
                                folderError = "无法在备份文件夹创建文件"
                            }
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun RecordingCard(row: RecordingRow, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    row.recording.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${typeLabel(row.recording.type)} · ${formatSize(row.recording.size)} · " +
                        formatTime(row.recording.createTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.downloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else if (row.downloaded) {
                Surface(color = Color(0xFF1565C0)) {
                    Text(
                        "已下",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            } else {
                Surface(
                    color = if (row.recording.type == RecordingType.UNKNOWN) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                ) {
                    Text(
                        typeLabel(row.recording.type),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

private fun typeLabel(type: RecordingType): String = when (type) {
    RecordingType.RECORDER -> "录音机"
    RecordingType.PHONE_CALL -> "通话"
    RecordingType.FM -> "FM"
    RecordingType.APP -> "应用"
    RecordingType.UNKNOWN -> "未知"
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatTime(millis: Long): String =
    if (millis > 0) TIME_FORMAT.format(Date(millis)) else "—"

private fun createRecordingDocument(
    contentResolver: android.content.ContentResolver,
    treeUri: String,
    recording: RemoteRecording,
): Uri? = SafHelper.createDocument(
    contentResolver,
    treeUri,
    "audio/mp4",
    recording.fileName.replace(Regex("[/\\\\:*?\"<>|\\s]+"), "_"),
)

