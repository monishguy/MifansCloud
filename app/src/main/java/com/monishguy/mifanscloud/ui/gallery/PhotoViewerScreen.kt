package com.monishguy.mifanscloud.ui.gallery

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import coil.compose.AsyncImage
import com.monishguy.mifanscloud.data.gallery.RemoteAsset
import com.monishguy.mifanscloud.data.local.SafHelper
import com.monishguy.mifanscloud.data.local.SaveDirStore
import com.monishguy.mifanscloud.data.local.SaveSection
import java.io.File

/**
 * 全屏原图查看器（需求 3）：
 * - 打开时经签名直链拉取原图到缓存，Coil 全屏展示（可捏合缩放）；
 * - 右下角无文字下载按钮：保存原图到相册备份目录；
 * - 返回回到来源页。
 */
@Composable
fun PhotoViewerScreen(
    viewModel: GalleryViewModel,
    asset: RemoteAsset,
    saveDirStore: SaveDirStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    var originalFile by remember { mutableStateOf<File?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
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

    LaunchedEffect(asset.id) {
        originalFile = viewModel.loadOriginal(asset, context.cacheDir)
        loadFailed = originalFile == null
    }

    BackHandler { onBack() }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        val file = originalFile
        when {
            file != null -> AsyncImage(
                model = file,
                contentDescription = asset.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            loadFailed -> Text(
                "原图加载失败（可能已在云端删除）",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 顶栏：返回 + 文件名
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0x66000000))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
            Text(
                asset.fileName,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }

        // 右下角：无文字下载按钮（圆形）
        IconButton(
            onClick = {
                val folder = treeUri
                if (folder == null) {
                    folderError = "请先选择备份文件夹"
                    pickFolder.launch(null)
                } else {
                    val uri = SafHelper.createDocument(
                        contentResolver, folder,
                        asset.mimeType.ifBlank { "image/jpeg" },
                        sanitizeFileName(asset.fileName),
                    )
                    if (uri != null) {
                        viewModel.downloadAsset(
                            asset = asset,
                            outputProvider = { contentResolver.openOutputStream(uri)!! },
                            onCompleted = { hint = "已保存到备份目录" },
                        )
                    } else {
                        folderError = "无法在备份文件夹创建文件"
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Surface(color = Color(0xAA000000), shape = CircleShape) {
                Text(
                    "下载",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        // 底部提示（保存结果 / 目录错误）
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            hint?.let {
                Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
            folderError?.let {
                Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
