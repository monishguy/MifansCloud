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
            // 排序后（dateTaken 降序）：9002(2000000) 在前为 NEW，9001(1000000) 匹配本机
            val byId = state.assets.associate { it.asset.id to it.status }
            assertEquals(MatchStatus.LOCAL_ALREADY, byId["9001"])
            assertEquals(MatchStatus.NEW, byId["9002"])
        }
    }

    @Test
    fun `相册列表按名称文字排序`() = runTest {
        withMainDispatcher {
            server.enqueue(
                MockResponse().setBody(
                    """{"data":{"albums":[
                        {"albumId":"2","name":"B 相册","mediaCount":1,"lastUpdateTime":2,"thumbnails":[]},
                        {"albumId":"1","name":"A 相册","mediaCount":1,"lastUpdateTime":1,"thumbnails":[]}
                    ],"isLastPage":true}}"""
                )
            )
            val vm = GalleryViewModel(
                api, FakeLocalMediaSource(emptyList()), store,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )

            vm.loadAlbums()
            advanceUntilIdle()

            val state = vm.state.value as GalleryUiState.Albums
            assertEquals(listOf("A 相册", "B 相册"), state.albums.map { it.name })
        }
    }

    @Test
    fun `相册内资产按 dateTaken 新旧降序`() = runTest {
        withMainDispatcher {
            server.enqueue(
                MockResponse().setBody(
                    """{"data":{"galleries":[
                        {"id":"1","fileName":"a.jpg","title":"A","type":"image",
                         "mimeType":"image/jpeg","size":1000,"sha1":"s1","dateTaken":1000,
                         "thumbnailInfo":null},
                        {"id":"2","fileName":"b.jpg","title":"B","type":"image",
                         "mimeType":"image/jpeg","size":1000,"sha1":"s2","dateTaken":3000,
                         "thumbnailInfo":null},
                        {"id":"3","fileName":"c.jpg","title":"C","type":"image",
                         "mimeType":"image/jpeg","size":1000,"sha1":"s3","dateTaken":2000,
                         "thumbnailInfo":null}
                    ],"isLastPage":true}}"""
                )
            )
            val vm = GalleryViewModel(
                api, FakeLocalMediaSource(emptyList()), store,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )

            vm.loadAlbum(RemoteAlbum("1", "相机", 3, 11, emptyList()))
            advanceUntilIdle()

            val state = vm.state.value as GalleryUiState.AlbumAssets
            assertEquals(listOf("2", "3", "1"), state.assets.map { it.asset.id })
        }
    }

    @Test
    fun `全部照片合并各相册并按新旧降序、排除私密相册`() = runTest {
        withMainDispatcher {
            // 0) 直连全量接口（不带 albumId）返回 503 → 触发回退逐相册合并
            server.enqueue(MockResponse().setResponseCode(503))
            // 1) 相册列表：相册 1、相册 2、私密相册 1000
            server.enqueue(
                MockResponse().setBody(
                    """{"data":{"albums":[
                        {"albumId":"1","name":"A","mediaCount":2,"lastUpdateTime":1,"thumbnails":[]},
                        {"albumId":"2","name":"B","mediaCount":1,"lastUpdateTime":2,"thumbnails":[]},
                        {"albumId":"1000","name":"私密","mediaCount":9,"lastUpdateTime":3,"thumbnails":[]}
                    ],"isLastPage":true}}"""
                )
            )
            // 2) 相册 1 资产
            server.enqueue(
                MockResponse().setBody(
                    """{"data":{"galleries":[
                        {"id":"11","fileName":"a.jpg","title":"A","type":"image",
                         "mimeType":"image/jpeg","size":1,"sha1":"s","dateTaken":1000,
                         "thumbnailInfo":null},
                        {"id":"12","fileName":"b.jpg","title":"B","type":"image",
                         "mimeType":"image/jpeg","size":1,"sha1":"s","dateTaken":4000,
                         "thumbnailInfo":null}
                    ],"isLastPage":true}}"""
                )
            )
            // 3) 相册 2 资产
            server.enqueue(
                MockResponse().setBody(
                    """{"data":{"galleries":[
                        {"id":"21","fileName":"c.jpg","title":"C","type":"image",
                         "mimeType":"image/jpeg","size":1,"sha1":"s","dateTaken":2000,
                         "thumbnailInfo":null}
                    ],"isLastPage":true}}"""
                )
            )
            val vm = GalleryViewModel(
                api, FakeLocalMediaSource(emptyList()), store,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )

            vm.loadAllPhotos()
            advanceUntilIdle()

            val state = vm.state.value as GalleryUiState.Photos
            // 按 dateTaken 降序：12(4000) > 21(2000) > 11(1000)；私密相册资产不并入
            assertEquals(listOf("12", "21", "11"), state.assets.map { it.asset.id })
            assertEquals(false, state.loading)
        }
    }

    @Test
    fun `批量下载按顺序执行、进度回调并写入存储`() = runTest {
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
            val vm = GalleryViewModel(
                api, FakeLocalMediaSource(emptyList()), store,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )
            val album = RemoteAlbum("1", "相机", 2, 11, emptyList())
            vm.loadAlbum(album)
            advanceUntilIdle()
            val assets = (vm.state.value as GalleryUiState.AlbumAssets).assets.map { it.asset }

            // 每张 3 个下载链响应（storage → JSONP → 文件流）
            repeat(2) { i ->
                server.enqueue(MockResponse().setBody("""{"code":0,"data":{"url":"${server.url("/oss$i")}"}}"""))
                server.enqueue(MockResponse().setBody("""dl_callback({"url":"${server.url("/dl$i")}","meta":"m$i"})"""))
                server.enqueue(MockResponse().setBody("BYTES-$i"))
            }
            // downloadAssets 完成后 refreshCurrent 会重新拉取清单
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
            val progress = mutableListOf<Pair<Int, Int>>()
            var completed = false

            vm.downloadAssets(
                assets = assets,
                outputProvider = { ByteArrayOutputStream() },
                onProgress = { done, total -> progress += done to total },
                onCompleted = { completed = true },
            )
            advanceUntilIdle()

            assertTrue(completed)
            assertEquals(listOf(1 to 2, 2 to 2), progress)
            assertEquals(setOf("9001", "9002"), store.ids("gallery"))
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
            assertEquals(setOf("9001"), store.ids("gallery"))
            val refreshed = vm.state.value as GalleryUiState.AlbumAssets
            assertEquals(MatchStatus.DOWNLOADED, refreshed.assets[0].status)
        }
    }
}
