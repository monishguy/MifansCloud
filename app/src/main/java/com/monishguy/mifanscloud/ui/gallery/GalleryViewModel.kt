package com.monishguy.mifanscloud.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.monishguy.mifanscloud.AppContainer
import com.monishguy.mifanscloud.data.gallery.GalleryApi
import com.monishguy.mifanscloud.data.gallery.RemoteAlbum
import com.monishguy.mifanscloud.data.gallery.RemoteAsset
import com.monishguy.mifanscloud.data.local.GalleryMetadataCache
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

    /** 相册列表（按名称文字排序；[fromCache] 表示先显示的本地缓存，网络刷新中）。 */
    data class Albums(
        val albums: List<RemoteAlbum>,
        val fromCache: Boolean = false,
    ) : GalleryUiState

    /** 全部照片（合并各相册，按 dateTaken 新旧降序，最新在最顶）。 */
    data class Photos(
        val assets: List<AssetRow>,
        val loading: Boolean,
        val stale: Boolean = false,
        /** 拉取失败的相册数（503 等，已跳过）。 */
        val failedAlbums: Int = 0,
    ) : GalleryUiState

    /** 相册内资产网格（按 dateTaken 新旧降序）：每条带匹配状态。 */
    data class AlbumAssets(
        val album: RemoteAlbum,
        val assets: List<AssetRow>,
        val loading: Boolean,
        val stale: Boolean = false,
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
 * - 相册列表按名称文字排序；相册内 / 全部照片按 dateTaken 新旧降序；
 * - 按 userId 持久化元数据缓存：先进缓存（秒开）再网络刷新；
 * - 全部照片合并时逐相册容错：单个相册 503/无 data 跳过，不影响整体；
 * - 原图仅查看/下载时经签名直链拉取（含 magic bytes 校验防错误页）。
 */
class GalleryViewModel(
    private val galleryApi: GalleryApi,
    private val localMediaSource: LocalMediaSource,
    private val downloadedStore: DownloadedStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 板块缓存代际：清除凭证后变化，缓存失效需重载。 */
    private val cacheVersion: () -> Int = { 0 },
    /** 相册元数据持久化缓存（按 userId 隔离），null 表示禁用（测试）。 */
    private val metadataCache: GalleryMetadataCache? = null,
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

    /** 拉取相册列表：本地缓存秒开 → 网络刷新 → 写缓存。 */
    fun loadAlbums() {
        viewModelScope.launch {
            // 1) 本地缓存先显（同一账号重进秒开，至少名称可见）
            withContext(ioDispatcher) { metadataCache?.loadAlbums() }?.let { cached ->
                _state.value = GalleryUiState.Albums(cached.sortedWith(ALBUM_NAME_ORDER), fromCache = true)
            }
            if (_state.value !is GalleryUiState.Albums) {
                _state.value = GalleryUiState.Loading
            }
            // 2) 网络刷新
            val fresh = withContext(ioDispatcher) {
                runCatching { galleryApi.fetchAlbums().sortedWith(ALBUM_NAME_ORDER) }
            }
            fresh.onSuccess { albums ->
                _state.value = GalleryUiState.Albums(albums)
                withContext(ioDispatcher) { metadataCache?.saveAlbums(albums) }
            }
            fresh.onFailure {
                val current = _state.value
                if (current !is GalleryUiState.Albums || current.albums.isEmpty()) {
                    _state.value = GalleryUiState.Error(it.message ?: "拉取相册失败")
                }
                // 有缓存则保留缓存显示（fromCache 已在上面标记）
            }
        }
    }

    /**
     * 拉取全部照片：合并各普通相册资产，按 dateTaken 新旧降序。
     * 单个相册拉取失败（503/无 data）跳过并计数 [GalleryUiState.Photos.failedAlbums]；
     * 全部失败时回退持久化缓存。
     */
    fun loadAllPhotos() {
        viewModelScope.launch {
            _state.value = GalleryUiState.Photos(emptyList(), loading = true)
            val result = withContext(ioDispatcher) {
                runCatching {
                    val albums = galleryApi.fetchAlbums()
                        .filterNot { it.isPrivate }
                        .filter { it.albumId.isNotBlank() }
                    var failed = 0
                    val all = mutableListOf<RemoteAsset>()
                    albums.forEach { album ->
                        runCatching { galleryApi.fetchAssets(album.albumId) }
                            .onSuccess { all += it }
                            .onFailure { failed++ }
                    }
                    Triple(matchRows(all), failed, all)
                }
            }
            result.fold(
                onSuccess = { (rows, failed, allAssets) ->
                    _state.value = GalleryUiState.Photos(rows, loading = false, failedAlbums = failed)
                    withContext(ioDispatcher) {
                        metadataCache?.saveAllPhotos(allAssets)
                    }
                },
                onFailure = { error ->
                    val cached = withContext(ioDispatcher) { metadataCache?.loadAllPhotos() }
                    if (cached != null) {
                        _state.value = GalleryUiState.Photos(
                            matchRows(cached), loading = false, stale = true,
                        )
                    } else {
                        _state.value = GalleryUiState.Error(error.message ?: "拉取照片失败")
                    }
                },
            )
        }
    }

    /**
     * 拉取相册资产清单（含缩略图，不下载原图），与本机媒体库匹配，
     * 按 dateTaken 新旧降序；网络失败回退持久化缓存（stale 标记）。
     */
    fun loadAlbum(album: RemoteAlbum) {
        viewModelScope.launch {
            _state.value = GalleryUiState.AlbumAssets(album, emptyList(), loading = true)
            val result = withContext(ioDispatcher) {
                runCatching { galleryApi.fetchAssets(album.albumId) }
            }
            result.fold(
                onSuccess = { assets ->
                    _state.value = GalleryUiState.AlbumAssets(album, matchRows(assets), loading = false)
                    withContext(ioDispatcher) { metadataCache?.saveAssets(album.albumId, assets) }
                },
                onFailure = { error ->
                    val cached = withContext(ioDispatcher) { metadataCache?.loadAssets(album.albumId) }
                    if (cached != null) {
                        _state.value = GalleryUiState.AlbumAssets(
                            album, matchRows(cached), loading = false, stale = true,
                        )
                    } else {
                        _state.value = GalleryUiState.Error(error.message ?: "拉取资产失败")
                    }
                },
            )
        }
    }

    /**
     * 拉取原图到 cacheDir 缓存文件（查看器用）；已缓存则复用。
     * 校验文件头（JPEG/PNG/GIF/HEIC），错误页（HTML/JSON）视为失败并清除。
     */
    suspend fun loadOriginal(asset: RemoteAsset, cacheDir: File): File? =
        withContext(ioDispatcher) {
            runCatching {
                val ext = asset.fileName.substringAfterLast('.', "jpg")
                    .takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) } ?: "jpg"
                val file = File(cacheDir, "photo_${asset.id}.$ext")
                if (!file.exists() || file.length() == 0L) {
                    if (!galleryApi.download(asset.id, file.outputStream())) return@runCatching null
                }
                if (file.length() == 0L) null
                else if (looksLikeImage(file)) file
                else {
                    file.delete()
                    null
                }
            }.getOrNull()
        }

    /** 文件头魔数校验：防把 JSON/HTML 错误页当图片交给 Coil 无限加载。 */
    private fun looksLikeImage(file: File): Boolean =
        file.inputStream().use { input ->
            val head = ByteArray(12)
            val n = input.read(head)
            when {
                n >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> true // JPEG
                n >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() &&
                    head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte() -> true // PNG
                n >= 4 && head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() &&
                    head[2] == 'F'.code.toByte() -> true // GIF
                n >= 12 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
                    head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte() -> true // HEIC
                else -> false
            }
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
                metadataCache = container.galleryMetadataCache,
            ) as T
    }

    private companion object {
        const val GALLERY_NS = "gallery"
    }
}
