package com.monishguy.mifanscloud.data.recording

import com.monishguy.mifanscloud.data.remote.DownloadSpec
import com.monishguy.mifanscloud.data.remote.MediaDeletedException
import com.monishguy.mifanscloud.data.remote.SignedDownloader
import com.monishguy.mifanscloud.data.remote.XiaomiApiClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.OutputStream

/** 云端录音条目（`/sfs/ns/recorder/dir/0/list`）。 */
data class RemoteRecording(
    val id: String,
    val rawName: String,
    val sha1: String,
    val size: Long,
    val createTime: Long,
    val fileName: String,
    val type: RecordingType,
)

/**
 * 小米云录音 API（i.mi.com 的 sfs/ns/recorder 路径族，双源验证）。
 *
 * - 列表：`dir/0/list`（offset/limit=500 分页，**无 isLastPage**，
 *   返回条数 == limit 即继续翻页）；
 * - 下载：`file/{id}/cb/dl_sfs_cb_{ts}_0/storage` 走签名直链三步流
 *   （与相册共用 [SignedDownloader]）；
 * - 文件名是编码格式，经 [RecordingNameParser] 还原。
 */
class RecordingApi(
    private val apiClient: XiaomiApiClient,
    baseUrl: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val baseUrl: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val downloader = SignedDownloader(apiClient)

    /** 拉取全部录音（分页）。 */
    fun fetchRecordings(): List<RemoteRecording> {
        val result = mutableListOf<RemoteRecording>()
        var offset = 0
        while (true) {
            val url = baseUrl.newBuilder()
                .addPathSegments("sfs/ns/recorder/dir/0/list")
                .addQueryParameter("ts", clock().toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("limit", PAGE_LIMIT.toString())
                .build()
            val data = getData(url.toString())
            val list = data.optJSONArray("list")
            val pageSize = list?.length() ?: 0
            for (i in 0 until pageSize) {
                result += parseRecording(list!!.getJSONObject(i))
            }
            if (pageSize < PAGE_LIMIT) break
            offset += pageSize
        }
        return result
    }

    /** 解析签名直链（供 [download] 使用）。 */
    fun resolveDownload(recordingId: String): DownloadSpec {
        val url = baseUrl.newBuilder()
            .addPathSegments("sfs/ns/recorder/file/$recordingId/cb/dl_sfs_cb_${clock()}_0/storage")
            .addQueryParameter("ts", clock().toString())
            .build()
        return downloader.resolveStorage(url.toString())
    }

    /** 下载录音到 [target]（签名直链三步流），false=云端已删除跳过。 */
    fun download(recordingId: String, target: OutputStream): Boolean = try {
        downloader.download(resolveDownload(recordingId), target)
        true
    } catch (e: MediaDeletedException) {
        false
    }

    private fun getData(url: String): JSONObject =
        getJson(url).optJSONObject("data")
            ?: throw IllegalStateException("响应缺少 data 字段")

    private fun getJson(url: String): JSONObject =
        apiClient.execute(okhttp3.Request.Builder().url(url).get().build()).use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("请求失败 HTTP ${resp.code}: $url")
            JSONObject(resp.body?.string().orEmpty())
        }

    private fun parseRecording(item: JSONObject): RemoteRecording {
        val rawName = item.optString("name")
        val parsed = RecordingNameParser.parse(rawName)
        return RemoteRecording(
            id = item.optString("id"),
            rawName = rawName,
            sha1 = item.optString("sha1"),
            size = item.optLong("size"),
            createTime = item.optLong("create_time"),
            fileName = parsed.fileName,
            type = parsed.type,
        )
    }

    private companion object {
        const val PAGE_LIMIT = 500
    }
}
