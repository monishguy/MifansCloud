package com.monishguy.mifanscloud.ui.recording

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.monishguy.mifanscloud.AppContainer
import com.monishguy.mifanscloud.data.recording.RecordingApi
import com.monishguy.mifanscloud.data.recording.RemoteRecording
import com.monishguy.mifanscloud.data.sync.DownloadedStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** 录音模块 UI 状态。 */
sealed interface RecordingUiState {
    data object Idle : RecordingUiState
    data object Loading : RecordingUiState

    data class Recordings(val recordings: List<RecordingRow>) : RecordingUiState

    data class Error(val message: String) : RecordingUiState
}

/** 录音行：云端条目 + 下载进度。 */
data class RecordingRow(
    val recording: RemoteRecording,
    val downloading: Boolean = false,
)

/**
 * 录音同步 ViewModel：列表 → 按需下载（命名空间 recording）。
 */
class RecordingsViewModel(
    private val api: RecordingApi,
    private val downloadedStore: DownloadedStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 板块缓存代际：清除凭证后变化，缓存失效需重载。 */
    private val cacheVersion: () -> Int = { 0 },
) : ViewModel() {

    private val _state = MutableStateFlow<RecordingUiState>(RecordingUiState.Idle)
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    @Volatile
    private var loadedGeneration: Int? = null

    /** 首次进入（或凭证清除后）才加载，其余复用缓存。 */
    fun loadOnce() {
        val generation = cacheVersion()
        if (loadedGeneration == generation) return
        loadedGeneration = generation
        load()
    }

    /** 拉取云端录音列表（元数据，不下载文件）。 */
    fun load() {
        viewModelScope.launch {
            _state.value = RecordingUiState.Loading
            _state.value = withContext(ioDispatcher) {
                runCatching { api.fetchRecordings() }
            }.fold(
                onSuccess = { list ->
                    RecordingUiState.Recordings(list.map { RecordingRow(it) })
                },
                onFailure = { RecordingUiState.Error(it.message ?: "拉取录音失败") },
            )
        }
    }

    /** 按需下载一条录音；成功后记录并刷新状态。 */
    fun download(recording: RemoteRecording, outputProvider: () -> OutputStream) {
        viewModelScope.launch {
            updateRow(recording.id) { it.copy(downloading = true) }
            val ok = withContext(ioDispatcher) {
                runCatching {
                    outputProvider().use { out -> api.download(recording.id, out) }
                }.getOrDefault(false)
            }
            if (ok) {
                downloadedStore.add(RECORDING_NS, recording.id, recording.fileName)
                load()
            } else {
                updateRow(recording.id) { it.copy(downloading = false) }
            }
        }
    }

    private fun updateRow(recordingId: String, transform: (RecordingRow) -> RecordingRow) {
        val current = _state.value as? RecordingUiState.Recordings ?: return
        _state.value = current.copy(
            recordings = current.recordings.map {
                if (it.recording.id == recordingId) transform(it) else it
            },
        )
    }

    /** AppContainer 装配工厂。 */
    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RecordingsViewModel(
                api = container.recordingApi,
                downloadedStore = container.downloadedStore,
                cacheVersion = container.cacheVersion,
            ) as T
    }

    private companion object {
        const val RECORDING_NS = "recording"
    }
}
