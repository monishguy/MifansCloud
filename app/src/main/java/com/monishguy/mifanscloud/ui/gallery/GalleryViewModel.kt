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
import kotlinx.coroutines.delay
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
        /** 失败相册 id（用于「重试失败项」）。 */
        val failedAlbumIds: List<String> = emptyList(),
        /** 渐进式加载进度文案（如「正在加载 第 3/12 个相册 · 已获取 1520 张」）。 */
        val progressText: String? = null,
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

    /** tab 级内存缓存：切「照片/相册」tab 不重复网络加载、页面不闪。 */
    private var albumsCache: List<RemoteAlbum>? = null
    private var photosCache: List<AssetRow>? = null
    private val albumAssetsCache = mutableMapOf<String, List<AssetRow>>()

    /** 本机媒体库匹配缓存：同一凭证代际只扫一次 MediaStore（近万张本地照片扫描很贵）。 */
    @Volatile
    private var localMediaCache: List<com.monishguy.mifanscloud.data.sync.LocalMedia>? = null
    private var localCacheGeneration: Int = -1

    private fun localMedia(): List<com.monishguy.mifanscloud.data.sync.LocalMedia> {
        val gen = cacheVersion()
        val cached = localMediaCache
        if (cached != null && localCacheGeneration == gen) return cached
        return localMediaSource.queryImagesAndVideos().also {
            localMediaCache = it
            localCacheGeneration = gen
        }
    }

    /** 首次进入（或凭证清除后）才加载，其余复用缓存。 */
    fun loadOnce() {
        val generation = cacheVersion()
        if (loadedGeneration == generation) return
        loadedGeneration = generation
        loadAlbums()
    }

    /** 相册 tab：有内存缓存直接显示；无则先显持久化缓存（不转圈），后台网络刷新。 */
    fun ensureAlbums() {
        val cached = albumsCache
        if (cached != null) {
            _state.value = GalleryUiState.Albums(cached)
            return
        }
        viewModelScope.launch {
            withContext(ioDispatcher) { metadataCache?.loadAlbums() }?.let { disk ->
                albumsCache = disk.sortedWith(ALBUM_NAME_ORDER)
                _state.value = GalleryUiState.Albums(albumsCache!!, fromCache = true)
            }
            loadAlbums()
        }
    }

    /** 照片 tab：有内存缓存直接显示；无则先显持久化缓存（不转圈），后台网络刷新。 */
    fun ensurePhotos() {
        val cached = photosCache
        if (cached != null) {
            _state.value = GalleryUiState.Photos(cached, loading = false)
            return
        }
        viewModelScope.launch {
            val diskRows = withContext(ioDispatcher) { metadataCache?.loadAllPhotos() }?.let { matchRows(it) }
            if (diskRows != null) {
                photosCache = diskRows
                _state.value = GalleryUiState.Photos(diskRows, loading = false, stale = true)
            }
            loadAllPhotos()
        }
    }

    /** 相册内页：有内存缓存直接显示；无则先显持久化缓存，后台网络刷新。 */
    fun ensureAlbum(album: RemoteAlbum) {
        val cached = albumAssetsCache[album.albumId]
        if (cached != null) {
            _state.value = GalleryUiState.AlbumAssets(album, cached, loading = false)
            return
        }
        viewModelScope.launch {
            val diskRows = withContext(ioDispatcher) { metadataCache?.loadAssets(album.albumId) }?.let { matchRows(it) }
            if (diskRows != null) {
                albumAssetsCache[album.albumId] = diskRows
                _state.value = GalleryUiState.AlbumAssets(album, diskRows, loading = false, stale = true)
            }
            loadAlbum(album)
        }
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
                albumsCache = albums
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
     * 拉取全部照片：**渐进式加载**——每拉完一个相册立即更新页面
     * （顶部实时进度文案，照片边拉边显示，绝不空白）。
     * 单个相册失败自动重试 3 次（503 限流常见），相册间 400ms 节流；
     * 仍失败的记录 [GalleryUiState.Photos.failedAlbumIds] 供「重试失败项」。
     */
    fun loadAllPhotos() {
        viewModelScope.launch {
            val existing = photosCache ?: (_state.value as? GalleryUiState.Photos)?.assets.orEmpty()
            _state.value = GalleryUiState.Photos(existing, loading = true, progressText = "正在加载相册列表…")

            val albums = withContext(ioDispatcher) {
                runCatching { fetchAlbumsWithRetry() }.getOrNull()
            }
            if (albums == null) {
                // 相册列表都拉不到：回退缓存或明确报错
                val cached = photosCache ?: withContext(ioDispatcher) { metadataCache?.loadAllPhotos() }
                    ?.let { matchRows(it) }
                if (cached != null) {
                    photosCache = cached
                    _state.value = GalleryUiState.Photos(cached, loading = false, stale = true)
                } else {
                    _state.value = GalleryUiState.Error("拉取相册列表失败（网络或限流）")
                }
                return@launch
            }

            val normal = albums.filterNot { it.isPrivate }.filter { it.albumId.isNotBlank() }
            if (normal.isEmpty()) {
                val rows = matchRows(emptyList())
                photosCache = rows
                _state.value = GalleryUiState.Photos(rows, loading = false)
                return@launch
            }

            val all = existing.map { it.asset }.toMutableList()
            var failed = 0
            val failedIds = mutableListOf<String>()
            normal.forEachIndexed { index, album ->
                if (index > 0) delay(400) // 相册间节流，防触发云端限流
                val assets = runCatching { fetchAssetsWithRetry(album.albumId) }.getOrNull()
                if (assets != null) {
                    all += assets
                } else {
                    failed++
                    failedIds += album.albumId
                }
                // 每相册后渐进更新：照片边拉边显示 + 实时进度
                val rows = matchRows(all)
                photosCache = rows
                val last = index == normal.size - 1
                _state.value = GalleryUiState.Photos(
                    assets = rows,
                    loading = !last,
                    failedAlbums = failed,
                    failedAlbumIds = failedIds.toList(),
                    progressText = if (last) null else "正在加载 第 ${index + 1}/${normal.size} 个相册 · 已获取 ${all.size} 张",
                )
            }
            withContext(ioDispatcher) { metadataCache?.saveAllPhotos(all.distinctBy { it.id }) }
        }
    }

    /** 重试上次失败的相册（方案 A：失败项单独重试，不重新拉全部）。 */
    fun retryFailedAlbums() {
        val current = _state.value as? GalleryUiState.Photos ?: return
        val retryIds = current.failedAlbumIds.toList()
        if (retryIds.isEmpty()) {
            loadAllPhotos()
            return
        }
        viewModelScope.launch {
            val all = current.assets.map { it.asset }.toMutableList()
            var stillFailed = 0
            val stillFailedIds = mutableListOf<String>()
            _state.value = current.copy(loading = true, progressText = "重试 ${retryIds.size} 个失败相册…")
            retryIds.forEachIndexed { index, albumId ->
                if (index > 0) delay(400)
                val assets = runCatching { fetchAssetsWithRetry(albumId) }.getOrNull()
                if (assets != null) {
                    all += assets
                } else {
                    stillFailed++
                    stillFailedIds += albumId
                }
                val rows = matchRows(all)
                photosCache = rows
                _state.value = GalleryUiState.Photos(
                    assets = rows,
                    loading = index < retryIds.size - 1,
                    failedAlbums = stillFailed,
                    failedAlbumIds = stillFailedIds.toList(),
                    progressText = null,
                )
            }
            withContext(ioDispatcher) { metadataCache?.saveAllPhotos(all.distinctBy { it.id }) }
        }
    }

    /** 相册列表拉取带重试。 */
    private suspend fun fetchAlbumsWithRetry(): List<RemoteAlbum> {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                return galleryApi.fetchAlbums()
            } catch (e: Throwable) {
                lastError = e
                if (attempt < 2) delay(1_500L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("拉取相册列表失败")
    }

    /** 单相册资产拉取带重试（503 限流时退避重试）。 */
    private suspend fun fetchAssetsWithRetry(albumId: String): List<RemoteAsset> {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                return galleryApi.fetchAssets(albumId)
            } catch (e: Throwable) {
                lastError = e
                if (attempt < 2) delay(1_500L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("拉取相册 $albumId 失败")
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
                    val rows = matchRows(assets)
                    albumAssetsCache[album.albumId] = rows
                    _state.value = GalleryUiState.AlbumAssets(album, rows, loading = false)
                    withContext(ioDispatcher) { metadataCache?.saveAssets(album.albumId, assets) }
                },
                onFailure = { error ->
                    val cached = withContext(ioDispatcher) { metadataCache?.loadAssets(album.albumId) }
                    if (cached != null) {
                        val rows = matchRows(cached)
                        albumAssetsCache[album.albumId] = rows
                        _state.value = GalleryUiState.AlbumAssets(
                            album, rows, loading = false, stale = true,
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
     * 成功后记录到 [DownloadedStore]，**仅局部更新该行徽标（不整页刷新）**。
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
            updateRow(asset.id) {
                it.copy(downloading = false, status = if (ok) MatchStatus.DOWNLOADED else it.status)
            }
            onCompleted()
        }
    }

    /**
     * 批量顺序下载（长按多选后调用）：按传入顺序逐个下载，
     * 逐个汇报进度 [onProgress]（done/total），全部结束后 [onCompleted]；
     * 每张下载完成**仅局部更新徽标**，不重新拉取列表（避免整页刷新）。
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
                updateRow(asset.id) {
                    it.copy(downloading = false, status = if (ok) MatchStatus.DOWNLOADED else it.status)
                }
                onProgress(index + 1, assets.size)
            }
            onCompleted()
        }
    }

    private fun matchRows(assets: List<RemoteAsset>): List<AssetRow> {
        val local = localMedia()
        val statuses = CloudLocalMatcher.match(assets, local, downloadedStore.ids(GALLERY_NS))
        return assets
            .map { AssetRow(it, statuses[it.id] ?: MatchStatus.NEW) }
            .sortedWith(NEWEST_FIRST)
    }

    /** 局部更新行状态并同步到内存缓存（下载后徽标立即变化，无整页刷新）。 */
    private fun updateRow(assetId: String, transform: (AssetRow) -> AssetRow) {
        val current = _state.value
        _state.value = when (current) {
            is GalleryUiState.AlbumAssets -> {
                val updated = current.copy(
                    assets = current.assets.map { if (it.asset.id == assetId) transform(it) else it },
                )
                albumAssetsCache[current.album.albumId] = updated.assets
                updated
            }

            is GalleryUiState.Photos -> {
                val updated = current.copy(
                    assets = current.assets.map { if (it.asset.id == assetId) transform(it) else it },
                )
                photosCache = updated.assets
                updated
            }

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
