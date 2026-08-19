package com.monishguy.mifanscloud.data.sms

import com.monishguy.mifanscloud.data.remote.XiaomiApiClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** 云端短信线程条目。 */
data class RemoteSms(
    val id: String,
    val threadId: String,
    val snippet: String,
    val recipients: String,
    val folder: String?,
    val lastUpdateTime: Long,
    val unread: Int,
    val total: Int,
)

data class SmsPage(
    val messages: List<RemoteSms>,
    val syncTag: String,
    val syncThreadTag: String,
)

/**
 * 小米云短信 API（MiCloud 验证）：
 * `GET /sms/full/thread?ts&_dc&limit&syncTag&syncThreadTag&readMode=older&withPhoneCall=false`
 * → entries[{entry:Message, operation}] + watermark{syncTag, syncThreadTag}。
 */
class SmsApi(
    private val apiClient: XiaomiApiClient,
    baseUrl: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val baseUrl: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    fun fetchMessages(limit: Int = 100): SmsPage {
        val ts = clock().toString()
        val url = baseUrl.newBuilder()
            .addPathSegments("sms/full/thread")
            .addQueryParameter("ts", ts)
            .addQueryParameter("_dc", ts)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("syncTag", "")
            .addQueryParameter("syncThreadTag", "")
            .addQueryParameter("readMode", "older")
            .addQueryParameter("withPhoneCall", "false")
            .build()
        val data = getData(url.toString())
        val entries = data.optJSONArray("entries")
        val messages = mutableListOf<RemoteSms>()
        for (i in 0 until (entries?.length() ?: 0)) {
            val e = entries!!.getJSONObject(i).optJSONObject("entry") ?: continue
            messages += RemoteSms(
                id = e.optString("id"),
                threadId = e.optString("threadId"),
                snippet = e.optString("snippet"),
                recipients = e.optString("recipients"),
                folder = e.opt("folder")?.toString(),
                lastUpdateTime = e.optLong("lastUpdateTime"),
                unread = e.optInt("unread"),
                total = e.optInt("total"),
            )
        }
        val watermark = data.optJSONObject("watermark")
        return SmsPage(
            messages = messages,
            syncTag = watermark?.optString("syncTag") ?: "",
            syncThreadTag = watermark?.optString("syncThreadTag") ?: "",
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
