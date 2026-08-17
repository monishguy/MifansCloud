package com.monishguy.mifanscloud.ui.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.monishguy.mifanscloud.data.gallery.RemoteAlbum
import com.monishguy.mifanscloud.data.gallery.RemoteAsset
import com.monishguy.mifanscloud.data.local.DownloadNotifier
import com.monishguy.mifanscloud.data.local.SafHelper
import com.monishguy.mifanscloud.data.local.SaveSection
import com.monishguy.mifanscloud.data.local.SaveDirStore

/** 相册板块子底栏：照片（默认） / 相册。 */
private enum class GalleryTab(val label: String) {
    PHOTOS("照片"),
    ALBUMS("相册"),
}

/**
 * 相册板块首页（需求 3）：
 * - 子底栏「照片 / 相册」，默认照片页；
 * - 照片页：全部照片按新旧排序（最新在顶），可上传、可长按多选批量下载；
 * - 相册页：按名称文字排序，私密相册点入需密码，点相册进入相册内页。
 */
@Composable
fun GallerySectionScreen(
    viewModel: GalleryViewModel,
    saveDirStore: SaveDirStore,
    onBack: () -> Unit,
    onOpenAlbum: (RemoteAlbum) -> Unit,
    onOpenPhoto: (rows: List<AssetRow>, index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(GalleryTab.PHOTOS) }

    LaunchedEffect(Unit) { viewModel.loadOnce() }

    BackHandler { onBack() }

    Scaffold(
        bottomBar = {
            NavigationBar {
                GalleryTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                if (t == GalleryTab.PHOTOS) Icons.Filled.Add else Icons.Filled.Lock,
                                contentDescription = t.label,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (tab) {
                GalleryTab.PHOTOS -> PhotosTab(
                    viewModel = viewModel,
                    saveDirStore = saveDirStore,
                    context = context,
                    onOpenPhoto = onOpenPhoto,
                )

                GalleryTab.ALBUMS -> AlbumsTab(
                    viewModel = viewModel,
                    onOpenAlbum = onOpenAlbum,
                )
            }
        }
    }
}

/** 照片页：全部照片（dateTaken 降序，最新在顶）+ 上传入口 + 长按多选批量下载。 */
@Composable
private fun PhotosTab(
    viewModel: GalleryViewModel,
    saveDirStore: SaveDirStore,
    context: android.content.Context,
    onOpenPhoto: (rows: List<AssetRow>, index: Int) -> Unit,
) {
    val contentResolver = context.contentResolver
    val state by viewModel.state.collectAsState()

    var treeUri by remember { mutableStateOf(saveDirStore.get(SaveSection.ALBUM)) }
    var folderError by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var notifId by remember { mutableStateOf<Int?>(null) }

    // 上传流程
    var uploadTarget by remember { mutableStateOf<RemoteAlbum?>(null) }
    var uploadHint by remember { mutableStateOf<String?>(null) }
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            uploadTarget = (state as? GalleryUiState.Albums)?.albums
                ?.firstOrNull { !it.isPrivate }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> viewModel.ensurePhotos() }

    LaunchedEffect(Unit) {
        // 媒体读取 + 通知权限（Android 13+）
        val needed = mediaPermissions().toMutableList()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            viewModel.ensurePhotos()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

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

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("全部照片", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = {
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Icon(Icons.Filled.Add, contentDescription = "上传照片")
            }
        }
        folderError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        uploadHint?.let {
            Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))

        when (val s = state) {
            is GalleryUiState.Photos -> {
                if (s.stale) {
                    Text(
                        "网络刷新失败，当前显示上次缓存",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (s.failedAlbums > 0) {
                    Text(
                        "${s.failedAlbums} 个相册拉取失败（可能限流），已跳过",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                // 渐进式加载进度横幅（有数据也不转圈，只显示进度）
                if (s.loading) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            s.progressText ?: "正在加载照片…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (s.assets.isEmpty() && s.progressText != null) {
                        Text(
                            "首次拉取全部照片可能需要 1-2 分钟",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (s.failedAlbums > 0) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${s.failedAlbums} 个相册拉取失败（可能限流）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.retryFailedAlbums() },
                            modifier = Modifier.height(28.dp),
                        ) { Text("重试失败项", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                if (s.loading && s.assets.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "正在加载全部照片，请稍候…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (s.assets.isEmpty() && !s.loading) {
                    // 空态：明确提示 + 重试（不做空白页）
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("未拉取到照片", style = MaterialTheme.typography.bodyLarge)
                        if (s.failedAlbums > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${s.failedAlbums} 个相册拉取失败（可能限流）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.loadAllPhotos() }) { Text("重新拉取") }
                    }
                } else {
                    AssetGrid(
                        rows = s.assets,
                        selection = selection,
                        onToggle = { id ->
                            selection = if (id in selection) selection - id else selection + id
                        },
                        onOpen = { asset ->
                            val rows = s.assets
                            onOpenPhoto(rows, rows.indexOfFirst { it.asset.id == asset.id })
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
                Button(onClick = { viewModel.loadAllPhotos() }) { Text("重试") }
            }

            else -> Unit // Albums/AlbumAssets 状态由其它页渲染
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
                                val rows = (state as? GalleryUiState.Photos)?.assets.orEmpty()
                                val assets = rows.map { it.asset }.filter { it.id in selection }
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

    // 上传目标相册选择（接口待逆向，诚实提示）
    uploadTarget?.let { target ->
        var picked by remember(target) { mutableStateOf(target) }
        AlertDialog(
            onDismissRequest = { uploadTarget = null },
            title = { Text("上传到哪个相册？") },
            text = {
                val albums = (state as? GalleryUiState.Albums)?.albums.orEmpty()
                Column {
                    albums.filterNot { it.isPrivate }.forEach { album ->
                        Row(
                            Modifier.fillMaxWidth().clickable { picked = album }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                color = if (album == picked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                shape = androidx.compose.foundation.shape.CircleShape,
                                modifier = Modifier.width(18.dp).height(18.dp),
                            ) {}
                            Spacer(Modifier.width(10.dp))
                            Text(album.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val msg = "上传接口尚未逆向（三个参考项目均无上传实现，需要 i.mi.com 网页上传的抓包 HAR），当前版本无法上传"
                    uploadHint = msg
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    uploadTarget = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { uploadTarget = null }) { Text("取消") }
            },
        )
    }
}

/** 相册页：按名称文字排序；私密相册点入需输入密码。 */
@Composable
private fun AlbumsTab(
    viewModel: GalleryViewModel,
    onOpenAlbum: (RemoteAlbum) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var privatePassword by remember { mutableStateOf<RemoteAlbum?>(null) }
    var password by remember { mutableStateOf("") }

    // 切到相册 tab：有内存缓存直接显示，不发网络
    LaunchedEffect(Unit) { viewModel.ensureAlbums() }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("云端相册 · 按名称排序", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        when (val s = state) {
            is GalleryUiState.Albums -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(s.albums) { album ->
                    AlbumCard(album = album, onClick = {
                        if (album.isPrivate) {
                            password = ""
                            privatePassword = album
                        } else {
                            onOpenAlbum(album)
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
                Button(onClick = { viewModel.loadAlbums() }) { Text("重试") }
            }

            else -> Unit
        }
    }

    // 私密相册密码门：输入后尝试拉取（云端是否支持此方式访问未验证，失败时诚实报错）
    privatePassword?.let { album ->
        AlertDialog(
            onDismissRequest = { privatePassword = null },
            title = { Text("私密相册") },
            text = {
                Column {
                    Text(
                        "输入私密相册密码后尝试打开。若云端不支持该访问方式会提示失败。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    privatePassword = null
                    onOpenAlbum(album)
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { privatePassword = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AlbumCard(album: RemoteAlbum, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Column {
            Box {
                AsyncImage(
                    model = album.coverUrls.firstOrNull(),
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    placeholder = androidx.compose.ui.graphics.painter.ColorPainter(Color(0xFFE8E8E8)),
                    error = androidx.compose.ui.graphics.painter.ColorPainter(Color(0xFFE8E8E8)),
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                )
                if (album.isPrivate) {
                    Surface(
                        color = Color(0x99000000),
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "私密相册",
                            tint = Color.White,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
            Column(Modifier.padding(8.dp)) {
                Text(
                    album.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    "${album.mediaCount} 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
