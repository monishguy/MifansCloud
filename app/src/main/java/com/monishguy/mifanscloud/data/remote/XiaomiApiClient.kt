package com.monishguy.mifanscloud.data.remote

import com.monishguy.mifanscloud.data.auth.XiaomiAuthException
import com.monishguy.mifanscloud.data.auth.XiaomiAuthService
import com.monishguy.mifanscloud.data.auth.XiaomiCredential
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 带认证的 i.mi.com 客户端。
 *
 * 为每个请求注入 `Cookie: userId=...; serviceToken=...;`（对齐
 * XiaomiAlbumSyncer `authHeader`）；收到 401 时：
 * - passToken 凭证 → 刷新 serviceToken 并**重发一次**（对齐 MiCloud 的 401 自动重登）；
 * - serviceToken 直连凭证 → 调用 [renewer]（AutoRenewal 续期）成功后重发一次；
 *   renewer 返回 null 或未配置则抛 [XiaomiAuthException] 提示重新登录。
 *
 * 各数据模块统一走此客户端。
 */
class XiaomiApiClient(
    private val client: OkHttpClient,
    private val auth: XiaomiAuthService,
    private val credentialsProvider: () -> XiaomiCredential,
    /** AutoRenewal 续期钩子：返回新 serviceToken，失败返回 null（由 AppContainer 装配并持久化）。 */
    private val renewer: (() -> String?)? = null,
) {

    /**
     * 执行请求；调用方负责 close 返回的 Response。
     * 返回 401 之外的原始响应，或刷新/续期后重发的响应。
     */
    fun execute(request: Request): Response {
        val credential = credentialsProvider()
        val first = callWithToken(
            request,
            credential.userId,
            auth.getServiceToken(credential).serviceToken,
        )
        if (first.code != 401) return first
        first.close()

        val refreshed = when (credential) {
            is XiaomiCredential.PassToken -> auth.invalidateAndRefresh(credential).serviceToken
            is XiaomiCredential.ServiceToken ->
                renewer?.invoke() ?: throw XiaomiAuthException(
                    "会话已失效（401）且无法自动续期，请重新登录 i.mi.com 并粘贴新 Cookie"
                )
        }
        return callWithToken(request, credential.userId, refreshed)
    }

    private fun callWithToken(request: Request, userId: String, serviceToken: String): Response {
        val authenticated = request.newBuilder()
            .header(HEADER_USER_AGENT, XiaomiAuthService.UA)
            .header(HEADER_COOKIE, "userId=$userId; serviceToken=$serviceToken;")
            .build()
        return client.newCall(authenticated).execute()
    }

    private companion object {
        const val HEADER_COOKIE = "Cookie"
        const val HEADER_USER_AGENT = "User-Agent"
    }
}
