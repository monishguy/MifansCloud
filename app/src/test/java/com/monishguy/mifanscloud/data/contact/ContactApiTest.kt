package com.monishguy.mifanscloud.data.contact

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
 * ContactApi seam：通讯录清单解析（content map + letterIndex + syncTag）。
 */
class ContactApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ContactApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().followRedirects(false).build()
        api = ContactApi(
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
    fun `解析通讯录清单与参数`() {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{
                    "content":{
                        "c1":{"content":{"displayName":"张三","name":{"formatted":"张三"},
                            "phoneNumbers":[{"type":1,"value":"13800000001"}]},
                            "pinyin":"zhangsan","createTime":1,"updateTime":2}
                    },
                    "letterIndex":{"Z":["c1"]},
                    "syncTag":"tag-1","lastPage":true
                }}"""
            )
        )

        val page = api.fetchContacts()

        assertEquals(1, page.contacts.size)
        val c = page.contacts[0]
        assertEquals("c1", c.id)
        assertEquals("张三", c.displayName)
        assertEquals("zhangsan", c.pinyin)
        assertEquals(2L, c.updateTime)
        assertEquals(listOf(RemotePhoneNumber("1", "13800000001")), c.phoneNumbers)
        assertEquals("tag-1", page.syncTag)

        val req = server.takeRequest()
        assertEquals("/contacts/initdata", req.path?.substringBefore("?"))
        assertEquals("0", req.requestUrl?.queryParameter("syncTag"))
        assertTrue(req.requestUrl?.queryParameter("ts") == "1234567890")
    }
}
