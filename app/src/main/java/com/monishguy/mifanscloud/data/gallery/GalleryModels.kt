package com.monishguy.mifanscloud.data.gallery

/** 云端相册（`/gallery/user/album/list`）。 */
data class RemoteAlbum(
    val albumId: String,
    val name: String,
    val mediaCount: Int,
    val lastUpdateTime: Long,
    /** 相册封面缩略图 URL 列表（`numOfThumbnails=1` 时返回）。 */
    val coverUrls: List<String>,
    /** 私密相册（albumId=1000）：点击需输入密码，云端拉取能力未验证。 */
    val isPrivate: Boolean = false,
)

/** 资产缩略图：`data` 为内嵌 base64（isUrl=false）或 URL（isUrl=true）。 */
data class ThumbnailInfo(
    val data: String?,
    val isUrl: Boolean,
)

/** 云端相册资产（`/gallery/user/galleries`）。 */
data class RemoteAsset(
    val id: String,
    val fileName: String,
    val title: String,
    val type: String,
    val mimeType: String,
    val size: Long,
    val sha1: String,
    val dateTaken: Long,
    val thumbnailInfo: ThumbnailInfo?,
)

/** 相册时间线（`/gallery/user/timeline`）：内容指纹 + 每日数量，用于增量同步。 */
data class AlbumTimeline(
    val indexHash: String,
    val dayCount: Map<String, Long>,
)
