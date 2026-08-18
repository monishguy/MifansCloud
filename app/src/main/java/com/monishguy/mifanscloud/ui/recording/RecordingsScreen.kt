package com.monishguy.mifanscloud.ui.recording

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.monishguy.mifanscloud.data.local.DownloadNotifier
import com.monishguy.mifanscloud.data.local.SafHelper
import com.monishguy.mifanscloud.data.local.SaveDirStore
import com.monishguy.mifanscloud.data.local.SaveSection
import com.monishguy.mifanscloud.data.recording.RemoteRecording
import com.monishguy.mifanscloud.data.recording.RecordingType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录音列表页（Material You 统一）：
 * - 标准 TopAppBar（返回 + 标题 + 刷新）
 * - 点条目按需下载；**长按多选 → 批量顺序下载**（进度 + 通知栏）
 * - 深色模式由主题自动适配（固定 scheme，文字始终可读）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecordingsScreen(
    viewModel: RecordingsViewModel,
    saveDirStore: SaveDirStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    var treeUri by remember { mutableStateOf(saveDirStore.get(SaveSection.RECORDING)) }
    var folderError by remember { mutableStateOf<String?>(null) }
    var savedHint by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var notifId by remember { mutableStateOf<Int?>(null) }

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

    // 批量下载所选录音
    fun startBatchDownload(recordings: List<RemoteRecording>) {
        val folder = treeUri
        if (folder == null) {
            folderError = "请先选择备份文件夹"
            pickFolder.launch(null)
            return
        }
        batchProgress = 0 to recordings.size
        notifId = DownloadNotifier.start(context, "批量下载录音", "0/${recordings.size}")
        viewModel.downloadMany(
            recordings = recordings,
            outputProvider = { r ->
                SafHelper.createDocument(contentResolver, folder, "audio/mp4", r.fileName)
                    ?.let { contentResolver.openOutputStream(it) }
            },
            onProgress = { done, total ->
                batchProgress = done to total
                DownloadNotifier.update(context, notifId, "批量下载录音", "$done/$total", done, total)
            },
            onCompleted = { okCount ->
                batchProgress = null
                selection = emptySet()
                notifId = null
                val msg = if (okCount == recordings.size) {
                    "已保存 ${okCount} 条录音"
                } else {
                    "完成：成功 $okCount / ${recordings.size} 条"
                }
                savedHint = msg
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                DownloadNotifier.finish(context, notifId, "批量下载完成", msg, success = true)
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("云端录音") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.load() }) { Text("刷新") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Text(
                    "点条目按需下载；长按多选可批量下载。",
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
                            val selected = row.recording.id in selection
                            RecordingCard(
                                row = row,
                                selected = selected,
                                onClick = {
                                    if (selection.isNotEmpty()) {
                                        selection = if (selected) selection - row.recording.id else selection + row.recording.id
                                    } else {
                                        val folder = treeUri
                                        if (folder == null) {
                                            folderError = "请先选择备份文件夹"
                                            pickFolder.launch(null)
                                        } else {
                                            val uri = createRecordingDocument(contentResolver, folder, row.recording)
                                            if (uri != null) {
                                                val id = DownloadNotifier.start(context, "录音下载", row.recording.fileName)
                                                viewModel.download(row.recording, outputProvider = {
                                                    contentResolver.openOutputStream(uri)!!
                                                }, onCompleted = { ok ->
                                                    savedHint = if (ok) "已保存：${row.recording.fileName}" else "下载失败：${row.recording.fileName}"
                                                    DownloadNotifier.finish(
                                                        context, id,
                                                        if (ok) "录音下载完成" else "录音下载失败",
                                                        row.recording.fileName, success = ok,
                                                    )
                                                })
                                            } else {
                                                folderError = "无法在备份文件夹创建文件"
                                            }
                                        }
                                    }
                                },
                                onLongClick = {
                                    selection = if (selected) selection - row.recording.id else selection + row.recording.id
                                },
                            )
                        }
                    }
                }

                if (selection.isNotEmpty() || batchProgress != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            batchProgress?.let { (done, total) -> "下载中 $done/$total" } ?: "已选 ${selection.size} 条",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row {
                            if (batchProgress == null) {
                                OutlinedButton(onClick = { selection = emptySet() }) { Text("取消") }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = {
                                    val rows = (state as? RecordingUiState.Recordings)?.recordings.orEmpty()
                                    val selected = rows.map { it.recording }.filter { it.id in selection }
                                    startBatchDownload(selected)
                                }) { Text("下载") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingCard(
    row: RecordingRow,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(12.dp),
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
                Surface(color = MaterialTheme.colorScheme.primary) {
                    Text(
                        "已下",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
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
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
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
