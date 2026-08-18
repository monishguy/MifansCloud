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
    /** 保存用 serviceToken（form 参数认证，网页端同款）；获取失败返回 null。 */
    private val serviceTokenProvider: () -> String? = { null },
) {

    private val baseUrl: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    /**
     * 保存笔记到云端（HAR 逆向：POST /note/note/{id}，entry JSON + serviceToken）。
     * [newTitle] 更新 extraInfo.title；[newBodyMarkdown] 转回富文本 snippet 存 content。
     * [onDone] 回调 (是否成功, 错误信息)；成功回调新的 tag 版本游标。
     */
    fun updateNote(
        note: RemoteNote,
        newTitle: String,
        newBodyMarkdown: String,
        onDone: (Boolean, String?) -> Unit,
    ) {
        val serviceToken = serviceTokenProvider()
        if (serviceToken == null) {
            onDone(false, "缺少 serviceToken，无法保存到云端")
            return
        }
        val now = clock()
        // 保留云端原字段：tag（版本游标）、createDate、folderId、status 等
        val tag = NoteMarkdown.rawField(note.raw, "tag", note.id)
        val createDate = NoteMarkdown.rawField(note.raw, "createDate", now.toString())
        val folderId = note.folderId ?: NoteMarkdown.rawField(note.raw, "folderId", "0")
        // extraInfo：更新 title（保留其余字段）
        val extraInfoJson = runCatching { JSONObject(note.extraInfo) }.getOrElse { JSONObject() }
        extraInfoJson.put("title", newTitle)
        // setting：保留云端原声明（含附件 data 列表——否则附件会丢失）
        val setting = runCatching {
            JSONObject(NoteMarkdown.rawField(note.raw, "setting", "{}"))
        }.getOrElse { JSONObject() }
        if (!setting.has("totalSize")) setting.put("totalSize", 0)
        if (!setting.has("themeId")) setting.put("themeId", 0)
        if (!setting.has("stickyTime")) setting.put("stickyTime", 0)
        if (!setting.has("version")) setting.put("version", 0)
        val entry = JSONObject()
            .put("id", note.id)
            .put("tag", tag)
            .put("status", "normal")
            .put("createDate", createDate.toLongOrNull() ?: now)
            .put("modifyDate", now)
            .put("colorId", NoteMarkdown.rawField(note.raw, "colorId", "0").toLongOrNull() ?: 0L)
            .put("content", NoteMarkdown.markdownToSnippet(newBodyMarkdown))
            .put("setting", setting)
            .put("folderId", folderId)
            .put("alertDate", 0)
            .put("extraInfo", extraInfoJson.toString())

        val body = okhttp3.FormBody.Builder()
            .add("entry", entry.toString())
            .add("serviceToken", serviceToken)
            .build()
        val url = baseUrl.newBuilder().addPathSegments("note/note/${note.id}").build()
        apiClient.execute(okhttp3.Request.Builder().url(url).post(body).build()).use { resp ->
            if (!resp.isSuccessful) {
                onDone(false, "保存失败 HTTP ${resp.code}")
                return
            }
            val json = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
            if (json?.optInt("code", 0) != 0) {
                onDone(false, json?.optString("description") ?: "保存失败")
                return
            }
            onDone(true, json.optJSONObject("data")?.optString("tag"))
        }
    }

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

    /**
     * 下载笔记附件图片（`GET /file/full?type=note_img&fileid={id}`），
     * 失败返回 null。
     */
    fun fetchNoteImage(fileId: String): ByteArray? = runCatching {
        val url = baseUrl.newBuilder()
            .addPathSegments("file/full")
            .addQueryParameter("type", "note_img")
            .addQueryParameter("fileid", fileId)
            .build()
        apiClient.execute(okhttp3.Request.Builder().url(url).get().build()).use { resp ->
            if (!resp.isSuccessful) return@use null
            resp.body?.bytes()
        }
    }.getOrNull()

    private fun getData(url: String): JSONObject =
        getJson(url).optJSONObject("data")
            ?: throw IllegalStateException("响应缺少 data 字段")

    private fun getJson(url: String): JSONObject =
        apiClient.execute(okhttp3.Request.Builder().url(url).get().build()).use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("请求失败 HTTP ${resp.code}: $url")
            JSONObject(resp.body?.string().orEmpty())
        }
}
