package com.monishguy.mifanscloud.data.contact

import com.monishguy.mifanscloud.data.remote.XiaomiApiClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** 云端联系人。 */
data class RemoteContact(
    val id: String,
    val displayName: String,
    val phoneNumbers: List<RemotePhoneNumber>,
    val pinyin: String,
    val updateTime: Long,
)

data class RemotePhoneNumber(
    val type: String,
    val value: String,
)

data class ContactPage(
    val syncTag: String,
    val contacts: List<RemoteContact>,
    val lastPage: Boolean,
)

/**
 * 小米云通讯录 API：`GET /contacts/initdata`（syncTag 增量游标，MiCloud 验证）。
 * `content` 为 map：联系人 id → { content:{displayName, name.formatted,
 * phoneNumbers:[{type,value}]}, pinyin, createTime, updateTime }。
 */
class ContactApi(
    private val apiClient: XiaomiApiClient,
    baseUrl: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val baseUrl: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    fun fetchContacts(limit: Int = 200): ContactPage {
        val ts = clock().toString()
        val url = baseUrl.newBuilder()
            .addPathSegments("contacts/initdata")
            .addQueryParameter("ts", ts)
            .addQueryParameter("_dc", ts)
            .addQueryParameter("syncTag", "0")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("syncIgnoreTag", "0")
            .build()
        val data = getData(url.toString())
        val content = data.optJSONObject("content")
        val contacts = mutableListOf<RemoteContact>()
        content?.keys()?.forEach { id ->
            val wrapper = content.optJSONObject(id)
            val c = wrapper?.optJSONObject("content")
            contacts += RemoteContact(
                id = id,
                displayName = c?.optString("displayName") ?: "",
                phoneNumbers = c?.optJSONArray("phoneNumbers")?.let { arr ->
                    (0 until arr.length()).mapNotNull { j ->
                        arr.optJSONObject(j)?.let { p ->
                            RemotePhoneNumber(p.optString("type"), p.optString("value"))
                        }
                    }
                } ?: emptyList(),
                pinyin = wrapper?.optString("pinyin") ?: "",
                updateTime = wrapper?.optLong("updateTime") ?: 0L,
            )
        }
        return ContactPage(
            syncTag = data.optString("syncTag"),
            contacts = contacts.sortedBy { it.displayName.lowercase() },
            lastPage = data.optBoolean("lastPage"),
        )
    }

    private fun getData(url: String): JSONObject =
        getJson(url).optJSONObject("data")
            ?: throw IllegalStateException("响应缺少 data 字段")

    private fun getJson(url: String): JSONObject =
        apiClient.execute(okhttp3.Request.Builder().url(url).get().build()).use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("请求失败 HTTP ${resp.code}: $url")
            JSONObject(resp.body?.string().orEmpty())
        }
}
