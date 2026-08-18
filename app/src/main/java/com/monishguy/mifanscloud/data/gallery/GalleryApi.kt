package com.monishguy.mifanscloud.data.gallery

import com.monishguy.mifanscloud.data.remote.DownloadSpec
import com.monishguy.mifanscloud.data.remote.MediaDeletedException
import com.monishguy.mifanscloud.data.remote.SignedDownloader
import com.monishguy.mifanscloud.data.remote.XiaomiApiClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
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
    /** 上传用 serviceToken（form 参数认证，网页端同款）；获取失败返回 null。 */
    private val serviceTokenProvider: () -> String? = { null },
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

    /**
     * 拉取**全部照片单页**（网页「照片」视图同款接口：**不带 albumId**，
     * 按时间线分页，不经过相册列表逐个拉取）。
     * @return (本页资产, 是否最后一页)
     */
    fun fetchAllPhotosPage(pageNum: Int): Pair<List<RemoteAsset>, Boolean> {
        val url = baseUrl.newBuilder()
            .addPathSegments("gallery/user/galleries")
            .addQueryParameter("ts", clock().toString())
            .addQueryParameter("pageNum", pageNum.toString())
            .addQueryParameter("pageSize", "200")
            .build()
        val data = getData(url.toString())
        val galleries = data.optJSONArray("galleries")
        val page = mutableListOf<RemoteAsset>()
        for (i in 0 until (galleries?.length() ?: 0)) {
            page += parseAsset(galleries!!.getJSONObject(i))
        }
        return page to data.optBoolean("isLastPage")
    }

    /**
     * 拉取全部照片（直连接口分页循环；不支持时抛异常由调用方回退）。
     */
    fun fetchAllPhotos(): List<RemoteAsset> {
        val result = mutableListOf<RemoteAsset>()
        var pageNum = 0
        while (true) {
            val (page, isLast) = fetchAllPhotosPage(pageNum)
            result += page
            if (isLast) break
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

    /**
     * 上传照片（HAR 逆向的四步链路，网页端同款）：
     * ① POST /gallery/user/full 预上传（data JSON + serviceToken）→ 拿
     *    kss(file_meta/block_metas/node_urls) 与新 asset id；
     * ② POST {node}/upload_block_chunk?chunk_pos=N&&file_meta&block_meta
     *    逐块上传原始字节 → 每块返回 commit_meta（base64 JSON）；
     * ③ POST /gallery/user/full/{id}/storage 提交全部 commit_metas → 完成；
     * ④ POST /gallery/user/lite/index/prepare 刷新索引（尽力而为）。
     *
     * 分块由客户端自定：≤[CHUNK_SIZE] 单块，更大按块切（block_infos 逐块
     * 声明 size/md5/sha1）。[onProgress] 上报已上传字节。
     * 成功返回新资产 id；失败抛异常。
     */
    fun uploadPhoto(
        file: java.io.File,
        fileName: String,
        mimeType: String,
        groupId: String,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): String {
        val serviceToken = serviceTokenProvider()
            ?: throw IllegalStateException("缺少 serviceToken，无法上传")
        val totalSize = file.length()
        val fileSha1 = digestHex(file, "SHA-1")

        // 分块声明（每块 size/md5/sha1，由客户端自定块大小）
        val blocks = buildBlocks(file, CHUNK_SIZE)
        val blockInfos = org.json.JSONArray()
        blocks.forEach { (start, size) ->
            blockInfos.put(
                org.json.JSONObject()
                    .put("blob", org.json.JSONObject())
                    .put("size", size)
                    .put("md5", blockDigestHex(file, start, size, "MD5"))
                    .put("sha1", blockDigestHex(file, start, size, "SHA-1")),
            )
        }
        val data1 = org.json.JSONObject()
            .put(
                "content",
                org.json.JSONObject()
                    .put("type", "image")
                    .put("groupId", groupId)
                    .put("mimeType", mimeType)
                    .put("fileName", fileName)
                    .put("title", fileName.substringBeforeLast('.'))
                    .put("sha1", fileSha1)
                    .put("size", totalSize)
                    .put("dateModified", clock()),
            )
            .put("block_infos", blockInfos)

        // ① 预上传
        val prepareBody = formBody(
            "data" to data1.toString(),
            "isClientUploadThumbnail" to "false",
            "serviceToken" to serviceToken,
        )
        val prepared = postJson(baseUrl.newBuilder().addPathSegments("gallery/user/full").build().toString(), prepareBody)
        val kss = prepared.optJSONObject("data")?.optJSONObject("kss")
            ?: throw IllegalStateException("预上传响应缺少 kss: ${prepared.optString("description")}")
        val assetId = prepared.optJSONObject("data")?.optJSONObject("content")?.optString("id")
            ?: throw IllegalStateException("预上传响应缺少资产 id")
        val fileMeta = kss.optString("file_meta").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("预上传响应缺少 file_meta")
        val blockMetas = kss.optJSONArray("block_metas") ?: org.json.JSONArray()
        val nodeUrl = kss.optJSONArray("node_urls")?.optString(0) ?: ""

        // ② 逐块上传
        val commitMetas = org.json.JSONArray()
        blocks.forEachIndexed { index, (start, size) ->
            val blockMeta = blockMetas.optJSONObject(index)?.optString("block_meta")
                ?: throw IllegalStateException("预上传响应缺少第 $index 块 block_meta")
            if (blockMetas.optJSONObject(index)?.optInt("is_existed", 0) != 1) {
                val chunkUrl = "$nodeUrl/upload_block_chunk?chunk_pos=$index&&file_meta=${urlEncode(fileMeta)}&block_meta=${urlEncode(blockMeta)}"
                val chunkBody = readBlock(file, start, size)
                val resp = postBytes(chunkUrl, chunkBody)
                // 分片响应为 base64 编码的 JSON（服务端返回），先解码再解析
                val respJson = try {
                    JSONObject(resp)
                } catch (e: org.json.JSONException) {
                    val raw = decodeBase64(resp) ?: throw IllegalStateException("分片上传响应无法解析")
                    JSONObject(raw)
                }
                val commitMeta = respJson.optString("commit_meta")
                    ?: throw IllegalStateException("第 $index 块上传响应缺少 commit_meta")
                commitMetas.put(org.json.JSONObject().put("commit_meta", commitMeta))
            }
            onProgress(((index + 1).toLong()) * size, totalSize)
        }

        // ③ 完成注册
        val storageData = org.json.JSONObject()
            .put(
                "kss",
                org.json.JSONObject()
                    .put("file_meta", fileMeta)
                    .put("commit_metas", commitMetas),
            )
        val storageBody = formBody(
            "data" to storageData.toString(),
            "serviceToken" to serviceToken,
        )
        val storageUrl = baseUrl.newBuilder()
            .addPathSegments("gallery/user/full/$assetId/storage")
            .build()
        postJson(storageUrl.toString(), storageBody)

        // ④ 刷新索引（尽力而为，失败忽略）
        runCatching {
            postJson(
                baseUrl.newBuilder().addPathSegments("gallery/user/lite/index/prepare").build().toString(),
                formBody("serviceToken" to serviceToken),
            )
        }
        return assetId
    }

    private fun postJson(url: String, body: okhttp3.RequestBody): JSONObject =
        apiClient.execute(okhttp3.Request.Builder().url(url).post(body).build()).use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("请求失败 HTTP ${resp.code}: $url")
            val text = resp.body?.string().orEmpty()
            val json = JSONObject(text)
            if (json.optInt("code", 0) != 0 && json.optString("result", "ok") != "ok") {
                throw IllegalStateException("接口错误: ${json.optString("description")}")
            }
            json
        }

    private fun postBytes(url: String, bytes: ByteArray): String =
        apiClient.execute(
            okhttp3.Request.Builder()
                .url(url)
                .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
                .build(),
        ).use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("分片上传失败 HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }

    private fun formBody(vararg pairs: Pair<String, String>): okhttp3.FormBody =
        okhttp3.FormBody.Builder().apply { pairs.forEach { (k, v) -> add(k, v) } }.build()

    /** 文件分块：返回 [(start, size)]。 */
    private fun buildBlocks(file: java.io.File, chunkSize: Int): List<Pair<Long, Int>> {
        val total = file.length()
        if (total <= chunkSize) return listOf(0L to total.toInt())
        val blocks = mutableListOf<Pair<Long, Int>>()
        var offset = 0L
        while (offset < total) {
            val size = minOf(chunkSize.toLong(), total - offset).toInt()
            blocks += offset to size
            offset += size
        }
        return blocks
    }

    private fun readBlock(file: java.io.File, start: Long, size: Int): ByteArray =
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(size)
            raf.readFully(buf)
            buf
        }

    private fun blockDigestHex(file: java.io.File, start: Long, size: Int, algorithm: String): String =
        java.security.MessageDigest.getInstance(algorithm).let { digest ->
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(64 * 1024)
                var remaining = size
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size, remaining))
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    remaining -= n
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    private fun digestHex(file: java.io.File, algorithm: String): String =
        java.security.MessageDigest.getInstance(algorithm).let { digest ->
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** 解码 base64（标准与 URL-safe 兼容），失败返回 null。 */
    private fun decodeBase64(s: String): String? = runCatching {
        val bytes = try {
            java.util.Base64.getDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            java.util.Base64.getUrlDecoder().decode(s)
        }
        String(bytes, Charsets.UTF_8)
    }.getOrNull()

    private fun ByteArray.toRequestBody(mediaType: okhttp3.MediaType): okhttp3.RequestBody =
        okhttp3.RequestBody.create(mediaType, this)

    private companion object {
        const val PRIVATE_ALBUM_ID = "1000"

        /** 上传分块大小：4 MiB。 */
        const val CHUNK_SIZE = 4 * 1024 * 1024
    }
}
