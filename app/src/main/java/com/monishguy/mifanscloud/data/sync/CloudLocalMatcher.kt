package com.monishguy.mifanscloud.data.sync

import com.monishguy.mifanscloud.data.gallery.RemoteAsset
import kotlin.math.abs

/**
 * 云端资产 ↔ 本机媒体 **两级匹配器**。
 *
 * 快速级：云端 `size + dateTaken` 与本机 MediaStore `SIZE + DATE_TAKEN`
 * 精确等值 + 时间容差内 → 「本机已有」，无需下载原图；
 * （精确级：对存疑项可用 sha1 复核——本机文件哈希 vs 云端 sha1，M3 后按需接入。）
 *
 * 匹配结果按 [MatchStatus] 标记：本机已有 / 云端新增 / 已下载到本工具。
 * 纯逻辑、无 IO，可单元测试。
 */
object CloudLocalMatcher {

    /**
     * @param cloudAssets 云端清单（仅需 id/size/dateTaken）
     * @param localMedia  本机媒体索引（MediaStore 查询结果）
     * @param downloadedIds 本工具已下载的云端资产 id 集合
     * @param dateToleranceMs dateTaken 容差（默认 ±2s）
     * @return 云端资产 id → 匹配状态
     */
    fun match(
        cloudAssets: List<RemoteAsset>,
        localMedia: List<LocalMedia>,
        downloadedIds: Set<String>,
        dateToleranceMs: Long = DEFAULT_TOLERANCE_MS,
    ): Map<String, MatchStatus> {
        // 按字节数索引本机媒体（快表），避免 O(n×m)
        val localBySize: Map<Long, List<LocalMedia>> = localMedia.groupBy { it.sizeBytes }

        return cloudAssets.associate { asset ->
            val status = when {
                asset.id in downloadedIds -> MatchStatus.DOWNLOADED
                localBySize[asset.size]?.any { local ->
                    abs(local.dateTakenMs - asset.dateTaken) <= dateToleranceMs
                } == true -> MatchStatus.LOCAL_ALREADY
                else -> MatchStatus.NEW
            }
            asset.id to status
        }
    }

    private const val DEFAULT_TOLERANCE_MS = 2_000L
}
