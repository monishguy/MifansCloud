package com.monishguy.mifanscloud.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DownloadedStore seam：命名空间隔离的已下载清单增查与跨实例持久化。
 */
class DownloadedStoreTest {

    @Test
    fun `命名空间隔离：同 id 不同命名空间互不干扰`() {
        val store = DownloadedStore(File.createTempFile("dlt", ".json").apply { deleteOnExit() })

        store.add("gallery", "9001", "a.jpg")
        store.add("recording", "9001", "rec.m4a")

        assertEquals(setOf("9001"), store.ids("gallery"))
        assertEquals(setOf("9001"), store.ids("recording"))
        assertEquals("a.jpg", store.fileNameOf("gallery", "9001"))
        assertEquals("rec.m4a", store.fileNameOf("recording", "9001"))
        assertNull(store.fileNameOf("recording", "a.jpg"))
    }

    @Test
    fun `跨实例持久化（重新读取同一文件）`() {
        val file = File.createTempFile("dlt", ".json").apply { deleteOnExit() }
        DownloadedStore(file).add("gallery", "9001", "a.jpg")

        val reloaded = DownloadedStore(file)

        assertTrue("9001" in reloaded.ids("gallery"))
        assertEquals("a.jpg", reloaded.fileNameOf("gallery", "9001"))
    }

    @Test
    fun `空文件与不存在文件返回空`() {
        val store = DownloadedStore(File.createTempFile("dlt", ".json").apply { deleteOnExit() })

        assertTrue(store.ids("gallery").isEmpty())
        assertNull(store.fileNameOf("gallery", "9001"))
    }
}
