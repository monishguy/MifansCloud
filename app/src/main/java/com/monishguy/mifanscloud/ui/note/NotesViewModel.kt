package com.monishguy.mifanscloud.ui.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.monishguy.mifanscloud.AppContainer
import com.monishguy.mifanscloud.data.note.NoteApi
import com.monishguy.mifanscloud.data.note.RemoteNote
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream

/** 笔记板块 UI 状态。 */
sealed interface NotesUiState {
    data object Idle : NotesUiState
    data object Loading : NotesUiState
    data class Notes(val notes: List<RemoteNote>) : NotesUiState
    data class Error(val message: String) : NotesUiState
}

/**
 * 笔记板块：按创建顺序（最新在前）清单拉取（缓存复用）与 JSON 导出。
 */
class NotesViewModel(
    private val noteApi: NoteApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cacheVersion: () -> Int = { 0 },
) : ViewModel() {

    private val _state = MutableStateFlow<NotesUiState>(NotesUiState.Idle)
    val state: StateFlow<NotesUiState> = _state.asStateFlow()

    @Volatile
    private var loadedGeneration: Int? = null

    fun loadOnce() {
        val generation = cacheVersion()
        if (loadedGeneration == generation) return
        loadedGeneration = generation
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = NotesUiState.Loading
            _state.value = withContext(ioDispatcher) {
                runCatching { noteApi.fetchNotes().notes }
            }.fold(
                onSuccess = { list ->
                    // 按修改时间降序（最新在前）
                    NotesUiState.Notes(list.sortedByDescending { it.modifyDate })
                },
                onFailure = { NotesUiState.Error(it.message ?: "拉取笔记失败") },
            )
        }
    }

    /** 导出为 Markdown 目录下的 notes.json 备份。 */
    fun exportJson(outputProvider: (String) -> OutputStream) {
        val notes = (_state.value as? NotesUiState.Notes)?.notes ?: return
        val arr = JSONArray()
        notes.forEach { n ->
            arr.put(
                JSONObject()
                    .put("id", n.id)
                    .put("subject", n.subject)
                    .put("content", n.content)
                    .put("folderId", n.folderId ?: JSONObject.NULL)
                    .put("modifyDate", n.modifyDate)
            )
        }
        viewModelScope.launch {
            withContext(ioDispatcher) {
                runCatching { outputProvider("notes.json").use { it.write(arr.toString(2).toByteArray()) } }
            }
        }
    }

    /** AppContainer 装配工厂。 */
    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NotesViewModel(
                noteApi = container.noteApi,
                cacheVersion = container.cacheVersion,
            ) as T
    }
}
