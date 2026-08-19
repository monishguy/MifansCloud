package com.monishguy.mifanscloud.data.sync

/**
 * 测试用本地媒体源 fake。
 */
class FakeLocalMediaSource(
    private val media: List<LocalMedia>,
) : LocalMediaSource {
    override fun queryImagesAndVideos(): List<LocalMedia> = media
}
