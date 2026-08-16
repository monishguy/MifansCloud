package com.monishguy.mifanscloud.ui.contact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.monishguy.mifanscloud.AppContainer
import com.monishguy.mifanscloud.data.contact.ContactApi
import com.monishguy.mifanscloud.data.contact.RemoteContact
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

/** 通讯录板块 UI 状态。 */
sealed interface ContactsUiState {
    data object Idle : ContactsUiState
    data object Loading : ContactsUiState
    data class Contacts(val contacts: List<RemoteContact>) : ContactsUiState
    data class Error(val message: String) : ContactsUiState
}

/**
 * 通讯录板块：按名字排序的清单拉取（缓存复用）与 JSON 导出。
 */
class ContactsViewModel(
    private val contactApi: ContactApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cacheVersion: () -> Int = { 0 },
) : ViewModel() {

    private val _state = MutableStateFlow<ContactsUiState>(ContactsUiState.Idle)
    val state: StateFlow<ContactsUiState> = _state.asStateFlow()

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
            _state.value = ContactsUiState.Loading
            _state.value = withContext(ioDispatcher) {
                runCatching { contactApi.fetchContacts().contacts }
            }.fold(
                onSuccess = { ContactsUiState.Contacts(it) },
                onFailure = { ContactsUiState.Error(it.message ?: "拉取通讯录失败") },
            )
        }
    }

    /** 导出为 JSON 到 [outputProvider]（联系人目录）。 */
    fun exportJson(outputProvider: (String) -> OutputStream) {
        val contacts = (_state.value as? ContactsUiState.Contacts)?.contacts ?: return
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
        viewModelScope.launch {
            withContext(ioDispatcher) {
                runCatching { outputProvider("contacts.json").use { it.write(arr.toString(2).toByteArray()) } }
            }
        }
    }

    /** AppContainer 装配工厂。 */
    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ContactsViewModel(
                contactApi = container.contactApi,
                cacheVersion = container.cacheVersion,
            ) as T
    }
}
