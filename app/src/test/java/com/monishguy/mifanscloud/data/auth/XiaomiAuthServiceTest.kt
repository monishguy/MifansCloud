package com.monishguy.mifanscloud.data.auth

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * XiaomiAuthService 认证链 seam（MockWebServer 验证，不依赖真实小米服务）：
 * 三步换取链的请求形态、cookie 注入、错误路径、10 分钟刷新策略。
 */
class XiaomiAuthServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var service: XiaomiAuthService
    private val credentials = XiaomiCredential.PassToken("42", "pt_abc")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = XiaomiAuthService(
            client = OkHttpClient.Builder().followRedirects(false).build(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueExchange(token: String, requestId: Int = 1) {
        val loginUrl = server.url("/mock/login-$requestId").toString()
        server.enqueue(
            MockResponse().setBody("""{"data":{"loginUrl":"$loginUrl"}}""")
        )
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", server.url("/mock/token-$requestId").toString())
        )
        server.enqueue(
            MockResponse().addHeader("Set-Cookie", "serviceToken=$token; Path=/; HttpOnly").setBody("{}")
        )
    }

    @Test
    fun `三步链成功换取 serviceToken 并携带正确 cookie`() {
        enqueueExchange("st_123")

        val session = service.exchange(credentials)

        assertEquals("st_123", session.serviceToken)

        // ① 预登录
        val preLogin = server.takeRequest()
        assertEquals("/api/user/login", preLogin.path?.substringBefore("?"))
        assertNotNull(preLogin.requestUrl?.queryParameter("ts"))
        assertNotNull(preLogin.requestUrl?.queryParameter("followUp"))
        assertEquals("zh_CN", preLogin.requestUrl?.queryParameter("_locale"))
        val preLoginCookie = preLogin.getHeader("Cookie").orEmpty()
        assertTrue("预登录应携带 userId", preLoginCookie.contains("userId=42"))
        assertTrue("预登录应携带 passToken", preLoginCookie.contains("passToken=pt_abc"))
        assertTrue("预登录应携带 wb_ 前缀 deviceId", preLoginCookie.contains("deviceId=wb_"))

        // ② 跟随 loginUrl，手动读 Location
        val follow = server.takeRequest()
        assertEquals("/mock/login-1", follow.path?.substringBefore("?"))

        // ③ 换取 token
        val token = server.takeRequest()
        assertEquals("/mock/token-1", token.path?.substringBefore("?"))
        assertTrue("换取请求应继续携带 userId", token.getHeader("Cookie").orEmpty().contains("userId=42"))
    }

    @Test
    fun `第二步无 Location 抛认证异常`() {
        server.enqueue(MockResponse().setBody("""{"data":{"loginUrl":"${server.url("/x")}"}}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("not a redirect"))

        assertThrows(XiaomiAuthException::class.java) { service.exchange(credentials) }
    }

    @Test
    fun `第三步无 serviceToken Set-Cookie 抛认证异常`() {
        server.enqueue(MockResponse().setBody("""{"data":{"loginUrl":"${server.url("/x")}"}}"""))
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/token").toString()))
        server.enqueue(MockResponse().setBody("{}"))

        assertThrows(XiaomiAuthException::class.java) { service.exchange(credentials) }
    }

    @Test
    fun `预登录非 2xx 抛认证异常`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        assertThrows(XiaomiAuthException::class.java) { service.exchange(credentials) }
    }

    @Test
    fun `10 分钟内复用缓存 token 不再请求网络`() {
        var now = 0L
        service = XiaomiAuthService(
            client = OkHttpClient.Builder().followRedirects(false).build(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
            clock = { now },
        )
        enqueueExchange("st_1")

        val first = service.getServiceToken(credentials)
        val second = service.getServiceToken(credentials)

        assertEquals("st_1", first.serviceToken)
        assertEquals("st_1", second.serviceToken)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `超过 10 分钟强制重新换取`() {
        var now = 0L
        service = XiaomiAuthService(
            client = OkHttpClient.Builder().followRedirects(false).build(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
            clock = { now },
        )
        enqueueExchange("st_1")
        service.getServiceToken(credentials)

        now = XiaomiAuthService.REFRESH_INTERVAL_MS + 1
        enqueueExchange("st_2", requestId = 2)

        val refreshed = service.getServiceToken(credentials)

        assertEquals("st_2", refreshed.serviceToken)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `缓存按凭证隔离，10 分钟内换凭证即重新换取`() {
        var now = 0L
        service = XiaomiAuthService(
            client = OkHttpClient.Builder().followRedirects(false).build(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
            clock = { now },
        )
        enqueueExchange("st_A")
        service.getServiceToken(credentials)

        enqueueExchange("st_B", requestId = 2)
        val other = service.getServiceToken(XiaomiCredential.PassToken("99", "pt_other"))

        assertEquals("st_B", other.serviceToken)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `ServiceToken 直连凭证直接返回且不发起任何网络请求`() {
        var now = 0L
        service = XiaomiAuthService(
            client = OkHttpClient.Builder().followRedirects(false).build(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
            clock = { now },
        )
        val direct = XiaomiCredential.ServiceToken("42", "st_direct")

        val session = service.getServiceToken(direct)

        assertEquals("st_direct", session.serviceToken)
        assertEquals(0, server.requestCount)
    }
}
