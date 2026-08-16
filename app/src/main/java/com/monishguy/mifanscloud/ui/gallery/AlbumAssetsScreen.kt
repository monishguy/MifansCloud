package com.monishguy.mifanscloud.ui.gallery

import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
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
import com.monishguy.mifanscloud.data.gallery.RemoteAlbum
import com.monishguy.mifanscloud.data.gallery.RemoteAsset
import com.monishguy.mifanscloud.data.local.DownloadNotifier
import com.monishguy.mifanscloud.data.local.SafHelper
import com.monishguy.mifanscloud.data.local.SaveDirStore
import com.monishguy.mifanscloud.data.local.SaveSection
import com.monishguy.mifanscloud.data.sync.MatchStatus

/**
 * 相册内资产页（需求 3）：
 * - 照片按 dateTaken 新旧降序（最新在顶，VM 已排序）；
 * - 单击照片 → 放大查看全图（查看器内可下载原图）；
 * - 长按照片 → 多选 → 批量顺序下载；
 * - 缩略图徽标：本机已有 / 云端新增 / 已下载。
 */
@Composable
fun AlbumAssetsScreen(
    viewModel: GalleryViewModel,
    album: RemoteAlbum,
    saveDirStore: SaveDirStore,
    onBack: () -> Unit,
    onOpenPhoto: (rows: List<AssetRow>, index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    var treeUri by remember { mutableStateOf(saveDirStore.get(SaveSection.ALBUM)) }
    var folderError by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
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
                saveDirStore.set(SaveSection.ALBUM, uri.toString())
                treeUri = uri.toString()
                folderError = null
            }.onFailure { folderError = it.message }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> viewModel.ensureAlbum(album) }

    LaunchedEffect(Unit) {
        val needed = mediaPermissions()
        if (needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            viewModel.ensureAlbum(album)
        } else {
            permissionLauncher.launch(needed)
        }
    }

    BackHandler { onBack() }
    val state by viewModel.state.collectAsState()

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text(album.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
        }
        val rows = (state as? GalleryUiState.AlbumAssets)?.assets
        if (rows != null) {
            val local = rows.count { it.status == MatchStatus.LOCAL_ALREADY }
            val new = rows.count { it.status == MatchStatus.NEW }
            val downloaded = rows.count { it.status == MatchStatus.DOWNLOADED }
            Text(
                "本机已有 $local · 云端新增 $new · 已下载 $downloaded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        folderError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))

        when (val s = state) {
            is GalleryUiState.AlbumAssets -> {
                if (s.stale) {
                    Text(
                        "网络刷新失败，当前显示上次缓存",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (s.loading && s.assets.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    AssetGrid(
                        rows = s.assets,
                        selection = selection,
                        onToggle = { id ->
                            selection = if (id in selection) selection - id else selection + id
                        },
                        onOpen = { asset ->
                            val currentRows = s.assets
                            onOpenPhoto(currentRows, currentRows.indexOfFirst { it.asset.id == asset.id })
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            GalleryUiState.Loading, GalleryUiState.Idle -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is GalleryUiState.Error -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.loadAlbum(album) }) { Text("重试") }
            }

            else -> Unit // Albums/Photos 状态由板块首页渲染
        }

        if (selection.isNotEmpty() || progress != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    progress?.let { (done, total) -> "下载中 $done/$total" } ?: "已选 ${selection.size} 张",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row {
                    if (progress == null) {
                        OutlinedButton(onClick = { selection = emptySet() }) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            val folder = treeUri
                            if (folder == null) {
                                folderError = "请先选择备份文件夹"
                                pickFolder.launch(null)
                            } else {
                                val assets = rows.orEmpty().map { it.asset }.filter { it.id in selection }
                                progress = 0 to assets.size
                                notifId = DownloadNotifier.start(context, "批量下载", "0/${assets.size}")
                                viewModel.downloadAssets(
                                    assets = assets,
                                    outputProvider = { asset ->
                                        SafHelper.createDocument(
                                            contentResolver, folder,
                                            asset.mimeType.ifBlank { "application/octet-stream" },
                                            sanitizeFileName(asset.fileName),
                                        )?.let { contentResolver.openOutputStream(it) }
                                    },
                                    onProgress = { done, total ->
                                        progress = done to total
                                        DownloadNotifier.update(
                                            context, notifId, "批量下载", "$done/$total", done, total,
                                        )
                                    },
                                    onCompleted = {
                                        progress = null
                                        selection = emptySet()
                                        DownloadNotifier.finish(
                                            context, notifId, "批量下载完成",
                                            "已保存 ${assets.size} 张照片", success = true,
                                        )
                                        notifId = null
                                    },
                                )
                            }
                        }) { Text("下载") }
                    }
                }
            }
        }
    }
}
