package com.monishguy.mifanscloud.ui.contact

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import com.monishguy.mifanscloud.data.local.DownloadNotifier
import com.monishguy.mifanscloud.data.local.SafHelper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.core.content.ContextCompat
import com.monishguy.mifanscloud.data.local.SaveDirStore
import com.monishguy.mifanscloud.data.local.SaveSection

/**
 * 通讯录板块页：按名字排序清单 + 导出 JSON + 导入本机系统通讯录。
 */
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

    // 导入本机通讯录：先申请 WRITE_CONTACTS，授权后执行导入
    fun startImport() {
        val notifId = DownloadNotifier.start(context, "通讯录导入", "正在导入…")
        viewModel.importToDevice(context) { count, error ->
            val msg = error ?: "已导入 $count 位联系人到本机通讯录"
            importHint = msg
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            DownloadNotifier.finish(
                context, notifId,
                if (error != null) "通讯录导入失败" else "通讯录导入完成", msg,
                success = error == null,
            )
        }
    }

    val writeContactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startImport()
        } else {
            val msg = "未授予通讯录写入权限，无法导入"
            importHint = msg
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
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
            Text("云端通讯录", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { viewModel.load() }) { Text("刷新") }
        }
        val contacts = (state as? ContactsUiState.Contacts)?.contacts
        if (contacts != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "共 ${contacts.size} 人 · 按名字排序",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(horizontal = 8.dp))
                OutlinedButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        startImport()
                    } else {
                        writeContactsLauncher.launch(Manifest.permission.WRITE_CONTACTS)
                    }
                }) { Text("导入本机") }
                Spacer(Modifier.padding(horizontal = 4.dp))
                OutlinedButton(onClick = {
                    val folder = treeUri
                    if (folder == null) {
                        folderError = "请先选择保存目录"
                        pickFolder.launch(null)
                    } else {
                        val uri = SafHelper.createDocument(
                            contentResolver, folder, "application/json", "contacts.json",
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

            is ContactsUiState.Contacts -> LazyColumn {
                items(s.contacts, key = { it.id }) { contact ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
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
}
