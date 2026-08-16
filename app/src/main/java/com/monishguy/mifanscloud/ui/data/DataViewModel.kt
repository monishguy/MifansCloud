package com.monishguy.mifanscloud.ui.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.monishguy.mifanscloud.AppContainer
import com.monishguy.mifanscloud.data.contact.ContactApi
import com.monishguy.mifanscloud.data.contact.RemoteContact
import com.monishguy.mifanscloud.data.note.NoteApi
import com.monishguy.mifanscloud.data.note.RemoteNote
import com.monishguy.mifanscloud.data.sms.RemoteSms
import com.monishguy.mifanscloud.data.sms.SmsApi
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

/** 「数据」模块（通讯录/笔记/短信）UI 状态。 */
sealed interface DataUiState {
    data object Idle : DataUiState
    data object Loading : DataUiState

    data class Loaded(
        val contacts: List<RemoteContact>,
        val notes: List<RemoteNote>,
        val sms: List<RemoteSms>,
    ) : DataUiState

    data class Error(val message: String) : DataUiState
}

/** 导出类型。 */
enum class ExportKind { CONTACTS, NOTES, SMS }

/**
 * 通讯录/笔记/短信：清单拉取（syncTag 增量）与 JSON 导出。
 */
class DataViewModel(
    private val contactApi: ContactApi,
    private val noteApi: NoteApi,
    private val smsApi: SmsApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<DataUiState>(DataUiState.Idle)
    val state: StateFlow<DataUiState> = _state.asStateFlow()

    /** 拉取三类数据（仅元数据清单）。 */
    fun loadAll() {
        viewModelScope.launch {
            _state.value = DataUiState.Loading
            val result = withContext(ioDispatcher) {
                runCatching {
                    DataUiState.Loaded(
                        contacts = contactApi.fetchContacts().contacts,
                        notes = noteApi.fetchNotes().notes,
                        sms = smsApi.fetchMessages().messages,
                    )
                }
            }
            _state.value = result.fold(
                onSuccess = { it },
                onFailure = { DataUiState.Error(it.message ?: "拉取数据失败") },
            )
        }
    }

    /**
     * 导出指定类型为 JSON 到 [outputProvider]（SAF 流），
     * 成功返回 true。provider 参数为建议文件名。
     */
    fun exportJson(kind: ExportKind, outputProvider: (String) -> OutputStream): Boolean {
        val loaded = _state.value as? DataUiState.Loaded ?: return false
        val (fileName, json) = when (kind) {
            ExportKind.CONTACTS -> "contacts.json" to contactsJson(loaded.contacts)
            ExportKind.NOTES -> "notes.json" to notesJson(loaded.notes)
            ExportKind.SMS -> "sms.json" to smsJson(loaded.sms)
        }
        viewModelScope.launch {
            withContext(ioDispatcher) {
                runCatching {
                    outputProvider(fileName).use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
            }
        }
        return true
    }

    private fun contactsJson(contacts: List<RemoteContact>): String {
        val arr = JSONArray()
        contacts.forEach { c ->
            val phones = JSONArray()
            c.phoneNumbers.forEach { phones.put(JSONObject().put("type", it.type).put("value", it.value)) }
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("displayName", c.displayName)
                    .put("pinyin", c.pinyin)
                    .put("updateTime", c.updateTime)
                    .put("phoneNumbers", phones)
            )
        }
        return arr.toString(2)
    }

    private fun notesJson(notes: List<RemoteNote>): String {
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
        return arr.toString(2)
    }

    private fun smsJson(sms: List<RemoteSms>): String {
        val arr = JSONArray()
        sms.forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("threadId", m.threadId)
                    .put("snippet", m.snippet)
                    .put("recipients", m.recipients)
                    .put("folder", m.folder ?: JSONObject.NULL)
                    .put("lastUpdateTime", m.lastUpdateTime)
                    .put("unread", m.unread)
                    .put("total", m.total)
            )
        }
        return arr.toString(2)
    }

    /** AppContainer 装配工厂。 */
    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DataViewModel(
                contactApi = container.contactApi,
                noteApi = container.noteApi,
                smsApi = container.smsApi,
            ) as T
    }
}
