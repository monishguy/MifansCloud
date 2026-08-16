package com.monishguy.mifanscloud.data.note

import com.monishguy.mifanscloud.data.remote.XiaomiApiClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** 云端笔记。 */
data class RemoteNote(
    val id: String,
    /** 真实标题：extraInfo.title / content.title（云端 subject 字段恒为空）。 */
    val title: String,
    val subject: String,
    val snippet: String,
    val content: String,
    val extraInfo: String,
    val folderId: String?,
    val modifyDate: Long,
    /** 原始 entry JSON 快照（诊断/兜底）。 */
    val raw: String = "",
)

data class NotePage(
    val notes: List<RemoteNote>,
    val syncTag: String,
    val lastPage: Boolean,
)

/**
 * 小米云笔记 API（MiCloud 验证）：
 * `GET /note/full/page/?limit&ts`（注意路径尾斜杠）→ entries + syncTag + lastPage。
 * `folderId` 字段可能为 int 或 string，统一转字符串处理。
 */
class NoteApi(
    private val apiClient: XiaomiApiClient,
    baseUrl: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val baseUrl: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    fun fetchNotes(limit: Int = 200): NotePage {
        val url = baseUrl.newBuilder()
            .addPathSegments("note/full/page")
            .addPathSegment("") // 路径尾斜杠（对齐 MiCloud 字面量）
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("ts", clock().toString())
            .build()
        val data = getData(url.toString())
        val entries = data.optJSONArray("entries")
        val notes = mutableListOf<RemoteNote>()
        for (i in 0 until (entries?.length() ?: 0)) {
            val e = entries!!.getJSONObject(i)
            val extraInfo = e.optString("extraInfo")
            val content = e.optString("content")
            notes += RemoteNote(
                id = e.optString("id"),
                // 真机验证：标题在 extraInfo.title / content.title，subject 恒为空
                title = NoteMarkdown.parseTitle(extraInfo, content),
                subject = e.optString("subject"),
                snippet = e.optString("snippet"),
                content = content,
                extraInfo = extraInfo,
                folderId = e.opt("folderId")?.toString(),
                modifyDate = e.optLong("modifyDate"),
                raw = e.toString(),
            )
        }
        return NotePage(
            notes = notes,
            syncTag = data.optString("syncTag"),
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
