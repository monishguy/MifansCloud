package com.monishguy.mifanscloud.data.recording

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RecordingNameParser seam：小米云编码录音文件名还原。
 *
 * 语义与生产参考实现 XiaomiAlbumSyncer 完全一致：正则
 * `^(.+)\.([^._]+)_(\d+)_(\d+)_(\d+)_(\d+)$`，destructured 取
 * (base, ext, _, typeCode) → 类型码 = **第 2 段数字**（第 4 组）；
 * 异常格式剥四段 `_数字` 尾缀兜底。
 */
class RecordingNameParserTest {

    @Test
    fun `标准格式还原文件名与类型码（第 2 段数字为类型码）`() {
        val parsed = RecordingNameParser.parse("录音_20240801_0930.m4a_1_0_1_3")

        assertEquals("录音_20240801_0930.m4a", parsed.fileName)
        assertEquals(RecordingType.RECORDER, parsed.type) // 第 2 段数字 = 0
    }

    @Test
    fun `类型码 1 为通话录音`() {
        val parsed = RecordingNameParser.parse("通话_20240801_0930.m4a_0_1_2_3")

        assertEquals(RecordingType.PHONE_CALL, parsed.type) // 第 2 段数字 = 1
    }

    @Test
    fun `未知类型码映射为 UNKNOWN`() {
        val parsed = RecordingNameParser.parse("a.m4a_9_9_9_9")

        assertEquals(RecordingType.UNKNOWN, parsed.type)
    }

    @Test
    fun `异常格式从右侧剥四段数字尾缀兜底`() {
        val parsed = RecordingNameParser.parse("历史录音文件.m4a_1_2")

        assertEquals("历史录音文件.m4a", parsed.fileName)
        assertEquals(RecordingType.UNKNOWN, parsed.type)
    }
}
