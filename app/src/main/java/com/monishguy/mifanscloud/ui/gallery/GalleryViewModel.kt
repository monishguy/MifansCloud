package com.monishguy.mifanscloud.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.monishguy.mifanscloud.AppContainer
import com.monishguy.mifanscloud.data.gallery.GalleryApi
import com.monishguy.mifanscloud.data.gallery.RemoteAlbum
import com.monishguy.mifanscloud.data.gallery.RemoteAsset
import com.monishguy.mifanscloud.data.sync.CloudLocalMatcher
import com.monishguy.mifanscloud.data.sync.DownloadedStore
import com.monishguy.mifanscloud.data.sync.LocalMediaSource
import com.monishguy.mifanscloud.data.sync.MatchStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** 相册模块 UI 状态。 */
sealed interface GalleryUiState {
    data object Idle : GalleryUiState
    data object Loading : GalleryUiState

    data class Albums(val albums: List<RemoteAlbum>) : GalleryUiState

    /** 相册内资产网格：每条带匹配状态（本机已有/新增/已下载）。 */
    data class AlbumAssets(
        val album: RemoteAlbum,
        val assets: List<AssetRow>,
        val loading: Boolean,
    ) : GalleryUiState

    data class Error(val message: String) : GalleryUiState
}

/** 资产行：云端资产 + 匹配状态 + 下载进度。 */
data class AssetRow(
    val asset: RemoteAsset,
    val status: MatchStatus,
    val downloading: Boolean = false,
)

/**
 * 相册同步 ViewModel（M3 智能同步）：
 * 云端清单（含缩略图）→ 本机两级匹配 → 按需下载原图。
 * 数据源 seam：注入 [GalleryApi] / [LocalMediaSource] / [DownloadedStore]，
 * 测试可替换为 MockWebServer + fake。
 */
class GalleryViewModel(
    private val galleryApi: GalleryApi,
    private val localMediaSource: LocalMediaSource,
    private val downloadedStore: DownloadedStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<GalleryUiState>(GalleryUiState.Idle)
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    /** 拉取云端相册列表（仅元数据 + 封面缩略图）。 */
    fun loadAlbums() {
        viewModelScope.launch {
            _state.value = GalleryUiState.Loading
            _state.value = withContext(ioDispatcher) {
                runCatching { galleryApi.fetchAlbums() }
            }.fold(
                onSuccess = { GalleryUiState.Albums(it) },
                onFailure = { GalleryUiState.Error(it.message ?: "拉取相册失败") },
            )
        }
    }

    /**
     * 拉取相册资产清单（含缩略图，不下载原图），与本机媒体库匹配，
     * 标记每条：本机已有 / 云端新增 / 已下载到本工具。
     */
    fun loadAlbum(album: RemoteAlbum) {
        viewModelScope.launch {
            _state.value = GalleryUiState.AlbumAssets(album, emptyList(), loading = true)
            val result = withContext(ioDispatcher) {
                runCatching {
                    val assets = galleryApi.fetchAssets(album.albumId)
                    val local = localMediaSource.queryImagesAndVideos()
                    val statuses = CloudLocalMatcher.match(assets, local, downloadedStore.ids(GALLERY_NS))
                    assets.map { asset ->
                        AssetRow(asset, statuses[asset.id] ?: MatchStatus.NEW)
                    }
                }
            }
            _state.value = result.fold(
                onSuccess = { GalleryUiState.AlbumAssets(album, it, loading = false) },
                onFailure = { GalleryUiState.Error(it.message ?: "拉取资产失败") },
            )
        }
    }

    /**
     * 按需下载原图：签名直链流式写入 [outputProvider] 提供的输出流；
     * 成功后记录到 [DownloadedStore] 并刷新状态（复用媒体库匹配逻辑）。
     */
    fun downloadAsset(asset: RemoteAsset, outputProvider: () -> OutputStream, onCompleted: () -> Unit) {
        viewModelScope.launch {
            updateRow(asset.id) { it.copy(downloading = true) }
            val ok = withContext(ioDispatcher) {
                runCatching {
                    outputProvider().use { out -> galleryApi.download(asset.id, out) }
                }.getOrDefault(false)
            }
            if (ok) {
                downloadedStore.add(GALLERY_NS, asset.id, asset.fileName)
                onCompleted()
                loadAlbum((_state.value as? GalleryUiState.AlbumAssets)?.album ?: return@launch)
            } else {
                updateRow(asset.id) { it.copy(downloading = false) }
            }
        }
    }

    private fun updateRow(assetId: String, transform: (AssetRow) -> AssetRow) {
        val current = _state.value as? GalleryUiState.AlbumAssets ?: return
        _state.value = current.copy(
            assets = current.assets.map { if (it.asset.id == assetId) transform(it) else it },
        )
    }

    /** AppContainer 装配工厂。 */
    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GalleryViewModel(
                galleryApi = container.galleryApi,
                localMediaSource = container.localMediaSource,
                downloadedStore = container.downloadedStore,
            ) as T
    }

    private companion object {
        const val GALLERY_NS = "gallery"
    }
}
