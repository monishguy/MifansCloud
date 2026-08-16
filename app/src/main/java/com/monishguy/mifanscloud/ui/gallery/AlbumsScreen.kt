package com.monishguy.mifanscloud.ui.gallery

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.monishguy.mifanscloud.data.gallery.RemoteAlbum

/**
 * 相册列表页：封面缩略图 + 名称 + 数量（仅元数据，不下载任何原图）。
 */
@Composable
fun AlbumsScreen(
    viewModel: GalleryViewModel,
    onOpenAlbum: (RemoteAlbum) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadAlbums() }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("云端相册", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { viewModel.loadAlbums() }) { Text("刷新") }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "仅浏览缩略图，不批量下载原图；点缩略图才按需下载。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        when (val s = state) {
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

            is GalleryUiState.Albums -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(s.albums) { album ->
                    AlbumCard(album = album, onClick = { onOpenAlbum(album) })
                }
            }

            is GalleryUiState.AlbumAssets -> Unit // 相册内页面单独渲染
        }
    }
}

@Composable
private fun AlbumCard(album: RemoteAlbum, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Column {
            AsyncImage(
                model = album.coverUrls.firstOrNull(),
                contentDescription = album.name,
                contentScale = ContentScale.Crop,
                placeholder = androidx.compose.ui.graphics.painter.ColorPainter(Color(0xFFE8E8E8)),
                error = androidx.compose.ui.graphics.painter.ColorPainter(Color(0xFFE8E8E8)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
            )
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
