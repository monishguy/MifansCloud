package com.monishguy.mifanscloud.data.local

import android.content.Context
import android.content.SharedPreferences
import com.monishguy.mifanscloud.data.gallery.RemoteAlbum
import com.monishguy.mifanscloud.data.gallery.RemoteAsset
import com.monishguy.mifanscloud.data.gallery.ThumbnailInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 相册元数据持久化缓存（按 userId 隔离）：
 * 保存相册列表（名称/数量/封面 URL）与各相册资产元数据（id/文件名/mime/size/dateTaken）。
 * 缩略图仅缓存 URL 形态（isUrl=true），内嵌 base64 不缓存（体积大）。
 * 用途：同一账号再次进入秒开（先显示缓存再网络刷新），换账号互不影响。
 */
class GalleryMetadataCache(
    context: Context,
    private val userIdProvider: () -> String?,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("gallery_meta_cache", Context.MODE_PRIVATE)

    private fun key(suffix: String): String? {
        val userId = userIdProvider() ?: return null
        return "gallery_$userId$suffix"
    }

    fun saveAlbums(albums: List<RemoteAlbum>) {
        val k = key("_albums") ?: return
        val arr = JSONArray()
        albums.forEach { a ->
            arr.put(
                JSONObject()
                    .put("albumId", a.albumId)
                    .put("name", a.name)
                    .put("mediaCount", a.mediaCount)
                    .put("lastUpdateTime", a.lastUpdateTime)
                    .put("isPrivate", a.isPrivate)
                    .put("covers", JSONArray().apply { a.coverUrls.forEach { put(it) } }),
            )
        }
        prefs.edit().putString(k, arr.toString()).apply()
    }

    fun loadAlbums(): List<RemoteAlbum>? {
        val k = key("_albums") ?: return null
        val raw = prefs.getString(k, null) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RemoteAlbum(
                    albumId = o.optString("albumId"),
                    name = o.optString("name"),
                    mediaCount = o.optInt("mediaCount"),
                    lastUpdateTime = o.optLong("lastUpdateTime"),
                    coverUrls = o.optJSONArray("covers")?.let { covers ->
                        (0 until covers.length()).mapNotNull { covers.optString(it).takeIf(String::isNotBlank) }
                    } ?: emptyList(),
                    isPrivate = o.optBoolean("isPrivate"),
                )
            }
        }.getOrNull()
    }

    fun saveAssets(albumId: String, assets: List<RemoteAsset>) {
        val k = key("_assets_$albumId") ?: return
        val arr = JSONArray()
        assets.forEach { a ->
            val o = JSONObject()
                .put("id", a.id)
                .put("fileName", a.fileName)
                .put("title", a.title)
                .put("type", a.type)
                .put("mimeType", a.mimeType)
                .put("size", a.size)
                .put("sha1", a.sha1)
                .put("dateTaken", a.dateTaken)
            a.thumbnailInfo?.takeIf { it.isUrl && !it.data.isNullOrBlank() }?.let { t ->
                o.put("thumbUrl", t.data)
            }
            arr.put(o)
        }
        prefs.edit().putString(k, arr.toString()).apply()
    }

    fun loadAssets(albumId: String): List<RemoteAsset>? {
        val k = key("_assets_$albumId") ?: return null
        val raw = prefs.getString(k, null) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RemoteAsset(
                    id = o.optString("id"),
                    fileName = o.optString("fileName"),
                    title = o.optString("title"),
                    type = o.optString("type"),
                    mimeType = o.optString("mimeType"),
                    size = o.optLong("size"),
                    sha1 = o.optString("sha1"),
                    dateTaken = o.optLong("dateTaken"),
                    thumbnailInfo = o.optString("thumbUrl").takeIf { it.isNotBlank() }
                        ?.let { ThumbnailInfo(data = it, isUrl = true) },
                )
            }
        }.getOrNull()
    }

    /** 保存全部照片合并清单（复用 saveAssets 的 all 键）。 */
    fun saveAllPhotos(assets: List<RemoteAsset>) = saveAssets(ALL_ALBUM_KEY, assets)

    fun loadAllPhotos(): List<RemoteAsset>? = loadAssets(ALL_ALBUM_KEY)

    private companion object {
        const val ALL_ALBUM_KEY = "__all__"
    }
}
