package com.monishguy.mifanscloud

import android.app.Application
import android.content.Context
import coil.Coil
import coil.ImageLoader
import com.monishguy.mifanscloud.data.auth.CredentialStore
import com.monishguy.mifanscloud.data.auth.SecureCredentialStore
import com.monishguy.mifanscloud.data.auth.XiaomiAuthService
import com.monishguy.mifanscloud.data.auth.XiaomiCredential
import com.monishguy.mifanscloud.data.contact.ContactApi
import com.monishguy.mifanscloud.data.gallery.GalleryApi
import com.monishguy.mifanscloud.data.note.NoteApi
import com.monishguy.mifanscloud.data.recording.RecordingApi
import com.monishguy.mifanscloud.data.local.SaveDirStore
import com.monishguy.mifanscloud.data.remote.XiaomiApiClient
import com.monishguy.mifanscloud.data.sms.SmsApi
import com.monishguy.mifanscloud.data.sync.DownloadedStore
import com.monishguy.mifanscloud.data.sync.LocalMediaSource
import com.monishguy.mifanscloud.data.sync.MediaStoreLocalMediaSource
import okhttp3.OkHttpClient
import java.io.File

/**
 * 手动依赖装配容器。
 *
 * 关键网络约定：全局关闭重定向跟随（签名直链 / 登录链均依赖手动处理 302）。
 * 图片客户端独立于业务客户端：只为缩略图 URL 注入认证 cookie
 * （仅直连会话可注入，passToken 会话不注入以免换取链被污染）。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val credentialStore: CredentialStore = SecureCredentialStore(appContext)

    /** 业务请求客户端（认证换取链 / 数据接口）：不注入 cookie 拦截器。 */
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** 图片客户端：为 mi.com 域缩略图 URL 注入直连会话 cookie。 */
    private val imageOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .addInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder().header("User-Agent", XiaomiAuthService.UA)
            if (request.url.host.endsWith(MI_COM_HOST)) {
                val credential = credentialStore.load()
                val token = (credential as? XiaomiCredential.ServiceToken)?.serviceToken
                if (token != null) {
                    builder.header("Cookie", "userId=${credential.userId}; serviceToken=$token;")
                }
            }
            chain.proceed(builder.build())
        }
        .build()

    val authService: XiaomiAuthService = XiaomiAuthService(
        client = okHttpClient,
        baseUrl = BASE_URL,
    )

    /** 各数据模块（相册/录音/笔记/通讯录/短信）统一经此客户端访问。 */
    val apiClient: XiaomiApiClient = XiaomiApiClient(
        client = okHttpClient,
        auth = authService,
        credentialsProvider = {
            credentialStore.load() ?: error("未配置小米云凭证")
        },
        // ServiceToken 直连会话 401 时：AutoRenewal 续期并持久化，无需用户重新粘贴
        renewer = {
            val credential = credentialStore.load() as? XiaomiCredential.ServiceToken ?: return@XiaomiApiClient null
            runCatching {
                authService.renewServiceToken(credential).serviceToken.also { fresh ->
                    credentialStore.updateServiceToken(fresh)
                }
            }.getOrNull()
        },
    )

    val galleryApi: GalleryApi = GalleryApi(
        apiClient = apiClient,
        baseUrl = BASE_URL,
    )

    val recordingApi: RecordingApi = RecordingApi(
        apiClient = apiClient,
        baseUrl = BASE_URL,
    )

    val contactApi: ContactApi = ContactApi(apiClient, BASE_URL)

    val noteApi: NoteApi = NoteApi(apiClient, BASE_URL)

    val smsApi: SmsApi = SmsApi(apiClient, BASE_URL)

    val localMediaSource: LocalMediaSource = MediaStoreLocalMediaSource(appContext)

    val downloadedStore: DownloadedStore = DownloadedStore(
        File(appContext.filesDir, DOWNLOADED_FILE),
    )

    /** 各板块独立保存目录（SAF）。 */
    val saveDirStore: SaveDirStore = SaveDirStore(appContext)

    /** 板块缓存代际：清除凭证时 +1，各 ViewModel 据此判断缓存是否失效。 */
    private val cacheGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    fun invalidateCache() {
        cacheGeneration.incrementAndGet()
    }

    val cacheVersion: () -> Int = { cacheGeneration.get() }

    /** 缩略图加载器（Coil），由 Application 设为默认。 */
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(appContext)
            .okHttpClient(imageOkHttpClient)
            .build()
    }

    companion object {
        /** 中国区小米云 Web 端（Global 区域暂不支持）。 */
        const val BASE_URL = "https://i.mi.com"

        private const val MI_COM_HOST = "mi.com"
        private const val DOWNLOADED_FILE = "downloaded.json"
    }
}

class MifansCloudApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Coil.setImageLoader(container.imageLoader)
    }
}
