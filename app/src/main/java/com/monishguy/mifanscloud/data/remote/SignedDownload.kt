package com.monishguy.mifanscloud.data.remote

import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.OutputStream

/** 签名直链下载规格：POST `url` + form `meta` 得到文件流。 */
data class DownloadSpec(
    val url: String,
    val meta: String,
)

/** 云端文件已删除（storage 返回 code=50050），调用方应跳过并标记。 */
class MediaDeletedException(assetId: String) :
    Exception("云端文件已删除: $assetId")

/**
 * 签名直链下载器（相册/录音共用）。
 *
 * 三步（对齐 XiaomiAlbumSyncer XiaoMiApi）：
 * ① GET 中转 URL（gallery/storage 或 recorder .../storage）→ `data.url`
 * ② GET 中转页 → JSONP `cb({url, meta})`
 * ③ POST `url` + form `meta` → 文件流写入 [OutputStream]
 */
class SignedDownloader(private val apiClient: XiaomiApiClient) {

    /** 把「中转 URL」解析为可下载规格。code=50050 抛 [MediaDeletedException]。 */
    fun resolveStorage(storageUrl: String): DownloadSpec {
        val json = getJson(storageUrl)
        val code = json.optInt("code")
        if (code == 50050) throw MediaDeletedException(storageUrl)
        val ossUrl = json.optJSONObject("data")?.optString("url")
            ?: throw IllegalStateException("storage 响应缺少 url (code=$code)")

        val jsonp = apiClient.execute(Request.Builder().url(ossUrl).get().build())
        jsonp.use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("签名中转页 HTTP ${resp.code}")
            val obj = JSONObject(JsonpParser.unwrap(resp.body?.string().orEmpty()))
            val downloadUrl = obj.optString("url")
            val meta = obj.optString("meta")
            if (downloadUrl.isBlank() || meta.isBlank()) {
                throw IllegalStateException("JSONP 响应缺少 url/meta")
            }
            return DownloadSpec(downloadUrl, meta)
        }
    }

    /** 按规格流式下载到 [target]（调用方负责关闭）。 */
    fun download(spec: DownloadSpec, target: OutputStream) {
        val body = FormBody.Builder().add("meta", spec.meta).build()
        val resp = apiClient.execute(Request.Builder().url(spec.url).post(body).build())
        resp.use { r ->
            if (!r.isSuccessful) throw IllegalStateException("下载失败 HTTP ${r.code}")
            val stream = r.body?.byteStream()
                ?: throw IllegalStateException("下载响应为空")
            stream.use { input -> input.copyTo(target, BUFFER_SIZE) }
        }
    }

    private fun getJson(url: String): JSONObject =
        apiClient.execute(Request.Builder().url(url).get().build()).use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("请求失败 HTTP ${resp.code}: $url")
            JSONObject(resp.body?.string().orEmpty())
        }

    private companion object {
        const val BUFFER_SIZE = 8192
    }
}
