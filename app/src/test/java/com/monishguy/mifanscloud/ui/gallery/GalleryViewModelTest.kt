package com.monishguy.mifanscloud.ui.gallery

import com.monishguy.mifanscloud.data.auth.XiaomiAuthService
import com.monishguy.mifanscloud.data.auth.XiaomiCredential
import com.monishguy.mifanscloud.data.gallery.GalleryApi
import com.monishguy.mifanscloud.data.gallery.RemoteAlbum
import com.monishguy.mifanscloud.data.remote.XiaomiApiClient
import com.monishguy.mifanscloud.data.sync.DownloadedStore
import com.monishguy.mifanscloud.data.sync.FakeLocalMediaSource
import com.monishguy.mifanscloud.data.sync.LocalMedia
import com.monishguy.mifanscloud.data.sync.MatchStatus
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
 * GalleryViewModel 行为 seam：相册/资产加载、本机匹配标记、按需下载。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var api: GalleryApi
    private lateinit var store: DownloadedStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().followRedirects(false).build()
        api = GalleryApi(
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
    fun `加载相册列表进入 Albums 状态`() = runTest {
        withMainDispatcher {
            server.enqueue(
                MockResponse().setBody(
                    """{"data":{"albums":[{"albumId":"1","name":"相机","mediaCount":2,
                        "lastUpdateTime":11,"thumbnails":[]}],"isLastPage":true}}"""
                )
            )
            val vm = GalleryViewModel(
                api,
                FakeLocalMediaSource(emptyList()),
                store,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )

            vm.loadAlbums()
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is GalleryUiState.Albums)
            assertEquals(1, (state as GalleryUiState.Albums).albums.size)
        }
    }

    @Test
    fun `加载资产后按本机媒体库标记匹配状态`() = runTest {
        withMainDispatcher {
            server.enqueue(
                MockResponse().setBody(
                    """{"data":{"galleries":[
                        {"id":"9001","fileName":"a.jpg","title":"A","type":"image",
                         "mimeType":"image/jpeg","size":1000,"sha1":"s1","dateTaken":1000000,
                         "thumbnailInfo":null},
                        {"id":"9002","fileName":"b.jpg","title":"B","type":"image",
                         "mimeType":"image/jpeg","size":2000,"sha1":"s2","dateTaken":2000000,
                         "thumbnailInfo":null}
                    ],"isLastPage":true}}"""
                )
            )
            val local = listOf(LocalMedia(id = 1, dateTakenMs = 1_000_000L, sizeBytes = 1000))
            val vm = GalleryViewModel(
                api,
                FakeLocalMediaSource(local),
                store,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )

            vm.loadAlbum(RemoteAlbum("1", "相机", 2, 11, emptyList()))
            advanceUntilIdle()

            val state = vm.state.value as GalleryUiState.AlbumAssets
            assertEquals(2, state.assets.size)
            assertEquals(MatchStatus.LOCAL_ALREADY, state.assets[0].status)
            assertEquals(MatchStatus.NEW, state.assets[1].status)
        }
    }

    @Test
    fun `下载成功后写入存储并标记为已下载`() = runTest {
        withMainDispatcher {
            server.enqueue(
                MockResponse().setBody(
                    """{"data":{"galleries":[
                        {"id":"9001","fileName":"a.jpg","title":"A","type":"image",
                         "mimeType":"image/jpeg","size":1000,"sha1":"s1","dateTaken":1000000,
                         "thumbnailInfo":null}
                    ],"isLastPage":true}}"""
                )
            )
            val vm = GalleryViewModel(
                api,
                FakeLocalMediaSource(emptyList()),
                store,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )
            val album = RemoteAlbum("1", "相机", 1, 11, emptyList())
            vm.loadAlbum(album)
            advanceUntilIdle()

            val asset = (vm.state.value as GalleryUiState.AlbumAssets).assets[0].asset
            // 下载三步链
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"url":"${server.url("/oss")}"}}"""))
            server.enqueue(MockResponse().setBody("""dl_callback({"url":"${server.url("/dl")}","meta":"m9"})"""))
            server.enqueue(MockResponse().setBody("JPEG-BYTES"))
            // downloadAsset 成功后内部会重新 loadAlbum 刷新状态
            server.enqueue(
                MockResponse().setBody(
                    """{"data":{"galleries":[
                        {"id":"9001","fileName":"a.jpg","title":"A","type":"image",
                         "mimeType":"image/jpeg","size":1000,"sha1":"s1","dateTaken":1000000,
                         "thumbnailInfo":null}
                    ],"isLastPage":true}}"""
                )
            )
            var completed = false

            vm.downloadAsset(asset, outputProvider = { ByteArrayOutputStream() }, onCompleted = { completed = true })
            advanceUntilIdle()

            assertTrue(completed)
            assertEquals(setOf("9001"), store.ids())
            val refreshed = vm.state.value as GalleryUiState.AlbumAssets
            assertEquals(MatchStatus.DOWNLOADED, refreshed.assets[0].status)
        }
    }
}
