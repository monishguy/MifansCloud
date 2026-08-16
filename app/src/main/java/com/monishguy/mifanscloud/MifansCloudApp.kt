package com.monishguy.mifanscloud

import android.app.Application
import android.content.Context
import com.monishguy.mifanscloud.data.auth.CredentialStore
import com.monishguy.mifanscloud.data.auth.SecureCredentialStore
import com.monishguy.mifanscloud.data.auth.XiaomiAuthService
import com.monishguy.mifanscloud.data.remote.XiaomiApiClient
import okhttp3.OkHttpClient

/**
 * 手动依赖装配容器（M2 阶段不引入 DI 框架）。
 * 关键网络约定：全局关闭重定向跟随（签名直链 / 登录链均依赖手动处理 302）。
 */
class AppContainer(context: Context) {

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    val credentialStore: CredentialStore = SecureCredentialStore(context)

    val authService: XiaomiAuthService = XiaomiAuthService(
        client = okHttpClient,
        baseUrl = BASE_URL,
    )

    /** M3 起各数据模块（相册/录音/笔记/通讯录/短信）统一经此客户端访问。 */
    val apiClient: XiaomiApiClient = XiaomiApiClient(
        client = okHttpClient,
        auth = authService,
        credentialsProvider = {
            credentialStore.load() ?: error("未配置小米云凭证")
        },
    )

    companion object {
        /** 中国区小米云 Web 端（Global 区域暂不支持）。 */
        const val BASE_URL = "https://i.mi.com"
    }
}

class MifansCloudApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
