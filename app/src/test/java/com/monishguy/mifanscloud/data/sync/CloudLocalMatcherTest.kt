package com.monishguy.mifanscloud.data.sync

import com.monishguy.mifanscloud.data.gallery.RemoteAsset
import com.monishguy.mifanscloud.data.gallery.ThumbnailInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CloudLocalMatcher seam：云端清单 ↔ 本机媒体匹配（本机已有 / 新增 / 已下载）。
 */
class CloudLocalMatcherTest {

    private fun asset(id: String, size: Long, dateTaken: Long = 1_000_000L) = RemoteAsset(
        id = id, fileName = "$id.jpg", title = id, type = "image",
        mimeType = "image/jpeg", size = size, sha1 = "s$id",
        dateTaken = dateTaken, thumbnailInfo = ThumbnailInfo(null, isUrl = false),
    )

    @Test
    fun `尺寸与拍摄时间命中判为本机已有`() {
        val cloud = listOf(asset("a1", 1000, dateTaken = 1_000_000L))
        val local = listOf(LocalMedia(id = 1, dateTakenMs = 1_000_000L, sizeBytes = 1000))

        val result = CloudLocalMatcher.match(cloud, local, downloadedIds = emptySet())

        assertEquals(MatchStatus.LOCAL_ALREADY, result["a1"])
    }

    @Test
    fun `时间在容差内仍判为本机已有`() {
        val cloud = listOf(asset("a1", 1000, dateTaken = 1_000_000L))
        val local = listOf(LocalMedia(id = 1, dateTakenMs = 1_000_500L, sizeBytes = 1000))

        val result = CloudLocalMatcher.match(cloud, local, downloadedIds = emptySet())

        assertEquals(MatchStatus.LOCAL_ALREADY, result["a1"])
    }

    @Test
    fun `尺寸不同判为云端新增`() {
        val cloud = listOf(asset("a1", 1000))
        val local = listOf(LocalMedia(id = 1, dateTakenMs = 1_000_000L, sizeBytes = 999))

        val result = CloudLocalMatcher.match(cloud, local, downloadedIds = emptySet())

        assertEquals(MatchStatus.NEW, result["a1"])
    }

    @Test
    fun `时间超出容差判为云端新增`() {
        val cloud = listOf(asset("a1", 1000, dateTaken = 1_000_000L))
        val local = listOf(LocalMedia(id = 1, dateTakenMs = 1_003_000L, sizeBytes = 1000))

        val result = CloudLocalMatcher.match(cloud, local, downloadedIds = emptySet())

        assertEquals(MatchStatus.NEW, result["a1"])
    }

    @Test
    fun `已下载优先于本机已有`() {
        val cloud = listOf(asset("a1", 1000))
        val local = listOf(LocalMedia(id = 1, dateTakenMs = 1_000_000L, sizeBytes = 1000))

        val result = CloudLocalMatcher.match(cloud, local, downloadedIds = setOf("a1"))

        assertEquals(MatchStatus.DOWNLOADED, result["a1"])
    }

    @Test
    fun `本机为空时全部判为新增`() {
        val cloud = listOf(asset("a1", 1000), asset("a2", 2000))

        val result = CloudLocalMatcher.match(cloud, localMedia = emptyList(), downloadedIds = emptySet())

        assertEquals(mapOf("a1" to MatchStatus.NEW, "a2" to MatchStatus.NEW), result)
    }

    @Test
    fun `大清单按尺寸索引无漏判`() {
        val cloud = (0 until 500).map { asset("c$it", 1000L + it, dateTaken = 1_000_000L + it) }
        // 本机只有一半的条目（偶数 id），尺寸与时间一一对应
        val local = (0 until 500 step 2).map { LocalMedia(id = it.toLong(), dateTakenMs = 1_000_000L + it, sizeBytes = 1000L + it) }

        val result = CloudLocalMatcher.match(cloud, local, downloadedIds = emptySet())

        assertEquals(MatchStatus.LOCAL_ALREADY, result["c0"])
        assertEquals(MatchStatus.NEW, result["c1"])
        assertEquals(MatchStatus.LOCAL_ALREADY, result["c498"])
        assertEquals(MatchStatus.NEW, result["c499"])
    }
}
