package com.monishguy.mifanscloud.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DownloadedStore seam：已下载清单的增查与跨实例持久化。
 */
class DownloadedStoreTest {

    @Test
    fun `新增后可查询 id 与文件名`() {
        val store = DownloadedStore(File.createTempFile("dlt", ".json").apply { deleteOnExit() })

        store.add("9001", "a.jpg")
        store.add("9002", "b.jpg")

        assertEquals(setOf("9001", "9002"), store.ids())
        assertEquals("a.jpg", store.fileNameOf("9001"))
    }

    @Test
    fun `跨实例持久化（重新读取同一文件）`() {
        val file = File.createTempFile("dlt", ".json").apply { deleteOnExit() }
        DownloadedStore(file).add("9001", "a.jpg")

        val reloaded = DownloadedStore(file)

        assertTrue("9001" in reloaded.ids())
        assertEquals("a.jpg", reloaded.fileNameOf("9001"))
    }

    @Test
    fun `空文件与不存在文件返回空`() {
        val store = DownloadedStore(File.createTempFile("dlt", ".json").apply { deleteOnExit() })

        assertTrue(store.ids().isEmpty())
        assertNull(store.fileNameOf("9001"))
    }
}
