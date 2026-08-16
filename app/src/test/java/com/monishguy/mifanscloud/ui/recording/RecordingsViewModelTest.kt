package com.monishguy.mifanscloud.ui.recording

import com.monishguy.mifanscloud.data.auth.XiaomiAuthService
import com.monishguy.mifanscloud.data.auth.XiaomiCredential
import com.monishguy.mifanscloud.data.recording.RecordingApi
import com.monishguy.mifanscloud.data.remote.XiaomiApiClient
import com.monishguy.mifanscloud.data.sync.DownloadedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * RecordingsViewModel 行为 seam：列表加载与按需下载（命名空间 recording）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var api: RecordingApi
    private lateinit var store: DownloadedStore

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
        )
        store = DownloadedStore(File.createTempFile("dlt", ".json").apply { deleteOnExit() })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private suspend fun TestScope.withMainDispatcher(block: suspend TestScope.() -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `加载录音列表并解析文件名`() = runTest {
        withMainDispatcher {
            server.enqueue(
                MockResponse().setBody(
                    """{"data":{"list":[
                        {"id":"11","name":"录音_20240801.m4a_1_0_1_3","sha1":"s","size":2048,"create_time":1}
                    ]}}"""
                )
            )
            val vm = RecordingsViewModel(api, store, ioDispatcher = StandardTestDispatcher(testScheduler))

            vm.load()
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is RecordingUiState.Recordings)
            assertEquals("录音_20240801.m4a", (state as RecordingUiState.Recordings).recordings[0].recording.fileName)
            assertEquals(
                com.monishguy.mifanscloud.data.recording.RecordingType.RECORDER,
                (state as RecordingUiState.Recordings).recordings[0].recording.type,
            )
        }
    }

    @Test
    fun `下载成功后写入 recording 命名空间并刷新列表`() = runTest {
        withMainDispatcher {
            server.enqueue(
                MockResponse().setBody(
                    """{"data":{"list":[
                        {"id":"11","name":"录音_20240801.m4a_1_0_1_3","sha1":"s","size":2048,"create_time":1}
                    ]}}"""
                )
            )
            val vm = RecordingsViewModel(api, store, ioDispatcher = StandardTestDispatcher(testScheduler))
            vm.load()
            advanceUntilIdle()
            val recording = (vm.state.value as RecordingUiState.Recordings).recordings[0].recording

            // 下载三步链 + 下载后自动 load() 刷新
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"url":"${server.url("/oss")}"}}"""))
            server.enqueue(MockResponse().setBody("""dl_callback({"url":"${server.url("/dl")}","meta":"m9"})"""))
            server.enqueue(MockResponse().setBody("AUDIO-BYTES"))
            server.enqueue(
                MockResponse().setBody(
                    """{"data":{"list":[
                        {"id":"11","name":"录音_20240801.m4a_1_0_1_3","sha1":"s","size":2048,"create_time":1}
                    ]}}"""
                )
            )

            vm.download(recording) { ByteArrayOutputStream() }
            advanceUntilIdle()

            assertEquals(setOf("11"), store.ids("recording"))
            assertTrue(store.ids("gallery").isEmpty())
        }
    }
}
