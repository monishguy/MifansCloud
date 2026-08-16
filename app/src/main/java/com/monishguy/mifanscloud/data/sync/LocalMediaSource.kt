package com.monishguy.mifanscloud.data.sync

/**
 * 云端资产与本机媒体的匹配状态。
 * - [LOCAL_ALREADY]：本机媒体库已存在（dateTaken+size 命中）——**无需下载**；
 * - [NEW]：云端新增，本机没有——按需下载候选；
 * - [DOWNLOADED]：本工具已下载到备份目录（优先于 LOCAL_ALREADY）。
 */
enum class MatchStatus { LOCAL_ALREADY, NEW, DOWNLOADED }

/** 本机媒体库条目（MediaStore 精简投影）。 */
data class LocalMedia(
    val id: Long,
    val dateTakenMs: Long,
    val sizeBytes: Long,
)

/** 本机媒体库数据源（Android MediaStore 实现见 [MediaStoreLocalMediaSource]）。 */
interface LocalMediaSource {
    fun queryImagesAndVideos(): List<LocalMedia>
}
