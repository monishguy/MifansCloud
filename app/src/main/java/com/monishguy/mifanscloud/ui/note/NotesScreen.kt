package com.monishguy.mifanscloud.ui.note

import android.content.Intent
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.monishguy.mifanscloud.data.local.DownloadNotifier
import com.monishguy.mifanscloud.data.local.SafHelper
import com.monishguy.mifanscloud.data.local.SaveDirStore
import com.monishguy.mifanscloud.data.local.SaveSection

/**
 * 笔记板块页（Material You 统一）：
 * - 标准 TopAppBar；按创建顺序（最新在前）清单；
 * - 长按多选 → 批量导出 Markdown（每篇一个 .md）；
 * - 深色模式由主题自动适配。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var exportHint by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }

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

    fun exportMarkdown(ids: Set<String>) {
        val folder = treeUri
        if (folder == null) {
            folderError = "请先选择保存目录"
            pickFolder.launch(null)
            return
        }
        val notifId = DownloadNotifier.start(context, "笔记导出", "正在导出 Markdown…")
        viewModel.exportMarkdown(
            outputProvider = { fileName ->
                SafHelper.createDocument(contentResolver, folder, "text/markdown", fileName)
                    ?.let { contentResolver.openOutputStream(it) }
            },
            ids = ids,
            onDone = { count, error ->
                val msg = error ?: "已导出 $count 篇笔记为 Markdown"
                exportHint = msg
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                DownloadNotifier.finish(
                    context, notifId,
                    if (error != null) "笔记导出失败" else "笔记导出完成", msg,
                    success = error == null,
                )
                if (error == null) selection = emptySet()
            },
        )
    }

    LaunchedEffect(Unit) { viewModel.loadOnce() }
    val state by viewModel.state.collectAsState()

    // 笔记详情/编辑（点击笔记打开）：标题 + Markdown 正文，可编辑
    var editing by remember { mutableStateOf<com.monishguy.mifanscloud.data.note.RemoteNote?>(null) }
    editing?.let { note ->
        NoteEditor(
            viewModel = viewModel,
            note = note,
            onClose = { editing = null },
        )
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("云端笔记") },
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
                val notes = (state as? NotesUiState.Notes)?.notes
                if (notes != null) {
                    Text(
                        "共 ${notes.size} 条 · 最新在前 · 长按多选",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                folderError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                exportHint?.let {
                    Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
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

                    is NotesUiState.Notes -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(s.notes, key = { it.id }) { note ->
                            val selected = note.id in selection
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier,
                                    ),
                            ) {
                                Column(
                                    Modifier
                                        .combinedClickable(
                                            onClick = {
                                                if (selection.isNotEmpty()) {
                                                    selection = if (selected) selection - note.id else selection + note.id
                                                } else {
                                                    editing = note
                                                }
                                            },
                                            onLongClick = {
                                                selection = if (selected) selection - note.id else selection + note.id
                                            },
                                        )
                                        .padding(12.dp),
                                ) {
                                    Text(
                                        note.title.ifBlank { "(无标题)" },
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        // 转 Markdown 纯文本摘要，不露原始 XML/JSON 标记
                                        viewModel.displaySnippet(note),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                    )
                                }
                            }
                        }
                    }
                }

                if (selection.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("已选 ${selection.size} 篇", style = MaterialTheme.typography.bodyMedium)
                        Row {
                            OutlinedButton(onClick = { selection = emptySet() }) { Text("取消") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { exportMarkdown(selection) }) { Text("导出所选 Markdown") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 笔记详情/编辑页：标题 + Markdown 正文（可编辑）。
 * 保存更新内存（列表与导出 Markdown 生效）；云端同步接口未逆向，
 * 诚实提示待抓包 HAR 落地。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditor(
    viewModel: NotesViewModel,
    note: com.monishguy.mifanscloud.data.note.RemoteNote,
    onClose: () -> Unit,
) {
    var title by remember(note.id) { mutableStateOf(note.title) }
    var body by remember(note.id) { mutableStateOf(viewModel.displayBody(note)) }
    var savedHint by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // 附件图片：解析 Markdown 中的 ![附件 fileId]()，下载真实图片展示
    var attachmentImages by remember(note.id) { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
    LaunchedEffect(note.id) {
        val fileIds = com.monishguy.mifanscloud.data.note.NoteMarkdown.extractFileIds(body)
        if (fileIds.isEmpty()) return@LaunchedEffect
        val images = mutableMapOf<String, ByteArray>()
        fileIds.forEach { id ->
            viewModel.fetchNoteImage(id) { bytes ->
                if (bytes != null && bytes.isNotEmpty()) {
                    images[id] = bytes
                    attachmentImages = images.toMap()
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("编辑笔记") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        viewModel.saveLocalEdit(note, title, body) { ok, message ->
                            savedHint = if (ok) "已保存到云端（列表与导出生效）" else (message ?: "保存失败")
                            Toast.makeText(
                                context,
                                if (ok) "已同步到云端" else "保存失败：${message ?: "未知错误"}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }) { Text("保存") }
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
                savedHint?.let {
                    Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("正文（Markdown）") },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                // 附件图片展示（![附件 fileId]() → 真实图片）
                if (attachmentImages.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "附件图片：",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        attachmentImages.forEach { (_, bytes) ->
                            AsyncImage(
                                model = bytes,
                                contentDescription = "附件",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(120.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "正文为 Markdown 格式；「保存」会同步到云端（标题与正文）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
