package com.monishguy.mifanscloud.ui.gallery

import android.Manifest
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.monishguy.mifanscloud.data.gallery.RemoteAsset
import com.monishguy.mifanscloud.data.gallery.ThumbnailInfo
import com.monishguy.mifanscloud.data.sync.MatchStatus

/**
 * 共享资产网格（照片页 / 相册内共用）：
 * - 单击：无选中态时打开放大查看器；
 * - 长按 / 多选态单击：切换选中（批量下载）；
 * - 每条徽标：本机已有 / 云端新增 / 已下载到本工具。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssetGrid(
    rows: List<AssetRow>,
    selection: Set<String>,
    onToggle: (String) -> Unit,
    onOpen: (RemoteAsset) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        items(rows, key = { it.asset.id }) { row ->
            AssetCell(
                row = row,
                selected = row.asset.id in selection,
                onClick = {
                    if (selection.isEmpty()) onOpen(row.asset) else onToggle(row.asset.id)
                },
                onLongClick = { onToggle(row.asset.id) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssetCell(
    row: AssetRow,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66156FC0))
                    .border(3.dp, Color(0xFF1565C0)),
            )
            Surface(
                color = Color(0xFF1565C0),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            ) {
                androidx.compose.material3.Icon(
                    Icons.Filled.Check,
                    contentDescription = "已选",
                    tint = Color.White,
                    modifier = Modifier.padding(3.dp).size(14.dp),
                )
            }
        } else {
            StatusBadge(
                status = row.status,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
            )
        }
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
fun thumbnailModel(asset: RemoteAsset): Any? {
    val info: ThumbnailInfo = asset.thumbnailInfo ?: return null
    val data = info.data
    return when {
        data.isNullOrBlank() -> null
        info.isUrl -> data
        else -> "data:image/jpeg;base64,$data"
    }
}

/** 媒体读取权限（Android 13+ 分区媒体）。 */
fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

/** 文件名清洗：去掉路径分隔符与非法字符。 */
fun sanitizeFileName(name: String): String =
    name.replace(Regex("[/\\\\:*?\"<>|\\s]+"), "_")
