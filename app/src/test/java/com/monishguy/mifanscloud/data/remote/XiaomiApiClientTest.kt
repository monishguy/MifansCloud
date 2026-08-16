package com.monishguy.mifanscloud.data.remote

import com.monishguy.mifanscloud.data.auth.XiaomiAuthService
import com.monishguy.mifanscloud.data.auth.XiaomiCredentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * XiaomiApiClient 401 处理 seam：会话失效时刷新 token 并重发一次。
 */
class XiaomiApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: XiaomiApiClient
    private val credentials = XiaomiCredentials("42", "pt_abc")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = XiaomiApiClient(
            client = OkHttpClient.Builder().followRedirects(false).build(),
            auth = XiaomiAuthService(
                client = OkHttpClient.Builder().followRedirects(false).build(),
                baseUrl = server.url("/").toString().removeSuffix("/"),
            ),
            credentialsProvider = { credentials },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueExchange(token: String, requestId: Int) {
        val loginUrl = server.url("/mock/login-$requestId").toString()
        server.enqueue(MockResponse().setBody("""{"data":{"loginUrl":"$loginUrl"}}"""))
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", server.url("/mock/token-$requestId").toString())
        )
        server.enqueue(
            MockResponse().addHeader("Set-Cookie", "serviceToken=$token; Path=/").setBody("{}")
        )
    }

    @Test
    fun `401 时刷新 token 并以新 token 重发一次`() {
        enqueueExchange("st_1", requestId = 1)
        server.enqueue(MockResponse().setResponseCode(401))
        enqueueExchange("st_2", requestId = 2)
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val request = Request.Builder().url(server.url("/gallery/user/album/list")).get().build()

        client.execute(request).use { resp ->
            assertEquals(200, resp.code)
            assertEquals("ok", resp.body?.string())
        }

        // 换取1(3) + 401(1) + 换取2(3) + 重发(1) = 8
        assertEquals(8, server.requestCount)

        repeat(3) { server.takeRequest() }   // 换取 st_1
        val first = server.takeRequest()
        assertEquals("/gallery/user/album/list", first.path?.substringBefore("?")) // 原始请求命中 401
        repeat(3) { server.takeRequest() }   // 换取 st_2
        val retried = server.takeRequest()
        assertEquals("/gallery/user/album/list", retried.path?.substringBefore("?"))
        assertTrue("重发请求应携带新 serviceToken", retried.getHeader("Cookie").orEmpty().contains("serviceToken=st_2"))
        assertTrue("重发请求应携带 userId", retried.getHeader("Cookie").orEmpty().contains("userId=42"))
    }

    @Test
    fun `非 401 响应直接返回不触发刷新`() {
        enqueueExchange("st_1", requestId = 1)
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val request = Request.Builder().url(server.url("/gallery/user/album/list")).get().build()

        client.execute(request).use { resp ->
            assertEquals(200, resp.code)
        }

        assertEquals(4, server.requestCount)
    }
}
