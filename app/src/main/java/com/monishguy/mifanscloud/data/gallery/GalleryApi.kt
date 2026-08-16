package com.monishguy.mifanscloud.data.gallery

import com.monishguy.mifanscloud.data.remote.DownloadSpec
import com.monishguy.mifanscloud.data.remote.MediaDeletedException
import com.monishguy.mifanscloud.data.remote.SignedDownloader
import com.monishguy.mifanscloud.data.remote.XiaomiApiClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.OutputStream

/**
 * 小米云相册 API（i.mi.com 的 gallery 路径族）。
 *
 * 设计要点（对齐用户需求：不批量下载、纯缩略图浏览、按需下载原图）：
 * - 清单接口（album/list、user/galleries、timeline）只拉**元数据 + 缩略图**，
 *   缩略图随清单返回（`thumbnailInfo: {data, isUrl}`），绝不触碰原图；
 * - 原图仅在 [download] 被显式调用时走签名直链三步流下载。
 *
 * 认证/UA/401 重试统一由 [XiaomiApiClient] 处理（直连会话与 passToken 会话皆可用）。
 */
class GalleryApi(
    private val apiClient: XiaomiApiClient,
    baseUrl: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val baseUrl: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val downloader = SignedDownloader(apiClient)

    /** 拉取全部相册（分页；私密相册 1000 保留并标记 isPrivate）。 */
    fun fetchAlbums(): List<RemoteAlbum> {
        val result = mutableListOf<RemoteAlbum>()
        var pageNum = 0
        while (true) {
            val url = baseUrl.newBuilder()
                .addPathSegments("gallery/user/album/list")
                .addQueryParameter("ts", clock().toString())
                .addQueryParameter("pageNum", pageNum.toString())
                .addQueryParameter("pageSize", "10")
                .addQueryParameter("isShared", "false")
                .addQueryParameter("numOfThumbnails", "1")
                .build()
            val data = getData(url.toString())
            val albums = data.optJSONArray("albums")
            for (i in 0 until (albums?.length() ?: 0)) {
                val item = albums!!.getJSONObject(i)
                val albumId = item.optString("albumId")
                val isPrivate = albumId == PRIVATE_ALBUM_ID
                result += RemoteAlbum(
                    albumId = albumId,
                    // 云端私密相册 name 可能为空：固定显示「私密相册」
                    name = item.optString("name").ifBlank {
                        if (isPrivate) "私密相册" else "未命名相册"
                    },
                    mediaCount = item.optInt("mediaCount"),
                    lastUpdateTime = item.optLong("lastUpdateTime"),
                    coverUrls = item.optJSONArray("thumbnails")?.let { arr ->
                        (0 until arr.length()).mapNotNull { j ->
                            arr.optJSONObject(j)?.optString("url")?.takeIf { it.isNotBlank() }
                        }
                    } ?: emptyList(),
                    // 私密相册保留在列表中（UI 显示锁并要求密码），不再跳过
                    isPrivate = isPrivate,
                )
            }
            if (data.optBoolean("isLastPage")) break
            pageNum++
        }
        return result
    }

    /**
     * 拉取相册内全部资产（分页 pageSize=200）。
     * 可选按天过滤（`yyyyMMdd`，配合 timeline-diff 增量同步）。
     */
    fun fetchAssets(albumId: String, startDate: String? = null, endDate: String? = null): List<RemoteAsset> {
        val result = mutableListOf<RemoteAsset>()
        var pageNum = 0
        while (true) {
            val builder = baseUrl.newBuilder()
                .addPathSegments("gallery/user/galleries")
                .addQueryParameter("ts", clock().toString())
                .addQueryParameter("pageNum", pageNum.toString())
                .addQueryParameter("pageSize", "200")
                .addQueryParameter("albumId", albumId)
            if (startDate != null) builder.addQueryParameter("startDate", startDate)
            if (endDate != null) builder.addQueryParameter("endDate", endDate)
            val data = getData(builder.build().toString())
            val galleries = data.optJSONArray("galleries")
            for (i in 0 until (galleries?.length() ?: 0)) {
                result += parseAsset(galleries!!.getJSONObject(i))
            }
            if (data.optBoolean("isLastPage")) break
            pageNum++
        }
        return result
    }

    /** 相册时间线：indexHash（内容指纹）+ dayCount（每日数量），增量同步依据。 */
    fun fetchTimeline(albumId: String): AlbumTimeline {
        val url = baseUrl.newBuilder()
            .addPathSegments("gallery/user/timeline")
            .addQueryParameter("ts", clock().toString())
            .addQueryParameter("albumId", albumId)
            .build()
        val data = getData(url.toString())
        val dayCount = mutableMapOf<String, Long>()
        data.optJSONObject("dayCount")?.let { obj ->
            obj.keys().forEach { key -> dayCount[key] = obj.optLong(key) }
        }
        return AlbumTimeline(
            indexHash = data.optString("indexHash"),
            dayCount = dayCount,
        )
    }

    /** 解析签名直链（storage → JSONP），供 [download] 使用。 */
    fun resolveDownload(assetId: String): DownloadSpec {
        val url = baseUrl.newBuilder()
            .addPathSegments("gallery/storage")
            .addQueryParameter("ts", clock().toString())
            .addQueryParameter("id", assetId)
            .build()
        return downloader.resolveStorage(url.toString())
    }

    /**
     * 下载原图到 [target]（签名直链三步流），返回是否成功（false=云端已删除跳过）。
     * 流式输出到任意 [OutputStream]（文件 / SAF / 测试内存流），由调用方关闭。
     */
    fun download(assetId: String, target: OutputStream): Boolean = try {
        downloader.download(resolveDownload(assetId), target)
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

    private fun parseAsset(item: JSONObject): RemoteAsset = RemoteAsset(
        id = item.optString("id"),
        fileName = item.optString("fileName"),
        title = item.optString("title"),
        type = item.optString("type"),
        mimeType = item.optString("mimeType"),
        size = item.optLong("size"),
        sha1 = item.optString("sha1"),
        dateTaken = item.optLong("dateTaken"),
        thumbnailInfo = item.optJSONObject("thumbnailInfo")?.let { t ->
            ThumbnailInfo(
                data = t.optString("data").takeIf { it.isNotBlank() },
                isUrl = t.optBoolean("isUrl"),
            )
        },
    )

    private companion object {
        const val PRIVATE_ALBUM_ID = "1000"
    }
}
