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

    /**
     * 云端通讯录 → 本机系统通讯录（ContactsContract 批量插入，无账号）。
     * 分批提交（每批 ≤100 联系人，规避 applyBatch 500 op 上限）；
     * [onDone] 回调 (成功条数, 错误信息)。
     */
    fun importToDevice(context: android.content.Context, onDone: (Int, String?) -> Unit) {
        val contacts = (_state.value as? ContactsUiState.Contacts)?.contacts.orEmpty()
        if (contacts.isEmpty()) {
            onDone(0, "云端通讯录为空")
            return
        }
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                runCatching {
                    val resolver = context.contentResolver
                    var inserted = 0
                    contacts.filter { it.displayName.isNotBlank() }.chunked(100).forEach { batch ->
                        val ops = ArrayList<android.content.ContentProviderOperation>()
                        batch.forEach { contact ->
                            val rawIndex = ops.size
                            ops += android.content.ContentProviderOperation
                                .newInsert(android.provider.ContactsContract.RawContacts.CONTENT_URI)
                                .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                                .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_NAME, null)
                                .build()
                            ops += android.content.ContentProviderOperation
                                .newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                                .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                                .withValue(
                                    android.provider.ContactsContract.Data.MIMETYPE,
                                    android.provider.ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                                )
                                .withValue(
                                    android.provider.ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                                    contact.displayName,
                                )
                                .build()
                            contact.phoneNumbers.forEach { phone ->
                                ops += android.content.ContentProviderOperation
                                    .newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                                    .withValueBackReference(
                                        android.provider.ContactsContract.Data.RAW_CONTACT_ID, rawIndex,
                                    )
                                    .withValue(
                                        android.provider.ContactsContract.Data.MIMETYPE,
                                        android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                                    )
                                    .withValue(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, phone.value)
                                    .withValue(android.provider.ContactsContract.CommonDataKinds.Phone.TYPE, phoneType(phone.type))
                                    .build()
                            }
                        }
                        resolver.applyBatch(android.provider.ContactsContract.AUTHORITY, ops)
                        inserted += batch.size
                    }
                    inserted
                }
            }
            result.fold(
                onSuccess = { onDone(it, null) },
                onFailure = { onDone(0, it.message) },
            )
        }
    }

    /** 云端 type → ContactsContract 类型常量。 */
    private fun phoneType(type: String): Int = when (type.lowercase()) {
        "mobile", "cell" -> android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
        "home" -> android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_HOME
        "work", "company", "office" -> android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_WORK
        "fax" -> android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK
        else -> android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
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
