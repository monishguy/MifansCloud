package com.monishguy.mifanscloud.ui.contact

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.monishguy.mifanscloud.data.local.DownloadNotifier
import com.monishguy.mifanscloud.data.local.SafHelper
import com.monishguy.mifanscloud.data.local.SaveDirStore
import com.monishguy.mifanscloud.data.local.SaveSection

/**
 * 通讯录板块页（Material You 统一）：
 * - 标准 TopAppBar；按名字排序列表；
 * - 长按多选 → 批量导出 JSON / 批量导入本机；
 * - 深色模式由主题自动适配。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(
    viewModel: ContactsViewModel,
    saveDirStore: SaveDirStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    var treeUri by remember { mutableStateOf(saveDirStore.get(SaveSection.CONTACT)) }
    var folderError by remember { mutableStateOf<String?>(null) }
    var importHint by remember { mutableStateOf<String?>(null) }
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
                saveDirStore.set(SaveSection.CONTACT, uri.toString())
                treeUri = uri.toString()
                folderError = null
            }.onFailure { folderError = it.message }
        }
    }

    fun startImport(ids: Set<String>? = null) {
        val notifId = DownloadNotifier.start(context, "通讯录导入", "正在导入…")
        viewModel.importToDevice(context, ids) { count, error ->
            val msg = error ?: "已导入 $count 位联系人到本机通讯录"
            importHint = msg
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            DownloadNotifier.finish(
                context, notifId,
                if (error != null) "通讯录导入失败" else "通讯录导入完成", msg,
                success = error == null,
            )
            if (error == null) selection = emptySet()
        }
    }

    fun exportSelected(ids: Set<String>) {
        val folder = treeUri
        if (folder == null) {
            folderError = "请先选择保存目录"
            pickFolder.launch(null)
            return
        }
        val uri = SafHelper.createDocument(contentResolver, folder, "application/json", "contacts.json")
        if (uri != null) {
            viewModel.exportJson({ contentResolver.openOutputStream(uri)!! }, ids)
            val msg = "已导出 ${ids.size} 位联系人"
            importHint = msg
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            selection = emptySet()
        } else {
            folderError = "无法在备份文件夹创建文件"
        }
    }

    val writeContactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startImport(selection.takeIf { it.isNotEmpty() })
        } else {
            val msg = "未授予通讯录写入权限，无法导入"
            importHint = msg
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) { viewModel.loadOnce() }
    val state by viewModel.state.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("云端通讯录") },
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
                val contacts = (state as? ContactsUiState.Contacts)?.contacts
                if (contacts != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "共 ${contacts.size} 人 · 按名字排序 · 长按多选",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                folderError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                importHint?.let {
                    Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))

                when (val s = state) {
                    ContactsUiState.Loading, ContactsUiState.Idle -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    is ContactsUiState.Error -> Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(s.message, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.load() }) { Text("重试") }
                    }

                    is ContactsUiState.Contacts -> LazyColumn(
                        modifier = Modifier.weight(1f),
                    ) {
                        items(s.contacts, key = { it.id }) { contact ->
                            val selected = contact.id in selection
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .then(
                                        if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier,
                                    ),
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                if (selection.isNotEmpty()) {
                                                    selection = if (selected) selection - contact.id else selection + contact.id
                                                }
                                            },
                                            onLongClick = {
                                                selection = if (selected) selection - contact.id else selection + contact.id
                                            },
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Column {
                                        Text(
                                            contact.displayName.ifBlank { "(未命名)" },
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        Text(
                                            contact.phoneNumbers.joinToString(" · ") { it.value },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
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
                        Text("已选 ${selection.size} 位", style = MaterialTheme.typography.bodyMedium)
                        Row {
                            OutlinedButton(onClick = { selection = emptySet() }) { Text("取消") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { exportSelected(selection) }) { Text("导出所选") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    startImport(selection)
                                } else {
                                    writeContactsLauncher.launch(Manifest.permission.WRITE_CONTACTS)
                                }
                            }) { Text("导入所选") }
                        }
                    }
                }
            }
        }
    }
}
