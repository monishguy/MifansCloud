package com.monishguy.mifanscloud.data.gallery

import com.monishguy.mifanscloud.data.auth.XiaomiAuthService
import com.monishguy.mifanscloud.data.auth.XiaomiCredential
import com.monishguy.mifanscloud.data.remote.MediaDeletedException
import com.monishguy.mifanscloud.data.remote.XiaomiApiClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * GalleryApi seam：相册/资产/时间线/签名直链下载（MockWebServer 验证，
 * 使用 serviceToken 直连凭证，认证零网络开销）。
 */
class GalleryApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: GalleryApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().followRedirects(false).build()
        val apiClient = XiaomiApiClient(
            client = client,
            auth = XiaomiAuthService(client, server.url("/").toString().removeSuffix("/")),
            credentialsProvider = { XiaomiCredential.ServiceToken("42", "st_direct") },
        )
        api = GalleryApi(
            apiClient = apiClient,
            baseUrl = server.url("/").toString().removeSuffix("/"),
            clock = { 1_234_567_890L },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `拉取全部相册支持分页并解析封面缩略图`() {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"albums":[
                    {"albumId":"1","name":"相机","mediaCount":100,"lastUpdateTime":11,
                     "thumbnails":[{"url":"http://cover/1","orientation":0}]},
                    {"albumId":"1000","name":"私密","mediaCount":5,"lastUpdateTime":12,
                     "thumbnails":[]}
                ],"isLastPage":false}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"albums":[
                    {"albumId":"2","name":"屏幕截图","mediaCount":3,"lastUpdateTime":13,"thumbnails":[]}
                ],"isLastPage":true}}"""
            )
        )

        val albums = api.fetchAlbums()

        assertEquals(2, albums.size) // 私密相册 1000 被跳过
        assertEquals("1", albums[0].albumId)
        assertEquals("相机", albums[0].name)
        assertEquals(100, albums[0].mediaCount)
        assertEquals(listOf("http://cover/1"), albums[0].coverUrls)
        assertEquals("2", albums[1].albumId)

        val first = server.takeRequest()
        assertEquals("/gallery/user/album/list", first.path?.substringBefore("?"))
        assertEquals("1", first.requestUrl?.queryParameter("numOfThumbnails"))
        assertEquals("false", first.requestUrl?.queryParameter("isShared"))
        assertEquals("0", first.requestUrl?.queryParameter("pageNum"))
        val second = server.takeRequest()
        assertEquals("1", second.requestUrl?.queryParameter("pageNum"))
    }

    @Test
    fun `拉取资产解析字段与缩略图并支持日期过滤`() {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"galleries":[
                    {"id":"9001","fileName":"a.jpg","title":"A","type":"image",
                     "mimeType":"image/jpeg","size":1234,"sha1":"s1","dateTaken":999,
                     "thumbnailInfo":{"data":"","isUrl":true}},
                    {"id":"9002","fileName":"b.jpg","title":"B","type":"image",
                     "mimeType":"image/jpeg","size":5678,"sha1":"s2","dateTaken":1000,
                     "thumbnailInfo":{"data":"base64data","isUrl":false}}
                ],"isLastPage":true}}"""
            )
        )

        val assets = api.fetchAssets(albumId = "1", startDate = "20240801", endDate = "20240801")

        assertEquals(2, assets.size)
        val a = assets[0]
        assertEquals("9001", a.id)
        assertEquals("a.jpg", a.fileName)
        assertEquals(1234L, a.size)
        assertEquals(999L, a.dateTaken)
        assertTrue(a.thumbnailInfo?.isUrl == true)
        assertNull(a.thumbnailInfo?.data)
        val b = assets[1]
        assertEquals("base64data", b.thumbnailInfo?.data)
        assertFalse(b.thumbnailInfo?.isUrl == true)

        val req = server.takeRequest()
        assertEquals("/gallery/user/galleries", req.path?.substringBefore("?"))
        assertEquals("1", req.requestUrl?.queryParameter("albumId"))
        assertEquals("200", req.requestUrl?.queryParameter("pageSize"))
        assertEquals("20240801", req.requestUrl?.queryParameter("startDate"))
        assertEquals("20240801", req.requestUrl?.queryParameter("endDate"))
    }

    @Test
    fun `拉取时间线解析 indexHash 与 dayCount`() {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"indexHash":"abc123","dayCount":{"20240801":3,"20240802":5}}}"""
            )
        )

        val timeline = api.fetchTimeline(albumId = "1")

        assertEquals("abc123", timeline.indexHash)
        assertEquals(mapOf("20240801" to 3L, "20240802" to 5L), timeline.dayCount)
        assertEquals("1", server.takeRequest().requestUrl?.queryParameter("albumId"))
    }

    @Test
    fun `解析签名直链走 storage 与 JSONP 两步并携带认证 cookie`() {
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"data":{"url":"${server.url("/oss")}"}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody("""dl_callback({"url":"${server.url("/download")}","meta":"m_123"})""")
        )

        val spec = api.resolveDownload(assetId = "9001")

        assertEquals(server.url("/download").toString(), spec.url)
        assertEquals("m_123", spec.meta)

        val storage = server.takeRequest()
        assertEquals("/gallery/storage", storage.path?.substringBefore("?"))
        assertEquals("9001", storage.requestUrl?.queryParameter("id"))
        assertTrue("storage 请求应带认证 cookie", storage.getHeader("Cookie").orEmpty().contains("userId=42"))
    }

    @Test
    fun `云端已删除的文件抛 MediaDeletedException`() {
        server.enqueue(MockResponse().setBody("""{"code":50050,"message":"media deleted"}"""))

        assertThrows(MediaDeletedException::class.java) {
            api.resolveDownload(assetId = "9001")
        }
    }

    @Test
    fun `下载走三步签名直链并写入输出流`() {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"url":"${server.url("/oss")}"}}"""))
        server.enqueue(MockResponse().setBody("""dl_callback({"url":"${server.url("/dl")}","meta":"m9"})"""))
        server.enqueue(MockResponse().setBody("JPEG-BYTES"))

        val sink = java.io.ByteArrayOutputStream()
        val ok = api.download(assetId = "9001", target = sink)

        assertTrue(ok)
        assertEquals("JPEG-BYTES", sink.toString("UTF-8"))

        server.takeRequest() // storage
        val jsonp = server.takeRequest()    // JSONP
        val post = server.takeRequest()     // 最终下载 POST
        assertEquals("/dl", post.path?.substringBefore("?"))
        assertTrue("下载 POST 应携带 meta 表单", post.body.readUtf8().contains("meta=m9"))
        assertTrue(jsonp.path?.startsWith("/oss") == true)
    }
}
