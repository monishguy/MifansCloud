package com.monishguy.mifanscloud.data.note

import com.monishguy.mifanscloud.data.auth.XiaomiAuthService
import com.monishguy.mifanscloud.data.auth.XiaomiCredential
import com.monishguy.mifanscloud.data.remote.XiaomiApiClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * NoteApi seam：笔记清单解析（entries/folders/syncTag，folderId int|string 兼容）。
 */
class NoteApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: NoteApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().followRedirects(false).build()
        api = NoteApi(
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
    fun `解析笔记清单，folderId 兼容 int 与 string`() {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{
                    "entries":[
                        {"id":"n1","subject":"备忘","snippet":"买东西","content":"买东西\n牛奶",
                         "folderId":5,"modifyDate":1000},
                        {"id":"n2","subject":"","snippet":"","content":"",
                         "folderId":"f-2","modifyDate":2000}
                    ],
                    "folders":[],
                    "syncTag":"st-9","lastPage":true
                }}"""
            )
        )

        val page = api.fetchNotes()

        assertEquals(2, page.notes.size)
        assertEquals("n1", page.notes[0].id)
        assertEquals("买东西\n牛奶", page.notes[0].content)
        assertEquals("5", page.notes[0].folderId)      // int → string
        assertEquals("f-2", page.notes[1].folderId)     // string 原样
        assertEquals("st-9", page.syncTag)
        assertTrue(page.lastPage)

        val req = server.takeRequest()
        assertEquals("/note/full/page/", req.path?.substringBefore("?"))
        assertNull(req.requestUrl?.queryParameter("pageNum"))
    }
}
