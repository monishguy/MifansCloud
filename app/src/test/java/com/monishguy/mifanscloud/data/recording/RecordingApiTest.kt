package com.monishguy.mifanscloud.data.recording

import com.monishguy.mifanscloud.data.auth.XiaomiAuthService
import com.monishguy.mifanscloud.data.auth.XiaomiCredential
import com.monishguy.mifanscloud.data.remote.XiaomiApiClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * RecordingApi seam：录音列表分页（无 isLastPage，按返回条数判断）与签名直链下载。
 */
class RecordingApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: RecordingApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().followRedirects(false).build()
        api = RecordingApi(
            apiClient = XiaomiApiClient(
                client = client,
                auth = XiaomiAuthService(client, server.url("/").toString().removeSuffix("/")),
                credentialsProvider = { XiaomiCredential.ServiceToken("42", "st_direct") },
            ),
            baseUrl = server.url("/").toString().removeSuffix("/"),
            clock = { 1_234_567_890L },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `列表分页按返回条数判断是否继续并解析编码文件名`() {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"list":[
                    {"id":"11","name":"录音_20240801.m4a_1_0_1_3","sha1":"s1","size":2048,
                     "create_time":1710000000000}
                ]}}"""
            )
        )

        val recordings = api.fetchRecordings()

        assertEquals(1, recordings.size)
        val r = recordings[0]
        assertEquals("11", r.id)
        assertEquals("录音_20240801.m4a", r.fileName)
        assertEquals(RecordingType.RECORDER, r.type) // 第 2 段数字 = 0
        assertEquals(2048L, r.size)

        val req = server.takeRequest()
        assertEquals("/sfs/ns/recorder/dir/0/list", req.path?.substringBefore("?"))
        assertEquals("0", req.requestUrl?.queryParameter("offset"))
        assertEquals("500", req.requestUrl?.queryParameter("limit"))
    }

    @Test
    fun `满页继续翻页直到不足 limit`() {
        // 第一页返回 500 条（满页）→ 继续；第二页返回 1 条 → 停止
        val full = (0 until 500).joinToString(",") { i ->
            """{"id":"$i","name":"r_$i.m4a_1_0_1_3","sha1":"s","size":100,"create_time":1}"""
        }
        server.enqueue(MockResponse().setBody("""{"data":{"list":[$full]}}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"list":[
            {"id":"999","name":"last.m4a_1_0_1_3","sha1":"s","size":100,"create_time":1}
        ]}}"""))

        val recordings = api.fetchRecordings()

        assertEquals(501, recordings.size)
        assertEquals(2, server.requestCount)
        assertEquals("0", server.takeRequest().requestUrl?.queryParameter("offset"))
        assertEquals("500", server.takeRequest().requestUrl?.queryParameter("offset"))
    }

    @Test
    fun `下载走录音专属中转 URL 的三步签名直链`() {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"url":"${server.url("/oss")}"}}"""))
        server.enqueue(MockResponse().setBody("""dl_callback({"url":"${server.url("/dl")}","meta":"m1"})"""))
        server.enqueue(MockResponse().setBody("AUDIO-BYTES"))

        val sink = ByteArrayOutputStream()
        val ok = api.download("11", sink)

        assertTrue(ok)
        assertEquals("AUDIO-BYTES", sink.toString("UTF-8"))

        val storage = server.takeRequest()
        assertTrue(
            "storage path 不符: ${storage.path}",
            storage.path.orEmpty().startsWith("/sfs/ns/recorder/file/11/cb/dl_sfs_cb_") &&
                storage.path.orEmpty().contains("/storage?"),
        )
        assertTrue(storage.getHeader("Cookie").orEmpty().contains("userId=42"))
    }
}
