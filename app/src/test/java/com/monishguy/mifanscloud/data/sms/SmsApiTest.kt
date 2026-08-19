package com.monishguy.mifanscloud.data.sms

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

/**
 * SmsApi seam：短信线程清单解析（entries + watermark 游标）。
 */
class SmsApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: SmsApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().followRedirects(false).build()
        api = SmsApi(
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
    fun `解析短信线程清单与水印游标`() {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{
                    "entries":[
                        {"entry":{"id":"m1","threadId":"t1","snippet":"你好","recipients":"13800000001",
                            "folder":"inbox","lastUpdateTime":1000,"unread":1,"total":3},
                         "operation":"add"}
                    ],
                    "watermark":{"syncTag":"w-1","syncThreadTag":"wt-1"}
                }}"""
            )
        )

        val page = api.fetchMessages()

        assertEquals(1, page.messages.size)
        val m = page.messages[0]
        assertEquals("m1", m.id)
        assertEquals("t1", m.threadId)
        assertEquals("你好", m.snippet)
        assertEquals("inbox", m.folder)
        assertEquals(1, m.unread)
        assertEquals("w-1", page.syncTag)
        assertEquals("wt-1", page.syncThreadTag)

        val req = server.takeRequest()
        assertEquals("/sms/full/thread", req.path?.substringBefore("?"))
        assertEquals("older", req.requestUrl?.queryParameter("readMode"))
        assertTrue(req.requestUrl?.queryParameter("_dc") == "1234567890")
    }
}
