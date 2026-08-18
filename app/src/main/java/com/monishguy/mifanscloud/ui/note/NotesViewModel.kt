package com.monishguy.mifanscloud.ui.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.monishguy.mifanscloud.AppContainer
import com.monishguy.mifanscloud.data.note.NoteApi
import com.monishguy.mifanscloud.data.note.NoteMarkdown
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /** 导出为 Markdown 目录下的 notes.json 备份（含原始字段 rawJson 兜底）。 */
    fun exportJson(outputProvider: (String) -> OutputStream) {
        val notes = (_state.value as? NotesUiState.Notes)?.notes ?: return
        val arr = JSONArray()
        notes.forEach { n ->
            val obj = JSONObject()
                .put("id", n.id)
                .put("subject", n.subject)
                .put("snippet", n.snippet)
                .put("content", n.content)
                .put("folderId", n.folderId ?: JSONObject.NULL)
                .put("modifyDate", n.modifyDate)
            // 原始 JSON 快照：即使上面字段为空也能从 rawJson 找回全部信息
            if (n.raw.isNotBlank()) {
                obj.put("rawJson", JSONObject(n.raw))
            }
            arr.put(obj)
        }
        viewModelScope.launch {
            withContext(ioDispatcher) {
                runCatching { outputProvider("notes.json").use { it.write(arr.toString(2).toByteArray()) } }
            }
        }
    }

    /**
     * 导出多份 Markdown：每篇笔记一个 .md 文件。
     * [outputProvider] 接收目标文件名（已清洗、已按序号去重）返回输出流；
     * [onDone] 回调 (成功数, 错误信息)。
     */
    fun exportMarkdown(
        outputProvider: (fileName: String) -> OutputStream?,
        ids: Set<String>? = null,
        onDone: (Int, String?) -> Unit,
    ) {
        val notes = (_state.value as? NotesUiState.Notes)?.notes
            ?.filter { ids == null || it.id in ids }
            .orEmpty()
        if (notes.isEmpty()) {
            onDone(0, "笔记列表为空")
            return
        }
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                runCatching {
                    var exported = 0
                    val usedNames = mutableSetOf<String>()
                    notes.sortedByDescending { it.modifyDate }.forEachIndexed { index, note ->
                        val fallback = NoteMarkdown.snippetToMarkdown(note.snippet)
                            .lineSequence().firstOrNull()?.take(20) ?: ""
                        val title = note.title.ifBlank { fallback }.ifBlank { "无标题" }
                        val base = sanitizeMarkdownFileName(title)
                        var fileName = "$base.md"
                        if (!usedNames.add(fileName)) {
                            fileName = "${base}_${index + 1}.md"
                            usedNames.add(fileName)
                        }
                        val body = buildString {
                            append("# ").append(title).append("\n\n")
                            append(displayBody(note))
                            append("\n\n---\n")
                            append("修改时间: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(note.modifyDate)))
                            append("\n")
                        }
                        val out = outputProvider(fileName) ?: return@forEachIndexed
                        out.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                        exported++
                    }
                    exported
                }
            }
            result.fold(
                onSuccess = { onDone(it, null) },
                onFailure = { onDone(0, it.message) },
            )
        }
    }

    /** Markdown 文件名清洗：去掉路径分隔符与非法字符，限制长度。 */
    private fun sanitizeMarkdownFileName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|\\s]+"), "_").trim('_').take(40).ifBlank { "note" }

    /** 本地编辑后的正文（键：noteId；导出/展示时优先使用）。 */
    private val editedBodies = mutableMapOf<String, String>()

    /** 本地编辑标题/正文：仅更新内存（导出 Markdown 与列表展示生效）。
     *  云端同步接口未逆向（需抓包 HAR），保存到云端为待实现占位。 */
    fun saveLocalEdit(noteId: String, newTitle: String, newBody: String) {
        val current = _state.value as? NotesUiState.Notes ?: return
        editedBodies[noteId] = newBody
        _state.value = current.copy(
            notes = current.notes.map { if (it.id == noteId) it.copy(title = newTitle) else it },
        )
    }

    /** 展示用正文（编辑过则用编辑内容，否则 snippet 转 Markdown）。 */
    fun displayBody(note: RemoteNote): String =
        editedBodies[note.id] ?: NoteMarkdown.snippetToMarkdown(note.snippet)

    /** 列表摘要（转 Markdown 纯文本，不露原始 XML/JSON 标记）。 */
    fun displaySnippet(note: RemoteNote): String =
        displayBody(note).take(120)

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
