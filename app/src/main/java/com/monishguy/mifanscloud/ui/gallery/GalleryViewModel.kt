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
import java.io.File
import java.io.OutputStream
import java.text.Collator
import java.util.Locale

/** 相册模块 UI 状态。 */
sealed interface GalleryUiState {
    data object Idle : GalleryUiState
    data object Loading : GalleryUiState

    /** 相册列表（按名称文字排序）。 */
    data class Albums(val albums: List<RemoteAlbum>) : GalleryUiState

    /** 全部照片（合并各相册，按 dateTaken 新旧降序，最新在最顶）。 */
    data class Photos(
        val assets: List<AssetRow>,
        val loading: Boolean,
    ) : GalleryUiState

    /** 相册内资产网格（按 dateTaken 新旧降序）：每条带匹配状态。 */
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

/** 文字排序：中文按拼音、英文按字母（Collator）。 */
private val ALBUM_NAME_ORDER: Comparator<RemoteAlbum> =
    compareBy(Collator.getInstance(Locale.CHINA)) { it.name }

/** 新旧排序：dateTaken 降序，最新在最顶。 */
private val NEWEST_FIRST: Comparator<AssetRow> =
    compareByDescending<AssetRow> { it.asset.dateTaken }
        .thenByDescending { it.asset.id }

/**
 * 相册同步 ViewModel：
 * - 相册列表按名称文字排序；
 * - 相册内 / 全部照片按 dateTaken 新旧降序（最新在最顶，对齐用户需求 3）；
 * - 纯缩略图浏览（云端清单自带），原图仅查看/下载时经签名直链拉取；
 * - 批量下载按选中顺序逐个写入 SAF 输出流。
 * 数据源 seam：注入 [GalleryApi] / [LocalMediaSource] / [DownloadedStore]。
 */
class GalleryViewModel(
    private val galleryApi: GalleryApi,
    private val localMediaSource: LocalMediaSource,
    private val downloadedStore: DownloadedStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 板块缓存代际：清除凭证后变化，缓存失效需重载。 */
    private val cacheVersion: () -> Int = { 0 },
) : ViewModel() {

    private val _state = MutableStateFlow<GalleryUiState>(GalleryUiState.Idle)
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    @Volatile
    private var loadedGeneration: Int? = null

    /** 首次进入（或凭证清除后）才加载，其余复用缓存。 */
    fun loadOnce() {
        val generation = cacheVersion()
        if (loadedGeneration == generation) return
        loadedGeneration = generation
        loadAlbums()
    }

    /** 拉取云端相册列表，按名称文字排序（仅元数据 + 封面缩略图）。 */
    fun loadAlbums() {
        viewModelScope.launch {
            _state.value = GalleryUiState.Loading
            _state.value = withContext(ioDispatcher) {
                runCatching { galleryApi.fetchAlbums().sortedWith(ALBUM_NAME_ORDER) }
            }.fold(
                onSuccess = { GalleryUiState.Albums(it) },
                onFailure = { GalleryUiState.Error(it.message ?: "拉取相册失败") },
            )
        }
    }

    /**
     * 拉取全部照片：合并各普通相册资产，按 dateTaken 新旧降序（最新在最顶）。
     * 私密相册不并入（需密码单独进入）。
     */
    fun loadAllPhotos() {
        viewModelScope.launch {
            _state.value = GalleryUiState.Photos(emptyList(), loading = true)
            val result = withContext(ioDispatcher) {
                runCatching {
                    val albums = galleryApi.fetchAlbums().filterNot { it.isPrivate }
                    val all = albums.flatMap { album ->
                        galleryApi.fetchAssets(album.albumId)
                    }
                    matchRows(all)
                }
            }
            _state.value = result.fold(
                onSuccess = { GalleryUiState.Photos(it, loading = false) },
                onFailure = { GalleryUiState.Error(it.message ?: "拉取照片失败") },
            )
        }
    }

    /**
     * 拉取相册资产清单（含缩略图，不下载原图），与本机媒体库匹配，
     * 按 dateTaken 新旧降序。
     */
    fun loadAlbum(album: RemoteAlbum) {
        viewModelScope.launch {
            _state.value = GalleryUiState.AlbumAssets(album, emptyList(), loading = true)
            val result = withContext(ioDispatcher) {
                runCatching {
                    matchRows(galleryApi.fetchAssets(album.albumId))
                }
            }
            _state.value = result.fold(
                onSuccess = { GalleryUiState.AlbumAssets(album, it, loading = false) },
                onFailure = { GalleryUiState.Error(it.message ?: "拉取资产失败") },
            )
        }
    }

    /** 拉取原图到 cacheDir 缓存文件（查看器用）；已缓存则直接复用。失败返回 null。 */
    suspend fun loadOriginal(asset: RemoteAsset, cacheDir: File): File? =
        withContext(ioDispatcher) {
            runCatching {
                val ext = asset.fileName.substringAfterLast('.', "jpg")
                    .takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) } ?: "jpg"
                val file = File(cacheDir, "photo_${asset.id}.$ext")
                if (!file.exists() || file.length() == 0L) {
                    if (!galleryApi.download(asset.id, file.outputStream())) return@runCatching null
                }
                if (file.length() == 0L) null else file
            }.getOrNull()
        }

    /**
     * 按需下载单张原图：签名直链流式写入 [outputProvider] 提供的输出流；
     * 成功后记录到 [DownloadedStore] 并刷新当前状态。
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
            }
            updateRow(asset.id) { it.copy(downloading = false) }
            onCompleted()
            refreshCurrent()
        }
    }

    /**
     * 批量顺序下载（长按多选后调用）：按传入顺序逐个下载，
     * 逐个汇报进度 [onProgress]（done/total），全部结束后 [onCompleted] 并刷新。
     * [outputProvider] 返回 null 表示该文件无法创建（跳过，不算失败）。
     */
    fun downloadAssets(
        assets: List<RemoteAsset>,
        outputProvider: (RemoteAsset) -> OutputStream?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onCompleted: () -> Unit,
    ) {
        viewModelScope.launch {
            assets.forEachIndexed { index, asset ->
                updateRow(asset.id) { it.copy(downloading = true) }
                val ok = withContext(ioDispatcher) {
                    val out = outputProvider(asset) ?: return@withContext false
                    runCatching { out.use { galleryApi.download(asset.id, it) } }
                        .getOrDefault(false)
                }
                if (ok) downloadedStore.add(GALLERY_NS, asset.id, asset.fileName)
                updateRow(asset.id) { it.copy(downloading = false) }
                onProgress(index + 1, assets.size)
            }
            onCompleted()
            refreshCurrent()
        }
    }

    private fun matchRows(assets: List<RemoteAsset>): List<AssetRow> {
        val local = localMediaSource.queryImagesAndVideos()
        val statuses = CloudLocalMatcher.match(assets, local, downloadedStore.ids(GALLERY_NS))
        return assets
            .map { AssetRow(it, statuses[it.id] ?: MatchStatus.NEW) }
            .sortedWith(NEWEST_FIRST)
    }

    /** 刷新当前状态（相册内 / 全部照片），供下载后更新徽标。 */
    private fun refreshCurrent() {
        when (val current = _state.value) {
            is GalleryUiState.AlbumAssets -> loadAlbum(current.album)
            is GalleryUiState.Photos -> loadAllPhotos()
            else -> Unit
        }
    }

    private fun updateRow(assetId: String, transform: (AssetRow) -> AssetRow) {
        val current = _state.value
        _state.value = when (current) {
            is GalleryUiState.AlbumAssets -> current.copy(
                assets = current.assets.map { if (it.asset.id == assetId) transform(it) else it },
            )

            is GalleryUiState.Photos -> current.copy(
                assets = current.assets.map { if (it.asset.id == assetId) transform(it) else it },
            )

            else -> current
        }
    }

    /** AppContainer 装配工厂。 */
    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GalleryViewModel(
                galleryApi = container.galleryApi,
                localMediaSource = container.localMediaSource,
                downloadedStore = container.downloadedStore,
                cacheVersion = container.cacheVersion,
            ) as T
    }

    private companion object {
        const val GALLERY_NS = "gallery"
    }
}
