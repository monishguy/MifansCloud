package com.monishguy.mifanscloud.ui.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import com.monishguy.mifanscloud.data.local.SafHelper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.monishguy.mifanscloud.data.gallery.RemoteAlbum
import com.monishguy.mifanscloud.data.gallery.RemoteAsset
import com.monishguy.mifanscloud.data.local.SaveSection
import com.monishguy.mifanscloud.data.sync.MatchStatus

/**
 * 相册内资产网格：
 * - 纯缩略图浏览（清单自带 thumbnailInfo：URL 或内嵌 base64，Coil 加载）；
 * - 每条资产徽标：本机已有 / 云端新增 / 已下载到本工具；
 * - 点缩略图按需下载原图（首次需选择 SAF 备份文件夹）。
 */
@Composable
fun AlbumAssetsScreen(
    viewModel: GalleryViewModel,
    album: RemoteAlbum,
    saveDirStore: com.monishguy.mifanscloud.data.local.SaveDirStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    var treeUri by remember { mutableStateOf(saveDirStore.get(SaveSection.ALBUM)) }
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
                saveDirStore.set(SaveSection.ALBUM, uri.toString())
                treeUri = uri.toString()
                folderError = null
            }.onFailure { folderError = it.message }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> viewModel.loadAlbum(album) }

    LaunchedEffect(Unit) {
        val needed = mediaPermissions()
        if (needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            viewModel.loadAlbum(album)
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
            is GalleryUiState.AlbumAssets -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 130.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(s.assets) { row ->
                    AssetCell(row = row, onDownload = {
                        val folder = treeUri
                        if (folder == null) {
                            folderError = "请先选择备份文件夹"
                            pickFolder.launch(null)
                        } else {
                            val uri = createDocument(contentResolver, folder, row.asset)
                            if (uri != null) {
                                viewModel.downloadAsset(
                                    asset = row.asset,
                                    outputProvider = { contentResolver.openOutputStream(uri)!! },
                                    onCompleted = {},
                                )
                            } else {
                                folderError = "无法在备份文件夹创建文件"
                            }
                        }
                    })
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

            is GalleryUiState.Albums -> Unit // 相册列表页单独渲染
        }
    }
}

@Composable
private fun AssetCell(row: AssetRow, onDownload: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(enabled = row.status != MatchStatus.DOWNLOADED && !row.downloading) { onDownload() },
    ) {
        AsyncImage(
            model = thumbnailModel(row.asset),
            contentDescription = row.asset.title,
            contentScale = ContentScale.Crop,
            placeholder = ColorPainter(Color(0xFFE8E8E8)),
            error = ColorPainter(Color(0xFFE8E8E8)),
            modifier = Modifier.fillMaxSize(),
        )
        if (row.downloading) {
            Surface(
                color = Color(0x88000000),
                modifier = Modifier.align(Alignment.Center),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp).padding(4.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            }
        }
        StatusBadge(
            status = row.status,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp),
        )
    }
}

@Composable
private fun StatusBadge(status: MatchStatus, modifier: Modifier = Modifier) {
    val (text, color) = when (status) {
        MatchStatus.LOCAL_ALREADY -> "本机" to Color(0xFF2E7D32)
        MatchStatus.NEW -> "新增" to Color(0xFFC62828)
        MatchStatus.DOWNLOADED -> "已下" to Color(0xFF1565C0)
    }
    Surface(color = color, modifier = modifier) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 缩略图 Coil 模型：URL 直出；内嵌 base64 转 data URI；无缩略图时返回 null（显示占位）。 */
private fun thumbnailModel(asset: RemoteAsset): Any? {
    val info = asset.thumbnailInfo ?: return null
    val data = info.data
    return when {
        data.isNullOrBlank() -> null
        info.isUrl -> data
        else -> "data:image/jpeg;base64,$data"
    }
}

private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

/** 在 SAF 树里创建目标文件，返回可写 URI（tree 需先转 document URI，见 SafHelper）。 */
private fun createDocument(
    contentResolver: android.content.ContentResolver,
    treeUri: String,
    asset: RemoteAsset,
): Uri? = SafHelper.createDocument(
    contentResolver,
    treeUri,
    asset.mimeType.ifBlank { "application/octet-stream" },
    sanitizeFileName(asset.fileName),
)

private fun sanitizeFileName(name: String): String =
    name.replace(Regex("[/\\\\:*?\"<>|\\s]+"), "_")
